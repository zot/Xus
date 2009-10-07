/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.xml.Elem
import scala.xml.Node
import scala.xml.NodeSeq
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.PublicKey
import scala.collection.immutable.{HashMap => PMap}
import scala.collection.mutable.Map
import scala.collection.mutable.HashMap
import scala.actors.Actor._
import scala.xml.persistent.SetStorage

import Util._

object Peer {
	val emptyProps = PMap[String, Property]()
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
//			new Challenge()(_.receive(_)),
//			new ChallengeResponse()(_.receive(_)),
//			new Completed() (_.receive(_)),
//			new Failed() (_.receive(_)),
//			new Direct() (_.receive(_)),
//			new DelegateDirect() (_.receive(_)),
//			new Broadcast() (_.receive(_)),
//			new Unicast() (_.receive(_)),
//			new DHT() (_.receive(_)),
//			new DelegatedDirect() (_.receive(_)),
//			new DelegatedBroadcast() (_.receive(_)),
//			new DelegatedUnicast() (_.receive(_)),
//			new DelegatedDHT() (_.receive(_))
	)
	implicit val emptyHandler = (r: Response) => ()
	implicit val emptyConnectBlock = () => ()
	implicit def hashMapToPropertyMap(m: PMap[String,Property]) = new {
		def + (tuple: ((String, Boolean), String)) = {
			val ((key, persist), value) = tuple

			m.+(key -> new Property(key, value, persist))
		}
	}
	
	def p[M <: Message](name: String, msg: => M)(block: (Peer, M) => Any) = name -> prep(msg)(block)
	def prep[M <: Message](msg: => M)(block: (Peer, M) => Any) = {(con: PeerConnection, node: Node) =>
		val m = msg

		m.set(con, node)
		block(con.peer, m)
		con.peer.received(m)
		m
	}
}
class WaitBlock(block: => Any) {
	def apply() = block
}
class Peer(name: String) extends SimpyPacketPeerAPI {
	import Peer._

	/**
	 * storage should only be modified in the inputActor
	 */
	var storage: SetStorage = null
	var waiters = PMap[(PeerConnection, Int), (Response)=>Any]()
	var peerConnections = PMap[SimpyPacketConnectionAPI, PeerConnection]()
	var storedNodes = PMap[String, Node]()
	var topicsOwned = PMap[(Int,Int), Topic]()
	var topicsJoined = PMap[(Int,Int), TopicConnection]()
	var props = PMap("prefs" -> PMap[String, Property]())
	var myKeyPair: KeyPair = null
	val selfConnection = addConnection(new DirectSimpyPacketConnection(this))
	val inputActor = daemonActor("Peer input") {
		self link Util.actorExceptions
		loop {
			react {
			case block: WaitBlock =>
				block()
				sender ! ()
			case block: (() => Any) => block()
			case (con: SimpyPacketConnectionAPI, str: OpenByteArrayInputStream) => handleInput(con, str)
			case 0 => exit
			}
		}
	}
	val outputActor = daemonActor("Peer output") {
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
		//split this creation into steps so the waiter is in place before the connection initiates
		val pcon = new PeerConnection(null, this)

		if (connectBlock != emptyHandler) {
			inputDo(waiters += ((pcon, 0) -> connectBlock))
		}
		SimpyPacketConnection(host, port, this) {con =>
			pcon.con = con
			peerConnections += con -> pcon
		}
		pcon
	}
	def inputDo(block: => Any, wait: Boolean = true) = {
		if (self == inputActor) block
		else if (wait) inputActor !? new WaitBlock(block)
		else inputActor ! (()=>block)
	}
	def handleInput(con: SimpyPacketConnectionAPI, str: OpenByteArrayInputStream) {
		dispatch(peerConnections(con), parse(str))
	}
	def shutdown {
		inputActor ! 0
	}
	def onResponseDo[M <: Message](msg: M, wait: Boolean = true)(block: (Response)=>Any): M = {
		inputDo(primOnResponseDo(msg, block), wait)
		msg
	}
	/**
	 * only executed by inputActor
	 */
	private def primOnResponseDo[M <: Message](msg: M, block: (Response)=>Any): M = {
		waiters += (msg.con, msg.msgId) -> (waiters.get((msg.con, msg.msgId)) match {
		case Some(inner) => {r =>
			inner(r)
			block(r)
		}
		case None => block
		})
		msg
	}
	def closed(con: SimpyPacketConnectionAPI) {
		topicsOwned.valuesIterator.foreach(_.removeMember(peerConnections(con)))
		peerConnections -= con
	}
	def received[M <: Message](msg: M): M = {
		inputOnly
		msg match {
		case r: Response =>
			val req = waiters.get((r.con, r.requestId))
			waiters -= ((r.con, r.requestId))
			req.foreach(_(r))
		case _ =>
		}
		msg
	}
	def publicKeyString = str(publicKey)
	def genId: this.type = {keyPair = genKeyPair; this}
	def addConnection(con: SimpyPacketConnectionAPI) = {
		val peerCon = new PeerConnection(con, this)
		peerConnections += con -> peerCon
		peerCon
	}
	def send[M <: Message](con: SimpyPacketConnectionAPI, msg: M, node: Node): M = {
		val bytes = new ByteArrayOutputStream {
			def byteArray = buf
		}

//		println("sending to " + con + ": " + node)
		serialize(node, bytes)
//		println("sending packet ["+bytes.size+"], "+node)
		// test serialization
		val parseResult = toXML(parse(new OpenByteArrayInputStream(bytes.byteArray, 0, bytes.size)))
		if (parseResult != toXML(node)) {
			Console.err.println("Error serializing node.\n input: "+toXML(node)+"\noutput: "+parseResult)
		}
		con.send(bytes.byteArray, 0, bytes.size)
		bytes.reset
		msg
	}
	///////////////////////////
	// API
	///////////////////////////
	var peerId: BigInt = BigInt(0)

	def own(connection: TopicConnection): Topic = own(new Topic(connection.space, connection.topic, this), connection)
	def own(space: Int, topic: Int): Topic = own(space, topic, new TopicConnection(space, topic, selfConnection))
	def own(space: Int, topic: Int, connection: TopicConnection): Topic = own(new Topic(space, topic, this), connection)
	def own[T <: Topic](topic: T): T = own(topic, new TopicConnection(topic.space, topic.topic, selfConnection))
	def own[T <: Topic](topic: T, connection: TopicConnection): T = {
		topicsOwned += (topic.space, topic.topic) -> topic
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
				val msg = new Failed()
				msg.set(null, node)
				con.failed(msg, "Not authenticated")
				con.close
				(con: PeerConnection, node: Node) => println("Connection did not validate: "+con+", msg: "+node)
			} else {
				dispatchers(node.label.toLowerCase)
			}
	})(con, node)
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
	def ownedTopic(space: Int, topic: Int) = topicsOwned.get(space, topic)
	def joinedTopic(space: Int, topic: Int) = topicsJoined.get(space, topic)
	def receive(msg: Direct) = {
		msg.payload match {
		case Seq(n @ <xus-join/>) =>
			for {
				spaceN <- strOpt(n, "space")
				topicN <- strOpt(n, "topic")
				topic <- ownedTopic(spaceN.toInt, topicN.toInt)
			} topic.joinRequest(msg)
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
				spaceN <- strOpt(n, "space")
				topicN <- strOpt(n, "topic")
				topic <- ownedTopic(spaceN.toInt, topicN.toInt)
			} topic.setPropRequest(msg)
		case Seq(n @ <xus-delprop/>) =>
			for {
				spaceN <- strOpt(n, "space")
				topicN <- strOpt(n, "topic")
				topic <- ownedTopic(spaceN.toInt, topicN.toInt)
			} topic.deletePropRequest(msg)
		case _ => ownedTopic(msg.space, msg.topic).foreach(_.process(msg))
		}
		basicReceive(msg)
	}
	def receive(msg: Unicast) {
		ownedTopic(msg.space, msg.topic).foreach(_.process(msg))
		basicReceive(msg)
	}
	def receive(msg: DHT) {
		ownedTopic(msg.space, msg.topic).foreach(_.process(msg))
		basicReceive(msg)
	}
	def receive(msg: DelegateDirect) {
		ownedTopic(msg.space, msg.topic).foreach(_.process(msg))
		basicReceive(msg)
	}

	//
	// space-to-peer messages
	// these are delegated from other peers
	//
	// delegate message reception handling to the topic
	def receive(msg: DelegatedBroadcast) {
		joinedTopic(msg.space, msg.topic).foreach(_.receive(msg))
		basicReceive(msg)
	}
	def receive(msg: DelegatedUnicast) {
		joinedTopic(msg.space, msg.topic).foreach(_.receive(msg))
		basicReceive(msg)
	}
	def receive(msg: DelegatedDHT) {
		joinedTopic(msg.space, msg.topic).foreach(_.receive(msg))
		basicReceive(msg)
	}
	def receive(msg: DelegatedDirect) {
		joinedTopic(msg.space, msg.topic).foreach(_.receive(msg))
		basicReceive(msg)
	}

	// catch-all
	def basicReceive(msg: Message) {}

	//
	// properties
	//
	def setProp(category: String, key: String, value: String, persist: Boolean) {
		inputOnly
		props += category -> (props.getOrElse("prefs", new PMap[String, Property]()) + (key -> new Property(key, value, persist)))
	}
	def deleteProp(category: String, key: String) = {
		inputOnly
		props.get(category) flatMap {propCat =>
			propCat.get(key) map {prop =>
				props += category -> (propCat - key)
				prop
			}
		}
	}
	def storePrefs {
		inputDo {
			setProp("prefs", "public", str(keyPair.getPublic), true)
			setProp("prefs", "private", str(keyPair.getPrivate), true)
//			if (storage != null) {
//				println("writing public: " + prefs("public").value)
//				println("writing private: " + prefs("private").value)
//			}
			storeProps("prefs")
		}
	}
	def nodeForProps(name: String) = {
		inputOnly
		import scala.xml.NodeSeq._
		<props name={name}>{
			for ((_, prop) <- props.getOrElse(name, emptyProps).toSeq filter {case (k, v) => v.persist} sortWith {case ((_, p1), (_, p2)) => p1.name < p2.name})
				yield <prop name={prop.name} value={prop.value}/>
		}</props>
	}
	def inputOnly = if (self != inputActor) throw new Exception("This method can only be executed in the peer's inputActor")
	def useProps(n: Node) {
		inputOnly
		for (name <- strOpt(n, "name")) {
			storedNodes += name -> n
			val map = PMap[String, Property]()
			props += name -> map
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
		inputDo {
			storage = null
			val str = new SetStorage(f)
			for (n <- str.nodes) useProps(n)
			storage = str
		}
	}
	def storeProps(name: String) {
		if (storage != null) {
			inputDo {
				val node = nodeForProps(name)

				storedNodes.get(name).foreach(storage -= _)
				storedNodes += name -> node
				storage += node
				storage.flush
			}
		}
	}
	override def toString = "Peer(" + name + ", " + str(peerId) + ")"
}
class Property(val name: String, var value: String, var persist: Boolean)
