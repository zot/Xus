####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

{d} = exports = module.exports = require './base'
{error_bad_connection} = require './proto'
_ = require './lodash.min'

####
# Codecs -- used by connections to encode and decode messages
#
# codec must understand these messages:
#   prepare connection -- initialize a connection to be used with the codec
#   send connection, data -- encodes the data and calls connection.write(encodedData)
#   newData connection, data -- handle new data on the connection
#
####

####
# JSONCodec
#
# encode/decode newline-terminated JSON batches
#
####

exports.JSONCodec = JSONCodec =
  prepare: (con)-> # con.saved = ''
  send: (con, obj)->
    con.write "#{JSON.stringify(obj)}\n"
  newData: (con, data)->
    if typeof data != 'string' then data = data.toString()
    msgs = (con.saved + data).trim().split('\n')
    con.saved = if data[data.length - 1] is '\n' then '' else msgs.pop()
    con.processBatch batch for batch in _.map msgs, (m)->
      try
        con.verbose "PROCESSING BATCH: #{m}"
        JSON.parse(m)
      catch err
        con.addCmd ['error', "Could not parse message: #{m}"]
        con.send()

####
#
# CONNECTION CLASS
#
# isConnected() -- returns whether the connection is connected
# close() -- close the connection
# addCmd cmd -- add a command to the queue
# send() -- send the command queue
#
# The default constructor takes a master and a codec
#
# master must understand
#   verbose
#   addConnection(connection)
#   disconnect(connection) -- which calls connection.basicClose()
#   processBatch(connection, batch)
#
# codec must be a codec
#
# Connection classes must define these methods:
#   write str -- send commands to the connection
#   basicClose -- close connection
#
####

exports.Connection = class Connection
  write: (str)-> @master.disconnect this, error_bad_connection, "Connection has no 'write' method"
  basicClose: ->
    @verbose "CLOSING: #{@}"
    @master.disconnect this, error_bad_connection, "Connection has no 'disconnect' method"
  constructor: (master, @codec, @saved = '')->
    @codec = @codec ? JSONCodec
    try
      @codec.prepare @
    catch err
      console.log err.stack
    @q = []
    @connected = true
    @setMaster master
  setMaster: (@master)->
  verbose: (str)-> @master.verbose str
  isConnected: -> @connected
  close: ->
    @connected = false
    @q = null
    @basicClose()
  addCmd: (cmd)-> @q.push cmd
  send: ->
    if @connected && @q.length
      @verbose "#{@} SENDING #{JSON.stringify @q}"
      [q, @q] = [@q, []]
      @codec.send @, q
  newData: (data)-> @verbose "#{@} read data: #{data}"; @codec.newData @, data
  processBatch: (batch)-> @master.processBatch @, batch
  toString: -> "#{@constructor.name} [#{@.peerPath ? '??'}]"

exports.SocketConnection = class SocketConnection extends Connection
  constructor: (@master, @con, initialData)->
    super @master, null, (initialData ? '').toString()
    @con.on 'data', (data) => @verbose "#{@} data: '#{data}'"; @newData data
    @con.on 'end', (hadError)=> @verbose "#{@} disconnect"; @master.disconnect @
    @con.on 'close', (hadError)=> @verbose "#{@} disconnect"; @master.disconnect @
    @con.on 'error', (hadError)=> @verbose "#{@} disconnect"; @master.disconnect @
    @master.addConnection @
  connected: true
  write: (str)-> @con.write str
  basicClose: ->
    try
      @verbose "CLOSING: #{@}"
      @con.destroy()
    catch err
      console.log "Error closing connection: #{err.stack}"

exports.WebSocketConnection = class WebSocketConnection extends Connection
  constructor: (@master, @con)->
    @pending = []
    super @master
  setMaster: (@master)->
    if @master
      if @con.readyState == 1 then @sendPending()
      else @con.onopen = (evt)=> @sendPending()
      @con.onmessage = (evt) => console.log "MESSAGE: #{JSON.stringify evt.data}"; @newData evt.data
      @con.onend = (hadError)=> @master.disconnect @
      @con.onclose = (hadError)=> @master.disconnect @
      @con.onerror = (hadError)=> @master.disconnect @
      @master.addConnection @
  connected: true
  write: (str)-> @pending.push str
  sendPending: ->
    console.log "CHANGING WRITE METHOD"
    @write = (str)->
      @verbose "#{@} writing: #{str}";
      @con.send str
    for msg in @pending
      @write msg
    @pending = null
    @sendPending = ->
  basicClose: ->
    try
      @verbose "CLOSING: #{@}"
      @con.terminate()
    catch err
      console.log "Error closing connection: #{err.stack}"

deadComets = {}

exports.CometConnection = class CometConnection extends Connection
  constructor: (@master, @socket)->
    super @master
  setMaster: (@master)->
    console.log "MASTER: #{@master}"
    @master.addConnection @
    @socket.on 'disconnect', => @master.disconnect @
    @socket.on 'xusCmd', (data)=> @verbose "MESSAGE: #{data.str}"; @newData data.str
  connected: true
  write: (str)->
    @verbose "#{@} writing: #{str}"
    @socket.emit 'xusCmd', {str: str}
  basicClose: ->
    @verbose "CLOSING: #{@}"
    if !@socket._zombi then @socket.emit 'xusTerminate', ''
    deadComets[@socket._uuid] = true

exports.CometClientConnection = class CometClientConnection extends Connection
  constructor: (@master, url)->
    super @master
    @pending = []
    @socket = comet.connect(url
    ).on('connect', => @sendPending()
    ).on('xusCmd', (data)=> @verbose "MESSAGE: #{data.str}"; @newData data.str
    ).on('xusTerminate', => @master.disconnect @
    )
  connected: true
  write: (str)-> @pending.push str
  sendPending: ->
    console.log "CHANGING WRITE METHOD"
    @write = (str)->
      @verbose "#{@} writing: #{str}"
      @socket.emit 'xusCmd', {str: str}
    for msg in @pending
      @write msg
    @pending = null
  basicClose: ->
    @verbose "CLOSING: #{@}"
    if !@socket._zombi then @socket.emit 'xus.terminate', ''
    deadComets[@socket._uuid] = true

#####
# Master for the muxed connection
#
# forwards batches between muxed connection and endpoints
#
# muxed connection -> processBatch -> endpoint
# endpoint -> send(endpoint, batch) -> muxed connection
#
#####
exports.ProxyMux = class ProxyMux
  constructor: (@handler)->
    @currentId = 0
    @connections = {}
  verbose: ->
  prepare: ->
  addConnection: (con)->
    @verbose "proxy main connection";
    @mainConnection = con
  newConnectionEndpoint: (conFactory)-> @newConnection (id)=>
    endPoint = new ConnectionEndpoint @, id
    conFactory endPoint
    endPoint
  newPeer: ->
    peer = new exports.Peer
    console.log "SETTING CONNECTION"
    # peer.setConnection @newConnection (id)=> new XusEndpoint peer, @, id
    @newConnection (id)=>
      peer.setConnection new XusEndpoint peer, @, id
      peer
    @mainSend [['connect', peer.con.id]]
    peer.con.newconnection = false
    peer
  newConnection: (factory)->
    id = @currentId++
    con = factory id
    @verbose "proxy got new connection: #{d con}, id: #{id}"
    @connections[id] = con
    con
  processBatch: (muxedCon, batch)-> # called by endpoint; calls @handleDemuxedBatch
    @verbose "proxy demuxing batch: #{JSON.stringify batch}"
    [cmd, id] = batch[0]
    con = @connections[id]
    switch cmd
      when 'connect'
        @verbose "MUX connect"
        con = new XusEndpoint @handler, @, id
        @connections[id] = con
        @handler.addConnection con
      when 'disconnect'
        @verbose "MUX disconnect"
        if con
          @removeConnection con
          con.disconnect()
      when 'data'
        @verbose "MUX data: #{JSON.stringify batch[1..]}"
    b = batch[1..]
    if b.length then @handler.processBatch con, b
  disconnect: (con)->
    if con == @mainConnection then @mainDisconnect con
    else
      @mainSend [['disconnect', con.id]]
      @removeConnection con
  mainDisconnect: (con)->
    console.log "Disconnecting mux connection"
    process.exit()
  removeConnection: (con)->
    if connected
      connected = false
      delete @connections[con.id]
  mux: (endpoint, batch)->
    b = batch[0..]
    b.splice 0, 0, ['data', endpoint.id]
    endpoint.newConnection = false
    @mainSend b
  mainSend: (batch)->
    @verbose "#{@} proxy forwarding muxed batch: #{JSON.stringify batch} to #{@mainConnection.constructor.name}"
    @mainConnection.q = batch
    @mainConnection.send()
  prepare: (con)->

#####
# Socket <-> mux; acts as a connection master
#
# mux -> forward -> socket
# mux <- processBatch <- socket
#####
class ConnectionEndpoint # connected to an endpoint
  constructor: (@mux, @id)->
    @verbose "New ConnectionEndpoint"
    @newConnection = true
  verbose: (str)-> @mux.verbose str
  addConnection: (@con)->
    @verbose "ConnectionEndpoint connection: #{@con.constructor.name}"
    @mux.mainSend [['connect', @id]]
    @newconnection = false
  disconnect: (con)-> @verbose "ConnectionEndpoint disconnecting"; @mux.disconnect @
  send: (demuxedBatch)->
    @verbose "ConnectionEndpoint writing: #{JSON.stringify demuxedBatch}"
    @con.q = demuxedBatch
    @con.send()
  processBatch: (con, batch)-> @verbose "Socket endpoint read: #{batch}"; @mux.mux @, batch

#####
# Xus <-> mux; acts as a connection to xus
#
# treats mux as a codec
#
# Xus -> addCmd, send -> proxy
# proxy -> processBatch -> Xus
#####
class XusEndpoint extends Connection # connected to an endpoint
  constructor: (@master, @proxy, @id)->
    super @master, @proxy
    @verbose = @proxy.verbose
  newConnection: false
  basicClose: ->
    @verbose "CLOSING: #{@}"
    @proxy.disconnect @
  send: ->
    @verbose "SEND #{JSON.stringify @q}"
    [q, @q] = [@q, []]
    @proxy.mux @, q
  disconnect: -> @master.disconnect @
