####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

exports = module.exports = require './base'
{ProxyMux, SocketConnection, WebSocketConnection, Connection, CometConnection} = require './transport'
ws = require 'ws'
_ = require './lodash.min'
path = require 'path'
fs = require 'fs'
send = require 'send'
url = require 'url'
comet = require 'comet.io'

exports.startWebSocketServer = (config, ready)->
  app = require('http').createServer createHandler(config)
  if config.port then app.listen config.port, config.host, ready
  else app.listen ready
  app

exports.addXusCometHandler = addXusCometHandler = (xusServer, pattern)->
  cometServer = comet.createServer()
  handler = (pathname, req, res, next)->
    cometServer.serve req, res
  handler.urlPat = pattern
  urlHandlers.splice 0, 0, handler
  cometServer.on 'connection', (socket)-> new CometConnection xusServer, socket

exports.dirMap = dirMap = []
urlHandlers = []
extensions =
  js: 'application/javascript'
  html: 'text/html'
  gif: 'image/gif'
  css: 'text/css'
  png: 'image/png'

exports.addDirHandler = addDirHandler = (urlPat, dir)->
  if dir[dir.length - 1] != '/' then dir = "#{dir}/"
  dirPattern = new RegExp "^#{dir}"
  handler = (pathname, req, res, next)->
    file = path.resolve pathname.replace(urlPat, dir)
    if "#{file}/" == dir
      file = "#{file}/index.html"
      pathname = "#{pathname}/index.html"
    if file.match dirPattern
      send(req, pathname.replace urlPat, "/")
      .root(dir)
      .pipe(res)
    else
      next()
  handler.urlPat = urlPat
  urlHandlers.push handler

createHandler = (config)-> (req, res)->
  #req.on 'end', ->
  nextHandler 0, url.parse(req.url).pathname, req, res

nextHandler = (index, pathname, req, res)->
  handler = urlHandlers[index]
  if !handler
    badPage req, res
  else if handler and pathname.match handler.urlPat
    handler pathname, req, res, -> nextHandler index + 1, pathname, req, res
  else nextHandler index + 1, pathname, req, res

contentType = (file)-> extensions[file.replace /^.*\.([^.]*)$/, '$1'] ? 'text/plain'

badPage = (req, res)->
  res.writeHead 404
  res.end "<html><body>Web page #{req.url} not available</body></html>"

exports.connectXus = (xusServer, httpServer)->
  xusServer.webSocketServer = httpServer
  wServer = new ws.Server noServer: true
  httpServer.on 'upgrade', (req, socket, head)->
    if req.url == '/cmd' then new SocketConnection xusServer, socket, head
    else if req.url == '/peer' then wServer.handleUpgrade req, socket, head, (con)->
      wsCon = new WebSocketConnection xusServer, con
      wsCon.sendPending()
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
        wsCon = new WebSocketConnection proxy, con
        wsCon.sendPending()
        connectBlock proxy
    else con.destroy()
  proxy
