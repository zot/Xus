####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

exports = module.exports = require './base'
{startWebSocketServer} = require './websocket'
{Server} = exports = require './peer'
pfs = require './pfs' # my tiny fs promise lib, based on q
path = require 'path'

usage = (args)->
  console.log "Usage: node #{args[1]} name [-p port]"
  process.exit()

stateFd = null
state = null

setup = (cont)->
  try
    xusDir = path.join process.env.HOME, '.xus'
    stateFile = path.join xusDir, 'state'
    pfs.stat(xusDir)
      .fail(-> pfs.mkdir(xusDir))
      .then(-> pfs.open(stateFile, 'a+'))
      .then((fd)-> pfs.flock (stateFd = fd), 'ex')
      .then(-> pfs.readFile stateFd)
      .then((s)-> (cont || ->)(s))
      .end()
  catch err
    console.log "Error: #{err.stack}"

run = ->
  setup (s)->
    socketAddr = null
    webSocketAddr = null
    state = (s && JSON.parse(s)) || {servers: {}}
    i = 2
    args = process.argv
    if i > args.length then usage args
    name = args[1] # name required -- only one instance per name allowed
    if state.servers[name]
      console.log "Error: there is already a server named #{name}"
      process.exit(2)
    else
      exports.xusServer = xusServer = new Server()
      cmd = null
      while i < args.length
        switch args[i]
          when '-s' then socketAddr = args[++i]
          when '-w' then webSocketAddr = args[++i]
          when '-e' then require(args[++i]).main()
          when '-x' then cmd = args[++i]
          when '-v' then xusServer.verbose = true
        i++
      [host, port] = parseAddr webSocketAddr || ':'
      startWebSocketServer xusServer, host, port, ->
        console.log "Server #{name} started on port: #{xusServer.webSocketServer.address().port}"
        process.env.XUS_SERVER = name
        process.env.XUS_PORT = xusServer.webSocketServer.address().port
        state.servers[name] = xusServer.webSocketServer.address()
        state.servers[name].pid = process.pid
        pfs.truncate(stateFd, 0)
          .then(-> pfs.writeFile(stateFd, JSON.stringify state))
          .then(-> pfs.close(stateFd))
          .then(-> if cmd? then require('child_process').spawn('/bin/sh', ['-c', cmd], {stdio: ['ignore', 1, 2]}) else 1)
          .end()

parseAddr = (addr)->
  [host, port] = parts = addr.split ':'
  if parts.length > 2 then throw new Error "Bad address format, expected [host:]port, but got #{addr}"
  (port && [host || null, port]) || [null, host || null]

exports.run = run
exports.setup = setup
