{Server} = exports = require './proto'
require './jquery.indexeddb'

modified = false

Server.useIndexedDb = (dbName, storeName, cont, err)->
  if !modified
    modified = true
    console.log "initializing"
    window.db = dbPrm = $.indexedDB(dbName)
    console.log "1"
    dbPrm.done = (d)->
      cont()
      d
    dbPrm.fail = err || ->
    console.log "2"
    window.os = dbPrm.transaction(storeName, 1).objectStore(storeName)
    console.log "3"
    @store = (key, value)->
      dbPrm.transaction(storeName, 1).objectStore(storeName).put(key, value)
    @sendAll = (con, path)->
      dbPrm.transaction(storeName, 0).objectStore(storeName)
