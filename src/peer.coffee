####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

{d} = exports = module.exports = require './base'
{
  setCmds,
  prefixes,
  VarStorage
} = require './proto'
_ = require './lodash.min'

exports.Peer = class Peer
  constructor: (con)->
    @setConnection con
    @inTransaction = false
    @changeListeners = {}
    @treeListeners = {}
    @valueListeners = {}
    @queuedListeners = []
    @name = null # this is set on connect, by the original @processBatch
    @namePrefixPat = /^$/
    @varStorage = new VarStorage @
    peer = @
    defaultHandler = @varStorage.handlerFor
    @varStorage.handlerFor = (key)-> defaultHandler.call @, key.replace peer.namePrefixPat, 'this/'
    @pendingBlocks = []
  addConnection: (con)->
  afterConnect: (block)-> if @name then block() else @pendingBlocks.push block
  setConnection: (@con)->
    @con?.setMaster @
    @verbose = @con?.verbose || (->)
    @verbose "ADDED CONNECTION: #{@con}, verbose: #{@verbose.toString()}"
  verbose: ->
  # API UTILS
  transaction: (block)->
    @inTransaction = true
    block()
    @inTransaction = false
    @con.send()
  send: (batch)-> @processBatch @con, batch
  get: (key)-> @varStorage.values[key]
  listen: (args...)->
    # IMPORTANT!
    # This is the initial @listen -- after connect, switches to connectedPeerMethods.listen
    @queuedListeners.push args
  name: (n)-> @addCmd ['name', n]
  value: (key, cookie, isTree, callback)->
    @grabTree key, callback
    @addCmd ['value', key, cookie, isTree]
  set: (key, value, storage)-> @addCmd (if storage then ['set', key, value, storage] else ['set', key, value])
  put: (key, index, value)-> @addCmd ['put', key, value, index]
  splice: (key, spliceArgs...)-> @addCmd ['splice', key, spliceArgs...]
  removeFirst: (key, value)-> @addCmd ['removeFirst', key, value]
  removeAll: (key, value)-> @addCmd ['removeAll', key, value]
  manage: (key, handler)->
  # INTERNAL API
  processBatch: (con, batch)->
    # IMPORTANT!
    # This is the initial @processBatch -- after connect, switches to connectedPeerMethods.processBatch
    if batch[0][0] == 'set' and batch[0][1] == 'this/name'
      @name = batch[0][2]
      @date = batch[0][3]
      @[k] = v for k,v of connectedPeerMethods
      for cmd in @queuedListeners
        @listen cmd...
      @queuedListeners = null
      # processBatch was redefined, above
      @processBatch con, batch[1..]
      block() for block in @pendingBlocks
  # PRIVATE
  rename: (newName)->
    if @name != newName
      newPath = "peer/#{newName}"
      thisPat = new RegExp "^this(?=/|$)"
      oldName = @name ? 'this'
      @name = newName
      @varStorage.sortKeys()
      exports.renameVars @varStorage.keys, @varStorage.values, @varStorage.handlers, oldName, newName
      t = {}
      for k, v of @treeListeners
        t[k.replace thisPat, newPath] = v
      @treeListeners = t
      c = {}
      for k, v of @changeListeners
        c[k.replace thisPat, newPath] = v
      @changeListeners = c
      listen = "peer/#{newName}/listen"
      if @varStorage.values[listen]
        oldPat = new RegExp "^peer/#{oldName}(?=/|$)"
        @varStorage.values[listen] = (k.replace oldPat, newPath).replace(thisPat, newPath)
  sendTreeSets: (sets, callback)->
    for msg in sets
      [x, k, v] = msg
      callback k, v, null, msg, sets
  tree: (key, simulate, callback)->
    prefix = "^#{key}(/|$)"
    idx = _.sortedIndex @varStorage.keys, key
    if simulate
      msgs = []
      msgs.push ['set', @varStorage.keys[idx], @varStorage.values[@varStorage.keys[idx]]] while @varStorage.keys[idx]?.match prefix
      @sendTreeSets msgs, callback
    else
      msg = ['value', key, null, true]
      while @varStorage.keys[idx].match prefix
        msg.push @varStorage.keys[idx], @varStorage.values[@varStorage.keys[idx]]
      callback null, null, null, msg, [msg]
  setsForTree: (msg)-> ['set', key, msg[i + 1]] for key, i in msg[4..] by 2
  grabTree: (key, callback)->
    #if @name then key = @personalize key
    if !@treeListeners[key] then @treeListeners[key] = []
    @treeListeners[key].push callback
  addCmd: (cmd)->
    @con.addCmd cmd
    if !@inTransaction then @con.send()
  disconnect: -> @con.close()
  listenersFor: (key)->
    _.flatten _.map prefixes(key), (k)=>@changeListeners[k] || []
  handleDelegation: (name, num, cmd)->
    # Override this for your own custom behavior
    @varStorage.handle cmd, ((type, msg)=> @sendCmd ['error', type, msg]), => @sendCmd ['response', num, cmd]
  sendCmd: (cmd)->
    @con.addCmd cmd
    @con.send()
  #addHandler: (path, obj)-> @varStorage.addHandler @personalize(path), obj
  addHandler: (path, obj)-> @varStorage.addHandler path, obj
  personalize: (path)-> path.replace new RegExp('^this(?=\/|$)'), "peer/#{@name}"

connectedPeerMethods =
  processBatch: (con, batch)->
    @verbose "PEER BATCH: #{JSON.stringify batch}"
    numKeys = @varStorage.keys.length
    for cmd in batch
      [name, key, value, index] = cmd
      if key.match @namePrefixPat then key = key.replace @namePrefixPat, 'this/'
      if name in setCmds and !@varStorage.contains key then @varStorage.keys.push key
      # track updates and respond to requests
      switch name
        when 'error' then console.log "ERROR '#{key}': value"
        when 'request'
          @verbose "GOT REQUEST: #{JSON.stringify cmd}, batch: #{JSON.stringify batch}"
          [x, name, num, dcmd] = cmd
          @handleDelegation name, num, dcmd
        else @varStorage.handle cmd, ((type, msg)-> console.log "Error, '#{type}': #{msg}"), ->
    if numKeys != @varStorage.keys.length then @varStorage.keys.sort()
    for cmd in batch
      [name, key, value, index] = cmd
      if key.match @namePrefixPat then key = key.replace @namePrefixPat, 'this/'
      if name == 'set' && key == 'this/name'
        @name = value
        @namePrefixPat = new RegExp "^peer/#{value}/"
      if name in setCmds then block(key, @varStorage.values[key], cmd, batch) for block in @listenersFor key
      else if name == 'value' && @treeListeners[key]
        cb cmd, batch for cb in @treeListeners[key]
        delete @treeListeners[key]
    null
  listen: (key, simulateSetsForTree, noChildren, callback)->
    key = key.replace /^this\//, "peer/#{@name}/"
    if typeof simulateSetsForTree == 'function'
      noChildren = simulateSetsForTree
      simulateSetsForTree = false
    if typeof noChildren == 'function'
      callback = noChildren
      noChildren = false
    if noChildren then callback = (changedKey, value, oldValue, cmd, batch)->
      if key == changedKey then callback changedKey, value, oldValue, cmd, batch
    #if @name? then key = key.replace new RegExp("^this(?=/|$)"), "peer/#{@name}"
    if !callback then [simulateSetsForTree, callback] = [null, simulateSetsForTree]
    if !@changeListeners[key]
      @changeListeners[key] = []
      @grabTree key, (msg, batch)=>
        if simulateSetsForTree then @sendTreeSets @setsForTree(msg), callback
        else callback key, (if msg[4] is key then msg[5] else null), null, msg, batch
        @changeListeners[key].push callback
      @splice "this/listen", -1, 0, key
    else @tree key, simulateSetsForTree, callback

####
#
# createDirectPeer xus, factory -- make a peer with an in-process connection to xus
#
####

exports.createDirectPeer = (xus, peerFactory)->
  ctx = connected: true, server: xus
  # the object that xus uses as its connection
  xusConnection = new DirectConnection
  # the object that the peer uses as its connection
  peerConnection = new DirectConnection
  peerConnection.verbose = xusConnection.verbose = xus.verbose
  peer = (peerFactory ? (con)-> new Peer con) peerConnection
  peerConnection.connect(xusConnection, xus, ctx)
  xusConnection.connect(peerConnection, peer, ctx)
  xus.addConnection xusConnection
  peer

class DirectConnection
  constructor: -> @q = []
  connect: (@otherConnection, @otherMaster, @ctx)->
  isConnected: -> @ctx.connected
  close: ->
    @ctx.connected = false
    @q = @otherConnection.q = null
  addCmd: (cmd)-> @q.push cmd
  send: ->
    if @ctx.connected && @q.length
      @ctx.server.verbose "#{d @} SENDING #{@name}, #{JSON.stringify @q}"
      [q, @q] = [@q, []]
      @otherMaster.processBatch @otherConnection, q
  setMaster: ->

class DelegationHandler
  constructor: (@peer)->
    @values = {}
  value: (reqId, cmd)->
  set: (reqId, cmd)->
  put: (reqId, cmd)->
  splice: (reqId, cmd)->
  removeFirst: (reqId, cmd)->
  removeAll: (reqId, cmd)->
