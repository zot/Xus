_ = require './lodash.min'

####
# JSONCodec
#
# encode/decode newline-terminated JSON batches
# 
####

exports.JSONCodec = JSONCodec =
  prepare: (con)-> con.saved = ''
  send: (con, obj)->
    console.log "@@ WRITING @@:#{JSON.stringify @q}"
    con.write "#{JSON.stringify(obj)}\n"
  newData: (con, data)->
    console.log "saved: #{con.saved}"
    msgs = (con.saved + data).split('\n')
    console.log "Received data, saved: #{con.saved}, msgs: #{JSON.stringify msgs},  data: #{data}"
    con.saved = if data[data.length - 1] is '\n' then '' else msgs.pop()
    con.processBatch batch for batch in _.map msgs, (m)->
      console.log "msg: #{m}"
      try
        JSON.parse(m)
      catch err
        ['error', "Could not parse message: #{m}"]

####
# CONNECTION CLASS
#
# Connection constructor takes a master an a codec
#
# master must understand processBatch(connection, batch)
#
# codec must understand these messages:
#   prepare connection -- initialize a connection to be used with the codec
#   send connection, data -- encodes the data and calls connection.write(encodedData)
#   newData connection, data -- handle new data on the connection
#
# Connection classes must define these methods:
#   write str -- send commands to the connection
#   close -- close connection
# 
####

exports.Connection = class Connection
  write: (str)-> @master.disconnect this, error_bad_connection, "Connection has no 'write' method"
  close: -> @master.disconnect this, error_bad_connection, "Connection has no 'disconnect' method"
  constructor: (@master, @codec)->
    @codec = @codec ? JSONCodec
    @codec.prepare @
    @q = []
    @connected = true
  disconnect: ->
    @connected = false
    @q = null
    @close()
  addCmd: (cmd)-> @q.push cmd
  dump: ->
    if @connected && @q.length
      @codec.send @, @q
      @q = []
  newData: (data)-> @codec.newData @, data
  processBatch: (batch)-> @master.processBatch @, batch
