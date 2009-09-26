package xus.testing

import xus.comm.Peer
import xus.comm.Topic
import xus.comm.Message
import xus.comm.DelegatedBroadcast
import xus.comm.PeerConnection
import xus.comm.DirectSimpyPacketConnection
import xus.comm.Xus
import xus.comm.Util._
import scala.actors.Actor._
import org.junit.Before
import org.junit.After
import org.junit.Test

object PeerTests {
	var eventCount = 0
	val eventSignal = new Object
	val logger = daemonActor {
		loop {
			react {
			case (peer: TestPeer, event: Any) =>
			println("event: " + event)
			peer.events += event
			eventCount -= 1
			if (eventCount == 0) {
				eventSignal synchronized {
					eventSignal.notifyAll
				}
			}
			}
		}
	}

	def main(args: Array[String]) {
		org.junit.runner.JUnitCore.runClasses(classOf[PeerTests])
	}
}
class PeerTests {
	val owner = newOwner
	val member1 = newMember

	def newOwner = {
		val o = new Peer().genId
		val testTopic = new TestTopic(0, 1, o)

		o.topicsOwned((0, 0)) = new Topic(0, 0, o)
		o.topicsOwned((0, 1)) = testTopic
		o.topicsJoined((0, 1)) = testTopic
		testTopic.addMember(o.selfConnection)
		o
	}
	
	def newMember = {
		val m = new Peer().genId
		val testTopic = new TestTopic(0, 1, m)
		val (myEnd, otherEnd) = connection(m, owner)

		m.topicsJoined((0, 1)) = testTopic
		owner.topicsOwned((0, 1)).addMember(otherEnd)
		m
	}
	
	def connection(peer1: Peer, peer2: Peer) = {
		val con = new DirectSimpyPacketConnection(peer1, peer2)

		peer1.addConnection(con)
		peer2.addConnection(con.otherEnd)
		(peer1.peerConnections(con), peer2.peerConnections(con.otherEnd))
	}

	@Before def hello = println("hello")
	@Test def test {
		println("test")
		Xus.shutdown
	}
	@After def goodbye = println("goodbye")
}
class TestPeer extends Peer {
	import PeerTests._

	val events = scala.collection.mutable.ArrayBuffer[Any]()

	override def basicReceive(msg: Message) {
		msg match {
		case _: DelegatedBroadcast => logger ! (this, msg)
		}
	}
}
class TestTopic(space: Int, topic: Int, peer: Peer) extends Topic(space, topic, peer) {
	
}