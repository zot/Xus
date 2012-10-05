# Xus: simple, storage-backed messaging

Xus provides simple, key-value publish and subscribe with optional storage.

## Key Features

* Easy to use
* Simple switchboard architecture
* Simple protocol
* Small codebase
* Powerful and flexible

## Easy to use "xus" command

* manage Xus instances (start, stop, list, etc.)
* provides basic access to all operations (get, set, listen, etc.)
* connects programs for more advanced usage
* no knowledge of sockets needed

## Simple switchboard architecture

* peers talk to each other through Xus, but Xus is not in control of
  your program
* Start your program using the xus command
* Load JS code into xus, if needed, at startup
* Plugins are other programs that connect to Xus

## Simple protocol

* Small -- only 6 data commands and 1 server-response command ('error')
* Commands are newline-terminated JSON arrays
* Publish/subscribe -- listen on keys and Keys form a tree: a a/b a/b/c
* Values are persistent -- new subscribers get current values
* Xus has a metamodel -- Xus acts like a xus plugin; control a peer by changing variables

## Small codebase

* Small JavaScript program/library
* Runs in both node and browsers

## Powerful and flexible

* Many ways to connect:
   * local: In-VM
   * worker: Shared Web Worker
   * WebSockets
   * Xus Proxy, running through WebSockets
* Use data in different ways
   * published -- publishes changes to subscribers
   * static -- peers may retrieve values; your in-VM code can reference them
   * computed -- values may be computed when retrieved
* Uses JSON values

# Xus command details

* list -- display running xuses
* stop server -- kill a xus
* run cmd arg... -- run command in the context of a xus
   * ENV contains connection info for spawned commands
   * fd 3 gets listener results from xus
   * fd 4 sends to xus
* listen -- listen for changes 
* get -- get the value of a variable (optionally all of its children)
* set -- set a variable
* put -- set a property of a variable's value
* splice -- splice a variable (if it's an array)
* removeFirst -- remove first occurrence of a value
* removeAll -- remove all occurrences of a value

# Protocol Details

* JSON-based (but other encodings are easy to add)
* key-value model
   * keys are paths and form a directory-like tree
      * a/b/c is a "child" of a/b
   * uses JSON values
* listen to changes for a variable and its children by setting this/listen
* Commands
   * value -- get the value of a variable
   * set -- set the value of a variable
   * put -- set a property of a variable's object array
   * splice -- splice a variable's array
   * removeFirst -- remove first occurrence of a value
   * removeAll -- remove all occurrences of a value
   * error -- server sends this to peers when there are errors
* reflective metamodel
   * peers have their own variables (peer/&lt;peer-name>/&lt;path>) that can affect Xus
   * this/&lt;path> -- alias for the current peer's variables
   * peer/&lt;name>/name -- name of this peer; change the name renames
     all of the peer's variables (only one peer can be connected with
     a particular name)
   * peer/&lt;name>/listen -- array of paths this peer is subscribed to
   * peer/&lt;name>/master -- set if this peer is the xus master;
     disconnecting will then shut xus down
   * peer/&lt;name>/public/&lt;path> -- public variables for a peer; other peers
     can change these
