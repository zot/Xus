package xus.comm

import scala.xml.Node
import scala.xml.Elem
import java.security.PublicKey
import com.sun.xml.internal.fastinfoset.sax.SAXDocumentSerializer
import Util._

object Protocol {
	val CHALLENGE = "challenge"
	val CHALLENGE_RESPONSE = "challenge-response"
	val COMPLETED = "completed"
	val FAILED = "failed"
	val DIRECT = "direct"
	val BROADCAST = "broadcast"
	val UNICAST = "unicast"
	val DELEGATE_DIRECT = "direct"
	val DHT = "dht"
	val DELEGATED_BROADCAST = "delegated-broadcast"
	val DELEGATED_UNICAST = "delegated-unicast"
	val DELEGATED_DIRECT = "delegated-direct"
	val DELEGATED_DHT = "delegated-dht"
}

/**
 * SimpyPacketConnection is a layer 1 connection to a peer
 */
trait SimpyPacketConnectionProtocol {
	//outgoing message id starts at 0 and increments with each call
	def nextOutgoingMsgId: Int
	def send(bytes: Array[Byte], offset: Int, len: Int): Unit
	def send(bytes: Array[Byte]) {send(bytes, 0, bytes.length)}
}
/**
 * TopicConnection is a layer 2 connection to a peer
 */
trait TopicSpaceConnectionProtocol {
	//
	// peer-to-peer messages
	//
	// request: challenge, response: challengeResponse
	def sendChallenge(token: String, msgId: Int = -1): Unit
	def sendChallengeResponse(token: String, key: PublicKey, msgId: Int = -1): Unit
	// request: direct, response: completed or failed
	// empty direct message functions as a ping
	def sendDirect(topicSpace: Int, topic: Int, message: Any, msgId: Int = -1): Unit
	def sendCompleted(requestId: Int, msgId: Int = -1): Unit
	def sendFailed(requestId: Int, msgId: Int = -1): Unit

	//
	// peer-to-space messages
	//
	def sendBroadcast(space: Int, topic: Int,  message: Any, msgId: Int = -1): Unit
	def sendUnicast(space: Int, topic: Int,  message: Any, msgId: Int = -1): Unit
	def sendDHT(space: Int, topic: Int,  message: Any, msgId: Int = -1): Unit
	def sendDelegate(peer: Int, space: Int, topic: Int,  message: Any, msgId: Int = -1): Unit
	
	//
	// space-to-peer messages
	// these are delegated from other peers
	//
	def sendBroadcast(sender: Int, space: Int, topic: Int,  message: Any, msgId: Int): Unit
	def sendUnicast(sender: Int, space: Int, topic: Int,  message: Any, msgId: Int): Unit
	def sendDHT(sender: Int, space: Int, topic: Int,  message: Any, msgId: Int): Unit
	def sendDelegatedDirect(sender: Int, space: Int, topic: Int,  message: Any, msgId: Int): Unit
}
trait SimpyPacketPeerProtocol {
	def receiveInput(con: SimpyPacketConnectionProtocol, bytes: Array[Byte], offset: Int, length: Int): Unit
}
object Message {
	def apply(con: TopicSpaceConnectionProtocol, node: Node) = {
		def set(msg: Message) = msg.set(con, node)

		node.label.toLowerCase match {
		case "challenge" => set(new Challenge)
		case "challenge-response" => set(new ChallengeResponse)
		case "completed" => set(new Completed)
		case "failed" => set(new Failed)
		case "direct" => set(new Direct)
		case "delegate-direct" => set(new DelegateDirect)
		case "broadcast" => set(new Broadcast)
		case "unicast" => set(new Unicast)
		case "dht" => set(new DHT)
		case "delegated-direct" => set(new DelegatedDirect)
		case "delegated-broadcast" => set(new DelegatedBroadcast)
		case "delegated-unicast" => set(new DelegatedUnicast)
		case "delegated-dht" => set(new DelegatedDHT)
		}
	}
}
class Message {
	var con: TopicSpaceConnectionProtocol = null
	var node: Node = null

	def set(newCon: TopicSpaceConnectionProtocol, newNode: Node): this.type = {
		con = newCon
		node = newNode
		this
	}
	def string(att: String) = attrString(node, att)
	def stringOpt(att: String): Option[String] = {
		val id = attrString(node, att)

		if (id == null) {
			None
		} else {
			Some(id)
		}
	}
	def int(att: String) = Integer.parseInt(string(att))
	def intOpt(att: String): Option[Int] = stringOpt(att).map(Integer.parseInt(_))
	def msgId = string("msgId")
}
class Challenge extends Message {
	def token = string("token")
}
class ChallengeResponse extends Message {
	def responseNode = (node \ "challenge-response")(0)
	def token = attrString(responseNode, "token")
}
class Completed extends Message
class Failed extends Message
class TopicSpaceMessage extends Message {
	def space = int("space")
	def topic = int("topic")
	def message = node.child
}
class Direct extends TopicSpaceMessage
class DHT extends TopicSpaceMessage
class Broadcast extends TopicSpaceMessage
class Unicast extends TopicSpaceMessage
class DelegateDirect extends TopicSpaceMessage
class DelegatedMessage extends TopicSpaceMessage {
	def sender = int("sender")
}
class DelegatedDirect extends DelegatedMessage
class DelegatedDHT extends DelegatedMessage
class DelegatedBroadcast extends DelegatedMessage
class DelegatedUnicast extends DelegatedMessage

trait TopicSpacePeerProtocol {
	def publicKey: PublicKey

	//
	// peer-to-peer messages
	//
	def basicReceive(msg: Message) {
		msg match {
		case m: Challenge => receive(m)
		case m: ChallengeResponse => receive(m, verifySignature(m))
		case m: Completed => receive(m)
		case m: Failed => receive(m)
		case m: Direct => receive(m)
		case m: Broadcast => receive(m)
		case m: Unicast => receive(m)
		case m: DHT => receive(m)
		case m: DelegateDirect => receive(m)
		case m: DelegatedBroadcast => receive(m)
		case m: DelegatedUnicast => receive(m)
		case m: DelegatedDHT => receive(m)
		case m: DelegatedDirect => receive(m)
		}
	}
	def verifySignature(msg: ChallengeResponse): Boolean
	def receive(msg: Challenge) {
		msg.con.sendChallengeResponse(msg.token, publicKey)
	}
	def receive(msg: ChallengeResponse, validSig: Boolean) {}
	def receive(msg: Completed) {}
	def receive(msg: Failed) {}
	def receive(msg: Direct) {}
	//
	// peer-to-space messages
	//
	def receive(msg: Broadcast) {}
	def receive(msg: Unicast) {}
	def receive(msg: DHT) {}
	def receive(msg: DelegateDirect) {}
	
	//
	// space-to-peer messages
	// these are delegated from other peers
	//
	def receive(msg: DelegatedBroadcast) {}
	def receive(msg: DelegatedUnicast) {}
	def receive(msg: DelegatedDHT) {}
	def receive(msg: DelegatedDirect) {}
}