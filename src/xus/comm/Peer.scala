/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.xml.Elem
import scala.xml.Node
import scala.xml.NodeSeq
import scala.xml.parsing.NoBindingFactoryAdapter
import java.io.File
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.PublicKey
import com.sun.xml.internal.fastinfoset.sax.SAXDocumentParser
import scala.collection.mutable.Map
import scala.collection.mutable.HashMap
import scala.actors.Actor._
import scala.xml.persistent.SetStorage

import Util._

object Peer {
	def prep[M <: Message](msg: => M)(block: (Peer, M) => Any) = {(con: PeerConnection, node: Node, peer: Peer) =>
		val m = msg

		m.set(con, node)
		peer.receiving(msg)
		block(peer, m)
		m
	}

	val dispatchers = Map(
	"challenge" -> prep(new Challenge()) {_.receive(_)},
	"challenge-response" -> prep(new ChallengeResponse()) {_.receive(_)},
	"completed" -> prep(new Completed()) {_.receive(_)},
	"failed" -> prep(new Failed()) {_.receive(_)},
	"direct" -> prep(new Direct()) {_.receive(_)},
	"delegate-direct" -> prep(new DelegateDirect()) {_.receive(_)},
	"broadcast" -> prep(new Broadcast()) {_.receive(_)},
	"unicast" -> prep(new Unicast()) {_.receive(_)},
	"dht" -> prep(new DHT()) {_.receive(_)},
	"delegated-direct" -> prep(new DelegatedDirect()) {_.receive(_)},
	"delegated-broadcast" -> prep(new DelegatedBroadcast()) {_.receive(_)},
	"delegated-unicast" -> prep(new DelegatedUnicast()) {_.receive(_)},
	"delegated-dht" -> prep(new DelegatedDHT()) {_.receive(_)}
	)
}
class Peer extends SimpyPacketPeerAPI {
	import Peer._

	val waiters = Map[Int, (Response) => Any]()
	var myKeyPair: KeyPair = null
	var storage: SetStorage = null
	var storedNodes = Map[String, Node]()
	var props = Map("prefs" -> new PropertyMap())
	val peerConnections = Map[SimpyPacketConnectionAPI, PeerConnection]()
	val topicsOwned = Map[(Int,Int), Topic]()
	val topicsJoined = Map[(Int,Int), Topic]()
	val selfConnection = peerConnections(addConnection(new DirectSimpyPacketConnection(this)))
	val inputActor = actor {
		loop {
			react {
			case (con: SimpyPacketConnectionAPI, str: ByteArrayInputStream) =>
				val parser = new SAXDocumentParser
				val fac = new NoBindingFactoryAdapter
		
				parser.setContentHandler(fac)
				try {
					parser.parse(str)
				} catch {
				case x: Exception => x.printStackTrace
				}
				dispatch(peerConnections(con), fac.rootElem)
			}
		}
	}

	def onResponseDo[M <: Message](msg: M)(block: (Response) => Any): M = {
		waiters(msg.msgId) = block
		msg
	}
	def closed(con: SimpyPacketConnectionAPI) {
		topicsOwned.values.foreach(_.removeMember(peerConnections(con)))
	}
	def receiving[M <: Message](msg: M): M = {
		if (msg.isInstanceOf[Response]) {
			waiters.remove(msg.msgId).foreach(_(msg.asInstanceOf[Response]))
		}
		msg
	}
	def publicKeyString = stringFor(publicKey.getEncoded)
	def genId {
		keyPair = genKeyPair
		peerId = digestInt(publicKey.getEncoded)
		selfConnection.setKey(publicKey.getEncoded)
	}
	def addConnection(con: SimpyPacketConnectionAPI) = {
		peerConnections(con) = new PeerConnection()(con)
		con
	}
	///////////////////////////
	// API
	///////////////////////////
	var peerId: BigInt = BigInt(0)

	def dispatch(con: PeerConnection, node: Node) = dispatchers(node.label.toLowerCase)(con, node, this)
	def publicKey = myKeyPair.getPublic
	def privateKey = myKeyPair.getPrivate
	def keyPair = myKeyPair
	def keyPair_=(kp: KeyPair) {
		myKeyPair = kp
		storePrefs
	}
	def delegateResponse(msg: Message, response: Response) = response match {
	case succeed: Completed => msg.con.sendCompleted(succeed.requestId, succeed.payload)
	case fail: Failed => msg.con.sendFailed(fail.requestId, fail.payload)
	}
	
	///////////////////////////
	// SimpyPacketPeerAPI
	///////////////////////////
//	def receiveInput(con: SimpyPacketConnectionAPI, bytes: Array[Byte], offset: Int, length: Int) {
//		val parser = new SAXDocumentParser
//		val fac = new NoBindingFactoryAdapter
//
//		parser.setContentHandler(fac)
//		try {
//			parser.parse(new ByteArrayInputStream(bytes, offset, length))
//		} catch {
//			case x: Exception => x.printStackTrace
//		}
//		dispatch(peerConnections(con), fac.rootElem)
//	}
	def receiveInput(con: SimpyPacketConnectionAPI, bytes: Array[Byte], offset: Int, length: Int) {
		receiveInput(con, bytes.slice(offset, offset + length))
	}
	def receiveInput(con: SimpyPacketConnectionAPI, bytes: Array[Byte]) {
		inputActor ! (con, new ByteArrayInputStream(bytes))
	}

	///////////////////////////
	// TopicSpacePeerAPI
	///////////////////////////
	//
	// peer-to-peer messages
	//
	def verifySignature(msg: ChallengeResponse): Boolean = true
	def receive(msg: Challenge) = msg.con.sendChallengeResponse(msg.token, publicKey, msg.msgId)
	def receive(msg: ChallengeResponse) = basicReceive(msg)
	def receive(msg: Completed) = basicReceive(msg)
	def receive(msg: Failed) = basicReceive(msg)
	def receive(msg: Direct) = basicReceive(msg)

	//
	// peer-to-space messages
	//
	// delegate broadcasting, etc to the topic
	def receive(msg: Broadcast) = topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))
	def receive(msg: Unicast) = topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))
	def receive(msg: DHT) = topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))
	def receive(msg: DelegateDirect) = topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))

	//
	// space-to-peer messages
	// these are delegated from other peers
	//
	// delegate message reception handling to the topic
	def receive(msg: DelegatedBroadcast) = topicsJoined.get(msg.space, msg.topic).foreach(_.receive(msg))
	def receive(msg: DelegatedUnicast) = topicsJoined.get(msg.space, msg.topic).foreach(_.receive(msg))
	def receive(msg: DelegatedDHT) = topicsJoined.get(msg.space, msg.topic).foreach(_.receive(msg))
	def receive(msg: DelegatedDirect) = topicsJoined.get(msg.space, msg.topic).foreach(_.receive(msg))

	// catch-all
	def basicReceive(msg: Message) {}

	//
	// properties
	//
	def storePrefs {
		val prefs = props("prefs")

		prefs("public", true) = stringFor(keyPair.getPublic.getEncoded)
		prefs("private", true) = stringFor(keyPair.getPrivate.getEncoded)
		storeProps("prefs")
	}
	def nodeForProps(p: Map[String,Property]) = {
		import scala.xml.NodeSeq._
		<props>{
			for ((_, prop) <- p filter {case (k, v) => v.persist} sortWith {case ((_, p1), (_, p2)) => p1.name < p2.name})
				yield <prop name={prop.name} value={prop.value}/>
		}</props>
	}
	def propNodeName(n: Node) = str(n.attribute("name"))
	def readStorage(f: File) {
		storage = new SetStorage(f)
		storage.nodes foreach {n =>
			val name = propNodeName(n)

			storedNodes(name) = n
			if (name == "prefs") {
				myKeyPair = new KeyPair(publicKeyFor(bytesFor(n.attribute("public").mkString)), privateKeyFor(bytesFor(n.attribute("private").mkString)))
			}
		}
	}
	def storeProps(name: String) {
		if (storage != null) {
			val node = nodeForProps(props(name))

			storedNodes.get(name).foreach(storage -= _)
			storedNodes(name) = node
			storage += node
		}
	}
}
class Property(val name: String, var value: String, var persist: Boolean)
class PropertyMap extends HashMap[String,Property] {
	def update(name: String, value: String) {update(name, false, value)}
	def update(name: String, persist: Boolean, value: String) {this(name) = new Property(name, value, persist)}
}
