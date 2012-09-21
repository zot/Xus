exports = module.exports
$ = require './jquery-1.7.2.min'
_ = require './lodash'

console.log "jquery: #{jQuery}"

cmds = ['connect', 'set', 'put', 'insert', 'remove', 'removeFirst', 'removeAll']

setCmds = ['set', 'put', 'insert', 'removeFirst', 'removeAll']

####
# Server
#
# connections: an array of objects representing Xus connections
#
# each connection has a 'xus' object with information and operations about the connection
#   name: the peer name
#   q: the message queue
#   listening: the variables it's listening to
#   dump(): dump the message queue to the connection
#   disconnect(): disconnect the connection
# 
####

exports.Server = class Server
  connections: []
  peers: {}
  values: {}
  transient: {} # names of transient keys
  processMsg: (con, [name, key], msg)->
    if name in cmds
      @[name] con, msg
      if name in setCmds
        con.q.push msg for con in @relevantConnections prefixes key
        if key.match '^peers/listen$' then @setListening con, @values[key]
        if !(@transient[key] is true) then @store key, value
    else @disconnect con, "Bad message: #{msg}"
  relevantConnections: (keyPrefixes)-> _.filter @connections, (con)->caresAbout con, keyPrefixes
  addPeer: (con, name)->
    @peers[name] = con
    con.name = name
    con.listening = {}
    con.q = []
  disconnect: (con, msg)->
    idx = @connections.indexOf con
    if idx > -1
      @connections.splice idx, 1
      if con.name then peers[con.name] = null
      con.q.push ["error", msg]
      con.dump()
      @primDisconnect
  setListening: (con, listening)->
    old = con.listening
    con.listening = {}
    con.listening[path + '/'] = true for path in listening
    for path in listening
      if _.all prefixes(path), ((p)->!old[p]) then @sendAll con, path
      old[path + '/'] = true
  # Storage methods -- have to be filled in by storage strategy
  store: (key, value)-> # do nothing, for now
    throw new Error "Can't store #{key} = #{JSON.stringify value}, because no storage is configured"
  sendAll: (con, path)-> # send values for path and all of its children to con
    throw new Error "Can't send data for #{path} because no storage is configured"
  # Commands
  connect: (con, [x, name])->
    if !name then @disconnect con, "No peer name"
    else if @peers[name] then @disconnect con, "Duplicate peer name: #{name}"
    else @addPeer con, name
  set: (con, [x, key, value])-> @values[key] = value
  put: (con, [x, key, index, value])-> @values[key][index] = value
  insert: (con, [x, key, index, value])->
    if index == -1 then @values[key].push value
    else @values[key].splice index, 0, value
  removeFirst: (key, value)->
    val = @values[key]
    idx = val.indexOf value
    if idx > -1 then val.splice idx, 1
  removeAll: (key, value)->
    val = @values[key]
    val.splice idx, 1 while (idx = val.indexOf value) > -1

caresAbout = (con, keyPrefixes)-> _.any keyPrefixes, (p)->con.listening[p] is true

prefixes = (key)->
  result = []
  splitKey = _without (key.split '/'), ''
  while splitKey.length
    result.push splitKey.join '/'
    splitKey.pop()
  result
