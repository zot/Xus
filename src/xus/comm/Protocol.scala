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
	val messageMap = Map(
		new Challenge().entry,
		new ChallengeResponse().entry,
		new Completed().entry,
		new Failed().entry,
		new Direct().entry,
		new DHT().entry,
		new Broadcast().entry,
		new Unicast().entry,
		new DelegateDirect().entry,
		new DelegatedDirect().entry,
		new DelegatedDHT().entry,
		new DelegatedBroadcast().entry,
		new DelegatedUnicast().entry)
	implicit object PeerConnectionOrdering extends Ordering[PeerConnection] {
		def compare(a: PeerConnection, b: PeerConnection) = a.peerId.compareTo(b.peerId)
	}
}

abstract class Message(val nodeName: String) extends Cloneable {
	var con: PeerConnection = null
	var node: Node = null
	implicit var innerNode: Node = null

	def copy = clone.asInstanceOf[this.type]
	def entry = nodeName -> this
	def attributes = List("msgid")
	def dispatch(peer: Peer)
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
	def completed(payload: Any) = con.completed(this, payload)
	def failed(payload: Any) = con.failed(this, payload)
}
abstract class PeerToPeerMessage(name: String) extends Message(name)
abstract class Response(name: String) extends Message(name) {
	override def attributes = "requestid" :: super.attributes
	def requestId = int("requestid")
}
class Challenge extends Response("challenge") {
	override def attributes = "token" :: super.attributes
	def dispatch(peer: Peer) = peer.receive(this)
	def token = string("token")
}
class ChallengeResponse extends Response("challenge-response") {
	def dispatch(peer: Peer) = peer.receive(this)
	override def attributes = List("sig", "publickey", "token", "challengetoken") ::: super.attributes
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
class Completed extends Response("completed") {
	def dispatch(peer: Peer) = peer.receive(this)
}
class Failed extends Response("failed") {
	def dispatch(peer: Peer) = peer.receive(this)
}
class Direct extends PeerToPeerMessage("direct") {
	def dispatch(peer: Peer) = peer.receive(this)
}
abstract class TopicSpaceMessage(name: String) extends Message(name) {
	override def attributes = "space" :: "topic" :: super.attributes
	def space = int("space")
	def topic = int("topic")
	def message = node.child
}
abstract class PeerToSpaceMessage(name: String) extends TopicSpaceMessage(name) {
	def sender = con.peerId
}
class DHT extends PeerToSpaceMessage("dht") {
	def dispatch(peer: Peer) = peer.receive(this)
	override def attributes = "key" :: super.attributes
	def key = bigInt("key")
}
class Broadcast extends PeerToSpaceMessage("broadcast") {
	def dispatch(peer: Peer) = peer.receive(this)
}
class Unicast extends PeerToSpaceMessage("unicast") {
	def dispatch(peer: Peer) = peer.receive(this)
}
class DelegateDirect extends PeerToSpaceMessage("delegate-direct") {
	def dispatch(peer: Peer) = peer.receive(this)
	override def attributes = "receiver" :: super.attributes
	def receiver = bigInt("receiver")
}
abstract class SpaceToPeerMessage(name: String) extends TopicSpaceMessage(name) {
	override def attributes = "sender" :: "sendermsgid" :: super.attributes
	def sender = bigInt("sender")
	def senderMsgId = bigInt("sendermsgid")
}
class DelegatedDirect extends SpaceToPeerMessage("delegated-direct") {
	def dispatch(peer: Peer) = peer.receive(this)
}
class DelegatedDHT extends SpaceToPeerMessage("delegated-dht") {
	def dispatch(peer: Peer) = peer.receive(this)
}
class DelegatedBroadcast extends SpaceToPeerMessage("delegated-broadcast") {
	def dispatch(peer: Peer) = peer.receive(this)
}
class DelegatedUnicast extends SpaceToPeerMessage("delegated-unicast") {
	def dispatch(peer: Peer) = peer.receive(this)
}
