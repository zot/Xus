exports = module.exports
_ = require './lodash.min'

####
# The Xus protocol
#
# Each message is a 
####

####
# cmds is a list of commands a peer can send
####

cmds = ['connect', 'badMessage', 'set', 'put', 'insert', 'remove', 'removeFirst', 'removeAll']

####
# Set commands -- commands that change data
#
# set key value [storageMode]
#   set the value of a key and optionally change its storage mode
#
# put key value index
#
# insert key value index
#
# removeFirst key value
#   remove the first occurance of value in the key's array
#
# removeAll key value
#   remove all occurances of value in the key's array
# 
####

setCmds = ['set', 'put', 'insert', 'removeFirst', 'removeAll']

####
# ERROR TYPES
####

# warning_no_storage doesn't disconnect, but the changes are only affect memory
warning_no_storage = 'warning_no_storage'

# errors cause disconnect
error_bad_message = 'error_bad_message'
error_bad_storage_mode = 'error_bad_storage_mode'
error_variable_not_object = 'error_variable_not_object'
error_variable_not_array = 'error_variable_not_array'
error_bad_connection = 'error_bad_connection'

####
# STORAGE MODES FOR VARIABLES
####

# memory: this is the default mode -- values are just stored in memory
storage_memory = 'memory'
# transient: new listeners won't get values for this variable
storage_transient = 'transient'
# permanent: values are store in permanent storage, like a database
storage_permanent = 'permanent'

storageModes = [storage_transient, storage_memory, storage_permanent]

####
# CONNECTION CLASS
#
# Transports must add these methods:
#
# dump(): send commands in @q to the connection and clear @q
# 
# disconnect(): disconnect connection
# 
####

exports.Connection = class Connection
  constructor: (@server)->
    @q = []
    @listening = {}
  setName: (@name)->
    @peerPath = "peers/#{name}"
    @listenPath = "#{@peerPath/listeners}"
  connected: false
  dump: -> @server.disconnect this, error_bad_connection, "Connection has no 'dump' method"
  disconnect: -> @server.disconnect this, error_bad_connection, "Connection has no 'disconnect' method"
    

####
# SERVER CLASS -- Xus server objects understand the Xus protocol
#
# connections: an array of objects representing Xus connections
#
# each connection has a 'xus' object with information and operations about the connection
#   connected: boolean indicating whether the peer is still connected
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
  keys: []
  newKeys: false
  oldListens: null
  storageModes: {} # keys and their storage modes
  processMessages: (con, msgs)->
    @processMsg con, msg, msg for msg in msgs
    if @newKeys
      @newKeys = false
      @keys.sort()
    if @oldListens
      @newListens = false
      @setListens con, oldListens
      @oldListens = null
    dump con for con in @connections
  processMsg: (con, [name, key], msg)->
    if con.connected
      if name in cmds
        isSetter = name in setCmds
        if isSetter and key.match '^peers/[^/]+/listen$' then @oldListens = @oldListens || @values[key]
        if @[name] con, msg
          if isSetter
            c.q.push msg for c in @relevantConnections c, prefixes key
            if @storageModes[key] is storage_permanent then @store con, key, value
      else @disconnect con, error_bad_message, "Unknown command, '#{name}' in message: #{JSON.stringify msg}"
  relevantConnections: (con, keyPrefixes)-> _.filter @connections, (c)-> c isnt con && caresAbout c, keyPrefixes
  addPeer: (con, name)->
    con.setName name
    @peers[name] = con
  disconnect: (con, errorType, msg)->
    idx = @connections.indexOf con
    if idx > -1
      @connections.splice idx, 1
      if con.name then peers[con.name] = null
      @error con, errorType, msg
      con.dump()
      @primDisconnect
    # return false so faulty message won't be forwarded
    false
  setListens: (con, listening)->
    old = con.listening
    con.listening = {}
    con.listening[path + '/'] = true for path in listening
    for path in listening
      if _.all prefixes(path), ((p)->!old[p]) then @sendAll con, path
      old[path + '/'] = true
  error: (con, errorType, msg)->
    con.q.push ['error', errorType, msg]
    false
  # Storage methods -- have to be filled in by storage strategy
  store: (con, key, value)-> # do nothing, for now
    @error con, warning_no_storage, "Can't store #{key} = #{JSON.stringify value}, because no storage is configured"
  delete: (con, key)-> # do nothing, for now
    @error con, warning_no_storage, "Can't delete #{key}, because no storage is configured"
  sendAll: (con, path)-> # send values for path and all of its children to con
    @error con, warning_no_storage, "Can't send data for #{path} because no storage is configured"
  # Commands
  connect: (con, [x, name])->
    if !name then @disconnect con, "No peer name"
    else if @peers[name] then @disconnect con, "Duplicate peer name: #{name}"
    else @addPeer con, name
    true
  badMessage: (con, [x, msg])-> @disconnect con, error_bad_message, "Malformed message: #{JSON.stringify msg}"
  set: (con, [x, key, value, storageMode])->
    if storageMode and storageModes.indexOf(storageMode) is -1 then @error con, error_bad_storage_mode, "#{storageMode} is not a valid storage mode"
    else
      if storageMode and storageMode isnt @storageModes[key] and @storageModes[key] is storage_permanent
        @delete con, key
      if (storageMode || @storageModes[key]) isnt storage_transient
        if !@storageModes[key]
          storageMode = storageMode || storage_memory
          @keys.push key
          @newKeys = true
        @values[key] = value
      if storageMode then @storageModes[key] = storageMode
      true
  put: (con, [x, key, value, index])->
    if !@values[key] || typeof @values[key] != 'object' then @disconnect con, error_variable_not_object, "Can't put with #{key} because it is not an object"
    else
      @values[key][index] = value
      true
  insert: (con, [x, key, value, index])->
    if !(@values[key] instanceof Array) then @disonnect con, error_variable_not_array, "Can't insert into #{key} because it is not an array"
    else
      if index is -1 then @values[key].push value
      else @values[key].splice index, 0, value
      true
  removeFirst: (con, [x, key, value])->
    if !(@values[key] instanceof Array) then @disconnect con, error_variable_not_array, "Can't insert into #{key} because it is not an array"
    else
      val = @values[key]
      idx = val.indexOf value
      if idx > -1 then val.splice idx, 1
      true
  removeAll: (con, [x, key, value])->
    if !(@values[key] instanceof Array) then @disconnect con, error_variable_not_array, "Can't insert into #{key} because it is not an array"
    else
      val = @values[key]
      val.splice idx, 1 while (idx = val.indexOf value) > -1
      true

caresAbout = (con, keyPrefixes)-> _.any keyPrefixes, (p)->con.listening[p] is true

prefixes = (key)->
  result = []
  splitKey = _without (key.split '/'), ''
  while splitKey.length
    result.push splitKey.join '/'
    splitKey.pop()
  result

# binarySearch -- seach a sorted array for a key
# returns the position of the smallest item >= key or array.length, if none
# This is the correct position to insert the item and maintain sorted order

_.search = (key, arr)->
  left = 0
  right = arr.length - 1
  while left < right
    mid = Math.floor (left + right) / 2
    if arr[mid] is key then left = right = mid
    if arr[mid] > key then right = mid
    else left = mid + 1
  return left
