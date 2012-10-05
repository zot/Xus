xus = require './peer'

exports.main = ->
  value = 0
  peer = xus.xusServer.createPeer (con)-> new xus.Peer con
  peer.set 'this/name', 'computed'
  peer.set 'this/public/value', -> value++
