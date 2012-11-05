####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

xus = require './peer'

# 
# peer/X/links -- array of linked variable names
#
# linked variables have an array of peers
# 
# linked variable changes propagate to peer/X/links and vice versa
#
# if a peer disconnects, it gets removed from all of its linked variables
# 

module.exports.main = (master)->
  peer = master.newPeer()
  peer.set 'this/links', ['leisure/storage']
