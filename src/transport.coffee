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
# CONNECTION CLASS
#
# isConnected() -- returns whether the connection is connected
# close() -- close the connection
# addCmd cmd -- add a command to the queue
# send() -- send the command queue
#
# The default constructor takes a master and a codec
#
# master must understand processBatch(connection, batch)
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
  xusConnection = new DirectConnectionPart
  # the object that the peer uses as its connection
  peerConnection = new DirectConnectionPart
  peer = peerFactory peerConnection
  peerConnection.connect(xusConnection, xus, ctx)
  xusConnection.connect(peerConnection, peer, ctx)
  xus.addConnection xusConnection
  peer

class DirectConnectionPart
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
