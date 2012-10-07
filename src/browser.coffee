window.xus = exports = module.exports = require './base'
require './proto'
{log, ProxyMux, WebSocketConnection} = require './transport'
_ = require './lodash.min'

if window.MozWebSocket then window.WebSocket = window.MozWebSocket

exports.xusToProxy = (xus, url)->
  proxy = new ProxyMux xus
  proxy.verbose = log
  sock = new WebSocket url
  sock.onopen = -> new WebSocketConnection proxy, sock
