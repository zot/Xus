package xus;

import java.net._
import java.nio.channels._
import scala.actors.Actor._
import scala.collection.JavaConversions._
import scala.collection.mutable.{Map => MMap}

object Acceptor {
	def listen(port: Int)(inputHandler: (Array[Byte], Int, Int) => Unit) = new Acceptor(new ServerSocket(port).getChannel) {
		def newConnection(connection: SocketChannel) = ClientConnection(connection, Some(this))(inputHandler)
	}
}

abstract class Acceptor(serverChan: ServerSocketChannel) extends Connection[ServerSocketChannel](serverChan) {
	import Connection._

	def newConnection(chan: SocketChannel): ClientConnection

	serverChan.configureBlocking(false)
	serverChan.register(selector, SelectionKey.OP_ACCEPT)

	override def handle(key: SelectionKey) {
		if (key.isAcceptable) {
			val connection = serverChan.accept

			connection.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE)
			connections(connection) = newConnection(connection)
		} else {
			super.handle(key)
		}
	}
	def remove(con: ClientConnection) {
		connections.remove(con.chan)
		con.chan.register(selector, 0)
	}
	def close {
		serverChan.close
	}
}