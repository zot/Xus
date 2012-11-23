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
    @varStorage = new VarStorage @
    @pendingBlocks = []
  afterConnect: (block)-> if @name then block() else @pendingBlocks.push block
  setConnection: (@con)->
    @verbose = @con?.verbose || (->)
    console.log "ADDED CONNECTION: #{@con}, verbose: #{(@con?.verbose || (->)).toString()}"
  #verbose: ->
  # API UTILS
  transaction: (block)->
    @inTransaction = true
    block()
    @inTransaction = false
    @con.send()
  send: (batch)-> @processBatch @con, batch
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
      @[k] = v for k,v of connectedPeerMethods
      for cmd in @queuedListeners
        @listen cmd...
      @queuedListeners = null
      # processBatch was redefined, above
      @processBatch con, batch[1..]
      block() for block in @pendingBlocks
  # PRIVATE
  rename: (newName)->
    newPath = "peer/#{newName}"
    thisPat = new RegExp "^this(?=/|$)"
    oldName = @name ? 'this'
    @name = newName
    exports.renameVars @varStorage.keys, @varStorage.values, oldName, newName
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
      msgs.push ['set', @varStorage.keys[idx], @varStorage.values[@varStorage.keys[idx]]] while @varStorage.keys[idx].match prefix
      @sendTreeSets msgs, callback
    else
      msg = ['value', key, null, true]
      while @varStorage.keys[idx].match prefix
        msg.push @varStorage.keys[idx], @varStorage.values[@varStorage.keys[idx]]
      callback null, null, null, msg, [msg]
  setsForTree: (msg)-> ['set', key, msg[i + 1]] for key, i in msg[4..] by 2
  grabTree: (key, callback)->
    if @name then key = @personalize key
    if !@treeListeners[key] then @treeListeners[key] = []
    @treeListeners[key].push callback
  personalize: (path)-> path.replace new RegExp('^this(?=\/|$)'), "peer/#{@name}"
  addCmd: (cmd)->
    @con.addCmd cmd
    if !@inTransaction then @con.send()
  disconnect: -> @con.close()
  listenersFor: (key)-> _.flatten _.map prefixes(key), (k)=>@changeListeners[k] || []
  handleDelegation: (name, num, cmd)->
    # Override this for your own custom behavior
    @verbose "HANDLING DELEGATION: #{JSON.stringify [name, num, cmd]}"
    @varStorage.handle cmd, (type, msg)-> cmd = ['error', type, msg]
    @verbose "2"
    @con.addCmd ['response', num, cmd]
    @verbose "3"
    @con.send()
    @verbose "4"
  addHandler: (path, obj)-> @varStorage.addHandler @personalize(path), obj

connectedPeerMethods =
  processBatch: (con, batch)->
    @verbose "PEER BATCH: #{JSON.stringify batch}"
    numKeys = @varStorage.keys.length
    oldValues = {}
    for cmd in batch
      [name, key, value, index] = cmd
      if name in setCmds then oldValues[key] = @varStorage.handle ['get', key]
      switch name
        when 'name' then @rename key
        when 'set' then @varStorage.values[key] = value
        when 'put' then @varStorage.values[key][index] = value
        when 'insert'
          if index < 0 then index = @varStorage.values[key].length + 1 + index
          @varStorage.values[key] = @varStorage.values[key].splice(index, 0, value)
        when 'removeFirst'
          idx = @varStorage.values[key].indexOf value
          if idx > -1 then @varStorage.values[key] = @varStorage.values[key].splice(index, 1)
        when 'removeAll' then @varStorage.values[key] = _.without @varStorage.values[key], value
        when 'value'
          for k, i in cmd[4..] by 2
            if !@varStorage.values[k]? then @varStorage.keys.push k
            @varStorage.values[k] = cmd[i]
          if l = @valueListeners[k]
            delete @valueListeners[k]
            block cmd for block in l
        when 'error'
          [name, type, msg] = cmd
          console.log msg
        when 'request'
          console.log "GOT REQUEST: #{JSON.stringify cmd}, batch: #{JSON.stringify batch}"
          [x, name, num, dcmd] = cmd
          @handleDelegation name, num, dcmd
      if name in setCmds && !oldValues[key] then @varStorage.keys.push key
    if numKeys != @varStorage.keys.length then @varStorage.keys.sort()
    for cmd in batch
      [name, key, value, index] = cmd
      if name in setCmds then block(key, @varStorage.values[key], oldValues[key], cmd, batch) for block in @listenersFor key
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
    if @name? then key = key.replace new RegExp("^this(?=/|$)"), "peer/#{@name}"
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

class DelegationHandler
  constructor: (@peer)->
    @values = {}
  value: (reqId, cmd)->
  set: (reqId, cmd)->
  put: (reqId, cmd)->
  splice: (reqId, cmd)->
  removeFirst: (reqId, cmd)->
  removeAll: (reqId, cmd)->
