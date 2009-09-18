package xus.testing

import scala.io.Source
import xus.comm._
import scala.actors.Actor._

object IM extends Peer {
	var con: SimpyPacketConnectionProtocol = null

	def main(args: Array[String]) {
		if (args(0) == "server") {
			server(Integer.parseInt(args(1)))
		} else {
			client(Integer.parseInt(args(1)))
		}
	}
//	def receiveInput(con: SimpyPacketConnectionProtocol, bytes: Array[Byte], offset: Int, len: Int) {
//		System.err.println(new String(bytes, offset, len))
//	}
	def server(port: Int) {
		Acceptor.listenCustom(port, this)({newCon: SimpyPacketConnection =>
			con = addConnection(newCon)
			actor {processInput}
		})
	}
	def processInput {
		while (true) {
			if (con != null) {
				System.out.print("> ")
				System.out.flush
				for (line <- Source.stdin.getLines()) {
					if (!line.isEmpty) {
						topicCons(con).sendDirect(0, 0, line)
						System.out.print("> ")
						System.out.flush
					}
				}
			}
		}
	}
	def client(port: Int) {
		con = addConnection(SimpyPacketConnection("localhost", port, this))
		actor {processInput}
	}
	override def receive(msg: Direct) {
		System.out.println("RECEIVED MESSAGE: " + msg.message)
	}
}