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
import java.net.InetSocketAddress

/**
 * A connection to another peer (wraps a SimpyPacketConnection)
 */
class PeerConnection(var con: SimpyPacketConnectionAPI, val peer: Peer) {
	import Util._
	import Peer._

	var peerId = BigInt(0)
	var peerKey: PublicKey = null
	var authenticationToken = ""
	var authenticated = false
	val ownedSpaces = MList[Int]()

	override def toString = "PeerConnection ("+str(peerId)+" "+con+")"

	def address = con.address

	def isConnected = con != null

	def close = if (isConnected) con.close

	def publicKeyString = str(peerKey)

	def setKey(bytes: Array[Byte]) = {
		val newPeerId = digestInt(bytes)

		if (peerId != BigInt(0) && peerId != newPeerId) {
			false
		} else {
			peerKey = publicKeyFor(bytes)
			peerId = newPeerId
			true
		}
	}
	//
	// peer-to-peer messages
	//
	// request: challenge, response: challengeResponse
	def challenge(token: String, msgId: Int = -1)(implicit block: (Response) => Any) = {
		authenticationToken = token
		send(new Challenge, <challenge token={token} msgid={msgIdFor(msgId)} requestid={str(-1)}/>)(block)
	}

	def challengeResponse(token: String, challengeToken: String, requestId: Int, msgId: Int = -1)(implicit block: (Response) => Any) =
		send(new ChallengeResponse, sign(<challenge-response token={token} challengetoken={challengeToken} requestid={str(requestId)} msgid={msgIdFor(msgId)}/>))(block)

	// request: direct, response: completed or failed
	// empty direct message functions as a ping
	def direct(payload: Any, msgId: Int = -1)(implicit block: (Response) => Any) =
		send(new Direct, <direct msgid={msgIdFor(msgId)}>{payload}</direct>)(block)

	def completed(msg: Message, payload: Any): Completed = completed(msg.msgId, payload)
	def completed(requestId: Int, payload: Any, msgId: Int = -1): Completed = send(new Completed, <completed requestid={str(requestId)} msgid={msgIdFor(msgId)}>{payload}</completed>)

	def failed(msg: Message, payload: Any): Failed = failed(msg.msgId, payload)
	def failed(requestId: Int, payload: Any, msgId: Int = -1): Failed = send(new Failed, <failed requestid={str(requestId)} msgid={msgIdFor(msgId)}>{payload}</failed>)

	//
	// peer-to-space messages
	//
	def broadcast(space: Int, topic: Int,  payload: Any, msgId: Int = -1)(implicit block: (Response) => Any) =
		send(new Broadcast, <broadcast space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{payload}</broadcast>)(block)

	def unicast(space: Int, topic: Int,  payload: Any, msgId: Int = -1)(implicit block: (Response) => Any) =
		send(new Unicast, <unicast space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{payload}</unicast>)(block)

	def dht(space: Int, topic: Int, key: BigInt, payload: Any, msgId: Int = -1)(implicit block: (Response) => Any) =
		send(new DHT, <dht space={str(space)} topic={str(topic)} key={str(key)} msgid={msgIdFor(msgId)}>{payload}</dht>)(block)

	def delegate(peer: Int, space: Int, topic: Int,  payload: Any, msgId: Int = -1)(implicit block: (Response) => Any) =
		send(new DelegateDirect, <delegate space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{payload}</delegate>)(block)

	//
	// space-to-peer messages
	// these are delegated from other peers
	//
	def delegatedBroadcast(sender: BigInt, space: Int, topic: Int,  payload: Any, msgId: Int = -1) =
		send(new DelegatedBroadcast, <delegated-broadcast sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{payload}</delegated-broadcast>)

	def delegatedUnicast(sender: BigInt, space: Int, topic: Int,  payload: Any, msgId: Int = -1)(implicit block: (Response) => Any) =
		send(new DelegatedUnicast, <delegated-unicast sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{payload}</delegated-unicast>)(block)

	def delegatedDht(sender: BigInt, space: Int, topic: Int, key: BigInt, payload: Any, msgId: Int = -1)(implicit block: (Response) => Any) =
		send(new DelegatedDHT, <delegated-dht sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{payload}</delegated-dht>)(block)

	def delegatedDirect(sender: BigInt, space: Int, topic: Int,  payload: Any, msgId: Int = -1)(implicit block: (Response) => Any) =
		send(new DelegatedDirect, <delegated-direct sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{payload}</delegated-direct>)(block)

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
	def sign(node: Node) = <signature sig={str(Util.sign(node, peer.privateKey))} publickey={str(peer.publicKey)}>{node}</signature>
}
