####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

{Server, startSocketServer} = exports = module.exports = require './socket'
pfs = require './pfs' # my tiny fs promise lib, based on q
path = require 'path'

usage = (args)->
  console.log "Usage: node #{args[1]} name [-p port]"
  process.exit()

stateFd = null

setup = (cont)->
  try
    xusDir = path.join process.env.HOME, '.xus'
    stateFile = path.join xusDir, 'state'
    pfs.stat(xusDir)
      #.fail(-> pfs.mkdir(xusDir))
      #.then(-> pfs.open(stateFile, 'a'))
      # changed when() to allow promises as well as functions
      .fail(pfs.mkdir(xusDir))
      .then(pfs.open(stateFile, 'r+'))
      .then((fd)-> pfs.flock (stateFd = fd), 'ex')
      .then(-> pfs.readFile stateFd)
      .then((s)-> (cont || ->)(s))
      .end()
  catch err
    console.log "Error: #{err.stack}"

run = ->
  console.log "ARGS: #{process.argv.join(' ')}"
  setup (s)->
    state = (s && JSON.parse(s)) || {servers: {}}
    console.log "State: '#{JSON.stringify state}'"
    i = 2
    args = process.argv
    if i > args.length then usage args
    name = args[1] # name required -- only one instance per name allowed
    while i < args.length
      switch args[i]
        when '-p' then port = args[++i]
        when '-h' then host = args[++i]
      i++
    xusServer = new Server()
    startSocketServer xusServer, port, host, ->
      console.log "Server #{name} started on port: #{xusServer.socketServer.address().port}"
      state.servers[name] = {address: xusServer.socketServer.address()}
      pfs.truncate(stateFd, 0)
        .then(pfs.writeFile(stateFd))
        .then(pfs.close(stateFd))

exports.run = run
exports.setup = setup
