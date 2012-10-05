# Xus: simple, storage-backed messaging

Xus provides simple, key-value based messaging, optionally backed with
storage.

## Key Features

* Easy to use
* Simple protocol
* Simple architecture
* Small codebase
* Powerful and flexible

## Easy to use "xus" command

* manage Xus instances (start, stop, list, etc.)
* provides basic access to all protocol operations (get, set, listen, etc.)
* runs programs for more advanced interaction with instances

## Simple protocol

* Key, values, newline-terminated JSON arrays
* Small -- 6 data commands, plus 2 others ('name' and 'error')
* Pubsub -- listen on keys and Keys form a tree: a a/b a/b/c
* Persistent -- values persist and new subscribers get current values
* Reflective -- control Xus interaction through Xus variables

## Simple architecture

* Start your program using the xus command
* Load JS code into xus, if needed, at startup
* Plugins are other programs that connect to Xus

## Small codebase

* Small JavaScript program/library which runs in node and/or browsers

## Powerful and flexible

* can use any JSON values
* Many ways to connect:
   * local: In-VM
   * worker: Shared Web Worker
   * WebSockets
   * Xus Proxy, running through WebSockets
* Exchange data in different ways
   * published -- publishes changes to subscribers
   * static -- peers may retrieve values; your in-VM code can reference them
   * computed -- values may be computed on access

# Xus command details

* list -- display running xuses
* stop server -- kill a xus
* get -- get the value of a variable (optionally all of its children)
* set -- set a variable
* put -- set a property of a variable's value
* splice -- splice a variable (if it's an array)
* removeFirst -- remove first occurrence of a value
* removeAll -- remove all occurrences of a value
* run cmd arg... -- run command in the context of a xus
   * cmd's ENV contains connection info for spawned commands
   * cmd's stdin gets listener results from xus
   * cmd's stdout talks to xus
   * cmd's fd 3 is original stdin
   * cmd's fd 4 is original stdout
   * run terminates when cmd terminates

# Protocol Details

* JSON-based (but other encodings are easy to add)
* key-value model
   * keys are paths and form a directory-like tree
      * a/b/c is a "child" of a/b
   * values are JSON-compatible
* subscribe to changes to a variable and its children
* small protocol
   * name -- sent on connect to inform peer of its initial name and
     when the name changes
   * value -- get the value of a variable
   * set -- set the value of a variable
   * put -- set a property of a variable's object array
   * splice -- splice a variable's array
   * removeFirst -- remove first occurrence of a value
   * removeAll -- remove all occurrences of a value
* reflective metamodel
   * peers have their own variables, some of which can affect Xus
   * this/&lt;path> -- alias for the current peer's variables
   * peer/&lt;name>/name -- name of this peer; change the name renames
     all of the peer's variables (only one peer can be connected with
     a particular name)
   * peer/&lt;name>/listen -- array of paths this peer is subscribed to
   * peer/&lt;name>/master -- set if this peer is the xus master
   * peer/&lt;name>/public/&lt;path> -- public variables for a peer; other peers
     can change these
