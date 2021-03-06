# Many ways to connect:

* local: In-VM
* worker: Shared Web Worker
* WebSockets
* Xus Proxy, running through WebSockets

# Reflect data in different ways

* observed -- changes are broadcast to listeners
* static -- may shared with your in-VM code
* computed -- may be computed on access

# Xus command

* list -- display running xuses
* stop server -- kill a xus
* get -- get the value of a variable (optionally all of its children)
* set -- set a variable
* put -- set a property of a variable's value
* insert -- insert a value in a variable (if it's an array)
* removeFirst -- remove first occurrence of a value
* removeAll -- remove all occurrences of a value
* run cmd arg... -- run command in the context of a xus
   * cmd's ENV contains connection info for spawned commands
   * cmd's stdin gets listener results from xus
   * cmd's stdout talks to xus
   * cmd's fd 3 is original stdin
   * cmd's fd 4 is original stdout
   * run terminates when cmd terminates

Example Leisure command, built using xus command

* list -- lists Leisure projects from GDrive
* edit project
   * start node
   * edit project using chrome, node puts connection parameters in URL
   * browser proxies to Xus in node (connect with WebSockets?)
* selections -- display contents of selections that the user makes
* select -- select a range of text
* replaceSelection -- replace selection with some text
* contents -- cat contents of document

# Misc

double-clicking a Xus db should start up the app

manage all Xus metadata within the Xus model (listener order, etc)

use Xus db for prefs

store db in workspace-like directory

opening a db with xus should be able to launch the executable (store launch info in db)

package management

# storage

* levelDb / Web Storage
* import
* inherit (open another sqlite db and/or add tables to the path)

variables

* transient (not remembered for new listeners, lost at shutdown) or persistent (remembered at shutdown)
* JSON data types
* names: a/b/c/d

# messages

* json arrays of commands (batches), separated by newlines
* connect name -- name of entry in peers tree
* set var value [transient?] -- set a variable's value, transient =
  true indicates that the variable should not be persisted
* put var index value
* insert var index value -- array insert, index -1 is an append
* removeFirst var value -- remove first occurance of value
* removeAll var value -- remove all occurances of value
* error description -- the last message a peer will ever receive!
* listening
   * a peer can listen on many paths
   * changes get sent to peers in batches, filtered by what they are listening to

# standard paths

* shared  -- shared data
* meta -- an entry for each variable that has metadata (/'s are backslashed)
   * transient -- map of transient variables
* peers -- peers/X is peer X's variables (for direct messages to X)
   * X -- variables for peer X
      * listen -- list of paths this peer is listening to
      * connection -- info about connection
* config -- config info for the app
 * commandPath
 * args
