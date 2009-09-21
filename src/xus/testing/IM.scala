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
	override def receive(msg: Direct) {
		val joinReq = msg.node.child(0)
		val space = msg.int("space")(joinReq)
		val topic = msg.int("topic")(joinReq)
		val id = msg.int("id")(joinReq)

		msg.con.asInstanceOf[PeerConnection].peerId = id
		println("peer joined space: " + space + ", topic: " + topic + ", id: " + id)
		topicsOwned((msg.int("space")(joinReq), msg.int("topic")(joinReq))).members.add(msg.con)
//		println("Received direct message: " + msg.node)
	}
	def server(port: Int) {
		topicsOwned((0, 0)) = new Topic(0, 0, this)
		topicsOwned((0, 1)) = new Topic(0, 1, this)
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
						peerConnections(con).sendBroadcast(0, 1, line)
						System.out.print("> ")
						System.out.flush
					}
				}
			}
		}
	}
	def client(port: Int) {
		topicsJoined((0, 1)) = new Topic(0, 1, this) {
			override def basicReceive(msg: SpaceToPeerMessage) {
				println("Basic receive: " + msg)
			}
		}
		con = addConnection(SimpyPacketConnection("localhost", port, this))
		peerConnections(con).sendDirect(0, 0, <join space="0" topic="1" id="1"/>)
		actor {processInput}
	}
}