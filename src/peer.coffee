####
# Copyright (C) 2012, Bill Burdick
# License: ZLIB license
####

{d} = exports = module.exports = require './base'
{setCmds, prefixes} = require './proto'
_ = require './lodash.min'

exports.Peer = class Peer
  constructor: (@con)->
    @inTransaction = false
    @changeListeners = {}
    @treeListeners = {}
    @values = {}
    @keys = []
    @valueListeners = {}
    @queuedListeners = []
    @name = null # this is set on connect, by the original @processBatch
  verbose: ->
  # API UTILS
  transaction: (block)->
    @inTransaction = true
    block()
    @inTransaction = false
    @con.send()
  realListen: (key, simulateSetsForTree, noChildren, callback)->
    key = key.replace /^this\//, "peer/#{@name}/"
    if typeof simulateSetsForTree == 'function'
      noChildren = simulateSetsForTree
      simulateSetsForTree = false
    if typeof noChildren == 'function'
      callback = noChildren
      noChildren = false
    if noChildren then callback = (changedKey, value, oldValue, cmd, batch)->
      if key == changedKey then callback changedKey, value, oldValue, cmd, batch
    if @peerName? then key = key.replace new RegExp("^this(?=/|$)"), "peer/#{@peerName}"
    if !callback then [simulateSetsForTree, callback] = [null, simulateSetsForTree]
    if !@changeListeners[key]
      @changeListeners[key] = []
      @grabTree key, (msg, batch)=>
        if simulateSetsForTree then @sendTreeSets @setsForTree(msg), callback
        else callback key, (if msg[4] is key then msg[5] else null), null, msg, batch
        @changeListeners[key].push callback
      @splice "this/listen", -1, 0, key
    else @tree key, simulateSetsForTree, callback
  listen: (args...)-> # the initial @listen -- on connect, this switches to @realListen
    @queuedListeners.push args
  switchListen: -> # called on connect, by the initial @processBatch
    @listen = @realListen
    for cmd in @queuedListeners
      @listen cmd...
    @queuedListeners = null
  name: (n)-> @addCmd ['name', n]
  value: (key, cookie, isTree, callback)->
    @grabTree key, callback
    @addCmd ['value', key, cookie, isTree]
  set: (key, value, storage)-> @addCmd (if storage then ['set', key, value, storage] else ['set', key, value])
  put: (key, index, value)-> @addCmd ['put', key, value, index]
  splice: (key, spliceArgs...)-> @addCmd ['splice', key, spliceArgs...]
  removeFirst: (key, value)-> @addCmd ['removeFirst', key, value]
  removeAll: (key, value)-> @addCmd ['removeAll', key, value]
  # INTERNAL API
  realProcessBatch: (con, batch)->
    @verbose "Peer batch: #{JSON.stringify batch}"
    numKeys = @keys.length
    oldValues = {}
    for cmd in batch
      [name, key, value, index] = cmd
      if name in setCmds then oldValues[key] = @values[key]
      switch name
        when 'name' then @rename key
        when 'set' then @values[key] = value
        when 'put' then @values[key][index] = value
        when 'insert'
          if index < 0 then index = @values[key].length + 1 + index
          @values[key] = @values[key].splice(index, 0, value)
        when 'removeFirst'
          idx = @values[key].indexOf value
          if idx > -1 then @values[key] = @values[key].splice(index, 1)
        when 'removeAll' then @values[key] = _.without @values[key], value
        when 'value'
          for k, i in cmd[4..] by 2
            if !@values[k]? then @keys.push k
            @values[k] = cmd[i]
          if l = @valueListeners[k]
            delete @valueListeners[k]
            block cmd for block in l
        when 'error'
          [name, type, msg] = cmd
          console.log msg
      if name in setCmds && !oldValues[key] then @keys.push key
    if numKeys != @keys.length then @keys.sort()
    for cmd in batch
      [name, key, value, index] = cmd
      if name in setCmds then block(key, @values[key], oldValues[key], cmd, batch) for block in @listenersFor key
      else if name == 'value' && @treeListeners[key]
        cb cmd, batch for cb in @treeListeners[key]
        delete @treeListeners[key]
  processBatch: (con, batch)->
    if batch[0][0] == 'set' and batch[0][1] == 'this/name'
      @name = batch[0][2]
      @processBatch = @realProcessBatch
      @switchListen()
      @realProcessBatch con, batch[1..]
  # PRIVATE
  rename: (newName)->
    newPath = "peer/#{newName}"
    thisPat = new RegExp "^this(?=/|$)"
    oldName = @peerName ? 'this'
    @peerName = newName
    exports.renameVars @keys, @values, oldName, newName
    t = {}
    for k, v of @treeListeners
      t[k.replace thisPat, newPath] = v
    @treeListeners = t
    c = {}
    for k, v of @changeListeners
      c[k.replace thisPat, newPath] = v
    @changeListeners = c
    listen = "peer/#{newName}/listen"
    if @values[listen]
      oldPat = new RegExp "^peer/#{oldName}(?=/|$)"
      @values[listen] = (k.replace oldPat, newPath).replace(thisPat, newPath)
  sendTreeSets: (sets, callback)->
    for msg in sets
      [x, k, v] = msg
      callback k, v, null, msg, sets
  tree: (key, simulate, callback)->
    prefix = "^#{key}(/|$)"
    idx = _.search @keys, key
    if simulate
      msgs = []
      msgs.push ['set', @keys[idx], @values[@keys[idx]]] while @keys[idx].match prefix
      @sendTreeSets msgs, callback
    else
      msg = ['value', key, null, true]
      while @keys[idx].match prefix
        msg.push @keys[idx], @values[@keys[idx]]
      callback null, null, null, msg, [msg]
  setsForTree: (msg)-> ['set', key, msg[i + 1]] for key, i in msg[4..] by 2
  grabTree: (key, callback)->
    if @peerName then key = key.replace new RegExp("^this(?=/|$)"), "peer/@peerName"
    if !@treeListeners[key] then @treeListeners[key] = []
    @treeListeners[key].push callback
  addCmd: (cmd)->
    @con.addCmd cmd
    if !@inTransaction then @con.send()
  disconnect: -> @con.close()
  listenersFor: (key)-> _.flatten _.map prefixes(key), (k)=>@changeListeners[k] || []

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
