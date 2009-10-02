package xus.testing

import xus.comm._
import xus.comm.Util._
import scala.xml.Node
import scala.actors.Actor
import scala.actors.Actor._
import scala.actors.DaemonActor
import scala.collection.Sequence
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
		loop {
			react {
			case i: Int => outstandingEvents += i
			case (peer: TestPeer, msg: Message) =>
				println("event, " + peer + ": " + msg.getClass.getSimpleName + ", msgId = " + msg.msgId)
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

	def main(args: Array[String]) {
		lock synchronized {
			daemonActor("Tests") {
				mainActor = self
				try {
					println("Staring tests...")
					direct = true
					org.junit.runner.JUnitCore.runClasses(classOf[PeerTests]).getFailures.foreach(f => Console.err.println(f.getTrace))
					stats = new LoggerStats
					direct = false
					org.junit.runner.JUnitCore.runClasses(classOf[PeerTests]).getFailures.foreach(f => Console.err.println(f.getTrace))
					println("Done with tests.")
					Xus.shutdown
					lock synchronized {
						done = true
						lock.notifyAll
					}
				} catch {
				case e: Error => e.printStackTrace
				}
			}
			while (!done) {
				lock.wait()
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
		println("setup")
	}

	@Test def test1 {
		for (i <- 1 to 100) {
			stats.logger ! 2
			member1.ownerCon.sendBroadcast(0, 1, "hello there")
		}
		stats.logger ! 2
		member1.ownerCon.sendUnicast(0, 1, "uni")
		member1.ownerCon.sendDHT(0, 1, 5000, "dht")
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


	def newConnection(connection: SocketChannel, peer: Peer, acceptor: Option[Acceptor]) = new CheckingConnection(connection, peer, acceptor)

	def newOwner = {
		val o = new TestPeer("Owner")
		val testTopic = new TestTopic(0, 1, o)

		o.topicsOwned((0, 0)) = new Topic(0, 0, o)
		o.topicsOwned((0, 1)) = testTopic
		o.topicsJoined((0, 1)) = testTopic
		testTopic.addMember(o.selfConnection)
		o
	}
	
	def newMember(name: String) = {
		val m = new TestPeer(name, mainActor ! ())
		val testTopic = new TestTopic(0, 1, m)
		val myEnd = connect(m, owner)

		myEnd.sendDirect(<join space="0" topic="1"/>)
		m.topicsJoined((0, 1)) = testTopic
		m
	}

	def connect(peer1: TestPeer, peer2: TestPeer) = {
		var pcon: PeerConnection = null

		if (direct) {
			val con = new DirectSimpyPacketConnection(peer1, peer2)
			peer2.addConnection(con.otherEnd)
			peer1.addConnection(con)
			peer2.peerConnections(con.otherEnd).sendChallenge(randomInt(1000000000).toString)
			pcon = peer1.peerConnections(con)
		} else {
			if (!accepting) {
				accepting = true;
				Acceptor.listen(PORT) {chan =>
					new Acceptor(chan, peer2) {
						override def newConnection(connection: SocketChannel) = {
							val con = PeerTests.this.newConnection(connection, peer2, Some(this))

							peer2.addConnection(con)
							peer2.peerConnections(con).sendChallenge(randomInt(1000000000).toString)
							con
						}
					}
				}
			}
			val chan = SocketChannel.open
			chan.configureBlocking(false)
			val con = Connection.addConnection(newConnection(chan, peer1, None))
			chan.connect(new InetSocketAddress(HOST, PORT))
			peer1.addConnection(con)
			pcon = peer1.peerConnections(con)
		}
		peer1.ownerCon = pcon
		mainActor.receive {
			case () => println("CONNECTED")
		}
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
class TestTopic(space: Int, topic: Int, peer: Peer) extends Topic(space, topic, peer) {
	import PeerTests._
	
	val stats = PeerTests.stats
	
	override def receive(msg: DelegatedBroadcast) {
		stats.logger ! (peer, msg)
	}
	override def receive(msg: DelegatedUnicast) {
		stats.logger ! (peer, msg)
	}
	override def receive(msg: DelegatedDHT) {
		stats.logger ! (peer, msg)
	}
}
class TestPeer(name: String, connectBlock: => Unit = null) extends Peer(name, connectBlock) {
	var ownerCon: PeerConnection = null
	val events = scala.collection.mutable.ArrayBuffer[Any]()
	var lastBytes: Sequence[Byte] = null
	var badBytes = Set[Array[Byte]]()
	var badNode = false
	
	genId
	println("New peer: "+this)

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