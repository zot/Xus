####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

{Connection} = exports = module.exports = require './proto'
net = require 'net'
_ = require './lodash.min'

exports.startSocketServer = (xusServer, host, port, ready)->
  context = connections: []
  server = net.createServer (c)-> context.connections.push new SocketConnection xusServer, context, c
  xusServer.socketServer = server
  if port then server.listen port, host, ready
  else server.listen ready

class SocketConnection extends Connection
  constructor: (@server, @context, @con)->
    super(@server)
    @con.on 'data', (data) => @newData data
    @con.on 'error', (hadError)=> @server.disconnect @
    @con.on 'close', (hadError)=> @server.disconnect @
    @server.addConnection @
  connected: true
  write: (str)->
    console.log "CONNECTION WRITING: #{str}"
    @con.write str
  close: ->
    try
      @con.destroy()
    catch err
      console.log "Error closing connection: #{err.stack}"
    @context.connections = _.without @context.connections, @con
