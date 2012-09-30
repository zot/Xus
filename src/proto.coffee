exports = module.exports
_ = require './lodash.min'

####
# 
# The Xus protocol
#
# Most of the messages change values for Xus' keys
# 
# Standard keys
# 
# this/** -- equivalent to peer/PEER_NAME/*
# 
# peer/X/listen -- list of paths that peer X is listening to
# peer/X/name -- the name of the peer (whatever X is)
# 
####

####
# cmds is a list of commands a peer can send
####

cmds = ['connect', 'tree', 'set', 'put', 'insert', 'remove', 'removeFirst', 'removeAll']

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
# write(str): send commands to the connection
# 
# close(): close connection
# 
####

exports.Connection = class Connection
  constructor: (@server)->
    @q = []
    @listening = {}
    @saved = ''
  setName: (@name)->
    console.log "Setting name: #{@name}"
    @peerPath = "peer/#{name}"
    @listenPath = "#{@peerPath}/listen"
  connected: false
  newData: (data)->
    console.log "saved: #{@saved}"
    msgs = (@saved + data).split('\n')
    console.log "Received data, saved: #{@saved}, msgs: #{JSON.stringify msgs},  data: #{data}"
    @saved = if data[data.length - 1] is '\n' then '' else msgs.pop()
    @server.processBatches @, _.map msgs, (m)->
      console.log "msg: #{m}"
      try
        JSON.parse(m)
      catch err
        ['error', "Could not parse message: #{m}"]
  dump: ->
    if @connected && @q.length
      console.log "@@ WRITING @@:#{JSON.stringify @q}"
      @write "#{JSON.stringify @q}\n"
      @q = []
  disconnect: ->
    @connected = false
    @q = null
    @close()
  write: -> @server.disconnect this, error_bad_connection, "Connection has no 'write' method"
  close: -> @server.disconnect this, error_bad_connection, "Connection has no 'disconnect' method"
    

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
  anonymousPeerCount: 0
  processBatches: (con, batches)->
    for batch in batches
      for msg in batch
        @processMsg con, msg, msg
    if @newKeys
      @newKeys = false
      @keys.sort()
    if @newListens
      @setListens con
      @newListens = false
    con.dump() for con in @connections
  processMsg: (con, [name, key], msg)->
    console.log "PROCESS #{msg}"
    if con.connected
      if name in cmds
        isSetter = name in setCmds
        if typeof key is 'string' then key = msg[1] = key.replace /^this/, "peer/#{con.name}"
        if isSetter and key is con.listenPath then @newListens = true
        if (@[name] con, msg) and isSetter
          console.log "KEY: #{key}, msg: #{JSON.stringify msg}"
          c.q.push msg for c in @relevantConnections c, prefixes key
          if @storageModes[key] is storage_permanent then @store con, key, value
      else @disconnect con, error_bad_message, "Unknown command, '#{name}' in message: #{JSON.stringify msg}"
  relevantConnections: (con, keyPrefixes)-> _.filter @connections, (c)-> c isnt con && caresAbout c, keyPrefixes
  addPeer: (con, name)->
    con.setName name
    @peers[name] = con
    @connections.push con
    @values[con.listenPath] = []
    @values["#{con.peerPath}/name"] = name
  disconnect: (con, errorType, msg)->
    console.log "*\n* DISCONNECT\n*"
    idx = @connections.indexOf con
    if idx > -1
      peerKey = "peer/#{con.name}"
      peerKeys = @keysForPrefix peerKey
      if con.name then delete @peers[con.name]
      @removeKey key for key in peerKeys # this could be more efficient, but does it matter?
      @connections.splice idx, 1
      if msg then @error con, errorType, msg
      con.dump()
      con.close()
    # return false becuase this is called by messages, so a faulty message won't be forwarded
    false
  setListens: (con)->
    old = con.listening
    con.listening = {}
    console.log "Setting listens, name: #{con.name}, old: #{old}, listenPath: #{con.listenPath}, new: #{@values[con.listenPath]}" # con = #{require('util').inspect con}"
    for path in @values[con.listenPath]
      con.listening[path + '/'] = true
      if _.all prefixes(path), ((p)->!old[p]) then @sendTree con, path
      old[path + '/'] = true
  error: (con, errorType, msg)->
    con.q.push ['error', errorType, msg]
    false
  removeKey: (key)->
    delete @storageModes[key]
    delete @values[key]
    idx = _.search key, @keys
    if idx > -1 then @keys.splice idx, 1
  keysForPrefix: (prefix)->
    keys = []
    idx = _.search prefix, @keys
    if idx > -1
      console.log "Getting all keys for prefix: #{prefix}, start: #{idx}, keys: #{@keys.join ', '}"
      prefixPattern = "^#{prefix}/"
      keys.push prefix
      keys.push @keys[idx] while @keys[++idx] && @keys[idx].match prefixPattern
    keys
  sendTree: (con, path)-> # send values for path and all of its children to con
    #*** send ["tree", null, path, ...] to con
    console.log "Keys for #{path} = #{@keysForPrefix path}"
    console.log "All keys: #{@keys.join ', '}"
    for key in @keysForPrefix path
      con.q.push ['set', key, @values[key]]
  # Storage methods -- have to be filled in by storage strategy
  store: (con, key, value)-> # do nothing, for now
    @error con, warning_no_storage, "Can't store #{key} = #{JSON.stringify value}, because no storage is configured"
  remove: (con, key)-> # do nothing, for now
    @error con, warning_no_storage, "Can't delete #{key}, because no storage is configured"
  # Commands
  connect: (con, [x, name])->
    console.log "CONNECT: #{name}"
    if !name then name = "$anonymous-#{@anonymousPeerCount++}"
    if @peers[name]
      console.log "*\n* DISCONNECTING BECAUSE OF DUPLICATE PEER NAME\n*"
      @disconnect con, "Duplicate peer name: #{name}"
    else @addPeer con, name
    true
  tree: (con, [x, cookie, key])-> # cookie, courtesy of Shlomi
    # *** handle tree command; add all key/value pairs in the tree to the command and send it back
  set: (con, [x, key, value, storageMode])->
    if storageMode and storageModes.indexOf(storageMode) is -1 then @error con, error_bad_storage_mode, "#{storageMode} is not a valid storage mode"
    else
      if storageMode and storageMode isnt @storageModes[key] and @storageModes[key] is storage_permanent
        @remove con, key
      if (storageMode || @storageModes[key]) isnt storage_transient
        if !@storageModes[key]
          storageMode = storageMode || storage_memory
          @keys.push key
          @newKeys = true
          console.log "Added key: #{key}, unsorted keys: #{@keys.join ', '}"
        console.log "Setting #{key} = #{value}"
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
      if index < 0 then index = @values.length + index + 1
      @values[key].splice index, 0, value
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
  splitKey = _.without (key.split '/'), ''
  while splitKey.length
    result.push splitKey.join '/'
    splitKey.pop()
  result

# binarySearch -- seach a sorted array for a key
# returns the position of the smallest item >= key or array.length, if none
# This is the correct position to insert the item and maintain sorted order

_.search = (key, arr)->
  if arr.length == 0 then return 0
  left = 0
  right = arr.length - 1
  while left < right
    mid = Math.floor (left + right) / 2
    if arr[mid] is key then return mid
    else if arr[mid] < key then left = mid + 1
    else right = mid - 1
  if arr[left] < key then left + 1 else left
