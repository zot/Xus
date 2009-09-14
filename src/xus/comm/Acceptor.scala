package xus.comm;

import java.net._
import java.nio.channels._
import scala.actors.Actor._
import scala.collection.JavaConversions._
import scala.collection.mutable.{Map => MMap}

object Acceptor {
	def listen(port: Int)(connectionHandler: (ClientConnection) => Any)(inputHandler: (Array[Byte], Int, Int) => Unit) = {
		val sock = ServerSocketChannel.open

		sock.configureBlocking(false)
		sock.socket.bind(new InetSocketAddress(port))
		Connection.addConnection(new Acceptor(sock) {
			def newConnection(connection: SocketChannel) = {
				val con: ClientConnection = ClientConnection(connection, Some(this))(inputHandler)

				connectionHandler(con)
				con
			}
		})
	}
}

abstract class Acceptor(serverChan: ServerSocketChannel) extends Connection[ServerSocketChannel](serverChan) {
	import Connection._

	def newConnection(chan: SocketChannel): ClientConnection
	def register {
		serverChan.register(selector, SelectionKey.OP_ACCEPT)
	}
	override def handle(key: SelectionKey) {
		if (key.isAcceptable) {
			println("accept")
			val connection = serverChan.accept
			val newCon = newConnection(connection)

			connections(connection) = newCon
			addConnection(newCon)
		}
		super.handle(key)
	}
	def remove(con: ClientConnection) {
		connections.remove(con.chan)
		con.chan.register(selector, 0)
	}
	override def close {
		serverChan.close
		super.close
	}
}