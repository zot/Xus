CURRENT

* convert main.coffee to use only web sockets
* make a wrapper for xus run that sends the header (xus wsconnect cmd arg...)
* make HTML for an iframe that connects a peer and sends it to the parent
* support multiplexed, reverse-connections
    * connector makes an outgoing connection to a connection server
    * connector connections on behalf of the connection server
    * connection protocol precedes xus batches with connection
        * connect <id> -- connection makes connection (incoming)
        * disconnect <id> -- both directions
        * data <id> -- data for connection (both directions)

FUTURE

* add leveldb storage
