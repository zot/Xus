# Xus reference

# Overview

Xus is a key-value data publish/subscribe protocol that aims to be
simple and easy to use.  Xus uses a peer-to-peer communication model,
overlayed on a client/server model.  The main action happens at the
peer, but at this point, if a peer is in the same VM as the server, it
gains a few advantages, because it can use special types of value.

# Model

* The Xus model is a key/value store
* Keys are named like file paths, like 'a/b/c'
* When a key is a prefix of another key, it is called that key's
  ancestor that that key is called its descendant
* Peers
   * Peers can listen to keys
      * Any commands that change a key are replicated to all peers
        which listen to that key or any of its ancestors
      * When a peer starts listening to a key, it receives a
        **'value'** message (see **Commands** below) with the values
        for that key and all of its descendants so that it is
      immediately up-to-date for any other changes
   * Each peer has a name, which starts out assigned by the Xus server
   * Peers have their own set of keys, named **peer/PEERNAME/**...
   * Peers can use 'this' as an abbreviation for **peer/PEERNAME**

## Standard peer keys

* **this/name = string:** the name of the peer; changing this will move
  the **peer/OLDNAME** keys to **peer/NEWNAME**.  It will give you an
  error if the name is already in use.
* **this/listen  = array:**  an array  of the  keys  this peer  is
  listening to
* **this/links = array:** an array of keys in which this peer's
  name will be inserted.  If this peer's name is removed from one of
  those arrays, the key name will be removed from **this/links**
* **this/public = any:** other peers can only listen to and change
  **this/public** and its descendants; other peer keys are private
* **this/master = boolean:** if this is true, when the peer
    disconnects, the server will shut down

# Commands

Xus commands are transmitted in JSON batches (using JSON or BSON);

* Each batch is an array of commands
* Each command is an array of the following form:
   * [command, arg...]
* There are 6 commands
   * **['value', key, cookie, tree]:** retrieve the value of a key
     and, optionally, of its descendants
      * **Effect:** responds with a value message back to the peer with the
        requested keys and values appended to it
      * **key -- string:** the key name
      * **cookie -- any:** anything you want -- it will also be in the
        response message
      * **tree -- boolean:** whether to send values of all descendants
   * **['set', key, value, storageMode?]:** set a value and optionally
     change its storage mode
      * **key -- string:** the key to set
      * **value -- any:** the new value
      * **storageMode -- string:** the optional storage mode of the key
         * **'memory' (default):** just keep the value in memory
         * **'transient'** do not keep the value; only publish it to
           listeners
         * **'permanent'** keep the value around so that it's there
           on the next startup
   * **['put', key, value, index]:** put a value at an index in object
       at key; the key must either be null or contain an object
      * **key -- string:** the key to change
      * **value -- any:** the value to store
      * **index -- string or int:** the index to set
   * **['splice', key, index, del, ...]:** Splice array in key, normal
       arguments to splice, except that negative indices indicate tail-indexing
      * **key -- string:** the key to change
      * **index -- int:** the location to start modifying; a negative
          index is converted to length + 1 + index, so -1 is the
          location right after the end of the array
      * **del -- int:** the number of items to delete
      * **... -- any:** the items to insert
   * **['removeFirst', key, value]:** remove first occurrence of a value
       from array in key; key must contain an array
      * **key -- string:** the key to change
      * **value -- any:** the value to remove
   * **['removeAll', key, value]:** remove all occurrences of a value
       from array in key; key must contain an array
      * **key -- string:** the key to change
      * **value -- any:** the value to remove
* Responses
   * **['error', type, msg]:** the server sends this if there is an
     error or warning.  Errors will disconnect the peer
      * **type -- string**
         * **'warning\_no\_storage':** peer requested permanent
             storage, but the server does not support it
         * **'error\_bad_message'**
         * **'error\_bad\_storage\_mode'**
         * **'error\_variable\_not\_object':** peer used put with a
             key that doesn't contain an object
         * **'error\_variable\_not\_array':** peer used splice,
             removeFirst, or removeAll with a key that doesn't contain
             an array
         * **'error\_duplicate\_peer\_name':** peer set this/name to a
             name that is already in use
         * **'error\_private\_variable':** peer tried to set another
             peer's private variable
         * **'error\_bad\_master':** peer tried to become master, but
             there is already a master

# Magic values

* Function values: peers in the same VM as the server can use functions as values.
   * set calls function with (newValue) -- **be careful**, because it could be
     called with null
   * get calls function with () -- **be careful**, because it could be
     called with null
* ValueHandlers: peers in the same VM as the server can use functions
  to handle key trees, kind of like servlets.  This might be useful
  for file services, etc.
