####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

require('source-map-support').install()
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
      .done()
  catch err
    console.log "Error: #{err.stack}"

run = ->
  setup (s)->
    config =
      proxy:false
      verbose: ->
      addr: null
      cmd: null
      args: []
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
            #exports.dirMap.push [pattern, new RegExp("^#{dir}/"), "#{dir}/"]
            exports.addDirHandler pattern, dir
          else
            config.args = args[i...]
            i = args.length
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
          .done()
      (if config.proxy then startProxy else startXus) config, httpServer, (master)->
        require(file).main(master, config) for file in requirements

startXus = (config, httpServer, thenBlock)->
  exports.xusServer = xusServer = new Server()
  xusServer.exit = -> process.exit()
  xusServer.verbose = config.verbose
  xusServer.verbose "Starting Xus"
  exports.connectXus xusServer, httpServer
  exports.addXusCometHandler xusServer, new RegExp "^/_comet.io"
  thenBlock xusServer

startProxy = (config, httpServer, thenBlock)->
  if config.verbose then console.log "Starting proxy"
  exports.connectProxy config, httpServer, thenBlock

parseAddr = (addr)->
  [host, port] = parts = addr.split ':'
  if parts.length > 2 then throw new Error "Bad address format, expected [host:]port, but got #{addr}"
  (port && [host || null, port]) || [null, host || null]

exports.FdConnection = class FdConnection extends exports.Connection
  constructor: (@input, @output)->
    super null, @null
    @q = []
    @writing = false
  setMaster: (@master)->
    if @master
      @master.addConnection @
      @read new Buffer 65536
  basicClose: ->
    fs.close @input, (err)-> console.log "Error closing connection: #{err.stack}"
    fs.close @output, (err)-> console.log "Error closing connection: #{err.stack}"
  connected: true
  read: (buf)->
    fs.read @input, buf, 0, buf.length, null, (err, bytesRead)=>
      if err then @verbose "#{d @} disconnect"; @master.disconnect @
      else
        @verbose "#{d @} data '#{data}'"
        @newData buf.toString(null, 0, bytesRead)
        @read buf
  write: (str)->
    if str.length
      @q.push str
      if !@writing
        @writing = true
        @writeNext()
  writeNext: ->
    buf = new Buffer @q[0]
    splice @q, 0, 1
    writeBuffer buf
  writeBuffer: (buf)->
    fs.write @output, buf, 0, buf.length, null, (err, written)=>
      if err then @verbose "#{d @} disconnect"; @master.disconnect @
      else if written < buf.length then @writeBuffer buf.slice(written)
      else if @q.length then @writeNext()
      else @writing = false

exports.run = run
exports.setup = setup
