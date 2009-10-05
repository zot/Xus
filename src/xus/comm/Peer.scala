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
	implicit val emptyHandler = (r: Response) => ()
	implicit val emptyConnectBlock = () => ()
	
	def prep[M <: Message](msg: => M)(block: (Peer, M) => Any) = {(con: PeerConnection, node: Node, peer: Peer) =>
		val m = msg

		m.set(con, node)
		block(peer, m)
		peer.received(m)
		m
	}
}
class Peer(name: String) extends SimpyPacketPeerAPI {
	import Peer._

	val waiters = Map[(PeerConnection, Int), (Response)=>Any]()
	var myKeyPair: KeyPair = null
	var storage: SetStorage = null
	var storedNodes = Map[String, Node]()
	var props = Map("prefs" -> new PropertyMap())
	val peerConnections = Map[SimpyPacketConnectionAPI, PeerConnection]()
	val topicsOwned = Map[(Int,Int), Topic]()
	val topicsJoined = Map[(Int,Int), TopicConnection]()
	val selfConnection = peerConnections(addConnection(new DirectSimpyPacketConnection(this)))
	val emptyProps = new PropertyMap()
	val inputActor = daemonActor("Peer input") {
		self link Util.actorExceptions
		loop {
			react {
			case (con: SimpyPacketConnectionAPI, str: OpenByteArrayInputStream) => handleInput(con, str)
			case 0 => exit
			}
		}
	}
	
	selfConnection.authenticated = true

	def connect(host: String, port: Int)(implicit connectBlock: (Response)=>Any): PeerConnection = {
		val pcon = new PeerConnection(null, this)

		if (connectBlock != emptyHandler) {
			waiters((pcon, 0)) = connectBlock
		}
		SimpyPacketConnection(host, port, this) {con =>
			pcon.con = con
			peerConnections(con) = pcon
		}
		pcon
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
	def onResponseDo[M <: Message](msg: M)(block: (Response)=>Any): M = {
		waiters((msg.con, msg.msgId)) = block
		msg
	}
	def closed(con: SimpyPacketConnectionAPI) {
		topicsOwned.valuesIterator.foreach(_.removeMember(peerConnections(con)))
		peerConnections.remove(con)
	}
	def received[M <: Message](msg: M): M = {
		msg match {
		case r: Response => waiters.remove((r.con, r.requestId)).foreach(_(r))
		case _ =>
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
//		println("sending to " + con + ": " + node)
		serialize(node, bytes)
//		println("sending packet ["+bytes.size+"] to "+node)
		con.send(bytes.byteArray, 0, bytes.size)
		bytes.reset
		msg
	}
	///////////////////////////
	// API
	///////////////////////////
	var peerId: BigInt = BigInt(0)

	def own(space: Int, topic: Int): Topic = own(space, topic, new TopicConnection(space, topic, selfConnection))
	def own(space: Int, topic: Int, connection: TopicConnection): Topic = own(new Topic(space, topic, this), connection)
	def own[T <: Topic](topic: T): T = own(topic, new TopicConnection(topic.space, topic.topic, selfConnection))
	def own[T <: Topic](topic: T, connection: TopicConnection): T = {
		topicsOwned((topic.space, topic.topic)) = topic
		if (connection != null) {
			connection.join
		}
		topic
	}
	def join(space: Int, topic: Int, con: PeerConnection): TopicConnection = join(new TopicConnection(space, topic, con))
	def join[C <: TopicConnection](con: C): C = con.join
	def dispatch(con: PeerConnection, node: Node) = (node match {
		case <signature>{n @ <challenge-response/>}</signature> => dispatchers(n.label.toLowerCase)
		case <challenge/> => dispatchers(node.label.toLowerCase)
		case _ =>
			if (!con.authenticated) {
				val msg = new Message()
				msg.set(null, node)
				con.failed(msg, "Not authenticated")
				con.close
				(con: PeerConnection, node: Node, peer: Peer) => println("Connection did not validate: "+con+", msg: "+node)
			} else {
				dispatchers(node.label.toLowerCase)
			}
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
	case succeed: Completed => msg.con.completed(succeed.requestId, succeed.payload)
	case fail: Failed => msg.con.failed(fail.requestId, fail.payload)
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
	def verifySignature(node: Node, key: PublicKey, signature: Array[Byte]): Boolean = verify(node, key, signature)
	def receive(msg: Challenge) {
		val myToken = str(randomInt(1000000000))
		msg.con.authenticationToken = myToken
		msg.con.challengeResponse(msg.token, str(myToken), msg.msgId)
		basicReceive(msg)
	}
	def receive(msg: ChallengeResponse) = {
		msg.con.setKey(bytesFor(msg.publicKey))
		if (verifySignature(msg.innerNode, msg.con.peerKey, bytesFor(msg.signature)) && msg.con.authenticationToken == msg.token) {
			msg.con.authenticated = true
			if (msg.challengeToken.length > 0) {
				msg.con.challengeResponse(msg.challengeToken, "", msg.msgId)
			}
		} else {
			msg.con.failed(msg, "Invalid signature")
			msg.con.close
		}
	}
	def receive(msg: Completed) = basicReceive(msg)
	def receive(msg: Failed) = basicReceive(msg)
	def receive(msg: Direct) = {
		msg.payload match {
		case Seq(n @ <xus-join/>) =>
			for {
				space <- strOpt(n, "space")
				topic <- strOpt(n, "topic")
			} topicsOwned((space.toInt, topic.toInt)).joinRequest(msg)
//			println(this + " received: " + msg.getClass.getSimpleName + ", msgId = " + msg.msgId)
		case _ =>
		}
		basicReceive(msg)
	}

	//
	// peer-to-space messages
	//
	// delegate broadcasting, etc to the topic
	def receive(msg: Broadcast) {
		msg.payload match {
		case Seq(n @ <xus-setprop/>) =>
			for {
				space <- strOpt(n, "space")
				topic <- strOpt(n, "topic")
			} topicsOwned((space.toInt, topic.toInt)).setPropRequest(msg)
		case Seq(n @ <xus-delprop/>) =>
			for {
				space <- strOpt(n, "space")
				topic <- strOpt(n, "topic")
			} topicsOwned((space.toInt, topic.toInt)).deletePropRequest(msg)
		case _ => topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))
		}
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
			for ((_, prop) <- props.get(name).getOrElse(emptyProps).toSeq filter {case (k, v) => v.persist} sortWith {case ((_, p1), (_, p2)) => p1.name < p2.name})
				yield <prop name={prop.name} value={prop.value}/>
		}</props>
	}
	def useProps(n: Node) {
		for (name <- strOpt(n, "name")) {
			storedNodes(name) = n
			val map = new PropertyMap()
			props(name) = map
			for {
				child <- n.child
				name <- strOpt(child, "name")
				value <- strOpt(child, "value")
			} map(name) = value
			if (name == "prefs") {
				var pub = ""
				var priv= ""

				for {
					child <- n.child
					name <- strOpt(child, "name")
					value <- strOpt(child, "value")} name match {
				case "public" => pub = value
				case "private" => priv = value
				case _ =>
				}
				keyPair = new KeyPair(publicKeyFor(bytesFor(pub)), privateKeyFor(bytesFor(priv)))
			}
		}
	}
	def readStorage(f: File) {
		storage = null
		val str = new SetStorage(f)
		for (n <- str.nodes) useProps(n)
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
	override def toString = "Peer(" + name + ", " + str(peerId) + ")"
}
class Property(val name: String, var value: String, var persist: Boolean)
class PropertyMap extends HashMap[String,Property] {
	def update(name: String, value: String) {update(name, false, value)}
	def update(name: String, persist: Boolean, value: String) {this(name) = new Property(name, value, persist)}
}
