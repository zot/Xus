window.Xus = exports = module.exports = require './base'
require './proto'
{log, ProxyMux, WebSocketConnection} = require './transport'
require './peer'
window._ = _ = require './lodash.min'

if window.MozWebSocket then window.WebSocket = window.MozWebSocket

exports.xusToProxy = (xus, url, verbose)->
  proxy = new ProxyMux xus
  if verbose? then proxy.verbose = log
  sock = new WebSocket url
  sock.onopen = -> new WebSocketConnection proxy, sock
