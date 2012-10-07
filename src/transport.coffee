exports = module.exports = require './base'
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
    @codec.prepare @
    @q = []
    @connected = true
  isConnected: -> @connected
  close: ->
    @connected = false
    @q = null
    @basicClose()
  addCmd: (cmd)-> @q.push cmd
  send: ->
    if @connected && @q.length
      if @master.verbose then console.log "SENDING #{@name}, #{JSON.stringify @q}"
      [q, @q] = [@q, []]
      @codec.send @, q
  newData: (data)-> @codec.newData @, data
  processBatch: (batch)-> @master.processBatch @, batch

####
#
# createDirectPeer xus, factory -- make a peer with an in-process connection to xus
#
####

exports.createDirectPeer = (xus, peerFactory)->
  ctx = connected: true, server: xus
  # the object that xus uses as its connection
  xusConnection = new DirectConnection
  # the object that the peer uses as its connection
  peerConnection = new DirectConnection
  peer = peerFactory peerConnection
  peerConnection.connect(xusConnection, xus, ctx)
  xusConnection.connect(peerConnection, peer, ctx)
  xus.addConnection xusConnection
  peer

class DirectConnection
  constructor: -> @q = []
  connect: (@otherConnection, @otherMaster, @ctx)->
  isConnected: -> @ctx.connected
  close: ->
    @ctx.connected = false
    @q = @otherConnection.q = null
  addCmd: (cmd)-> @q.push cmd
  send: ->
    if @ctx.connected && @q.length
      if @ctx.server.verbose then console.log "SENDING #{@name}, #{JSON.stringify @q}"
      [q, @q] = [@q, []]
      @otherMaster.processBatch @otherConnection, q

exports.SocketConnection = class SocketConnection extends Connection
  constructor: (@master, @con, initialData)->
    super @master, null, (initialData ? '').toString()
    @con.on 'data', (data) => @newData data
    @con.on 'end', (hadError)=> @master.disconnect @
    @con.on 'close', (hadError)=> @master.disconnect @
    @con.on 'error', (hadError)=> @master.disconnect @
    @master.addConnection @
  connected: true
  write: (str)-> @con.write str
  basicClose: ->
    try
      @con.end()
    catch err
      console.log "Error closing connection: #{err.stack}"

exports.WebSocketConnection = class WebSocketConnection extends Connection
  constructor: (@master, @con)->
    super @master
    @con.on 'message', (data) => @newData data
    @con.on 'end', (hadError)=> @master.disconnect @
    @con.on 'close', (hadError)=> @master.disconnect @
    @con.on 'error', (hadError)=> @master.disconnect @
    @master.addConnection @
  connected: true
  write: (str)-> @con.send str
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
  constructor: (@handleDemuxedBatch)->
    @currentId = 0
    @connections = {}
  addConnection: (con)-> @mainConnection = con
  newSocketEndpoint: (conFactory)-> newConnection (id)-> conFactory new SocketEndpoint @, id
  newXusEndpoint: (xus, conFactory)-> newConnection (id)-> conFactory new XusEndpoint xus, @, id
  newConnection: (factory)->
    id = @currentId++
    con = factory id
    @connections[id] = con
    con
  processBatch: (muxedCon, batch)-> # called by endpoint; calls @handleDemuxedBatch
    [cmd, id] = batch[0]
    con = @connections[id]
    switch cmd
      when 'connect' then @connections[id] = new ProxyConnection @, id
      when 'disconnect'
        if con
          @removeConnection con
          con.disconnect()
    b = batch[1..]
    if b.length then @handleDemuxedBatch con, b
  disconnect: (con)->
    @mainSend [['delete', con.id]]
    @removeConnection con
  removeConnection: (con)->
    if connected
      connected = false
      delete @connections[con.id]
  mux: (endpoint, batch)->
    batch.splice 0, 0, [(if endpoint.newConnection then 'connect' else 'data'), endpoint.id]
    if endpoint.newConnection then endpoint.newConnection = false
    @mainSend batch
  mainSend: (batch)->
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
  constructor: (@mux, @proxy, @id)->
    @newConnection = true
    @verbose = @mux.verbose
  addConnection: (@con)->
  disconnect: (@con)-> @mux.disconnect @
  send: (demuxedBatch)->
    @con.q = batch
    @con.send()
  processBatch: (batch)-> @mux.mux @, batch

#####
# Xus <-> mux; acts as a connection
#
# treats mux as a codec
# 
# Xus -> addCmd, send -> proxy
# proxy -> processBatch -> Xus
#####
class XusEndpoint extends Connection # connected to an endpoint
  constructor: (@xus, @proxy, @id)->
    super @master, @proxy
    @verbose = @xus.verbose
  basicClose: -> @proxy.disconnect @
  send: (batch)-> @proxy.mux @, batch
  disconnect: -> @xus.disconnect @