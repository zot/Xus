package xus.comm

import scala.xml.Elem
import scala.xml.Node
import scala.xml.parsing.NoBindingFactoryAdapter
import scala.io.Directory
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.PublicKey
import com.sun.xml.internal.fastinfoset.sax.SAXDocumentParser
import scala.collection.mutable.Map

import Util._

class Peer extends TopicSpacePeerProtocol with SimpyPacketPeerProtocol {
	var keyPair: KeyPair = null
	var prefsDirectory: Directory = null
	val topicCons = Map[SimpyPacketConnectionProtocol, TopicSpaceConnectionProtocol]()

	def publicKey = keyPair.getPublic

	def addConnection(con: SimpyPacketConnectionProtocol) = {
		topicCons(con) = new TopicSpaceConnection(con)
		con
	}
	///////////////////////////
	// SimpyPacketPeerProtocol
	///////////////////////////
	def receiveInput(con: SimpyPacketConnectionProtocol, bytes: Array[Byte], offset: Int, length: Int) {
		val parser = new SAXDocumentParser
		val fac = new NoBindingFactoryAdapter

		parser.setContentHandler(fac)
		try {
			parser.parse(new ByteArrayInputStream(bytes, offset, length))
		} catch {
			case x: Exception => x.printStackTrace
		}
		basicReceive(Message(topicCons(con), fac.rootElem))
	}

	///////////////////////////
	// TopicSpacePeerProtocol
	///////////////////////////
	//
	// peer-to-peer messages
	//
	def verifySignature(msg: ChallengeResponse): Boolean = true
}