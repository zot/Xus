/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.testing

import xus.comm._
import xus.comm.Util._
import scala.xml.Node
import scala.xml.persistent.SetStorage
import scala.actors.Actor
import scala.actors.Actor._
import scala.actors.DaemonActor
import scala.collection.mutable.{ArrayBuffer => MList}
import scala.collection.JavaConversions._
import java.io.File
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.nio.channels.ServerSocketChannel
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert._

class LoggerStats {
	var totalEvents = 0
	var outstandingEvents = 0
	var errors = List[Throwable]()
	var counts = List[Int]()
	val eventSignal = new Object
	val logger = daemonActor("Test Logger") {
		self link Util.actorExceptions
//		println("NEW LOGGER: " + self)
		loop {
			react {
			case i: Int => outstandingEvents += i
//				println(self+" increased outstanding events to "+outstandingEvents)
			case (peer: TestPeer, msg: Message) =>
//				println("event, " + peer + ": " + msg.getClass.getSimpleName + ", msgId = " + msg.msgId)
				Console.flush
				peer.events += msg
				eventSignal synchronized {
					outstandingEvents -= 1
//					println(self+" decreased outstanding events to "+outstandingEvents)
					totalEvents += 1
					if (outstandingEvents < 0) {
						errors ::= new Exception("BAD EVENT COUNT: "+outstandingEvents)
//						errors(0).printStackTrace
					}
					if (outstandingEvents <= 0) {
						counts ::= outstandingEvents
					}
					eventSignal.notifyAll
				}
			}
		}
	}
}

object PeerTests {
	val HOST = "localhost"
	val PORT = 9999
	var accepting = false
	var direct = true
	var errors = List[Throwable]()
	var exceptions = List[Throwable]()
	var stats = new LoggerStats
	var mainActor: Actor = null
	val lock = new Object
	var done = false
	var connectionBlock: (SocketChannel, Option[Acceptor]) => CheckingConnection = null
	
	def newStats = {
		stats = new LoggerStats
		stats
	}

	def main(args: Array[String]) {
		var maxWaitUntil = System.currentTimeMillis + 15000
		val writer = new java.io.PrintWriter(Console.err)

		Util.actorExceptionBlock = {(from: Actor, ex: Exception) =>
			exceptions = ex :: exceptions
		}
		lock synchronized {
			daemonActor("Tests") {
				self link Util.actorExceptions
				mainActor = self
				try {
					direct = true
					val failures1 = errors ++ org.junit.runner.JUnitCore.runClasses(classOf[PeerTests]).getFailures.map(_.getException)
					errors = Nil
					direct = false
					val failures2 = errors ++ org.junit.runner.JUnitCore.runClasses(classOf[PeerTests]).getFailures.map(_.getException)
					Xus.shutdown
					if (exceptions.isEmpty && failures1.isEmpty && failures2.isEmpty) {
						println("Success")
					} else {
						if (!failures1.isEmpty) {
							writer.println("Direct failures...")
							failures1.foreach(f => f.printStackTrace(writer))
						}
						if (!failures2.isEmpty) {
							writer.println("Socket failures...")
							failures2.foreach(f => f.printStackTrace(writer))
						}
//						if (!exceptions.isEmpty) {
//							writer.println("General exceptions...")
//							exceptions.foreach(_.printStackTrace(writer))
//						}
						writer.flush
					}
					lock synchronized {
						done = true
						lock.notifyAll
					}
				} catch {
				case e: Error => e.printStackTrace
				}
			}
			while (!done) {
				val wtime = maxWaitUntil - System.currentTimeMillis

				if (wtime > 0) lock.wait(wtime)
				else done = true
			}
			if (!exceptions.isEmpty) {
				writer.println("General exceptions...")
				exceptions.foreach(_.printStackTrace(writer))
				writer.flush
			}
		}
	}
}
class PeerTests {
	var stats: LoggerStats = null
	var owner: TestPeer = null
	var member1: TestPeer = null

	@Before def setup {
//		println("SETUP: "+this)
		PeerTests.connectionBlock = {(chan, optAcceptor) => 
			val con = new CheckingConnection(chan, owner, optAcceptor)
	
			owner.addConnection(con)
			owner.peerConnections(con).challenge(randomInt(1000000000).toString)
			con
		}
		stats = new LoggerStats
		PeerTests.stats = stats
		val baseDir = new File("/tmp/base-storage")
		val trash = new File(baseDir, "trash")
		val ownerStorage = new File(baseDir, "owner")
		val memberStorage = new File(baseDir, "member")

		if (baseDir.exists) {
			baseDir.listFiles.foreach {file =>
				if (file.getName != "trash") {
					deleteFile(file, trash)
				}
			}
		}
		if (!trash.exists) trash.mkdirs
		deleteFile(ownerStorage, trash)
		deleteFile(memberStorage, trash)
		owner = newOwner
		owner.readStorage(ownerStorage)
		member1 = newMember("member 1")
		member1.readStorage(memberStorage)
	}

	def deleteFile(dir: File, trash: File) {
		if (dir.exists) {
			var suffix = 1
			var newName: File = null

			do {
				newName = new File(trash, dir.getName+"-"+randomInt(1000000))
			} while (newName.exists)
			dir.renameTo(newName)
//			daemonActor("trash removal") {
//				newName.deleteAll
//			}
		}
	}

	@Test def testMessaging {
		verifyIds
		testAuthorization
		sendPropSettings
		sendBroadcasts
		waitForCompletion
		verifyPropSettings
	}
	def verifyIds {
		assertEquals(member1.peerId, member1.selfConnection.peerId)
		assertEquals(member1.peerId, digestInt(member1.publicKey.getEncoded))
		assertEquals(owner.peerId, owner.selfConnection.peerId)
		assertEquals(owner.peerId, digestInt(owner.publicKey.getEncoded))
	}
	def sendBroadcasts {
		for (i <- 1 to 100) {
			stats.logger ! 2
			member1.topic.broadcast("hello there")
		}
		stats.logger ! 2
		member1.topic.unicast("uni")
		member1.topic.dht(5000, "dht")
	}
	def sendPropSettings {
		stats.logger ! 2
		member1.topic(Properties).setprop("a", "b", true)
	}
	def verifyPropSettings {
		assertEquals(member1.topic(Properties).getprop("a"), Some("b"))
		assertEquals(Some("b"), for {
			props <- member1.storage.nodes.find(strOpt(_, "name") == Some("(0,1)"))
			prop <- props.child.find(strOpt(_, "name") == Some("a"))
			value <- strOpt(prop, "value")
		} yield value)
		assertEquals(owner.topic(Properties).getprop("a"), Some("b"))
		verifyNodes(owner.storage)
		verifyNodes(member1.storage)
	}
	def verifyNodes(storage: SetStorage) {
		val map = storage.nodes.foldLeft(Map.newBuilder[String,Node])((b,n)=>b += strOpt(n, "name").get -> n).result

		assertEquals(2, map.size)
		assertEquals(2, map("prefs").child.length)
		assertEquals(1, map("(0,1)").child.length)
	}
	def testAuthorization {
		member1.topic.owner.broadcast(0, 0, "test") {msg =>
			assertTrue(msg.isInstanceOf[Failed])
		}
	}
	/**
	 * this verifies that all broadcasts, unicasts, and dht msgs have come through properly
	 */
	def waitForCompletion {
		var oldCount = -1
		var done = false
		
		while (!done) {
			stats.eventSignal synchronized {
				if (stats.outstandingEvents == 0) done = true
				else {
					val startWait = System.currentTimeMillis
					stats.eventSignal.wait(1000)
					if (oldCount != stats.totalEvents) {
						oldCount = stats.totalEvents
					} else if (System.currentTimeMillis - startWait >= 1000) done = true
				}
			}
		}
		assertEquals(0, stats.outstandingEvents)
	}

	def newConnection(connection: SocketChannel, peer: Peer, acceptor: Option[Acceptor]) = new CheckingConnection(connection, peer, acceptor)

	def newOwner = {
		val o = new TestPeer("Owner")

		// support(Properties) sends out a broadcast
		stats.logger ! 1
		o.topic = new TestTopicConnection(0, 1, o.selfConnection)
		o.own(0, 0)
		o.own(0, 1, o.topic).support(Properties)
		o
	}
	
	def newMember(name: String) = {
		val m = new TestPeer(name)
		val myEnd = connect(m, owner)

		m.topic = m.join(new TestTopicConnection(0, 1, myEnd))
		m
	}
	def connect(peer1: TestPeer, peer2: TestPeer) = {
		var pcon: PeerConnection = null

		if (PeerTests.direct) {
			val con = new DirectSimpyPacketConnection(peer1, new DirectSimpyPacketConnection(peer2) with LastMsgId) with LastMsgId
			peer2.addConnection(con.otherEnd)
			peer1.addConnection(con)
			pcon = peer1.peerConnections(con)
			peer1.waiters((pcon, 0)) = {PeerTests.mainActor ! _}
			peer2.peerConnections(con.otherEnd).challenge(randomInt(1000000000).toString)
		} else {
			// this is a hack
			// maybe the acceptor should be changed to use the current owner instead of peer2
			// or else we need to shut down the acceptor in an @after method
			if (!PeerTests.accepting) {
				PeerTests.accepting = true;
				Acceptor.listen(PeerTests.PORT) {chan =>
					new Acceptor(chan, peer2) {
						override def newConnection(connection: SocketChannel) = PeerTests.connectionBlock(connection, Some(this))
					}
				}
			}
			pcon = peer1.connect(PeerTests.HOST, PeerTests.PORT) {PeerTests.mainActor ! _}
		}
		peer1.ownerCon = pcon
		var connected = false
		PeerTests.mainActor.receiveWithin(1000) {
			case x => connected = true // println("CONNECTED "+peer1)
		}
		assertTrue(connected)
		pcon
	}
}
trait LastMsgId {
	var lastMsgId = -1
	var msg: Message = null
}
class CheckingConnection(connection: SocketChannel, peer: Peer, acceptor: Option[Acceptor]) extends SimpyPacketConnection(connection, peer, acceptor) with LastMsgId {
	var lastBytes: Sequence[Byte] = null

	override def send(newOutput: Array[Byte], offset: Int, len: Int) {
		val newBytes = newOutput.slice(offset, offset + len).toSeq

		if (newBytes == lastBytes) {
			println("ERROR: SAME BYTES SENT")
		}
		assertFalse(newBytes == lastBytes)
		lastBytes = newBytes
		super.send(newOutput, offset, len)
	}
}
class TestTopicConnection(space: Int, topic: Int, peer: PeerConnection) extends TopicConnection(space, topic, peer) {
	import PeerTests._
	
	val stats = PeerTests.stats
	
	override def receive(msg: DelegatedBroadcast) {
//		println(owner.peer + " received: "+msg)
		stats.logger ! (owner.peer, msg)
		super.receive(msg)
	}
	override def receive(msg: DelegatedUnicast) {
//		println(owner.peer + " received: "+msg)
		stats.logger ! (owner.peer, msg)
		super.receive(msg)
	}
	override def receive(msg: DelegatedDHT) {
//		println(owner.peer + " received: "+msg)
		stats.logger ! (owner.peer, msg)
		super.receive(msg)
	}
}
class TestPeer(name: String) extends Peer(name) {
	var ownerCon: PeerConnection = null
	val events = scala.collection.mutable.ArrayBuffer[Any]()
	var lastBytes: Sequence[Byte] = null
	var badBytes = Set[Array[Byte]]()
	var topic: TopicConnection = null
	
	genId

	override def createSelfConnection = addConnection(new DirectSimpyPacketConnection(this) with LastMsgId)
	override def receiveInput(con: SimpyPacketConnectionAPI, bytes: Array[Byte]) {
		val newBytes = bytes.slice(0, bytes.length).toSeq

		if (lastBytes == newBytes) {
			badBytes.add(bytes)
			println("ERROR: SAME BYTES RECEIVED")
		}
		assertFalse(newBytes == lastBytes)
		lastBytes = newBytes
		super.receiveInput(con, bytes)
	}
	override def handleInput(con: SimpyPacketConnectionAPI, str: OpenByteArrayInputStream) = {
//println("Input: "+parse(str)); str.reset
		if (badBytes(str.bytes)) {
			badBytes.remove(str.bytes)
			println("Duplicate input: " + parse(str))
		}
		super.handleInput(con, str)
	}
	override def received[M <: Message](msg: M): M = {
		val lastIdCon = msg.con.con.asInstanceOf[LastMsgId]

		if (lastIdCon.lastMsgId >= msg.msgId) {
//			println("Received message out of order.  Last received id: "+lastIdCon.lastMsgId+" current id: "+msg.msgId)
			fail("Received message out of order.  Last message:\n"+lastIdCon.msg.node+"\nCurrent message:\n"+msg.node)
		}
		lastIdCon.lastMsgId = msg.msgId
		lastIdCon.msg = msg
		super.received(msg)
	}
	override def connect(host: String, port: Int)(implicit connectBlock: (Response)=>Any): PeerConnection = {
		//split this creation into steps so the waiter is in place before the connection initiates
		val pcon = new PeerConnection(null, this)

		if (connectBlock != Peer.emptyHandler) {
			inputDo(waiters += ((pcon, 0) -> connectBlock))
		}
		SimpyPacketConnection(host, port, this, (con, peer, acc) => new CheckingConnection(con, peer.asInstanceOf[TestPeer], acc)) {con =>
		pcon.con = con
		peerConnections += con -> pcon
		}
		pcon
	}
}