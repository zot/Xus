Two configurations:

* standalone with sockets
* in JS vm, using Web Workers and Web SQL Database

double-clicking a Xus db should start up the app

manage all Xus metadata within the Xus model (listener order, etc)

use Xus db for prefs

store db in workspace-like directory

opening a db with xus should be able to launch the executable (store launch info in db)

package management

storage
        mongodb / Web Storage
        import
        inherit (open another sqlite db and/or add tables to the path)

variables
        transient (lost at shutdown) or persistent (remembered at shutdown)
        JSON data types
        names: a/b/c/d

messages
        json arrays of commands (batches), separated by newlines
        connect name -- name of entry in peers tree
        set var value
        put var index value
        insert var index value -- array insert, index -1 is an append
        remove var index value
        removeFirst var value -- remove first occurance of value
        removeAll var value -- remove all occurances of value
        error description -- the last message a peer will ever receive!

listening
        a peer can listen on many paths
        changes get sent to peers in batches, filtered by what they are listening to

standard paths
        shared  -- shared data
        meta -- an entry for each variable that has metadata (/'s are backslashed)
                listeners -- listeners/a/b is a list of listeners on path a/b
                transient -- true if variable is transient
        peers -- peers/X is peer X's variables (for direct messages to X)
                connection -- info about connection
        config -- config info for the app
                commandPath
                args
