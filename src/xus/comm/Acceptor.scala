/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm;

import java.net._
import java.nio.channels._
import scala.actors.Actor._
import scala.collection.JavaConversions._
import scala.collection.mutable.{Map => MMap}

object Acceptor {
	def listenCustom(port: Int, peer: SimpyPacketPeerProtocol)(connectionHandler: (SimpyPacketConnection) => Any): Acceptor = {
		listen(port) {chan =>
			new Acceptor(chan, peer) {
				override def newConnection(connection: SocketChannel) = {
					val con: SimpyPacketConnection = SimpyPacketConnection(connection, peer, Some(this))
					
					connectionHandler(con)
					con
				}
			}
		}
	}
	def listen(port: Int, peer: SimpyPacketPeerProtocol): Acceptor = listen(port) {chan =>	new Acceptor(chan, peer)}
	def listen(port: Int)(socketHandler: (ServerSocketChannel) => Acceptor): Acceptor = {
		val sock = ServerSocketChannel.open

		sock.configureBlocking(false)
		sock.socket.bind(new InetSocketAddress(port))
		Connection.addConnection(socketHandler(sock))
	}
}

class Acceptor(serverChan: ServerSocketChannel, peer: SimpyPacketPeerProtocol) extends Connection[ServerSocketChannel](serverChan) {
	import Connection._

	def newConnection(chan: SocketChannel) = SimpyPacketConnection(chan, peer, Some(this))
	def register {
		serverChan.register(selector, SelectionKey.OP_ACCEPT)
	}
	override def handle(key: SelectionKey) {
		if (key.isAcceptable) {
			val connection = serverChan.accept
			val newCon = newConnection(connection)

			connections(connection) = newCon
			addConnection(newCon)
		}
		super.handle(key)
	}
	def remove(con: SimpyPacketConnection) {
		connections.remove(con.chan)
		con.chan.keyFor(selector).cancel
	}
	override def close {
		serverChan.close
		super.close
	}
}