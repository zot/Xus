package xus.comm

import scala.actors.Actor._

class DirectSimpyPacketConnection(peer: SimpyPacketPeerProtocol, var otherEnd: DirectSimpyPacketConnection) extends SimpyPacketConnectionProtocol {
	var msgId = -1

	if (otherEnd == null) {
		otherEnd = this
	} else {
		otherEnd.otherEnd = this
	}

	def this(peer: SimpyPacketPeerProtocol) = this(peer, null.asInstanceOf[DirectSimpyPacketConnection])
	def this(peer: SimpyPacketPeerProtocol, otherPeer: SimpyPacketPeerProtocol) = this(peer, new DirectSimpyPacketConnection(otherPeer))
	def nextOutgoingMsgId: Int = {
		msgId += 1
		msgId
	}
	def send(bytes: Array[Byte], offset: Int, len: Int) {
		otherEnd.receive(bytes, 0, bytes.length)
	}
	def receive(bytes: Array[Byte], offset: Int, len: Int) {
		peer.receiveInput(this, bytes, 0, bytes.length)
	}
}