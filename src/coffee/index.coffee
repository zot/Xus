xus = exports = module.exports = require './xus/proto'
net = require('net')
_ = require('lodash')

server = null

###
  Start a xus program
###
exports.boot = (ip, port, ready)-> new Server.boot ip, port, ready

cmds =
  connect: true
  set: true
  put: true
  insert: true
  remove: true
  removeFirst: true
  removeAll: true

class Server
  connections: []
  peers: {}
  boot: (ip, port, ready)->
  server: net.createServer (c)-> @handleConnection(c)
    server.listen port, ready || ->
  handleConnection: (con)->
    @connections.push(con)
    con.on 'data', (data) => @eatData c, data
  eatData: (con, data)->
    msgs = (@saved + data).split('\n')
    if data[data.length - 1] != '\n')
      @saved = msgs.pop()
      @processMsg con, msg for msg in msgs
  processMsg: (con, [cmd])->
    # === with literal true here, instead of using it as a boolean to avoid coercion
    if cmds[cmd] is true then @[cmd] con, msg else @disconnect con, "Bad message: #{msg}"
  addPeer: (con, name)->
    peers[name] = con
    con.name = name
  disconnect: (con)->
    connections.splice (connections.indexOf con), 1
    if con.name then peers[con.name] = null
  connect: (con, [x, name])->
    if !name then @disconnect con, "No peer name"
    else if !peers[name] then @addPeer con, name
    else @disconnect con, "Duplicate peer name: #{name}"
  set: (con, [x, key, value])->
    
  put:
  insert:
  remove:
  removeFirst:
  removeAll:
