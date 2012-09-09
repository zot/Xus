xus = exports = module.exports = require './xus/proto'
net = require('net')
_ = require('lodash')

cmds = ['connect', 'set', 'put', 'insert', 'remove', 'removeFirst', 'removeAll']

setCmds = ['set', 'put', 'insert', 'removeFirst', 'removeAll']

###
  Start a xus program
###
exports.boot = (ip, port, ready)-> new Server.boot ip, port, read

class Server
  connections: []
  peers: {}
  values: {}
  transient: {} # names of transient keys
  boot: (port, host, ready)->
    net.createServer (c)-> @handleConnection(c)
    server.listen port, host, ready
    this
  handleConnection: (con)->
    con.q = []
    @connections.push(con)
    con.on 'data', (data) =>
      msgs = (@saved + data).split('\n')
      if data[data.length - 1] != '\n' then @saved = msgs.pop()
      @processMsg con, msg, msg for msg in msgs
      dump con for con in @connections
  processMsg: (con, [name, key], msg)->
    if name in cmds
      @[name] con, msg
      if name in setCmds
        con.q.push msg for con in @relevantConnections prefixes key
        if key.match '^peers/listen$' then setListening con, @values[key]
        if !(@transient[key] is true) then @store key, value
    else @disconnect con, "Bad message: #{msg}"
  relevantConnections: (keyPrefixes)-> _.filter @connections, (con)->caresAbout con, keyPrefixes
  addPeer: (con, name)->
    @peers[name] = con
    con.name = name
    con.listening = {}
  disconnect: (con, msg)->
    idx = @connections.indexOf con
    if idx > -1
      @connections.splice idx, 1
      if con.name then peers[con.name] = null
      con.q.push ["error", msg]
      dump con
  store: (key, value)-> # do nothing, for now
    console.log "Store #{key} = #{JSON.stringify value}"
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

dump = (con)->
  if con.q.length
    con.write JSON.stringify con.q
    con.q = []

setListening = (con, listening)->
  con.listening = {}
  con.listening[path + '/'] = true for path in listening

caresAbout = (con, keyPrefixes)-> _.any keyPrefixes, (p)->con.listening[p] is true

prefixes = (key)->
  result = []
  splitKey = key.split '/'
  while splitKey.length
    result.push splitKey.join '/'
    splitKey.pop()
  result
