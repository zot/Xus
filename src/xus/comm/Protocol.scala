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

class Message {
	var con: PeerConnection = null
	var node: Node = null
	implicit var innerNode: Node = null

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
	def int(att: String)(implicit n: Node) = Integer.parseInt(string(att)(n))
	def intOpt(att: String)(implicit n: Node): Option[Int] = stringOpt(att)(n).map(Integer.parseInt(_))
	def bigInt(att: String)(implicit n: Node) = BigInt(bytesFor(string(att)(n)))
	def msgId = int("msgid")
	override def toString = getClass.getSimpleName + ": " + node
	def payload = node.child
}
class PeerToPeerMessage extends Message
class Response extends Message {
	def requestId = int("requestid")
}
class Challenge extends Response {
	def token = string("token")
}
class ChallengeResponse extends Response {
	override def set(newCon: PeerConnection, newNode: Node): this.type = {
		super.set(newCon, newNode)
		innerNode = (newNode \ "challenge-response")(0)
		this
	}
	def signature = string("sig")(node)
	def publicKey = string("publickey")(node)
	def token = string("token")
	def challengeToken = string("challengetoken")
}
class Completed extends Response
class Failed extends Response
class Direct extends PeerToPeerMessage
class TopicSpaceMessage extends Message {
	def space = int("space")
	def topic = int("topic")
	def message = node.child
}
class PeerToSpaceMessage extends TopicSpaceMessage {
	def sender = con.peerId
}
class DHT extends PeerToSpaceMessage {
	def key = bigInt("key")
}
class Broadcast extends PeerToSpaceMessage
class Unicast extends PeerToSpaceMessage
class DelegateDirect extends PeerToSpaceMessage {
	def receiver = bigInt("receiver")
}
class SpaceToPeerMessage extends TopicSpaceMessage {
	def sender = bigInt("sender")
}
class DelegatedDirect extends SpaceToPeerMessage
class DelegatedDHT extends SpaceToPeerMessage
class DelegatedBroadcast extends SpaceToPeerMessage
class DelegatedUnicast extends SpaceToPeerMessage
