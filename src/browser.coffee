####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

window.Xus = exports = module.exports = require './base'
require './proto'
{log, ProxyMux, WebSocketConnection} = require './transport'
require './peer'
window._ = _ = require './lodash.min'

if window.MozWebSocket then window.WebSocket = window.MozWebSocket

exports.xusToProxy = (xus, url, verbose)->
  proxy = new ProxyMux xus
  proxy.mainDisconnect = (con)->
    console.log "Disconnecting mux connection and closing"
    window.open '', '_self', ''
    window.close()
  if verbose? then proxy.verbose = log
  sock = new WebSocket url
  sock.onopen = -> new WebSocketConnection proxy, sock
