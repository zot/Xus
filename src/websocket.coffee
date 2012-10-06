exports = module.exports = require './base'
{Connection} = require './transport'
ws = require 'ws'
_ = require './lodash.min'
pfs = require './pfs'
path = require 'path'

exports.startWebSocketServer = (xusServer, host, port, ready)->
  app = require('http').createServer handler
  context = connections: []
  xusServer.webSocketServer = app
  wServer = new ws.Server noServer: true
  app.on 'upgrade', (req, socket, head)->
    new (req.url.match('^/cmd') && SimpleCon || StandardCon) xusServer, context, socket, head, wServer, req
  if port then app.listen port, host, ready
  else app.listen ready

handler = (req, res)->
  pfx = new RegExp '^/file/'
  if req.url.match pfx
    file = req.url.replace pfx, "#{path.resolve path.dirname path.dirname process.argv[1]}/html/"
    pfs.open(file, 'r')
      .then((fd)-> pfs.readFile fd)
      .then((s)->
        res.writeHead 200
        res.end s)
      .end()

class SimpleCon extends Connection
  constructor: (@server, @context, @con, data)->
    super @server, null, data.toString()
    @con.on 'data', (data) => @newData data
    @con.on 'end', (hadError)=> @server.disconnect @
    @con.on 'close', (hadError)=> @server.disconnect @
    @con.on 'error', (hadError)=> @server.disconnect @
    @server.addConnection @
  connected: true
  write: (str)-> @con.write str
  basicClose: ->
    try
      @con.end()
    catch err
      console.log "Error closing connection: #{err.stack}"
    @context.connections = _.without @context.connections, @con

class StandardCon extends Connection
  constructor: (@server, @context, sock, data, wServer, req)->
    super @server
    wServer.handleUpgrade req, sock, data, (@con)=>
      @con.on 'message', (data) => @newData data
      @con.on 'end', (hadError)=> @server.disconnect @
      @con.on 'close', (hadError)=> @server.disconnect @
      @con.on 'error', (hadError)=> @server.disconnect @
      @server.addConnection @
  connected: true
  write: (str)-> @con.send str
  basicClose: ->
    try
      @con.terminate()
    catch err
      console.log "Error closing connection: #{err.stack}"
    @context.connections = _.without @context.connections, @con
