####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

exports = module.exports = require './base'
require './transport'

_ = require './lodash.min'

####
#
# The Xus protocol
#
# Most of the messages change values for Xus' keys
#
# Standard keys
#
# this/** -- equivalent to peer/PEER_NAME/*
#
# peer/X/listen -- list of paths that peer X is listening to
# peer/X/name -- the name of the peer (whatever X is)
#
####

####
# cmds is a list of commands a peer can send
#
# Response is a special command that responds when Xus sends it a 'request' command
#
# Request format: ['request', peerName, requestId, cmd]
# Response format: ['response', requestId, cmd]
####

cmds = ['response', 'value', 'set', 'put', 'splice', 'removeFirst', 'removeAll']

####
# Commands
#
# name name -- set the peer name to a unique name
#
# value cookie tree key -- fetch the value or the tree (it tree is true) for a key
#                       -- sends cmd back, with values added: ["get", cookie, tree, key, k1, v1, ...]
#
# -- commands that change data, they all start with key, value --
#
# set key value [storageMode]
#   set the value of a key and optionally change its storage mode
#
# put key value index
#
# insert key value index -- negative indexes start at the position right after the end
#                        -- so index for a negative is length + 1 + index
#
# removeFirst key value
#   remove the first occurance of value in the key's array
#
# removeAll key value
#   remove all occurances of value in the key's array
#
####

exports.setCmds = setCmds = ['set', 'put', 'splice', 'removeFirst', 'removeAll']

####
# ERROR TYPES
####

# warning_no_storage doesn't disconnect, but the changes are only affect memory
warning_no_storage = 'warning_no_storage'
# warning_bad_peer_request doesn't disconnect, but indicates a problem with a peer request
warning_peer_request = 'warning_peer_request'

# errors cause disconnect
error_bad_message = 'error_bad_message'
error_bad_storage_mode = 'error_bad_storage_mode'
error_variable_not_object = 'error_variable_not_object'
error_variable_not_array = 'error_variable_not_array'
error_duplicate_peer_name = 'error_duplicate_peer_name'
error_private_variable = 'error_private_variable'
error_bad_master = 'error_bad_master'
error_bad_peer_request = 'error_bad_peer_request'

####
# STORAGE MODES FOR VARIABLES
####

# memory: this is the default mode -- values are just stored in memory
storage_memory = 'memory'
# transient: new listeners won't get values for this variable
storage_transient = 'transient'
# permanent: values are store in permanent storage, like a database
storage_permanent = 'permanent'
# peer: 'value' and change commands are delegated to the peer that owns them
# only legal for public peer variables
# note that the peer has the power to disconnect the requester if it returns an error
storage_peer = 'peer'

storageModes = [storage_transient, storage_memory, storage_permanent, storage_peer]

####
# SERVER CLASS -- Xus server objects understand the Xus protocol
#
# connections: an array of objects representing Xus connections
#
# each connection has a 'xus' object with information and operations about the connection
#   isConnected(): boolean indicating whether the peer is still connected
#   name: the peer name
#   q: the message queue
#   listening: the variables it's listening to
#   send(): send the message queue to the connection
#   disconnect(): disconnect the connection
#
####

exports.Server = class Server
  verbose: ->
  newKeys: false
  anonymousPeerCount: 0
  constructor: ->
    @connections = []
    @peers = {}
    @varStorage = new VarStorage @
    @storageModes = {} # keys and their storage modes
    @linksToPeers = {} # key -> {peerName: true...}
    @changedLinks = null
    @pendingRequests = {}
    @pendingRequestNum = 0
  createPeer: (peerFactory)-> exports.createDirectPeer @, peerFactory
  newPeer: -> @createPeer (con)-> new xus.Peer con
  processBatch: (con, batch, nolinks)->
    while batch.length
      @nextBatch = []
      for msg in batch
        @verbose "RECEIVED #{JSON.stringify msg}"
        @processMsg con, msg, msg, nolinks
      nolinks = true
      @varStorage.sortKeys()
      if @newListens
        @setListens con
        @newListens = false
      if @newConLinks
        @setLinks con
        @newConLinks = false
      if @changedLinks
        @processLinks con, @changedLinks
        @changedLinks = null
      batch = @nextBatch
    c.send() for c in @connections
  processMsg: (con, [name], msg, noLinks)->
    if con.isConnected()
      if name in cmds
        if name is 'response' then [x1, x2, tmpMsg] = msg else tmpMsg = msg
        [x, key] = tmpMsg
        if typeof key is 'string'
          key = tmpMsg[1] = key.replace new RegExp('^this/'), "#{con.peerPath}/"
        isMyPeerKey = key.match("^#{con.peerPath}/")
        if !isMyPeerKey && !noLinks && key.match("^peer/") && !key.match("^.*/public(/|$)")
          @primDisconnect con, error_private_variable, """
          Error, #{con.name} (key = #{key}, peerPath = #{con.peerPath}, match = #{key.match("^#{con.peerPath}")}) attempted to change another peer's private variable: '#{key}' in message: #{JSON.stringify msg}
          """
        else
          if isMyPeerKey
            switch key
              when con.listenPath then @newListens = true
              when !noLinks && con.linksPath
                @verbose "Setting links: #{msg}"
                @newConLinks = true
          if !noLinks && @linksToPeers[key]
            if !@changedLinks then @changedLinks = {}
            @changedLinks[key] = true
          if name != 'response' and @shouldDelegate con, key then @delegate con, msg
          else
            @verbose "EXECUTING: #{msg}"
            @[name] con, msg, =>
              @verbose "EXECUTED: #{msg}"
              if name in setCmds
                @verbose "CMD: #{JSON.stringify msg}, VALUE: #{JSON.stringify @varStorage.values[key]}"
                if key == con.namePath then @name con, msg[2]
                else if key == con.masterPath then @setMaster con, msg[2]
                c.addCmd msg for c in @relevantConnections prefixes key
                if @varStorage.keyInfo[key] is storage_permanent then @store con, key, value
      else @primDisconnect con, error_bad_message, """
      Unknown command, '#{name}' in message: #{JSON.stringify msg}
      """
    else if noLinks
      [x, key] = msg
      if !key.match(new RegExp "^this|^peer/#{con.peerPath}/")
        @[name] con, msg, =>
          @verbose "EXECUTED: #{msg}"
          c.addCmd msg for c in @relevantConnections prefixes key
  shouldDelegate: (con, key)->
    if @isPeerVar key
      match = key.match /^peer\/([^/]+)\//
      @peers[match[1]] != con
    else false
  isPeerVar: (key)-> _.any(prefixes(key), (k)=> @varStorage.keyInfo[k] is storage_peer)
  relevantConnections: (keyPrefixes)-> _.filter @connections, (c)-> caresAbout c, keyPrefixes
  setConName: (con, name)->
    con.name = name
    con.peerPath = "peer/#{name}"
    con.namePath = "#{con.peerPath}/name"
    con.listenPath = "#{con.peerPath}/listen"
    con.linksPath = "#{con.peerPath}/links"
    con.masterPath = "#{con.peerPath}/master"
    con.requests = {}
    @peers[name] = con
    @varStorage.setKey con.namePath, name
  addConnection: (con)->
    @verbose "Xus add connection"
    @setConName con, "@anonymous-#{@anonymousPeerCount++}"
    con.listening = {}
    con.links = {}
    @connections.push con
    @varStorage.setKey con.listenPath, []
    con.addCmd ['set', 'this/name', con.name]
    con.send()
  renamePeerKeys: (con, oldName, newName)->
    [@varStorage.keys] = renameVars @varStorage.keys, @varStorage.values, @varStorage.handlers, oldName, newName
    newCL = {}
    newVL = []
    newPrefix = "peer/#{newName}"
    oldPrefixPat = new RegExp "^peer/#{oldName}(?=/|$)"
    for l of con.listening
      l = l.replace oldPrefixPat, newPrefix
      newCL[l] = true
      newVL.push l
    con.listening = newCL
    newVL.sort()
    @varStorage.setKey "#{newPrefix}/listen", newVL
  disconnect: (con, errorType, msg)->
    @primDisconnect con, errorType, msg
    if @nextBatch then @processBatch con, @nextBatch, true
  primDisconnect: (con, errorType, msg)->
    idx = @connections.indexOf con
    batch = []
    if idx > -1
      @varStorage.setKey con.linksPath, []
      batch = @setLinks con
      peerKey = con.peerPath
      peerKeys = @varStorage.keysForPrefix peerKey
      if con.name then delete @peers[con.name]
      @varStorage.removeKey key for key in peerKeys # this could be more efficient, but does it matter?
      @connections.splice idx, 1
      if msg then @error con, errorType, msg
      con.send()
      con.close()
      delete @pendingRequests[num] for num in con.requests
      if con is @master then @exit()
    # return false becuase this is called by messages, so a faulty message won't be forwarded
    false
  exit: -> console.log "No custom exit function"
  setListens: (con)->
    thisPath = new RegExp "^this/"
    conPath = "#{con.peerPath}/"
    old = con.listening
    con.listening = {}
    finalListen = []
    for path in @varStorage.values[con.listenPath]
      if path.match("^peer/") and !path.match("^peer/[^/]+/public") and !path.match("^#{con.peerPath}")
        @primDisconnect con, error_private_variable, "Error, #{con.name} attempted to listen to a peer's private variables in message: #{JSON.stringify msg}"
        return
      path = path.replace thisPath, conPath
      finalListen.push path
      con.listening[path] = true
      if _.all prefixes(path), ((p)->!old[p]) then @sendTree con, path, ['value', path, null, true]
      old[path] = true
    @varStorage.setKey con.listenPath, finalListen
  setLinks: (con)->
    @verbose "PRIM SET LINKS, LINKS PATH: #{con.linksPath}, NEW #{JSON.stringify @varStorage.values[con.linksPath]}, OLD: #{JSON.stringify con.links}"
    old = {}
    old[l] = true for l of con.links
    for l in @varStorage.values[con.linksPath]
      if !old[l]
        @addLink con, l
      else delete old[l]
    for l of old
      @removeLink con, l
  processLinks: (con, changed)->
    for link of changed
      old = {}
      old[l] = true for l of @linksToPeers[link]
      for p in @varStorage.values[link]
        if !old[p]
          @addLink @peers[p], link
        else delete old[p]
      for p of old
        @removeLink @peers[p], link
  addLink: (con, link)->
    @verbose "ADDING LINK: #{JSON.stringify link}"
    if !@linksToPeers[link] then @linksToPeers[link] = {}
    @linksToPeers[link][con.name] = con.links[link] = true
    @nextBatch.push ['splice', link, -1, 0, con.name]
    @nextBatch.push ['splice', "peer/#{con.name}/links", -1, 0, link]
  removeLink: (con, link)->
    @verbose "REMOVING LINK: #{JSON.stringify link}"
    delete con.links[link]
    delete @linksToPeers[link]?[con.name]
    if @linksToPeers[link] && !@linksToPeers[link].length then delete @linksToPeers[link]
    @nextBatch.push ['removeAll', link, con.name]
    @nextBatch.push ['removeAll', "peer/#{con.name}/links", link]
  error: (con, errorType, msg)->
    con.addCmd ['error', errorType, msg]
    false
  sendTree: (con, path, cmd)-> # add values for path and all of its children to msg and send to con
    @handleStorageCommand con, cmd, -> con.addCmd cmd
  # delegation
  delegate: (con, cmd)->
    [x, key] = cmd
    if match = key.match /^peer\/([^/]+)\//
      @verbose "DELEGATING: #{JSON.stringify cmd}"
      peer = @peers[match[1]]
      num = @pendingRequestNum++
      peer.requests[num] = true
      @pendingRequests[num] = [peer, con]
      peer.addCmd ['request', con.name, num, cmd]
    else @error con, error_bad_peer_request, "Bad request: #{cmd}"
  get: (key)-> @varStorage.values[key]
  name: (con, name)->
    if !name? then @primDisconnect con, error_bad_message, "No name given in name message"
    else if @peers[name] then @primDisconnect con, error_duplicate_peer_name, "Duplicate peer name: #{name}"
    else
      delete @peers[con.name]
      @renamePeerKeys con, con.name, name
      @setConName con, name
      con.addCmd ['set', 'this/name', name]
  setMaster: (con, value)->
    if @master? and @master != con then @primDisconnect con, error_bad_master, "Xus cannot serve two masters"
    else
      @master = if value then con else null
      con.addCmd ['set', 'this/master', value]
  # Commands
  value: (con, cmd, cont)-> # cookie, courtesy of Shlomi
    [x, key] = cmd
    if @isPeerVar key then @delegate con, [cmd], cont
    else
      @handleStorageCommand con, cmd, ->
        con.addCmd cmd
        cont()
  set: (con, cmd, cont)->
    [x, key, value, storageMode] = cmd
    if storageMode and storageModes.indexOf(storageMode) is -1 then @error con, error_bad_storage_mode, "#{storageMode} is not a valid storage mode"
    else if @varStorage.values[key] is value then false
    else
      if storageMode and storageMode isnt @varStorage.keyInfo[key] and @varStorage.keyInfo[key] is storage_permanent
        @remove con, key
      @varStorage.keyInfo[key] = storageMode = storageMode || @varStorage.keyInfo[key] || storage_memory
      if storageMode isnt storage_transient
        if !@varStorage.keyInfo[key]
          @varStorage.keys.push key
          @newKeys = true
        @handleStorageCommand con, cmd, ->
          cmd[2] = value
          cont()
      else cont()
  put: (con, cmd, cont)-> @handleStorageCommand con, cmd, cont
  splice: (con, cmd, cont)-> @handleStorageCommand con, cmd, cont
  removeFirst: (con, cmd, cont)->
    [x, key, value] = cmd
    if !@varStorage.canRemove(key) then @primDisconnect con, error_variable_not_array, "Can't insert into #{key} because it does not support splice and indexOf"
    else @handleStorageCommand con, cmd, cont
  removeAll: (con, cmd, cont)-> @handleStorageCommand con, cmd, cont
  response: (con, rcmd, cont)->
    [x, id, cmd] = rcmd
    [peer, receiver] = @pendingRequests[id]
    delete @pendingRequests[id]
    if peer != con then @primDisconnect peer, error_bad_peer_request, "Attempt to responsd to an invalid request"
    else
      delete peer.requests[id]
      if cmd?
        [cmdName, key, arg] = cmd
        if cmdName is 'error' and key is error_bad_peer_request then @primDisconnect receiver, key, arg
        else if cmdName in ['error', 'value'] then receiver.addCmd cmd
        else c.addCmd msg for c in @relevantConnections prefixes key
      cont()
  handleStorageCommand: (con, cmd, cont)-> @varStorage.handle cmd, ((type, msg)=> @primDisconnect con, type, msg), cont

exports.VarStorage = class VarStorage
  constructor: (@owner)->
    @keys = []
    @values = {}
    @handlers = {}
    @keyInfo = {}
    @newKeys = false
  toString: -> "A VarStorage"
  verbose: (args...)-> @owner.verbose args...
  handle: (cmd, errBlock, cont)->
    [name, key, args...] = cmd
    @handlerFor(key)[name] cmd, errBlock, cont
  handlerFor: (key)->
    k = _.find prefixes(key), (p)=> @handlers[p]
    handler = if k then @handlers[k] else @
    handler
  addKey: (key, info)->
    if !@keyInfo[key]
      @newKeys = true
      @keyInfo[key] = info
      @keys.push key
  sortKeys: ->
    if @newKeys
      @keys.sort()
      @newKeys = false
  setKey: (key, value, info)->
    if typeof value == 'function'
      obj = @addHandler key, put: ([x, args...], errBlock, cont)->
        try
          result = value args...
        catch err
          return errBlock error_bad_peer_request, "Error in computed value: #{if err.stack then err.stack.join '\n' else err}"
        cont result
      obj.set = obj.get = obj.put
    else @values[key] = value
    @addKey key, info || storage_memory
    value
  removeKey: (key)->
    delete @keyInfo[key]
    delete @varStorage.values[key]
    idx = _.sortedIndex key, @keys
    if idx > -1 then @keys.splice idx, 1
  isObject: (key)-> typeof @values[key] == 'object'
  canSplice: (key)-> !@values[key] ||(@values[key].splice? && @values[key].length?)
  canRemove: (key)-> canSplice(key) && @values[key].indexOf?
  contains: (key)-> @values[key]?
  keysForPrefix: (pref)-> keysForPrefix @keys, @values, pref
  addHandler: (path, obj)->
    obj.__proto__ = @
    obj.toString = -> "A Handler for #{path}"
    @handlers[path] = obj
    @addKey path, 'handler'
    obj
  # handler methods
  value: (cmd, errBlock, cont)->
    [x, path, cookie, tree] = cmd
    if tree
      keys = @keysForPrefix path
      counter = keys.length
      blk = (args...)->
        counter = 0
        errBlock(args...)
      if counter
        for key in keys
          @handle ['get',key], blk, (v)->
            cmd.push key, v
            if --counter == 0 then cont cmd
          if counter < 1 then return
      else cont cmd
    else @handle ['get', path], errBlock, (v)->
      cmd.push path, v
      cont cmd
    cmd
  get: ([x, key], errBlock, cont)-> cont @values[key]
  set: (cmd, errBlock, cont)->
    [x, key, value, info] = cmd
    if storageMode and storageModes.indexOf(storageMode) is -1
      errBlock error_bad_storage_mode, "#{storageMode} is not a valid storage mode"
    else
      @keyInfo[key] = storageMode = storageMode || @keyInfo[key] || storage_memory
      cmd[2] = value
      if storageMode isnt storage_transient
        if !@keyInfo[key] then @keys.push key
        cont(@setKey key, value, info)
  put: ([x, key, value, index], errBlock, cont)->
    if !@values[key] then @values[key] = {}
    if typeof @values[key] != 'object' or @values[key] instanceof Array
      errBlock error_variable_not_object "#{key} is not an object"
    else cont(@values[key][index] = value)
  splice: ([x, key, args...], errBlock, cont)->
    @verbose "SPLICING: #{JSON.stringify [x, key, args...]}"
    if !@values[key] then @values[key] = []
    if typeof @values[key] != 'object' or !(@values[key] instanceof Array)
      errBlock error_variable_not_array, "#{key} is not an array"
    else
      if index < 0 then index = @varStorage.values[key].length + index + 1
      @values[key].splice args...
      cont @values[key]
  removeFirst: ([x, key, value], errBlock, cont)->
    if typeof @values[key] != 'object' or !(@values[key] instanceof Array)
      errBlock error_variable_not_array, "#{key} is not an array"
    else
      val = @values[key]
      idx = val.indexOf value
      if idx > -1 then val.splice idx, 1
      cont val
  removeAll: ([x, key, value], errBlock, cont)->
    if typeof @values[key] != 'object' or !(@values[key] instanceof Array)
      errBlock error_variable_not_array, "#{key} is not an array"
    else
      val = @values[key]
      val.splice idx, 1 while (idx = val.indexOf value) > -1
      cont val

exports.renameVars = renameVars = (keys, values, handlers, oldName, newName)->
  oldPrefix = "peer/#{oldName}"
  newPrefix = "peer/#{newName}"
  oldPrefixPat = new RegExp "^#{oldPrefix}(?=/|$)"
  trans = {}
  for key in keysForPrefix keys, values, oldPrefix
    newKey = key.replace oldPrefixPat, newPrefix
    values[newKey] = values[key]
    handlers[newKey] = handlers[key]
    trans[key] = newKey
    delete values[key]
    delete handlers[key]
  keys = (k for k of values)
  keys.sort()
  [keys, trans]

keysForPrefix = (keys, values, prefix)->
  initialPattern = "^#{prefix}(/|$)"
  result = []
  idx = _.find [0...keys.length], (i)-> keys[i].match initialPattern
  if idx > -1
    prefixPattern = "^#{prefix}/"
    if values[prefix]? then result.push prefix
    (if values[prefix]? then result.push keys[idx]) while keys[++idx]?.match prefixPattern
  result

caresAbout = (con, keyPrefixes)-> _.any keyPrefixes, (p)-> con.listening[p]

exports.prefixes = prefixes = (key)->
  result = []
  splitKey = _.without (key.split '/'), ''
  while splitKey.length
    result.push splitKey.join '/'
    splitKey.pop()
  result
