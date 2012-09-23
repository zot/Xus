exports = module.exports = require './proto'
_ = require './lodash.min'

exports.startSocketServer = (xusServer, port, host, ready)->
  context = {connections: []}
  server = net.createServer (c)-> context.connections.push new Connection xusServer, context, c
  server.listen port, host, ready
  this

class SocketConnection extends proto.Connection
  constructor: (@server, @context, @con)->
    @con.on 'data', (data) =>
      msgs = (@saved + data).split('\n')
      if data[data.length - 1] != '\n' then @saved = msgs.pop()
      @server.processMessages this, _.map msgs, (m)->
        try
          JSON.parse(m)
        catch
          ['error', m]
  connected: true
  dump: ->
    if @connected && @q.length
      @con.write JSON.stringify @q
      @q = []
  disconnect: ->
    @connected = false
    @con.close()
    @q = null
    @context.connections = @context.connections.without @con
