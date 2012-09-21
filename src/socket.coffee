proto = require './proto'

proto.Server.start = (port, host, ready)->
  net.createServer (c)-> @handleConnection(c)
  server.listen port, host, ready
  this

proto.Server.handleConnection = (con)->
  xus =
    dump: ->
      if @q.length
        con.write JSON.stringify @q
        @q = []
    disconnect: -> con.close()
  con.on 'data', (data) =>
    msgs = (@saved + data).split('\n')
    if data[data.length - 1] != '\n' then @saved = msgs.pop()
    @processMsg con, msg, msg for msg in msgs
    dump con for con in @connections
  @connections.push(xus)
