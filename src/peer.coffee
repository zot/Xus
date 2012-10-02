{prefixes} = exports = require './proto'

exports.PeerConnection = class PeerConnection
  send: (batch)->

exports.Peer = class Peer
  constructor: (@connection)->
    @cmds = null
    @changeListeners = {}
    @treeListeners = {}
    @values = {}
  # listen(key, (cmd, newValue, oldValue, cmds)-> ...)
  listen: (key, callback, simualateSetsForTree)->
    if !@changeListeners[key]
      @changeListeners[key] = []
      @insert "this/list", -1, key
    else @tree key
    @grabTree key, (msg, batch)->
      if simulateSetsForTree then callback setMsg, batch for setMsg in @setsForTree msg
      callback (if simulateSetsForTree then @setsForTree msg else msg), batch
      @changeListeners[key].push = callback
  setsForTree: (msg)-> ['set', key, msg[i + 1]] for key, i in msg by 2
  grabTree: (key, callback)->
    if !@treeListeners[key] then @treeListeners[key] = []
    @treeListeners[key].push callback
  tree: (key, callback)->
    @grabTree key, callback
    @pushCmd ['tree', key]
  set: (key, value)-> @pushCmd ['set', key, value]
  put: (key, index, value)-> @pushCmd ['put', key, index, value]
  insert: (key, index, value)-> @pushCmd ['insert', key, index, value]
  removeFirst: (key, value)-> @pushCmd ['removeFirst', key, value]
  removeAll: (key, value)-> @pushCmd ['removeAll', key, value]
  pushCmd: (cmd)-> if @cmds? then @cmds.push cmd else throw new Error("Not in a transaction")
  transaction: (block)->
    @cmds = []
    block()
    [curCmds, @cmds] = [@cmds, null]
    if curCmds.length then @connection.send curCmds
  disconnect: -> @connection.disconnect()
  handleBatch: (batch)->
    for cmd in batch
      [name, key, value, index] = cmd
      oldValue = name in setCmds && @values[key]
      switch name
        when 'error'
          [name, type, msg] = cmd
          console.log msg
        when 'set' then @values[key] = value
        when 'put' then @values[key][index] = value
        when 'insert' then @values[key] = @values[key].splice(index, 0, value)
        when 'removeFirst'
          idx = @values[key].indexOf value
          if idx > -1 then @values[key] = @values[key].splice(index, 1)
        when 'removeAll' then @values[key] = _.without @values[key], value
      if name in setCmds then block(cmd, @values[key], oldValue, queue) for block in @listenersFor key
  listenersFor: (key)-> _.flatten prefixes(key), (k)->@listeners[k] || []
