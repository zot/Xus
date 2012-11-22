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
    @varStorage = new VarStorage
    @storageModes = {} # keys and their storage modes
    @linksToPeers = {} # key -> {peerName: true...}
    @changedLinks = null
    @pendingRequests = {}
    @pendingRequestNum = 0
  createPeer: (peerFactory)-> exports.createDirectPeer @, peerFactory
  newPeer: -> @createPeer (con)-> new xus.Peer con
  processBatch: (con, batch)->
    @verbose "RECEIVED #{JSON.stringify batch}"
    for msg in batch
      @processMsg con, msg, msg
    @varStorage.sortKeys()
    if @newListens
      @setListens con
      @newListens = false
    if @newConLinks
      @setLinks con
      @newConLinks = false
    if @changedLinks
      @processLinks(con, @changedLinks)
      @changedLinks = null
    c.send() for c in @connections
  processMsg: (con, [name, key], msg, noLinks)->
    if con.isConnected()
      if name in cmds
        if typeof key is 'string' then key = msg[1] = key.replace new RegExp('^this/'), "#{con.peerPath}/"
        isMyPeerKey = key.match("^#{con.peerPath}/")
        if !isMyPeerKey && !noLinks && key.match("^peer/") && !key.match("^.*/public(/|$)")
          @disconnect con, error_private_variable, "Error, #{con.name} (key = #{key}, peerPath = #{con.peerPath}, match = #{key.match("^#{con.peerPath}")}) attempted to change another peer's private variable: '#{key}' in message: #{JSON.stringify msg}"
        else
          if isMyPeerKey
            switch key
              when con.listenPath then @newListens = true
              when !noLinks && con.linksPath then @newConLinks = true
          if !noLinks && @linksToPeers[key]
            if !@changedLinks then @changedLinks = {}
            @changedLinks[key] = true
          if name != 'response' and @shouldDelegate con, key then @delegate con, msg
          else if (@[name] con, msg, msg) and name in setCmds
            @verbose "CMD: #{JSON.stringify msg}, VALUE: #{JSON.stringify @varStorage.values[key]}"
            if key == con.namePath then @name con, msg[2]
            else if key == con.masterPath then @setMaster con, msg[2]
            c.addCmd msg for c in @relevantConnections prefixes key
            if @varStorage.keyInfo[key] is storage_permanent then @store con, key, value
      else @disconnect con, error_bad_message, "Unknown command, '#{name}' in message: #{JSON.stringify msg}"
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
    [@varStorage.keys] = renameVars @varStorage.keys, @varStorage.values, oldName, newName
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
    idx = @connections.indexOf con
    if idx > -1
      @varStorage.setKey con.linksPath, []
      @setLinks con
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
        @disconnect con, error_private_variable, "Error, #{con.name} attempted to listen to a peer's private variables in message: #{JSON.stringify msg}"
        return
      path = path.replace thisPath, conPath
      finalListen.push path
      con.listening[path] = true
      if _.all prefixes(path), ((p)->!old[p]) then @sendTree con, path, ['value', path, null, true]
      old[path] = true
    @varStorage.setKey con.listenPath, finalListen
  setLinks: (con)->
    filter = {}
    batch = []
    old = {}
    old[l] = true for l of con.links
    for l in @varStorage.values[con.linksPath]
      if !old[l]
        @addLink con, l
        batch.push ['splice', l, -1, 0, con.name]
      else delete old[l]
    for l of old
      @removeLink con, l
      batch.push ['removeAll', l, con.name]
    @processMsg con, cmd, cmd, true for cmd in batch
  processLinks: (con, changed)->
    batch = []
    for link of changed
      old = {}
      old[l] = true for l of @linksToPeers[link]
      for p in @varStorage.values[link]
        if !old[p]
          @addLink @peers[p], link
          batch.push ['splice', "peer/#{p}/links", -1, 0, link]
        else delete old[p]
      for p of old
        @removeLink @peers[p], link
        batch.push ['removeAll', "peer/#{p}/links", link]
    @processMsg con, cmd, cmd, true for cmd in batch
  addLink: (con, link)->
    if !@linksToPeers[link] then @linksToPeers[link] = {}
    @linksToPeers[link][con.name] = con.links[link] = true
  removeLink: (con, link)->
    delete con.links[link]
    delete @linksToPeers[link]?[con.name]
    if @linksToPeers[link] && !@linksToPeers[link].length then delete @linksToPeers[link]
  error: (con, errorType, msg)->
    con.addCmd ['error', errorType, msg]
    false
  sendTree: (con, path, cmd)-> # add values for path and all of its children to msg and send to con
    con.addCmd @varStorage.valueTree(con, path, cmd)
  # delegation
  delegate: (con, cmd)->
    [x, key] = cmd
    if match = key.match /^peer\/([^/]+)\//
      peer = @peers[match[1]]
      num = @pendingRequestNum++
      peer.requests[num] = true
      @pendingRequests[num] = [peer, con]
      peer.addCmd ['request', con.name, num, cmd]
    else @error con, error_bad_peer_request, "Bad request: #{cmd}"
  name: (con, name)->
    if !name? then @disconnect con, error_bad_message, "No name given in name message"
    else if @peers[name] then @disconnect con, error_duplicate_peer_name, "Duplicate peer name: #{name}"
    else
      delete @peers[con.name]
      @renamePeerKeys con, con.name, name
      @setConName con, name
      con.addCmd ['set', 'this/name', name]
  setMaster: (con, value)->
    if @master? and @master != con then @disconnect con, error_bad_master, "Xus cannot serve two masters"
    else
      @master = if value then con else null
      con.addCmd ['set', 'this/master', value]
  # Storage methods -- have to be filled in by storage strategy
  store: (con, key, value)-> # do nothing, for now
    @error con, warning_no_storage, "Can't store #{key} = #{JSON.stringify value}, because no storage is configured"
  remove: (con, key)-> # do nothing, for now
    @error con, warning_no_storage, "Can't delete #{key}, because no storage is configured"
  # Commands
  # value: (con, [x, key, cookie, tree], cmd)-> # cookie, courtesy of Shlomi
  #   if @isPeerVar key then @delegate con, [cmd]
  #     else if tree then @sendTree con, key, cmd
  #     else
  #       if (value = @varStorage.handle ['get', key])? then cmd.push key, value
  #       con.addCmd cmd
  value: (con, [x, key], cmd)-> # cookie, courtesy of Shlomi
    if @isPeerVar key then @delegate con, [cmd]
    else
      @varStorage.value con, cmd
      con.addCmd cmd
      true
  set: (con, [x, key, value, storageMode], cmd)->
    if storageMode and storageModes.indexOf(storageMode) is -1 then @error con, error_bad_storage_mode, "#{storageMode} is not a valid storage mode"
    else if @varStorage.values[key] is value then false
    else
      if storageMode and storageMode isnt @varStorage.keyInfo[key] and @varStorage.keyInfo[key] is storage_permanent
        @remove con, key
      if (storageMode || @varStorage.keyInfo[key]) isnt storage_transient
        if !@varStorage.keyInfo[key]
          storageMode = storageMode || storage_memory
          @varStorage.keys.push key
          @newKeys = true
        @handleStorageCommand con, cmd
      if storageMode then @varStorage.keyInfo[key] = storageMode
      cmd[2] = value
      true
  put: (con, [x, key, value, index], cmd)->
    @handleStorageCommand con, cmd
    true
  splice: (con, [x, key, index, del, items...], cmd)->
    @handleStorageCommand con, cmd
    true
  removeFirst: (con, [x, key, value], cmd)->
    if !@varStorage.canRemove(key) then @disconnect con, error_variable_not_array, "Can't insert into #{key} because it does not support splice and indexOf"
    else
      @handleStorageCommand con, cmd
      true
  removeAll: (con, [x, key, value], cmd)->
    @handleStorageCommand con, cmd
    true
  handleStorageCommand: (con, cmd)-> @varStorage.handle cmd, (type, msg)=> @disconnect con, type, msg
  response: (con, [x, id, cmd])->
    [peer, receiver] = @pendingRequests[id]
    delete @pendingRequests[id]
    if peer != con then @disconnect peer, error_bad_peer_request, "Attempt to responsd to an invalid request"
    else
      delete peer.requests[id]
      if cmd?
        [cmdName, key, arg] = cmd
        if cmdName is 'error' and key is error_bad_peer_request then @disconnect receiver, key, arg
        else if cmdName in ['error', 'value'] then receiver.addCmd cmd
        else c.addCmd msg for c in @relevantConnections prefixes key

exports.VarStorage = class VarStorage
  constructor: ->
    @keys = []
    @values = {}
    @handlers = {}
    @keyInfo = {}
    @newKeys = false
  handle: ([cmd, key, args...], errBlock)-> @handlerFor(key)[cmd] [cmd, key, args...], errBlock
  handlerFor: (key)->
    k = _.find prefixes(key), (p)=> @handlers[p]
    res = if k then @handlers[k] else @
    res
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
      h = @handlers[key] = new BasicVarHandler @
      h.put = h.set = h.get = (args...)-> value args...
    else @values[key] = value
    @addKey key, info || storage_memory
  removeKey: (key)->
    delete @keyInfo[key]
    delete @varStorage.values[key]
    idx = _.sortedIndex key, @keys
    if idx > -1 then @keys.splice idx, 1
  isObject: (key)-> typeof @values[key] == 'object'
  canSplice: (key)-> !@values[key] ||(@values[key].splice? && @values[key].length?)
  canRemove: (key)-> canSplice(key) && @values[key].indexOf?
  contains: (key)-> @values[key]?
  # access to vars
  value: (con, cmd)->
    [x, path, cookie, tree] = cmd
    if tree
      for key in @keysForPrefix path
        cmd.push key, @handle(['get',key])
    else if (value = @handle(['get', path]))? then cmd.push path, value
    cmd
  valueTree: (con, path, cmd)-> # add values for path and all of its children to msg and send to con
    for key in @keysForPrefix path
      cmd.push key, @handle(['get',key])
    cmd
  keysForPrefix: (pref)-> keysForPrefix @keys, @values, pref
  get: ([x, key])-> @values[key]
  set: ([x, key, value, info])-> @setKey key, value, info
  put: ([x, key, value, index], errBlock)->
    if !@values[key] then @values[key] = {}
    if typeof @values[key] != 'object' or @values[key] instanceof Array then errBlock error_variable_not_object "#{key} is not an object"
    else @values[key][index] = value
  splice: ([x, key, args...], errBlock)->
    if !@values[key] then @values[key] = []
    else if typeof @values[key] != 'object' or !(@values[key] instanceof Array) then errBlock error_variable_not_array, "#{key} is not an array"
    else
      if index < 0 then index = @varStorage.values[key].length + index + 1
      @values[key].splice args...
  removeFirst: ([x, key, value], errBlock)->
    if typeof @values[key] != 'object' or !(@values[key] instanceof Array) then errBlock error_variable_not_array, "#{key} is not an array"
    else
      val = @values[key]
      idx = val.indexOf value
      if idx > -1 then val.splice idx, 1
  removeAll: ([x, key, value], errBlock)->
    if typeof @values[key] != 'object' or !(@values[key] instanceof Array) then errBlock error_variable_not_array, "#{key} is not an array"
    else
      val = @values[key]
      val.splice idx, 1 while (idx = val.indexOf value) > -1

exports.BasicVarHandler = class BasicVarHandler
  constructor: (storage)-> if storage then @.__proto__ = storage

exports.renameVars = renameVars = (keys, values, oldName, newName)->
  oldPrefix = "peer/#{oldName}"
  newPrefix = "peer/#{newName}"
  oldPrefixPat = new RegExp "^#{oldPrefix}(?=/|$)"
  trans = {}
  for key in keysForPrefix keys, values, oldPrefix
    newKey = key.replace oldPrefixPat, newPrefix
    values[newKey] = values[key]
    trans[key] = newKey
    delete values[key]
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

caresAbout = (con, keyPrefixes)-> _.any keyPrefixes, (p)->con.listening[p]

exports.prefixes = prefixes = (key)->
  result = []
  splitKey = _.without (key.split '/'), ''
  while splitKey.length
    result.push splitKey.join '/'
    splitKey.pop()
  result
