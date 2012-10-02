exports = module.exports = require './base'
{setCmds, prefixes} = require './proto'

exports.Peer = class Peer
  constructor: (@con)->
    @inTransaction = false
    @changeListeners = {}
    @treeListeners = {}
    @values = {}
    @keys = []
  # API UTILS
  transaction: (block)->
    @inTransaction = true
    block()
    @inTransaction = false
    @con.send()
  listen: (key, simualateSetsForTree, callback)->
    if !callback then [simulateSetsForTree, callback] = [null, simulateSetsForTree]
    if !@changeListeners[key]
      @changeListeners[key] = []
      @insert "this/listen", key, -1
      @grabTree key, (msg, batch)->
        if simulateSetsForTree then sendTreeSets msg, callback
        else callback null, null, msg, batch
        @changeListeners[key].push = callback
    else @tree key, simulateSetsForTree, callback
  value: (key, cookie, isTree, callback)->
    @grabTree key, callback
    @addCmd ['tree', key, cookie, isTree]
  set: (key, value)-> @addCmd ['set', key, value]
  put: (key, index, value)-> @addCmd ['put', key, value, index]
  insert: (key, value, index)-> @addCmd ['insert', key, value, index]
  removeFirst: (key, value)-> @addCmd ['removeFirst', key, value]
  removeAll: (key, value)-> @addCmd ['removeAll', key, value]
  # INTERNAL API
  processBatch: (con, batch)->
    console.log "PEER GOT BATCH: #{batch}"
    newKeys = false
    for cmd in batch
      [name, key, value, index] = cmd
      oldValue = name in setCmds && @values[key]
      switch name
        when 'error'
          [name, type, msg] = cmd
          console.log msg
        when 'set' then @values[key] = value
        when 'put' then @values[key][index] = value
        when 'insert'
          if index < 0 then index = @values[key].length + 1 + index
          @values[key] = @values[key].splice(index, 0, value)
        when 'removeFirst'
          idx = @values[key].indexOf value
          if idx > -1 then @values[key] = @values[key].splice(index, 1)
        when 'removeAll' then @values[key] = _.without @values[key], value
        when 'value' then if @treeListeners[key] then cb cmd, batch for cb in @treeListeners[key]
      if name in setCmds
        block(key, @values[key], oldValue, cmd, batch) for block in @listenersFor key
        if !oldValue
          newKeys = true
          @keys.push key
    if newKeys then sort @keys
  # PRIVATE
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
  setsForTree: (msg)-> ['set', key, msg[i + 1]] for key, i in msg by 2
  grabTree: (key, callback)->
    if !@treeListeners[key] then @treeListeners[key] = []
    @treeListeners[key].push callback
  addCmd: (cmd)->
    if @inTransaction then @con.addCmd cmd
    else
      @con.addCmd cmd
      @con.send()
  disconnect: -> @con.close()
  listenersFor: (key)-> _.flatten prefixes(key), (k)->@changeListeners[k] || []
