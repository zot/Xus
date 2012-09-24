####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

{Connection} = exports = module.exports = require './proto'
net = require 'net'
_ = require './lodash.min'

exports.startSocketServer = (xusServer, port, host, ready)->
  context = {connections: []}
  server = net.createServer (c)-> context.connections.push new Connection xusServer, context, c
  xusServer.socketServer = server
  if port then server.listen port, host, ready
  else server.listen ready
  this

class SocketConnection extends Connection
  constructor: (@server, @context, @con)->
    @con.on 'data', (data) =>
      msgs = (@saved + data).split('\n')
      if data[data.length - 1] != '\n' then @saved = msgs.pop()
      @server.processMessages this, _.map msgs, (m)->
        try
          JSON.parse(m)
        catch err
          ['error', m]
  connected: true
  dump: ->
    if @connected && @q.length
      @con.write JSON.stringify @q
      @q = []
  disconnect: ->
    @connected = false
    @con.close()
    @q = null
    @context.connections = @context.connections.without @con
