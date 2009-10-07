/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.xml.Node
import scala.xml.Elem
import java.security.PublicKey
import com.sun.xml.internal.fastinfoset.sax.SAXDocumentSerializer
import Util._

object Protocol {
	val CHALLENGE = "challenge"
	val CHALLENGE_RESPONSE = "challenge-response"
	val COMPLETED = "completed"
	val FAILED = "failed"
	val DIRECT = "direct"
	val BROADCAST = "broadcast"
	val UNICAST = "unicast"
	val DELEGATE_DIRECT = "direct"
	val DHT = "dht"
	val DELEGATED_BROADCAST = "delegated-broadcast"
	val DELEGATED_UNICAST = "delegated-unicast"
	val DELEGATED_DIRECT = "delegated-direct"
	val DELEGATED_DHT = "delegated-dht"

	implicit object PeerConnectionOrdering extends Ordering[PeerConnection] {
		def compare(a: PeerConnection, b: PeerConnection) = a.peerId.compareTo(b.peerId)
	}
}

class Message(val nodeName: String) {
	var con: PeerConnection = null
	var node: Node = null
	implicit var innerNode: Node = null

	def apply(block: (Peer, this.type) => Any): (String, (PeerConnection, Node) => this.type) = nodeName -> {(con: PeerConnection, node: Node) =>
		val m = clone.asInstanceOf[this.type].set(con, node)

		m.set(con, node)
		block(con.peer, m)
		con.peer.received(m)
		m
	}
	def set(newCon: PeerConnection, newNode: Node): this.type = {
		con = newCon
		node = newNode
		innerNode = newNode
		this
	}
	def string(att: String)(implicit n: Node) = attrString(n, att)
	def stringOpt(att: String)(implicit n: Node): Option[String] = {
		val id = attrString(n, att)

		if (id == null) {
			None
		} else {
			Some(id)
		}
	}
	def int(att: String)(implicit n: Node) = string(att)(n).toInt
	def intOpt(att: String)(implicit n: Node): Option[Int] = stringOpt(att)(n).map(_.toInt)
	def bigInt(att: String)(implicit n: Node) = BigInt(bytesFor(string(att)(n)))
	def msgId = int("msgid")
	override def toString = getClass.getSimpleName + ": " + node
	def payload = node.child
}
class PeerToPeerMessage(name: String) extends Message(name)
class Response(name: String) extends Message(name) {
	def requestId = int("requestid")
}
class Challenge extends Response("challenge") {
	def token = string("token")
}
class ChallengeResponse extends Response("challenge-response") {
	override def set(newCon: PeerConnection, newNode: Node): this.type = {
		super.set(newCon, newNode)
		innerNode = (newNode \ nodeName)(0)
		this
	}
	def signature = string("sig")(node)
	def publicKey = string("publickey")(node)
	def token = string("token")
	def challengeToken = string("challengetoken")
}
class Completed extends Response("completed")
class Failed extends Response("failed")
class Direct extends PeerToPeerMessage("direct")
class TopicSpaceMessage(name: String) extends Message(name) {
	def space = int("space")
	def topic = int("topic")
	def message = node.child
}
class PeerToSpaceMessage(name: String) extends TopicSpaceMessage(name) {
	def sender = con.peerId
}
class DHT extends PeerToSpaceMessage("dht") {
	def key = bigInt("key")
}
class Broadcast extends PeerToSpaceMessage("broadcast")
class Unicast extends PeerToSpaceMessage("unicast")
class DelegateDirect extends PeerToSpaceMessage("delegate-direct") {
	def receiver = bigInt("receiver")
}
class SpaceToPeerMessage(name: String) extends TopicSpaceMessage(name) {
	def sender = bigInt("sender")
}
class DelegatedDirect extends SpaceToPeerMessage("delegated-direct")
class DelegatedDHT extends SpaceToPeerMessage("delegated-dht")
class DelegatedBroadcast extends SpaceToPeerMessage("delegated-broadcast")
class DelegatedUnicast extends SpaceToPeerMessage("delegated-unicast")
