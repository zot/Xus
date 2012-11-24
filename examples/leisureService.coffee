####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

xus = require './peer'
fs = require 'fs'
path = require 'path'

curDir = null

module.exports.main = (master, config)->
  i = 0
  dir = null
  while i < config.args.length
    if config.args[i] == '--dir'
      dir = config.args[++i]
    i++
  if !dir
    console.log "No directory provided; use --dir directory"
    process.exit 1
  fs.realpath dir, (err, pth)->
    dir = path.normalize pth
    peer = master.newPeer()
    peer.set 'this/public/storage', '', 'peer'
    peer.afterConnect ->
      peer.addHandler 'this/public/storage',
        get: ([x, key], errBlock, cont)->
          if m = key.match new RegExp 'peer/[^/]+/public/storage(/(.*)|)$'
            #pth = path.normalize "#{dir}/#{m[2] ? ''}"
            pth = path.normalize m[2] ? ''
            checkDangerous (pth), dir, check errBlock, ->
              fs.realpath path.resolve(dir, pth), check errBlock, (pth)->
                fs.stat pth, check errBlock, (stats)->
                  if stats.isDirectory() then fs.readdir pth, check errBlock, (files)->
                    i = 0
                    result = {}
                    readNext = ->
                      if i < files.length
                        child = path.normalize "#{pth}/#{files[i]}"
                        fs.stat child, check errBlock, (stats)->
                          result[child] =
                            type: if stats.isDirectory() then 'directory' else 'file'
                            size: stats.size
                            atime: stats.atime
                            mtime: stats.mtime
                            ctime: stats.ctime
                          i++
                          readNext()
                      else cont result
                    readNext()
                  else fs.readFile pth, check errBlock, (data)-> cont data.toString()
          else errBlock 'error_bad_peer_request', "Bad storage path: #{key}"
        set: ([x, key, value], errBlock, cont)->
          console.log "STORING: #{key} <- #{value}"
          if m = key.match new RegExp 'peer/[^/]+/public/storage/(.+)$'
            fs.writeFile "#{dir}/#{m[1]}", value, (err)->
              cont value
          else errBlock 'error_bad_peer_request', "File retrieval not supported, yet: #{m[1]}"
    peer.set 'this/links', ['leisure/storage']

check = (errBlock, block)->(err, args...)->
  if err then errBlock 'error_bad_peer_request', err
  else block args...

checkDangerous = (file, dir, block)->
  if path.normalize(path.resolve(dir, file)) == path.join(dir, file) then block()
  else block "Bad file: #{file} != #{path.join dir, file}"
