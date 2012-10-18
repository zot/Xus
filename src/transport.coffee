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
  basicClose: -> @master.disconnect this, error_bad_connection, "Connection has no 'disconnect' method"
  constructor: (@master, @codec, @saved = '')->
    @codec = @codec ? JSONCodec
    try
      @codec.prepare @
    catch err
      console.log err.stack
    @q = []
    @connected = true
  verbose: (str)-> @master.verbose str
  isConnected: -> @connected
  close: ->
    @connected = false
    @q = null
    @basicClose()
  addCmd: (cmd)-> @q.push cmd
  send: ->
    if @connected && @q.length
      @verbose "#{d @} SENDING #{@name}, #{JSON.stringify @q}"
      [q, @q] = [@q, []]
      @codec.send @, q
  newData: (data)-> @verbose "#{d @} read data: #{data}"; @codec.newData @, data
  processBatch: (batch)-> @master.processBatch @, batch

exports.SocketConnection = class SocketConnection extends Connection
  constructor: (@master, @con, initialData)->
    super @master, null, (initialData ? '').toString()
    @con.on 'data', (data) => @verbose "#{d @} data: '#{data}'"; @newData data
    @con.on 'end', (hadError)=> @verbose "#{d @} disconnect"; @master.disconnect @
    @con.on 'close', (hadError)=> @verbose "#{d @} disconnect"; @master.disconnect @
    @con.on 'error', (hadError)=> @verbose "#{d @} disconnect"; @master.disconnect @
    @master.addConnection @
  connected: true
  write: (str)-> @con.write str
  basicClose: ->
    try
      @con.destroy()
    catch err
      console.log "Error closing connection: #{err.stack}"

exports.WebSocketConnection = class WebSocketConnection extends Connection
  constructor: (@master, @con)->
    super @master
    @con.onmessage = (evt) => @newData evt.data
    @con.onend = (hadError)=> @master.disconnect @
    @con.onclose = (hadError)=> @master.disconnect @
    @con.onerror = (hadError)=> @master.disconnect @
    @master.addConnection @
  connected: true
  write: (str)->
    @verbose "#{d @} writing: #{str}";
    @con.send str
  basicClose: ->
    try
      @con.terminate()
    catch err
      console.log "Error closing connection: #{err.stack}"

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
  newSocketEndpoint: (conFactory)-> @newConnection (id)=>
    endPoint = new SocketEndpoint @, id
    conFactory endPoint
    endPoint
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
        @verbose "MUX data"
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
    @verbose "#{d @} proxy forwarding muxed batch: #{JSON.stringify batch} to #{@mainConnection.constructor.name}"
    @mainConnection.q = batch
    @mainConnection.send()
  prepare: (con)->

#####
# Socket <-> mux; acts as a connection master
#
# mux -> forward -> socket
# mux <- processBatch <- socket
#####
class SocketEndpoint # connected to an endpoint
  constructor: (@mux, @id)->
    @verbose "New SocketEndpoint"
    @newConnection = true
  verbose: (str)-> @mux.verbose str
  addConnection: (@con)->
    @verbose "SocketEndpoint connection: #{@con.constructor.name}"
    @mux.mainSend [['connect', @id]]
    @newconnection = false
  disconnect: (con)-> @verbose "SocketEndpoint disconnecting"; @mux.disconnect @
  send: (demuxedBatch)->
    @verbose "SocketEndpoint writing: #{JSON.stringify demuxedBatch}"
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
  newConnection: false
  constructor: (@xus, @proxy, @id)->
    super @master, @proxy
    @verbose = @xus.verbose
  basicClose: -> @proxy.disconnect @
  send: ->
    @verbose "SEND #{JSON.stringify @q}"
    [q, @q] = [@q, []]
    @proxy.mux @, q
  disconnect: -> @xus.disconnect @
