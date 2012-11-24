####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

xus = require './peer'
fs = require 'fs'
path = require 'path'

curDir = null

module.exports.main = (master)->
  console.log "Initializing Leisure Service"
  fs.realpath process.cwd(), (err, pth)->
    curDir = path.normalize pth
    peer = master.newPeer()
    peer.set 'this/public/storage', '', 'peer'
    peer.afterConnect ->
      peer.addHandler 'this/public/storage',
        get: ([x, key], errBlock)->
          console.log "SERVICE GET"
          @verbose "SERVICE GET"
          if m = key.match new RegExp 'peer/[^/]+/public/storage/(.*)$'
            console.log "GET #{m[1]}"
          errBlock 'error_bad_peer_request', "File retrieval not supported, yet: #{m[1]}"
        set: ([x, key, value], errBlock)->
    peer.listen 'this/public/storage', (key, value)->
      switch key.replace /^peer\/[^/]*\/public\/storage\/(.*)$/, '$1'
        when 'retrieve' then retrieveFile peer, value
        when 'list' then listFiles peer, value
        when 'store' then storeFile peer, value
    peer.set 'this/links', ['leisure/storage']

checkDangerous = (file, block)->
  if path.normalize(path.resolve(curDir, file)) == path.join(curDir, file) then block()
  else block "Bad file: #{file}"

sendResult = (key, id, result)-> peer.set responseKey, [id, true, result]
createCheck = (peer, responseKey, id, file)->(block)->(err, args...)->
  if err then peer.set responseKey, [id, false, "Bad path: #{file}"]
  else block args...

retrieveFile = (peer, [responseKey, id, file])->
  check = createCheck peer, responseKey, id, file
  if responseKey.match(/^peer\/[^/]\/public/) || !responseKey.match /^peer\//
    checkDangerous file, check ->
      fs.realpath path.resolve(curDir, file), check (pth)->
        fs.readFile pth, check (data)->
          peer.verbose "Sending file: [#{id}, true, #{JSON.stringify data.toString()}]"
          peer.set responseKey, [id, true, data.toString()]

storeFile = (peer, [responseKey, id, file, contents])->
  check = createCheck peer, responseKey, id, file
  if responseKey.match(/^peer\/[^/]\/public/) || !responseKey.match /^peer\//
    checkDangerous file, check ->
      pth = path.normalize(path.resolve(curDir, file))
      fs.writeFile pth, contents, check ->
        peer.verbose "Sending file: [#{id}, true, #{JSON.stringify contents.toString()}]"
        peer.set responseKey, [id, true]

listFiles = (peer, [responseKey, id, file])->
  check = createCheck peer, responseKey, id, file
  checkDangerous file, check ->
    output = []
    addFile peer, check, curDir, output, ->
      peer.verbose "SENDING FILE LIST: #{JSON.stringify [id, true, output[1..]]}"
      peer.set responseKey, [id, true, output[1..]]

addFile = (peer, check, file, output, block)->
  fs.lstat file, check (stats)->
    output.push file
    if stats.isDirectory()
      fs.readdir file, check (files)->
        addFiles peer, check, files, output, block
    else block()

addFiles = (peer, check, files, output, block)->
  if !files.length then block()
  else
    addFile peer, check, files[0], output, check ->
      addFiles peer, check, files[1...], output, block
