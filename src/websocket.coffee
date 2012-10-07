exports = module.exports = require './base'
{ProxyMux, SocketConnection, WebSocketConnection, Connection} = require './transport'
ws = require 'ws'
_ = require './lodash.min'
pfs = require './pfs'
path = require 'path'

exports.startWebSocketServer = (host, port, ready)->
  app = require('http').createServer handler
  if port then app.listen port, host, ready
  else app.listen ready
  app

fileDir = "#{path.resolve path.dirname path.dirname process.argv[1]}/examples/"
xusPath = "#{path.resolve path.dirname path.dirname process.argv[1]}/xus.js"

handler = (req, res)->
  pfx = new RegExp '^/file/'
  if req.url.match(pfx) || req.url == '/xus.js'
    file = if req.url == '/xus.js' then xusPath else path.resolve req.url.replace(pfx, fileDir)
    if file.match("^#{fileDir}") or file == xusPath
      pfs.open(file, 'r')
        .then((fd)-> pfs.readFile fd)
        .then((s)->
          res.writeHead 200
          res.end s)
        .fail(-> badPage req, res)
        .end()
      return
  badPage req, res

badPage = (req, res)->
  res.writeHead 404
  res.end "<html><body>Web page #{req.url} not available</body></html>"

exports.connectXus = (xusServer, httpServer)->
  xusServer.webSocketServer = httpServer
  wServer = new ws.Server noServer: true
  httpServer.on 'upgrade', (req, socket, head)->
    if req.url == '/cmd' then new SocketConnection xusServer, socket, head
    else if req.url == '/peer' then wServer.handleUpgrade req, socket, head, (con)-> new WebSocketConnection xusServer, con
    else con.destroy()

exports.connectProxy = (httpServer)->
  proxy = new ProxyMux
    processBatch: (con, demuxedBatch)-> proxy.verbose "proxy sending: #{JSON.stringify demuxedBatch} to #{con.constructor.name}"; con.send demuxedBatch
  wServer = new ws.Server noServer: true
  httpServer.on 'upgrade', (req, socket, head)->
    proxy.verbose "REQUEST: #{req.url}"
    if req.url == '/cmd' # proxy this new connection from a peer
      proxy.newSocketEndpoint (proxyCon)-> new SocketConnection proxyCon, socket, head
    else if req.url == '/proxy' # main connection from a Xus server
      wServer.handleUpgrade req, socket, head, (con)-> new WebSocketConnection proxy, con
    else con.destroy()
  proxy
