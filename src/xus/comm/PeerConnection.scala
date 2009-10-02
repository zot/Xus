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
class PeerConnection(val con: SimpyPacketConnectionAPI, peer: Peer) {
	import Util._

	var peerId = BigInt(0)
	var peerKey: PublicKey = null
	var authenticationToken = ""
	var authenticated = false
	implicit var emptyHandler = (m: Response) => ()

	def publicKeyString = str(peerKey)
	def setKey(bytes: Array[Byte]) {
		peerKey = publicKeyFor(bytes)
		peerId = digestInt(bytes)
	}
	//
	// peer-to-peer messages
	//
	// request: challenge, response: challengeResponse
	def sendChallenge(token: String, msgId: Int = -1)(implicit block: (Response) => Unit) = {
		authenticationToken = token
		send(new Challenge, <challenge token={token} msgid={msgIdFor(msgId)}/>)(block)
	}

	def sendChallengeResponse(token: String, key: PublicKey, requestId: Int, msgId: Int = -1) =
		send(new ChallengeResponse, sign(<challenge-response publickey={str(key)} token={token} requestid={str(requestId)} msgid={msgIdFor(msgId)}/>))

	// request: direct, response: completed or failed
	// empty direct message functions as a ping
	def sendDirect(message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) =
		send(new Direct, <direct msgid={msgIdFor(msgId)}>{message}</direct>)(block)

	def sendCompleted(requestId: Int, message: Any, msgId: Int = -1) = send(new Completed, <completed requestid={str(requestId)} msgid={msgIdFor(msgId)}>{message}</completed>)

	def sendFailed(requestId: Int, message: Any, msgId: Int = -1) = send(new Failed, <failed requestid={str(requestId)} msgid={msgIdFor(msgId)}>{message}</failed>)

	//
	// peer-to-space messages
	//
	def sendBroadcast(space: Int, topic: Int,  message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) =
		send(new Broadcast, <broadcast space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</broadcast>)(block)

	def sendUnicast(space: Int, topic: Int,  message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) =
		send(new Unicast, <unicast space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</unicast>)(block)

	def sendDHT(space: Int, topic: Int, key: BigInt, message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) =
		send(new DHT, <dht space={str(space)} topic={str(topic)} key={str(key)} msgid={msgIdFor(msgId)}>{message}</dht>)(block)

	def sendDelegate(peer: Int, space: Int, topic: Int,  message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) =
		send(new DelegateDirect, <delegate space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegate>)(block)

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

	def msgIdFor(id: Int) = (if (id == -1) con.nextOutgoingMsgId else id).toString
	// low level protocol
	def send[M <: Message](msg: M, node: Node)(implicit block: (Response)=>Unit): M = {
		msg.set(null, node)
		if (block != emptyHandler) {
			peer.onResponseDo(msg)(block)
		}
		peer.send(con, msg, node)
	}
//	def sign(node: Node) = <signature sig={str(sign(keyPair.getPrivate, node.toString.toByteArray))}>{node}</signature>
	def sign(node: Node) = <signature sig="TMP">{node}</signature>
}
