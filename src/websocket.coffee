####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

exports = module.exports = require './base'
{ProxyMux, SocketConnection, WebSocketConnection, Connection} = require './transport'
ws = require 'ws'
_ = require './lodash.min'
path = require 'path'
fs = require 'fs'
send = require 'send'
url = require 'url'

exports.startWebSocketServer = (config, ready)->
  app = require('http').createServer createHandler(config)
  if config.port then app.listen config.port, config.host, ready
  else app.listen ready
  app

exports.dirMap = dirMap = []
extensions =
  js: 'application/javascript'
  html: 'text/html'
  gif: 'image/gif'
  css: 'text/css'
  png: 'image/png'

createHandler = (config)-> (req, res)->
  pathname = url.parse(req.url).pathname
  for [urlPattern, dirPattern, dir] in dirMap
    file = path.resolve pathname.replace(urlPattern, dir)
    if file.match(dirPattern) # make sure there's no funny business :)
      send(req, pathname.replace(urlPattern, "/"))
      .root(dir)
      .pipe(res);
      return
  badPage req, res

contentType = (file)-> extensions[file.replace /^.*\.([^.]*)$/, '$1'] ? 'text/plain'

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

exports.connectProxy = (config, httpServer, connectBlock)->
  exports.proxy = proxy = new ProxyMux
    processBatch: (con, demuxedBatch)-> proxy.verbose "proxy sending: #{JSON.stringify demuxedBatch} to #{con.constructor.name}"; con.send demuxedBatch
  proxy.verbose = config.verbose
  wServer = new ws.Server noServer: true
  httpServer.on 'upgrade', (req, socket, head)->
    proxy.verbose "REQUEST: #{req.url}"
    if req.url == '/cmd' # proxy this new connection from a peer
      proxy.newConnectionEndpoint (proxyCon)-> new SocketConnection proxyCon, socket, head
    else if req.url == '/proxy' # main connection from a Xus server
      wServer.handleUpgrade req, socket, head, (con)->
        new WebSocketConnection proxy, con
        connectBlock proxy
    else con.destroy()
  proxy
