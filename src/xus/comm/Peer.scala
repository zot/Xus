/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.xml.Elem
import scala.xml.Node
import scala.xml.parsing.NoBindingFactoryAdapter
import scala.tools.nsc.io.Directory
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.PublicKey
import com.sun.xml.internal.fastinfoset.sax.SAXDocumentParser
import scala.collection.mutable.Map
import scala.actors.Actor._

import Util._

class Peer extends PeerTrait with SimpyPacketPeerProtocol {
	var keyPair: KeyPair = null
	var peerId: BigInt = BigInt(0)
	var prefsDirectory: Directory = null
	val peerConnections = Map[SimpyPacketConnectionProtocol, PeerConnection]()
	val topicsOwned = Map[(Int,Int), Topic]()
	val topicOwners = Map[Topic, PeerConnectionProtocol]()
	val topicsJoined = Map[(Int,Int), Topic]()
	val selfConnection = peerConnections(addConnection(new DirectSimpyPacketConnection(this)))
	val inputActor = actor {
		loop {
			react {
			case (con: SimpyPacketConnectionProtocol, str: ByteArrayInputStream) =>
				val parser = new SAXDocumentParser
				val fac = new NoBindingFactoryAdapter
		
				parser.setContentHandler(fac)
				try {
					parser.parse(str)
				} catch {
				case x: Exception => x.printStackTrace
				}
				dispatch(peerConnections(con), fac.rootElem)
			}
		}
	}

	def publicKey = keyPair.getPublic
	def publicKeyString = stringFor(publicKey.getEncoded)
	def genId {
		keyPair = genKeyPair
		peerId = digestInt(publicKey.getEncoded)
		selfConnection.setKey(publicKey.getEncoded)
	}
	def addConnection(con: SimpyPacketConnectionProtocol) = {
		peerConnections(con) = new PeerConnection()(con)
		con
	}
	///////////////////////////
	// SimpyPacketPeerProtocol
	///////////////////////////
//	def receiveInput(con: SimpyPacketConnectionProtocol, bytes: Array[Byte], offset: Int, length: Int) {
//		val parser = new SAXDocumentParser
//		val fac = new NoBindingFactoryAdapter
//
//		parser.setContentHandler(fac)
//		try {
//			parser.parse(new ByteArrayInputStream(bytes, offset, length))
//		} catch {
//			case x: Exception => x.printStackTrace
//		}
//		dispatch(peerConnections(con), fac.rootElem)
//	}
	def receiveInput(con: SimpyPacketConnectionProtocol, bytes: Array[Byte], offset: Int, length: Int) {
		receiveInput(con, bytes.slice(offset, offset + length))
	}
	def receiveInput(con: SimpyPacketConnectionProtocol, bytes: Array[Byte]) {
		inputActor ! (con, new ByteArrayInputStream(bytes))
	}

	///////////////////////////
	// TopicSpacePeerProtocol
	///////////////////////////
	//
	// peer-to-peer messages
	//
	def verifySignature(msg: ChallengeResponse): Boolean = true
	override def receive(msg: Challenge) = msg.con.sendChallengeResponse(msg.token, publicKey, msg.msgId)
	override def receive(msg: ChallengeResponse) {}
	override def receive(msg: Completed) {}
	override def receive(msg: Failed) {}
	override def receive(msg: Direct) {}

	//
	// peer-to-space messages
	//
	// delegate broadcasting, etc to the topic
	override def receive(msg: Broadcast) = topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))
	override def receive(msg: Unicast) = topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))
	override def receive(msg: DHT) = topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))
	override def receive(msg: DelegateDirect) = topicsOwned.get((msg.space, msg.topic)).foreach(_.process(msg))

	//
	// space-to-peer messages
	// these are delegated from other peers
	//
	// delegate message reception handling to the topic
	override def receive(msg: DelegatedBroadcast) = topicsJoined.get(msg.space, msg.topic).foreach(_.receive(msg))
	override def receive(msg: DelegatedUnicast) = topicsJoined.get(msg.space, msg.topic).foreach(_.receive(msg))
	override def receive(msg: DelegatedDHT) = topicsJoined.get(msg.space, msg.topic).foreach(_.receive(msg))
	override def receive(msg: DelegatedDirect) = topicsJoined.get(msg.space, msg.topic).foreach(_.receive(msg))
}