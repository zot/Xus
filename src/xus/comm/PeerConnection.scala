/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.collection.mutable.{ArrayBuffer => MList}
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
class PeerConnection(var con: SimpyPacketConnectionAPI, val peer: Peer) {
	import Util._

	var peerId = BigInt(0)
	var peerKey: PublicKey = null
	var authenticationToken = ""
	var authenticated = false
	val ownedSpaces = MList[Int]()
	implicit var emptyHandler = (m: Response) => ()
	implicit var emptyHandler2: (Response) => Any = m => ()

	override def toString = "PeerConnection ("+con+")"
	def close = con.close
	def publicKeyString = str(peerKey)
	def setKey(bytes: Array[Byte]) {
		peerKey = publicKeyFor(bytes)
		peerId = digestInt(bytes)
	}
	//
	// peer-to-peer messages
	//
	// request: challenge, response: challengeResponse
	def challenge(token: String, msgId: Int = -1)(implicit block: (Response) => Unit) = {
		authenticationToken = token
		send(new Challenge, <challenge token={token} msgid={msgIdFor(msgId)} requestId={str(-1)}/>)(block)
	}

	def challengeResponse(token: String, challengeToken: String, requestId: Int, msgId: Int = -1)(implicit block: (Response) => Unit) =
		send(new ChallengeResponse, sign(<challenge-response token={token} challengetoken={challengeToken} requestid={str(requestId)} msgid={msgIdFor(msgId)}/>))(block)

	// request: direct, response: completed or failed
	// empty direct message functions as a ping
	def direct(message: Any, msgId: Int = -1)(implicit block: (Response) => Any) =
		send(new Direct, <direct msgid={msgIdFor(msgId)}>{message}</direct>)(block)

	def completed(msg: Message, message: Any): Completed = completed(msg.msgId, message)
	def completed(requestId: Int, message: Any, msgId: Int = -1): Completed = send(new Completed, <completed requestid={str(requestId)} msgid={msgIdFor(msgId)}>{message}</completed>)

	def failed(msg: Message, message: Any): Failed = failed(msg.msgId, message)
	def failed(requestId: Int, message: Any, msgId: Int = -1): Failed = send(new Failed, <failed requestid={str(requestId)} msgid={msgIdFor(msgId)}>{message}</failed>)

	//
	// peer-to-space messages
	//
	def broadcast(space: Int, topic: Int,  message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) =
		send(new Broadcast, <broadcast space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</broadcast>)(block)

	def unicast(space: Int, topic: Int,  message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) =
		send(new Unicast, <unicast space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</unicast>)(block)

	def dht(space: Int, topic: Int, key: BigInt, message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) =
		send(new DHT, <dht space={str(space)} topic={str(topic)} key={str(key)} msgid={msgIdFor(msgId)}>{message}</dht>)(block)

	def delegate(peer: Int, space: Int, topic: Int,  message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) =
		send(new DelegateDirect, <delegate space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegate>)(block)

	//
	// space-to-peer messages
	// these are delegated from other peers
	//
	def broadcast(sender: BigInt, space: Int, topic: Int,  message: Any, msgId: Int) =
		send(new DelegatedBroadcast, <delegated-broadcast sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegated-broadcast>)

	def unicast(sender: BigInt, space: Int, topic: Int,  message: Any, msgId: Int) =
		send(new DelegatedUnicast, <delegated-unicast sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegated-unicast>)

	def dht(sender: BigInt, space: Int, topic: Int, key: BigInt, message: Any, msgId: Int) =
		send(new DelegatedDHT, <delegated-dht sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegated-dht>)

	def delegatedDirect(sender: BigInt, space: Int, topic: Int,  message: Any, msgId: Int) =
		send(new DelegatedDirect, <delegated-direct sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegated-direct>)

	//
	// implementation
	//
	import Util._

	def msgIdFor(id: Int) = (if (id == -1) con.nextOutgoingMsgId else id).toString
	// low level protocol
	def send[M <: Message](msg: M, node: Node)(implicit block: (Response) => Any): M = {
		msg.set(this, node)
		if (block != emptyHandler) {
			peer.onResponseDo(msg)(block)
		}
		peer.send(con, msg, node)
	}
//	def sign(node: Node) = <signature sig={str(sign(keyPair.getPrivate, node.toString.toByteArray))}>{node}</signature>
	def sign(node: Node) = <signature sig={str(Util.sign(node, peer.privateKey))} publickey={str(peer.publicKey)}>{node}</signature>
}
