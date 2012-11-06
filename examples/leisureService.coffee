####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

xus = require './peer'

# 
# 
# 

module.exports.main = (master)->
  peer = master.newPeer()
  peer.set 'this/links', ['leisure/storage']
