exports = module.exports = require './base'
require './proto'
require './transport'
_ = require './lodash.min'

if window.MozWebSocket then window.WebSocket = window.MozWebSocket

exports.xusToProxy = (xus, url)->
  proxy = new ProxyMux (con, batch)-> xus.processBatch con, batch
  sock = new WebSocket url
  sock.onopen = -> proxy.newXusEndpoint xus, (proxyCon)-> new WebSocketConnection proxyCon, sock
