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
//	var waitBlockNum = 0
	implicit val emptyHandler = (r: Response) => ()
	implicit val emptyConnectBlock = () => ()

	implicit def hashMapToPropertyMap(m: PMap[String,Property]) = new {
		def + (tuple: ((String, Boolean), String)) = {
			val ((key, persist), value) = tuple

			m.+(key -> new Property(key, value, persist))
		}
	}
}
class WaitBlock(block: => Any) {
//	val num = Peer.waitBlockNum
//	Peer.waitBlockNum += 1
//
//	println(this)
//
	def apply() = {
//		println("Executing wait block: "+num)
		block
	}
//	override def toString = "WaitBlock: "+num
}
class Peer(name: String) extends SimpyPacketPeerAPI {
	import Peer._

	/**
	 * storage should only be modified in the inputActor
	 */
	var storage: SetStorage = null
	var waiters = PMap[(PeerConnection, Int), (Response)=>Any]()
	var peerConnections = PMap[SimpyPacketConnectionAPI, PeerConnection]()
	var connectionsByPeerId = PMap[BigInt, PeerConnection]()
	var storedNodes = PMap[String, Node]()
	var topicsOwned = PMap[(Int,Int), TopicMaster]()
	var topicsJoined = PMap[(Int,Int), TopicConnection]()
	var props = PMap("prefs" -> PMap[String, Property]())
	var myKeyPair: KeyPair = null
	var observers = Set[MonitorObserver]()
	val selfConnection = createSelfConnection
	val inputActor = daemonActor("Peer input") {
		self link Util.actorExceptions
		loop {
			react {//case x => println("input received: "+x); x match {
			case block: WaitBlock => sender ! block()
			case block: (() => Any) => block()
//			case (con: SimpyPacketConnectionAPI, str: OpenByteArrayInputStream) => println("stream input"); handleInput(con, str); println("Done with input")
			case (con: SimpyPacketConnectionAPI, str: OpenByteArrayInputStream) => handleInput(con, str)
			case 0 => exit
			case any => new Exception("unknown input to peer's inputActor: " + any).printStackTrace
//			}
			}
		}
	}

	selfConnection.authenticated = true

	def addObserver(obs: MonitorObserver) {
		observers += obs
	}
	def createSelfConnection = addConnection(new DirectSimpyPacketConnection(this))
	def inputDo(block: => Any): Any = inputDo(true)(block)
	def inputDo(wait: Boolean)(block: => Any) = {
		if (self == inputActor) block
		else if (wait) inputActor !? new WaitBlock(block)
		else inputActor ! (()=>block)
	}
	def connect(host: String, port: Int, expectedPeerId: BigInt)(implicit connectBlock: (Response)=>Any): PeerConnection = {
		//this creation is split into steps so the waiter is in place before the connection initiates
		val pcon = new PeerConnection(null, this)

		pcon.peerId = expectedPeerId
		if (connectBlock != emptyHandler) {
			inputDo(waiters += ((pcon, 0) -> connectBlock))
		}
		SimpyPacketConnection(host, port, this) {con =>
			pcon.con = con
			addPeerConnection(pcon)
		}
		pcon
	}
	def addPeerConnection(pcon: PeerConnection) {
		peerConnections += pcon.con -> pcon
		pcon
	}
	def removePeerConnection(pcon: PeerConnection) {
		peerConnections -= pcon.con
		observers foreach (_.removePeerConnection(pcon))
	}
	def handleInput(scon: SimpyPacketConnectionAPI, str: OpenByteArrayInputStream) {
		val node = parse(str)
		val con = peerConnections(scon)

		for (msg <- node match {
			case <signature>{n @ <challenge-response/>}</signature> => Protocol.messageMap.get(n.label.toLowerCase)
			case <challenge/> => Protocol.messageMap.get(node.label.toLowerCase)
			case _ =>
				if (!con.authenticated) {
					val msg = new Failed()
					msg.set(null, node)
					con.failed(msg, "Not authenticated")
					con.close
					println("Connection did not validate: "+con+", msg: "+node)
					None
				} else {
					Protocol.messageMap.get(node.label.toLowerCase)
				}
		}) {
			val m = msg.copy

			m.set(con, node)
			m.dispatch(this)
			received(m)
		}
	}
	def shutdown = inputActor ! 0
	def onResponseDo[M <: Message](msg: M)(block: (Response)=>Any): M = {
		inputDo(waiters += (msg.con, msg.msgId) -> (waiters.get((msg.con, msg.msgId)) match {
		case Some(inner) => {r =>
			inner(r)
			block(r)
		}
		case None => block
		}))
		msg
	}
	def closed(con: SimpyPacketConnectionAPI) {
		val pcon = peerConnections(con)

		topicsOwned.valuesIterator.foreach(_.removeMember(pcon))
		connectionsByPeerId -= pcon.peerId
		removePeerConnection(pcon)
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
		addPeerConnection(peerCon)
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
//		val parseResult = toXML(parse(new OpenByteArrayInputStream(bytes.byteArray, 0, bytes.size)))
//		if (parseResult != toXML(node)) {
//			Console.err.println("Error serializing node.\n input: "+toXML(node)+"\noutput: "+parseResult)
//		}
		con.send(bytes.byteArray, 0, bytes.size)
		msg
	}
	///////////////////////////
	// API
	///////////////////////////
	var peerId: BigInt = BigInt(0)

	def own(connection: TopicConnection): TopicMaster = own(new TopicMaster(connection.space, connection.topic, this), connection)
	def own(space: Int, topic: Int): TopicMaster = own(space, topic, new TopicConnection(space, topic, selfConnection))
	def own(space: Int, topic: Int, connection: TopicConnection): TopicMaster = own(new TopicMaster(space, topic, this), connection)
	def own[M <: TopicMaster](master: M): M = own(master, new TopicConnection(master.space, master.topic, selfConnection))
	def own[M <: TopicMaster](master: M, connection: TopicConnection): M = {
		topicsOwned += (master.space, master.topic) -> master
		if (connection != null) {
			connection.join
		}
		master
	}
	def join(space: Int, topic: Int, con: PeerConnection): TopicConnection = join(new TopicConnection(space, topic, con))
	def join[C <: TopicConnection](con: C): C = con.join
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
		if (msg.con.setKey(bytesFor(msg.publicKey))) {
			//TODO this could replace a redundant connection to the same peer
			connectionsByPeerId += msg.con.peerId -> msg.con
			if (verifySignature(msg.innerNode, msg.con.peerKey, bytesFor(msg.signature)) && msg.con.authenticationToken == msg.token) {
				msg.con.authenticated = true
				observers foreach (_.addPeerConnection(msg.con))
				if (msg.challengeToken.length > 0) {
					msg.con.challengeResponse(msg.challengeToken, "", msg.msgId)
				}
			} else {
				msg.con.failed(msg, "Invalid signature")
				msg.con.close
			}
		} else {
			msg.con.failed(msg, "Unexpected PeerId")
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
		ownedTopic(msg.space, msg.topic).foreach(_.process(msg))
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
	def getPref(key: String) = getProp("prefs", key)
	def setPref(key: String, value: String) = setProp("prefs", key, value, true)
	def getProp(category: String, key: String) = for {
		p <- props.get(category)
		prop <- p.get(key)
	} yield prop.value
	def setProp(category: String, key: String, value: String, persist: Boolean) {
		inputOnly
		val oldPersist = (for {pr <- props.get(category); p <- pr.get(key)} yield p.persist).getOrElse(false)
		props += category -> (props.getOrElse(category, new PMap[String, Property]()) + (key -> new Property(key, value, persist)))
		if (persist || oldPersist) storeProps(category)
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
			setPref("public", str(keyPair.getPublic))
			setPref("private", str(keyPair.getPrivate))
			storeProps("prefs")
		}
	}
	def nodeForProps(name: String) = {
		inputOnly
		import scala.xml.NodeSeq._
		<xus-props name={name}>{
			for ((name, prop) <- props.getOrElse(name, emptyProps).toSeq filter {case (k, v) => v.persist} sortWith {case ((k1, _), (k2, _)) => k1 < k2})
				yield <prop name={name} value={prop.value}/>
		}</xus-props>
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
			val str = new SetStorage(f)
			storage = null
			for (n <- str.nodes) useProps(n)
			storage = str
			if (!str.nodes.hasNext) storePrefs
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

trait MonitorObserver {
	def addPeerConnection(con: PeerConnection): Unit
	def removePeerConnection(con: PeerConnection): Unit
	def joinTopic(con: PeerConnection, topic: TopicMaster): Unit
}
