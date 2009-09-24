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
}

/**
 * SimpyPacketConnection is a layer 1 connection to a peer
 */
trait SimpyPacketConnectionProtocol {
	//outgoing message id starts at 0 and increments with each call
	def nextOutgoingMsgId: Int
	def send(bytes: Array[Byte], offset: Int, len: Int): Unit
	def send(bytes: Array[Byte]) {send(bytes, 0, bytes.length)}
}
/**
 * TopicConnection is a layer 2 connection to a peer
 */
trait PeerConnectionProtocol {
	def peerId: BigInt
	def peerKey: PublicKey

	// low level protocol
	def send(node: Node): Unit

	//
	// peer-to-peer messages
	//
	// request: challenge, response: challengeResponse
	def sendChallenge(token: String, msgId: Int = -1): Unit
	def sendChallengeResponse(token: String, key: PublicKey, requestId: Int, msgId: Int = -1): Unit
	// request: direct, response: completed or failed
	// empty direct message functions as a ping
	def sendDirect(topicSpace: Int, topic: Int, message: Any, msgId: Int = -1): Unit
	def sendCompleted(requestId: Int, msgId: Int = -1): Unit
	def sendFailed(requestId: Int, msgId: Int = -1): Unit

	//
	// peer-to-space messages
	//
	def sendBroadcast(space: Int, topic: Int,  message: Any, msgId: Int = -1): Unit
	def sendUnicast(space: Int, topic: Int,  message: Any, msgId: Int = -1): Unit
	def sendDHT(space: Int, topic: Int,  message: Any, msgId: Int = -1): Unit
	def sendDelegate(peer: Int, space: Int, topic: Int,  message: Any, msgId: Int = -1): Unit
	
	//
	// space-to-peer messages
	// these are delegated from other peers
	//
	def sendBroadcast(sender: BigInt, space: Int, topic: Int,  message: Any, msgId: Int): Unit
	def sendUnicast(sender: BigInt, space: Int, topic: Int,  message: Any, msgId: Int): Unit
	def sendDHT(sender: BigInt, space: Int, topic: Int,  message: Any, msgId: Int): Unit
	def sendDelegatedDirect(sender: BigInt, space: Int, topic: Int,  message: Any, msgId: Int): Unit
}
trait SimpyPacketPeerProtocol {
	def receiveInput(con: SimpyPacketConnectionProtocol, bytes: Array[Byte], offset: Int, length: Int): Unit
}
class Message {
	var con: PeerConnectionProtocol = null
	implicit var node: Node = null

	def set(newCon: PeerConnectionProtocol, newNode: Node): this.type = {
		con = newCon
		node = newNode
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
class Challenge extends Message {
	def token = string("token")
}
class ChallengeResponse extends Message {
	def responseNode = (node \ "challenge-response")(0)
	def token = attrString(responseNode, "token")
}
class Completed extends Message
class Failed extends Message
class TopicSpaceMessage extends Message {
	def space = int("space")
	def topic = int("topic")
	def message = node.child
}
class PeerToSpaceMessage extends TopicSpaceMessage {
	def sender = con.peerId
}
class Direct extends PeerToSpaceMessage
class DHT extends PeerToSpaceMessage
class Broadcast extends PeerToSpaceMessage
class Unicast extends PeerToSpaceMessage
class DelegateDirect extends PeerToSpaceMessage
class SpaceToPeerMessage extends TopicSpaceMessage {
	def sender = bigInt("sender")
}
class DelegatedDirect extends SpaceToPeerMessage
class DelegatedDHT extends SpaceToPeerMessage
class DelegatedBroadcast extends SpaceToPeerMessage
class DelegatedUnicast extends SpaceToPeerMessage

object PeerTrait {
	val dispatchers = Map(
	"challenge" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new Challenge().set(con, node))},
	"challenge-response" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new ChallengeResponse().set(con, node))},
	"completed" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new Completed().set(con, node))},
	"failed" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new Failed().set(con, node))},
	"direct" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new Direct().set(con, node))},
	"delegate-direct" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new DelegateDirect().set(con, node))},
	"broadcast" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new Broadcast().set(con, node))},
	"unicast" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new Unicast().set(con, node))},
	"dht" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new DHT().set(con, node))},
	"delegated-direct" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new DelegatedDirect().set(con, node))},
	"delegated-broadcast" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new DelegatedBroadcast().set(con, node))},
	"delegated-unicast" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new DelegatedUnicast().set(con, node))},
	"delegated-dht" -> {(con: PeerConnectionProtocol, node: Node, peer: PeerTrait) => peer.receive(new DelegatedDHT().set(con, node))}
	)
}
trait PeerTrait {
	import PeerTrait._

	def publicKey: PublicKey
	def peerId: BigInt

	//
	// peer-to-peer messages
	//
	def dispatch(con: PeerConnectionProtocol, node: Node) = dispatchers(node.label.toLowerCase)(con, node, this)

	def receive(msg: Challenge) = basicReceive(msg)
	def receive(msg: ChallengeResponse) = basicReceive(msg)
	def receive(msg: Completed) = basicReceive(msg)
	def receive(msg: Failed) = basicReceive(msg)
	def receive(msg: Direct) = basicReceive(msg)
	//
	// peer-to-space messages
	//
	def receive(msg: Broadcast) = basicReceive(msg)
	def receive(msg: Unicast) = basicReceive(msg)
	def receive(msg: DHT) = basicReceive(msg)
	def receive(msg: DelegateDirect) = basicReceive(msg)

	//
	// space-to-peer messages
	// these are delegated from other peers
	//
	def receive(msg: DelegatedBroadcast) = basicReceive(msg)
	def receive(msg: DelegatedUnicast) = basicReceive(msg)
	def receive(msg: DelegatedDHT) = basicReceive(msg)
	def receive(msg: DelegatedDirect) = basicReceive(msg)

	// catch-all
	def basicReceive(msg: Message) {}
}