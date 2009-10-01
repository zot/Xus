package xus.testing

import xus.comm._
import xus.comm.Util._
import scala.xml.Node
import scala.actors.Actor
import scala.actors.Actor._
import scala.collection.Sequence
import scala.collection.JavaConversions._
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
		loop {
			react {
			case i: Int => outstandingEvents += i
			case (peer: TestPeer, msg: Message) =>
//				println("event, " + peer + ": " + msg.getClass.getSimpleName + ", msgId = " + msg.msgId)
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

	def main(args: Array[String]) {
		try {
			println("Staring tests...")
			direct = false
			org.junit.runner.JUnitCore.runClasses(classOf[PeerTests]).getFailures.foreach(f => Console.err.println(f.getTrace))
			stats = new LoggerStats
			direct = true
			org.junit.runner.JUnitCore.runClasses(classOf[PeerTests]).getFailures.foreach(f => Console.err.println(f.getTrace))
			println("Done with tests.")
		} catch {
		case e: Error => e.printStackTrace
		}
	}
}
class PeerTests {
	import PeerTests._

	val stats = PeerTests.stats
	val owner = newOwner
	val member1 = newMember("member 1")

	@After def goodbye {
		Xus.shutdown
	}

	@Test def test1 {
		for (i <- 1 to 100) {
			stats.logger ! 2
			member1.ownerCon.sendBroadcast(0, 1, "hello there")
		}
		var oldCount = -1
		var done = false

		while (!done) {
			stats.eventSignal synchronized {
				if (stats.outstandingEvents == 0) {
					done = true
				} else {
					stats.eventSignal.wait(5000)
					if (oldCount == stats.totalEvents) {
						done = true
						println("outstanding events: "+stats.outstandingEvents+", totalEvents: "+stats.totalEvents)
					} else {
						oldCount = stats.totalEvents
					}
				}
			}
		}
		assertEquals(0, stats.outstandingEvents)
	}

	def newPeer(name: String) = new TestPeer(name)
	
	def newConnection(connection: SocketChannel, peer: Peer, acceptor: Option[Acceptor]) = new CheckingConnection(connection, peer, acceptor)

	def newOwner = {
		val o = newPeer("Owner")
		val testTopic = new TestTopic(0, 1, o)

		o.topicsOwned((0, 0)) = new Topic(0, 0, o)
		o.topicsOwned((0, 1)) = testTopic
		o.topicsJoined((0, 1)) = testTopic
		testTopic.addMember(o.selfConnection)
		o
	}
	
	def newMember(name: String) = {
		val m = newPeer(name)
		val testTopic = new TestTopic(0, 1, m)
		val myEnd = connect(m, owner)

		m.ownerCon = myEnd
		myEnd.sendDirect(<join space="0" topic="1"/>)
		m.topicsJoined((0, 1)) = testTopic
		m
	}

	def connect(peer1: Peer, peer2: Peer) = {
		val con = if (direct) {
			val con = new DirectSimpyPacketConnection(peer1, peer2)

			peer2.addConnection(con.otherEnd)
			con
		} else {
			if (!accepting) {
				Acceptor.listen(PORT) {chan =>
					new Acceptor(chan, peer2) {
						override def newConnection(connection: SocketChannel) = {
							val con = PeerTests.this.newConnection(connection, peer2, Some(this))

							peer2.addConnection(con)
							con
						}
					}
				}
			}
			val chan = SocketChannel.open
			chan.configureBlocking(false)
			val con = Connection.addConnection(newConnection(chan, peer1, None))
			chan.connect(new InetSocketAddress(HOST, PORT))
			con
		}
		peer1.addConnection(con)
		peer1.peerConnections(con)
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
class TestTopic(space: Int, topic: Int, peer: Peer) extends Topic(space, topic, peer) {
	import PeerTests._
	
	val stats = PeerTests.stats
	
	override def receive(msg: DelegatedBroadcast) {
		stats.logger ! (peer, msg)
	}
}
class TestPeer(name: String) extends Peer {
	var ownerCon: PeerConnection = null
	val events = scala.collection.mutable.ArrayBuffer[Any]()
	var lastBytes: Sequence[Byte] = null
	
	genId
	
	override def toString = "Peer: " + name
	override def receive(msg: Direct) {
		msg.payload match {
		case Seq(n @ <join/>) =>
		for {
			space <- strOpt(n.attribute("space"))
			topic <- strOpt(n.attribute("topic"))
		} {
			topicsOwned((Integer.parseInt(space), Integer.parseInt(topic))).addMember(msg.con)
		}
//			println(this + " received: " + msg.getClass.getSimpleName + ", msgId = " + msg.msgId)
		}
	}
	override def receiveInput(con: SimpyPacketConnectionAPI, bytes: Array[Byte]) {
		val newBytes = bytes.slice(0, bytes.length).toSequence
		
		if (lastBytes == newBytes) {
			println("ERROR: SAME BYTES RECEIVED")
		}
		assertFalse(newBytes == lastBytes)
		lastBytes = newBytes
		super.receiveInput(con, bytes)
	}
//	override def send[M <: Message](con: SimpyPacketConnectionAPI, msg: M, node: Node): M = {
//		val ret = super.send(con, msg, node)
	
//		println("SENDING: " + msg.getClass.getSimpleName + ", msgId = " + msg.msgId)
//		ret
//	}
//	override def basicReceive(msg: Message) {
//		println(this + " received: " + msg.getClass.getSimpleName + ", msgId = " + msg.msgId)
//	}
}