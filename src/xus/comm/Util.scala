/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.xml.Elem
import scala.xml.TopScope
import scala.xml.Node
import scala.xml.Text
import scala.xml.Atom
import scala.xml.Document
import scala.xml.ProcInstr
import scala.xml.Utility
import scala.xml.MetaData
import scala.xml.EntityRef
import scala.xml.Comment
import scala.actors.Exit
import scala.actors.Actor
import scala.actors.DaemonActor
import scala.actors.Actor._
import scala.collection.immutable.Stream
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.util.HashMap
import java.security._
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import com.sun.xml.internal.fastinfoset.sax.AttributesHolder;
import com.sun.xml.internal.fastinfoset.QualifiedName;
import com.sun.xml.internal.fastinfoset.sax.SAXDocumentSerializer
import com.sun.xml.internal.org.jvnet.fastinfoset.EncodingAlgorithmIndexes
import com.sun.xml.internal.fastinfoset.algorithm.BASE64EncodingAlgorithm

object Xus {
	def shutdown {
		Util.actorExceptions ! 0
		Connection.shutdown
	}
}
class NodeBlock(val block: (SAXDocumentSerializer) => Unit) extends Elem("", "", null, TopScope) {}
object Util {
	val rand = new java.util.Random
	val digest = MessageDigest.getInstance("SHA1")
	val actorExceptions = actor {
		self.trapExit = true
		loop {
			react {
			case Exit(from: Actor, ex: Exception) => ex.printStackTrace
			case 0 => exit
			}
		}
	}

	def nextInt(range: Int) = rand.nextInt(range)
	def msgIdFor(id: Int)(implicit con: SimpyPacketConnectionAPI) = (if (id == -1) con.nextOutgoingMsgId else id).toString
	def str(o: Any) = o.toString
	def str(o: BigInt) = stringFor(o.toByteArray)
	def attrString(node: Node, attr: String) = {
		val v = node.attributes(attr)

		if (v == null) {
			null
		} else {
			val sb = new StringBuilder()

			Utility.sequenceToXML(node.attributes(attr), TopScope, sb, true)
			sb.toString
		}
	}
	def attributes(d: MetaData) = {
		val attrs = new AttributesHolder

		for (attr <- d) {
			val sb = new StringBuilder()
		    Utility.sequenceToXML(attr.value, TopScope, sb, true)
		    findAlgorithm(sb.toString) match {
				case (_, -1, v: String) => attrs.addAttribute(new QualifiedName("", "", attr.key.toLowerCase), v)
				case (null, id: Int, data: Any) => attrs.addAttributeWithAlgorithmData(new QualifiedName("", "", attr.key.toLowerCase), null, id, data)
				case (uri: String, id: Int, data: Any) => attrs.addAttributeWithAlgorithmData(new QualifiedName("", "", attr.key.toLowerCase), uri, id, data)
			}
		}
		attrs
	}
	def findAlgorithm(data: String): (String, Int, Any) = {
		val INT_PAT = "^([0-9]+)$".r

		data match {
		case INT_PAT(i) => (null, EncodingAlgorithmIndexes.INT, Array(Integer.parseInt(i)));
		case _ => (null, -1, data)
		}
	}
	def serialize(node: Node, serializer: SAXDocumentSerializer) {
		node match {
		case _ : ProcInstr | _ : Comment | _ : EntityRef =>
		case x : Atom[_] =>
			val chars = x.text.toCharArray
			serializer.characters(chars, 0, chars.length)
		case nb: NodeBlock => nb.block(serializer)
		case _ : Elem =>
			serializer.startElement("", node.label.toLowerCase, node.label.toLowerCase, attributes(node.attributes))
			for (m <- node.child) serialize(m, serializer)
			serializer.endElement("", node.label.toLowerCase, node.label.toLowerCase)
		}
	}
	def serialize(node: Node, bytes: ByteArrayOutputStream) {
		val serializer = new SAXDocumentSerializer()

		serializer.setOutputStream(bytes)
		serializer.startDocument();
		serialize(node, serializer)
		serializer.endDocument();
	}
	implicit def happyCloseable[T <: Closeable](stream: T) = new {
		def closeAfter(block: (T) => Any) {
			try {
				block(stream)
			} finally {
				stream.close
			}
		}
	}
	def genKeyPair = KeyPairGenerator.getInstance("RSA").genKeyPair
	def sign(key: PrivateKey, bytes: Array[Byte]) = {
		val sig = Signature.getInstance("SHA1withRSA")

		sig.initSign(key)
		sig.update(bytes)
		sig.sign
	}
	def publicKeyFor(bytes: Array[Byte]) = new sun.security.rsa.RSAPublicKeyImpl(bytes)
	def privateKeyFor(bytes: Array[Byte]) = sun.security.rsa.RSAPrivateCrtKeyImpl.newKey(bytes)
	def publicKeySpecFor(fileBytes: Array[Byte]) = new X509EncodedKeySpec(fileBytes)
	def privateKeySpecFor(fileBytes: Array[Byte]) = new PKCS8EncodedKeySpec(fileBytes)
	def keySpecFor(key: PublicKey) = new X509EncodedKeySpec(key.getEncoded)
	def keySpecFor(key: PrivateKey) = new PKCS8EncodedKeySpec(key.getEncoded)
	def stringFor(bytes: Array[Byte]) = {
		val out = new StringBuffer

		(new BASE64EncodingAlgorithm).convertToCharacters(bytes, 0, bytes.length, out)
		out.toString
	}
	def bytesFor(str: String) = (new BASE64EncodingAlgorithm).convertFromCharacters(str.toCharArray, 0, str.length).asInstanceOf[Array[Byte]]
	def hexForBytes(bytes: Array[Byte]) = bytes.map("%02X".format(_)).mkString
	def bytesForHex(str: String) = (for (i <- 0 until str.length by 2) yield java.lang.Byte.parseByte(str.slice(i, i + 2), 16)).toArray[Byte]
	def digestInt(bytes: Array[Byte]) = {
		val i = BigInt(digest.digest(bytes))
		digest.reset
		i
	}
	def daemonActor(body: => Unit): Actor = {
		val a = new DaemonActor {
			def act() = body
//			override final val scheduler: IScheduler = Actor.parentScheduler
		}
		a.start()
		a
	}
}