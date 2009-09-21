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
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.util.HashMap
import java.security._
import com.sun.xml.internal.fastinfoset.sax.AttributesHolder;
import com.sun.xml.internal.fastinfoset.QualifiedName;
import com.sun.xml.internal.fastinfoset.sax.SAXDocumentSerializer
import com.sun.xml.internal.org.jvnet.fastinfoset.EncodingAlgorithmIndexes
import com.sun.xml.internal.fastinfoset.algorithm.BASE64EncodingAlgorithm

class NodeBlock(val block: (SAXDocumentSerializer) => Unit) extends Elem("", "", null, TopScope) {}
object Util {
	def msgIdFor(id: Int)(implicit con: SimpyPacketConnectionProtocol) = (if (id == -1) con.nextOutgoingMsgId else id).toString
	def str(i: Int) = i.toString
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
	def getKeyPair = KeyPairGenerator.getInstance("RSA").genKeyPair
	implicit def happyCloseable[T <: Closeable](stream: T) = new {
		def closeAfter(block: (T) => Any) {
			try {
				block(stream)
			} finally {
				stream.close
			}
		}
	}
	def sign(key: PrivateKey, bytes: Array[Byte]) = {
		val sig = Signature.getInstance("SHA1withRSA")

		sig.initSign(key)
		sig.update(bytes)
		sig.sign
	}
	def stringFor(bytes: Array[Byte]) = {
		val out = new StringBuffer

		(new BASE64EncodingAlgorithm).convertToCharacters(bytes, 0, bytes.length, out)
		out.toString
	}
	def bytesFor(str: String) = {
		(new BASE64EncodingAlgorithm).convertFromCharacters(str.toCharArray, 0, str.length)
	}
}