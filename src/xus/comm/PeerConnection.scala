/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.xml.Node
import scala.xml.MetaData
import scala.xml.TopScope
import scala.xml.Elem
import java.io.ByteArrayOutputStream
import java.security.PublicKey
import com.sun.xml.internal.fastinfoset.sax.SAXDocumentSerializer

/**
 * A connection to another peer (wraps a SimpyPacketConnection)
 */
class PeerConnection(implicit val con: SimpyPacketConnectionAPI) {
	import Util._

	var peerId = BigInt(0)
	var peerKey: PublicKey = null
	var authenticated = false

	def setKey(bytes: Array[Byte]) {
		peerKey = publicKeyFor(bytes)
		peerId = digestInt(bytes)
	}
	//
	// peer-to-peer messages
	//
	// request: challenge, response: challengeResponse
	def sendChallenge(token: String, msgId: Int = -1) = send(new Challenge, <challenge token={token} msgId={msgIdFor(msgId)}/>)

	def sendChallengeResponse(token: String, key: PublicKey, requestId: Int, msgId: Int = -1) =
		send(new ChallengeResponse, sign(<challenge-response peerid={publicKeyString} token={token} requestId={str(requestId)} msgId={msgIdFor(msgId)}/>))

	// request: direct, response: completed or failed
	// empty direct message functions as a ping
	def sendDirect(space: Int, topic: Int, message: Any, msgId: Int = -1) =
		send(new Direct, <direct space={str(space)} topic={str(topic)} msgId={msgIdFor(msgId)}>{message}</direct>)

	def sendCompleted(requestId: Int, message: Any, msgId: Int = -1) = send(new Completed, <completed requestId={str(requestId)} msgId={msgIdFor(msgId)}>{message}</completed>)

	def sendFailed(requestId: Int, message: Any, msgId: Int = -1) = send(new Failed, <failed requestId={str(requestId)} msgId={msgIdFor(msgId)}>{message}</failed>)

	//
	// peer-to-space messages
	//
	def sendBroadcast(space: Int, topic: Int,  message: Any, msgId: Int = -1) =
		send(new Broadcast, <broadcast space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</broadcast>)

	def sendUnicast(space: Int, topic: Int,  message: Any, msgId: Int = -1) =
		send(new Unicast, <unicast space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</unicast>)

	def sendDHT(space: Int, topic: Int, key: BigInt, message: Any, msgId: Int = -1) =
		send(new DHT, <dht space={str(space)} topic={str(topic)} key={str(key)} msgid={msgIdFor(msgId)}>{message}</dht>)

	def sendDelegate(peer: Int, space: Int, topic: Int,  message: Any, msgId: Int = -1) =
		send(new DelegateDirect, <delegate space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegate>)
	
	//
	// space-to-peer messages
	// these are delegated from other peers
	//
	def sendBroadcast(sender: BigInt, space: Int, topic: Int,  message: Any, msgId: Int) =
		send(new DelegatedBroadcast, <delegated-broadcast sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegated-broadcast>)

	def sendUnicast(sender: BigInt, space: Int, topic: Int,  message: Any, msgId: Int) =
		send(new DelegatedUnicast, <delegated-unicast sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegated-unicast>)

	def sendDHT(sender: BigInt, space: Int, topic: Int, key: BigInt, message: Any, msgId: Int) =
		send(new DelegatedDHT, <delegated-dht sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegated-dht>)

	def sendDelegatedDirect(sender: BigInt, space: Int, topic: Int,  message: Any, msgId: Int) =
		send(new DelegatedDirect, <delegated-direct sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegated-direct>)

	//
	// implementation
	//
	import Util._

	val bytes = new ByteArrayOutputStream {
		def byteArray = buf
	}

	// low level protocol
	def send[M <: Message](msg: M, node: Node): M = {
		println("sending to " + str(peerId) + ": " + node)
		serialize(node, bytes)
		con.send(bytes.byteArray, 0, bytes.size)
		bytes.reset
		msg.set(null, node)
	}
	def sign(node: Node) = <signature sig="{stringFor(sign(keyPair.getPrivate, node.toString.toByteArray))}">{node}</signature>
	def publicKeyString = ""
}