####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

xus = require './peer'
fs = require 'fs'
path = require 'path'

curDir = null

module.exports.main = (master)->
  fs.realpath process.cwd(), (err, pth)->
    curDir = path.normalize pth
    peer = master.newPeer()
    peer.set 'this/public/storage/list', '', 'transient'
    peer.set 'this/public/storage/request', '', 'transient'
    peer.listen 'this/public/storage', (key, value)->
      console.log "GOT REQUEST: #{key.replace /^peer\/[^/]\/public\/storage\/(.*)$/, '$1'}, #{value}"
      switch key.replace /^peer\/[^/]*\/public\/storage\/(.*)$/, '$1'
        when 'request' then requestFile peer, value
        when 'list' then listFiles peer, value
    peer.set 'this/links', ['leisure/storage']

requestFile = (peer, [responseKey, id, file])->
  if responseKey.match(/^peer\/[^/]\/public/) || !responseKey.match /^peer\//
    fs.realpath path.resolve(curDir, file), (err, pth)->
      if !err && path.normalize(file) == file
        fs.readFile pth, (err, data)->
          if err then peer.set responseKey, [id, false, "Bad file: #{file}"]
          else
            console.log "Sending file: [#{id}, true, #{JSON.stringify data.toString()}]"
            peer.set responseKey, [id, true, data.toString()]
      else
        peer.set responseKey, [id, false, "Bad path: #{file}"]

listFiles = (peer, [responseKey, id])->
  output = []
  addFile peer, curDir, output, (err)->
    if err then peer.set responseKey, [id, false, "Error listing files"]
    else
      console.log "SENDING FILE LIST: #{JSON.stringify [id, true, output[1..]]}"
      peer.set responseKey, [id, true, output[1..]]

addFile = (peer, file, output, block)->
  fs.lstat file, (err, stats)->
    if err then block err
    else
      output.push file
      if stats.isDirectory()
        fs.readdir file, (err, files)->
          if err then block(err)
          else addFiles peer, files, output, block
      else block()

addFiles = (peer, files, output, block)->
  if !files.length then block()
  else
    addFile peer, files[0], output, (err)->
      if err then block(err)
      else addFiles peer, files[1...], output, block
