/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.testing

import xus.comm._
import xus.comm.Util._
import scala.xml.Node
import scala.actors.Actor
import scala.actors.Actor._
import scala.actors.DaemonActor
import scala.collection.Sequence
import scala.collection.mutable.{ArrayBuffer => MList}
import scala.collection.JavaConversions._
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
		loop {
			react {
			case i: Int => outstandingEvents += i
			case (peer: TestPeer, msg: Message) =>
//				println("event, " + peer + ": " + msg.getClass.getSimpleName + ", msgId = " + msg.msgId)
				Console.flush
				peer.events += msg
				eventSignal synchronized {
					outstandingEvents -= 1
					totalEvents += 1
					if (outstandingEvents < 0) {
						errors ::= new Exception("BAD EVENT COUNT!")
						errors(0).printStackTrace
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
	var stats = new LoggerStats
	var mainActor: Actor = null
	val lock = new Object
	var done = false
	var exceptions = MList[Throwable]()

	def main(args: Array[String]) {
		Util.actorExceptionBlock = {(from: Actor, ex: Exception) =>
			exceptions.add(ex)
		}
		lock synchronized {
			daemonActor("Tests") {
				self link Util.actorExceptions
				mainActor = self
				try {
					direct = true
					val failures1 = org.junit.runner.JUnitCore.runClasses(classOf[PeerTests]).getFailures
					stats = new LoggerStats
					direct = false
					val failures2 = org.junit.runner.JUnitCore.runClasses(classOf[PeerTests]).getFailures
					Xus.shutdown
					if (exceptions.isEmpty && failures1.isEmpty && failures2.isEmpty) {
						println("Success")
					} else {
						if (!failures1.isEmpty) {
							println("Direct failures...")
							failures1.foreach(f => Console.err.println(f.getTrace))
						}
						if (!failures2.isEmpty) {
							println("Socket failures...")
							failures2.foreach(f => Console.err.println(f.getTrace))
						}
						if (!exceptions.isEmpty) {
							println("General exceptions...")
							exceptions.foreach(_.printStackTrace)
						}
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
				lock.wait(1000)
			}
		}
	}
}
class PeerTests {
	import PeerTests._

	val stats = PeerTests.stats
	var owner: TestPeer = null
	var member1: TestPeer = null

	@Before def setup {
		owner = newOwner
		member1 = newMember("member 1")
	}

	@Test def testPeerIds {
		assertEquals(member1.peerId, member1.selfConnection.peerId)
		assertEquals(member1.peerId, digestInt(member1.publicKey.getEncoded))
		assertEquals(owner.peerId, owner.selfConnection.peerId)
		assertEquals(owner.peerId, digestInt(owner.publicKey.getEncoded))
	}
	@Test def testMessaging {
		for (i <- 1 to 100) {
			stats.logger ! 2
			member1.topic.broadcast("hello there")
		}
		stats.logger ! 2
		member1.topic.unicast("uni")
		member1.topic.dht(5000, "dht")
		var oldCount = -1
		var done = false

		while (!done) {
			stats.eventSignal synchronized {
				if (stats.outstandingEvents == 0) {
					done = true
				} else {
					stats.eventSignal.wait(1000)
					if (oldCount == stats.totalEvents) {
						done = true
//						println("outstanding events: "+stats.outstandingEvents+", totalEvents: "+stats.totalEvents)
					} else {
						oldCount = stats.totalEvents
					}
				}
			}
		}
		assertEquals(0, stats.outstandingEvents)
	}


	def newConnection(connection: SocketChannel, peer: Peer, acceptor: Option[Acceptor]) = new CheckingConnection(connection, peer, acceptor)

	def newOwner = {
		val o = new TestPeer("Owner")

		o.own(0, 0)
		o.own(0, 1, new TestTopicConnection(0, 1, o.selfConnection))
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

		if (direct) {
			val con = new DirectSimpyPacketConnection(peer1, peer2)
			peer2.addConnection(con.otherEnd)
			peer1.addConnection(con)
			pcon = peer1.peerConnections(con)
			peer1.waiters((pcon, 0)) = {mainActor ! _}
			peer2.peerConnections(con.otherEnd).challenge(randomInt(1000000000).toString)
		} else {
			// this is a hack
			// maybe the acceptor should be changed to use the current owner instead of peer2
			// or else we need to shut down the acceptor in an @after method
			if (!accepting) {
				accepting = true;
				Acceptor.listen(PORT) {chan =>
					new Acceptor(chan, peer2) {
						override def newConnection(connection: SocketChannel) = {
							val con = PeerTests.this.newConnection(connection, peer2, Some(this))

							peer2.addConnection(con)
							peer2.peerConnections(con).challenge(randomInt(1000000000).toString)
							con
						}
					}
				}
			}
			pcon = peer1.connect(HOST, PORT) {mainActor ! _}
		}
		peer1.ownerCon = pcon
		var connected = false
		mainActor.receiveWithin(1000) {
			case x => connected = true // println("CONNECTED "+peer1)
		}
		assertTrue(connected)
		pcon
	}
}
class CheckingConnection(connection: SocketChannel, peer: Peer, acceptor: Option[Acceptor]) extends SimpyPacketConnection(connection, peer, acceptor) {
	var lastBytes: Sequence[Byte] = null

	override def send(newOutput: Array[Byte], offset: Int, len: Int) {
		val newBytes = newOutput.slice(offset, offset + len).toSequence
		
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
	}
	override def receive(msg: DelegatedUnicast) {
//		println(owner.peer + " received: "+msg)
		stats.logger ! (owner.peer, msg)
	}
	override def receive(msg: DelegatedDHT) {
//		println(owner.peer + " received: "+msg)
		stats.logger ! (owner.peer, msg)
	}
}
class TestPeer(name: String) extends Peer(name) {
	var ownerCon: PeerConnection = null
	val events = scala.collection.mutable.ArrayBuffer[Any]()
	var lastBytes: Sequence[Byte] = null
	var badBytes = Set[Array[Byte]]()
	var badNode = false
	var topic: TopicConnection = null
	
	genId

	override def receiveInput(con: SimpyPacketConnectionAPI, bytes: Array[Byte]) {
		val newBytes = bytes.slice(0, bytes.length).toSequence

		if (lastBytes == newBytes) {
			badBytes.add(bytes)
			println("ERROR: SAME BYTES RECEIVED")
		}
		assertFalse(newBytes == lastBytes)
		lastBytes = newBytes
		super.receiveInput(con, bytes)
	}
	override def handleInput(con: SimpyPacketConnectionAPI, str: OpenByteArrayInputStream) = {
		if (badBytes(str.bytes)) {
			badBytes.remove(str.bytes)
			badNode = true
		}
		super.handleInput(con, str)
	}
	override def dispatch(con: PeerConnection, node: Node) = {
		if (badNode) {
			badNode = false
			println("Duplicate input: " + node)
		}
		super.dispatch(con, node)
	}
}