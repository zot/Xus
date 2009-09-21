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
 * A connection to another peer
 */
class PeerConnection(implicit con: SimpyPacketConnectionProtocol) extends PeerConnectionProtocol {
	import Util._

	var peerId = 0
	var peerKey = null
	var authenticated = false

	//
	// peer-to-peer messages
	//
	// request: challenge, response: challengeResponse
	def sendChallenge(token: String, msgId: Int = -1) = send(<challenge token={token} msgId={msgIdFor(msgId)}/>)

	def sendChallengeResponse(token: String, key: PublicKey, requestId: Int, msgId: Int = -1) =
		send(sign(<challenge-response peerid={publicKeyString} token={token} requestId={str(requestId)} msgId={msgIdFor(msgId)}/>))

	// request: direct, response: completed or failed
	// empty direct message functions as a ping
	def sendDirect(space: Int, topic: Int, message: Any, msgId: Int = -1) =
		send(<direct space={str(space)} topic={str(topic)} msgId={msgIdFor(msgId)}>{message}</direct>)

	def sendCompleted(requestId: Int, msgId: Int = -1) = send(<completed requestId={str(requestId)} msgId={msgIdFor(msgId)}/>)

	def sendFailed(requestId: Int, msgId: Int = -1) = send(<failed requestId={str(requestId)} msgId={msgIdFor(msgId)}/>)

	//
	// peer-to-space messages
	//
	def sendBroadcast(space: Int, topic: Int,  message: Any, msgId: Int = -1) =
		send(<broadcast space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</broadcast>)

	def sendUnicast(space: Int, topic: Int,  message: Any, msgId: Int = -1) =
		send(<unicast space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</unicast>)

	def sendDHT(space: Int, topic: Int,  message: Any, msgId: Int = -1) =
		send(<dht space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</dht>)

	def sendDelegate(peer: Int, space: Int, topic: Int,  message: Any, msgId: Int = -1) =
		send(<delegate space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegate>)
	
	//
	// space-to-peer messages
	// these are delegated from other peers
	//
	def sendBroadcast(sender: Int, space: Int, topic: Int,  message: Any, msgId: Int) =
		send(<delegated-broadcast sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegated-broadcast>)

	def sendUnicast(sender: Int, space: Int, topic: Int,  message: Any, msgId: Int) =
		send(<delegated-unicast sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegated-unicast>)

	def sendDHT(sender: Int, space: Int, topic: Int,  message: Any, msgId: Int) =
		send(<delegated-dht sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegated-dht>)

	def sendDelegatedDirect(sender: Int, space: Int, topic: Int,  message: Any, msgId: Int) =
		send(<delegated-direct sender={str(sender)} space={str(space)} topic={str(topic)} msgid={msgIdFor(msgId)}>{message}</delegated-direct>)

	//
	// implementation
	//
	import Util._

	val bytes = new ByteArrayOutputStream {
		def byteArray = buf
	}

	def send(node: Node) {
		serialize(node, bytes)
		con.send(bytes.byteArray, 0, bytes.size)
		bytes.reset
	}
	def sign(node: Node) = <signature sig="{stringFor(sign(keyPair.getPrivate, node.toString.toByteArray))}">{node}</signature>
	def publicKeyString = ""
}