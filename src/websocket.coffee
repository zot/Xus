exports = module.exports = require './base'
{Connection} = require './transport'
html = require './websocket-html'
_ = require './lodash.min'

exports.startWebSocketServer = (xusServer, host, port, ready)->
  app = require('http').createServer handler
  context = connections: []
  app.on 'upgrade', (req, socket, head)->
    console.log "upgrade, head: #{head.toString()}"
    context.connections.push new WebSocketConnection xusServer, context, socket, head
  xusServer.webSocketServer = app
  xusServer.socketServer = app
  if port then app.listen port, host, ready
  else app.listen ready

handler = (req, res)->
  res.writeHead 200
  res.end html

class WebSocketConnection extends Connection
  constructor: (@server, @context, @con, data)->
    super @server, null, data.toString()
    console.log "connection data: '#{data.toString()}'"
    @con.on 'data', (data) => @newData data
    @con.on 'error', (hadError)=> @server.disconnect @
    @con.on 'close', (hadError)=> @server.disconnect @
    @con.on 'end', (hadError)=> @server.disconnect @
    @server.addConnection @
  connected: true
  write: (str)-> @con.write str
  basicClose: ->
    try
      @con.end()
    catch err
      console.log "Error closing connection: #{err.stack}"
    @context.connections = _.without @context.connections, @con
