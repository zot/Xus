{prefixes} = exports = require './proto'

exports.LocalPeer = class LocalPeer extends Connection
  constructor: (@server)->
    @cmds = null
    @listeners = {}
    @connected = true
    @values = {}
  # listen(key, (cmd, newValue, oldValue, cmds)-> ...)
  listen: (key, callback)->
    if !@listeners[key] then @listeners[key] = []
    @listeners[key].push callback
  set: (key, value)-> @pushCmd ['set', key, value]
  put: (key, index, value)-> @pushCmd ['put', key, index, value]
  insert: (key, index, value)-> @pushCmd ['insert', key, index, value]
  removeFirst: (key, value)-> @pushCmd ['removeFirst', key, value]
  removeAll: (key, value)-> @pushCmd ['removeAll', key, value]
  pushCmd: (cmd)->
    if @cmds is null then throw new Error("Not in a transaction")
    @cmds.push cmd
  transaction: (block)->
    @cmds = []
    block()
    [curCmds, @cmds] = [@cmds, null]
    if curCmds.length then @server.processMessages this, curCmds
  disconnect: ->
    @connected = false
  dump: ->
    [queue, @q] = [@q, []]
    for cmd in queue
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
