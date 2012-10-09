####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

xus = require './peer'

exports.main = ->
  peer = xus.xusServer.createPeer (con)-> new xus.Peer con
  peer.set 'this/name', 'echo'
  peer.listen 'this/public/echo', true, (key, value)-> console.log "***\n*** #{key} = #{value}\n***"
