####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

{log} = exports = module.exports = require './base'
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
    config =
      proxy:false
      verbose: ->
      addr: null
      cmd: null
    state = (s && JSON.parse(s)) || {servers: {}}
    i = 2
    args = process.argv
    if i > args.length then usage args
    config.name = args[1] # name required -- only one instance per name allowed
    if state.servers[config.name]
      console.log "Error: there is already a server named #{config.name}"
      process.exit(2)
    else
      requirements = []
      while i < args.length
        switch args[i]
          when '-w' then config.addr = args[++i]
          when '-e' then requirements.push args[++i]
          when '-x' then config.cmd = args[++i]
          when '-v' then config.verbose = log
          when '-p' then config.proxy = true
          when '-u'
            pattern = new RegExp "^#{args[++i]}/"
            dir = path.resolve args[++i]
            exports.dirMap.push [pattern, new RegExp("^#{dir}/"), "#{dir}/"]
        i++
      [config.host, config.port] = parseAddr config.addr || ':'
      httpServer = startWebSocketServer config, ->
        console.log "Server #{config.name} started on port: #{httpServer.address().port}"
        process.env.XUS_SERVER = config.name
        process.env.XUS_PORT = httpServer.address().port
        state.servers[config.name] = httpServer.address()
        state.servers[config.name].pid = process.pid
        pfs.truncate(stateFd, 0)
          .then(-> pfs.writeFile(stateFd, JSON.stringify state))
          .then(-> pfs.close(stateFd))
          .then(-> if config.cmd? then require('child_process').spawn('/bin/sh', ['-c', config.cmd], {stdio: ['ignore', 1, 2]}) else 1)
          .end()
      (if config.proxy then startProxy else startXus) config, httpServer, (master)->
        require(file).main(master) for file in requirements

startXus = (config, httpServer, thenBlock)->
  exports.xusServer = xusServer = new Server()
  xusServer.exit = -> process.exit()
  xusServer.verbose = config.verbose
  xusServer.verbose "Starting Xus"
  exports.connectXus xusServer, httpServer
  thenBlock xusServer

startProxy = (config, httpServer, thenBlock)->
  exports.connectProxy config, httpServer, thenBlock

parseAddr = (addr)->
  [host, port] = parts = addr.split ':'
  if parts.length > 2 then throw new Error "Bad address format, expected [host:]port, but got #{addr}"
  (port && [host || null, port]) || [null, host || null]

exports.run = run
exports.setup = setup
