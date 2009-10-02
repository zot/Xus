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
import java.io.ByteArrayOutputStream
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
		peer.receiving(m)
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
class Peer(name: String, connectBlock: => Unit = null) extends SimpyPacketPeerAPI {
	import Peer._

	val waiters = Map[Int, (Response)=>Unit]()
	var myKeyPair: KeyPair = null
	var storage: SetStorage = null
	var storedNodes = Map[String, Node]()
	var props = Map("prefs" -> new PropertyMap())
	val peerConnections = Map[SimpyPacketConnectionAPI, PeerConnection]()
	val topicsOwned = Map[(Int,Int), Topic]()
	val topicsJoined = Map[(Int,Int), Topic]()
	val selfConnection = peerConnections(addConnection(new DirectSimpyPacketConnection(this)))
	val inputActor = daemonActor("Peer input") {
		loop {
			react {
			case (con: SimpyPacketConnectionAPI, str: OpenByteArrayInputStream) => handleInput(con, str)
			case 0 => exit
			}
		}
	}

	def handleInput(con: SimpyPacketConnectionAPI, str: OpenByteArrayInputStream) {
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
	def shutdown {
		inputActor ! 0
	}
	def onResponseDo[M <: Message](msg: M)(block: (Response)=>Unit): M = {
		waiters(msg.msgId) = block
		msg
	}
	def closed(con: SimpyPacketConnectionAPI) {
		topicsOwned.valuesIterator.foreach(_.removeMember(peerConnections(con)))
	}
	def receiving[M <: Message](msg: M): M = {
		if (msg.isInstanceOf[Response]) {
			waiters.remove(msg.msgId).foreach(_(msg.asInstanceOf[Response]))
		}
		msg
	}
	def publicKeyString = str(publicKey)
	def genId: this.type = {keyPair = genKeyPair; this}
	def addConnection(con: SimpyPacketConnectionAPI) = {
		peerConnections(con) = new PeerConnection(con, this)
		con
	}

	val bytes = new ByteArrayOutputStream {
		def byteArray = buf
	}
	def send[M <: Message](con: SimpyPacketConnectionAPI, msg: M, node: Node): M = {
//		println("sending to " + str(peerId) + ": " + node)
		serialize(node, bytes)
		con.send(bytes.byteArray, 0, bytes.size)
		bytes.reset
		msg
	}
	///////////////////////////
	// API
	///////////////////////////
	var peerId: BigInt = BigInt(0)

	def dispatch(con: PeerConnection, node: Node) = (node match {
		case <signature>{n @ _}</signature> => dispatchers(n.label.toLowerCase)
		case _ => dispatchers(node.label.toLowerCase)
	})(con, node, this)
	def publicKey = myKeyPair.getPublic
	def privateKey = myKeyPair.getPrivate
	def keyPair = myKeyPair
	def keyPair_=(kp: KeyPair) {
		myKeyPair = kp
		peerId = digestInt(publicKey.getEncoded)
		selfConnection.setKey(publicKey.getEncoded)
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
		inputActor ! (con, new OpenByteArrayInputStream(bytes))
	}

	///////////////////////////
	// TopicSpacePeerAPI
	///////////////////////////
	//
	// peer-to-peer messages
	//
	def verifySignature(msg: ChallengeResponse): Boolean = true
	def receive(msg: Challenge) {
		msg.con.sendChallengeResponse(msg.token, publicKey, msg.msgId)
		connectBlock
		basicReceive(msg)
	}
	def receive(msg: ChallengeResponse) = {
		msg.con.authenticated = true
		msg.con.setKey(bytesFor(msg.publicKey))
	}
	def receive(msg: Completed) = basicReceive(msg)
	def receive(msg: Failed) = basicReceive(msg)
	def receive(msg: Direct) = {
		msg.payload match {
		case Seq(n @ <join/>) =>
			for {
				space <- strOpt(n.attribute("space"))
				topic <- strOpt(n.attribute("topic"))
			} topicsOwned((Integer.parseInt(space), Integer.parseInt(topic))).joinRequest(msg)
//			println(this + " received: " + msg.getClass.getSimpleName + ", msgId = " + msg.msgId)
		case _ => basicReceive(msg)
		}
	}

	//
	// peer-to-space messages
	//
	// delegate broadcasting, etc to the topic
	def receive(msg: Broadcast) {
		topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))
		basicReceive(msg)
	}
	def receive(msg: Unicast) {
		topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))
		basicReceive(msg)
	}
	def receive(msg: DHT) {
		topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))
		basicReceive(msg)
	}
	def receive(msg: DelegateDirect) {
		topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))
		basicReceive(msg)
	}

	//
	// space-to-peer messages
	// these are delegated from other peers
	//
	// delegate message reception handling to the topic
	def receive(msg: DelegatedBroadcast) {
		topicsJoined.get(msg.space, msg.topic).foreach(_.receive(msg))
		basicReceive(msg)
	}
	def receive(msg: DelegatedUnicast) {
		topicsJoined.get(msg.space, msg.topic).foreach(_.receive(msg))
		basicReceive(msg)
	}
	def receive(msg: DelegatedDHT) {
		topicsJoined.get(msg.space, msg.topic).foreach(_.receive(msg))
		basicReceive(msg)
	}
	def receive(msg: DelegatedDirect) {
		topicsJoined.get(msg.space, msg.topic).foreach(_.receive(msg))
		basicReceive(msg)
	}

	// catch-all
	def basicReceive(msg: Message) {}

	//
	// properties
	//
	def storePrefs {
		val prefs = props("prefs")

		prefs("public", true) = str(keyPair.getPublic)
		prefs("private", true) = str(keyPair.getPrivate)
//		if (storage != null) {
//			println("writing public: " + prefs("public").value)
//			println("writing private: " + prefs("private").value)
//		}
		storeProps("prefs")
	}
	def nodeForProps(name: String) = {
		import scala.xml.NodeSeq._
		<props name={name}>{
			for ((_, prop) <- props(name).toSequence filter {case (k, v) => v.persist} sortWith {case ((_, p1), (_, p2)) => p1.name < p2.name})
				yield <prop name={prop.name} value={prop.value}/>
		}</props>
	}
	def strOpt(opt: Option[Seq[Node]]) = opt.map(_.mkString)
	def readStorage(f: File) {
		storage = null
		val str = new SetStorage(f)
		str.nodes foreach {n =>
			var pub = ""
			var priv= ""

			for (name <- strOpt(n.attribute("name"))) {
				storedNodes(name) = n
				if (name == "prefs") {
					for (child <- n.child) {
						for (name <- strOpt(child.attribute("name"))) name match {
						case "public" => for (key <- strOpt(child.attribute("value"))) {
								pub = key
//								println("read public: " + pub)
							}
						case "private" => for (key <- strOpt(child.attribute("value"))) {
								priv = key
//								println("read private: " + priv)
							}
						}
					}
					keyPair = new KeyPair(publicKeyFor(bytesFor(pub)), privateKeyFor(bytesFor(priv)))
				}
			}
		}
		storage = str
	}
	def storeProps(name: String) {
		if (storage != null) {
			val node = nodeForProps(name)

			storedNodes.get(name).foreach(storage -= _)
			storedNodes(name) = node
			storage += node
			storage.flush
		}
	}
	override def toString = "Peer: " + name + "(" + str(peerId) + ")"
}
class Property(val name: String, var value: String, var persist: Boolean)
class PropertyMap extends HashMap[String,Property] {
	def update(name: String, value: String) {update(name, false, value)}
	def update(name: String, persist: Boolean, value: String) {this(name) = new Property(name, value, persist)}
}
