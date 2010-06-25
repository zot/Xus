/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.xml.Elem
import scala.xml.TopScope
import scala.xml.Node
import scala.xml.Null
import scala.xml.Text
import scala.xml.Atom
import scala.xml.Document
import scala.xml.Group
import scala.xml.Utility
import scala.xml.MetaData
import scala.xml.ProcInstr
import scala.xml.EntityRef
import scala.xml.Comment
import scala.xml.UnprefixedAttribute
import scala.xml.NamespaceBinding
import scala.xml.SpecialNode
import scala.xml.parsing.NoBindingFactoryAdapter
import scala.actors.Exit
import scala.actors.Actor
import scala.actors.DaemonActor
import scala.actors.Actor._
import scala.collection.JavaConversions
import scala.collection.mutable.HashMap
import scala.collection.immutable.Stream
import java.io.File
import java.io.Closeable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security._
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.xml.namespace.QName
import com.sun.xml.internal.fastinfoset.sax.SAXDocumentParser
import com.sun.xml.internal.fastinfoset.QualifiedName
import com.sun.xml.internal.fastinfoset.sax.AttributesHolder;
import com.sun.xml.internal.fastinfoset.QualifiedName;
import com.sun.xml.internal.fastinfoset.sax.SAXDocumentSerializer
import com.sun.xml.internal.org.jvnet.fastinfoset.EncodingAlgorithmIndexes
import com.sun.xml.internal.fastinfoset.algorithm.BASE64EncodingAlgorithm
import com.sun.xml.internal.org.jvnet.fastinfoset.Vocabulary
import com.sun.xml.internal.fastinfoset.vocab.ParserVocabulary
import com.sun.xml.internal.fastinfoset.vocab.SerializerVocabulary
import org.eclipse.jgit.lib.Commit
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectWriter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.Tree
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry

object Xus {
	def shutdown {
		Util.actorExceptions ! 0
		Connection.shutdown
	}
}
class NodeBlock(val block: (SAXDocumentSerializer) => Unit) extends Elem("", "", null, TopScope) {}
object Util {
	val rand = new java.security.SecureRandom
	val digest = MessageDigest.getInstance("SHA1")
	// variable so that apps can override this behavior 
	var actorExceptionBlock = {(from: Actor, ex: Exception) =>
		Console.err.println("Eception in actor "+from+": "+ex); ex.printStackTrace
	}
	val actorExceptions = daemonActor("Exception handler") {
		self.trapExit = true
		loop {
			react {
			case Exit(from: Actor, ex: Exception) => actorExceptionBlock(from, ex)
			case 0 => exit
			}
		}
	}
	val xusVocabulary = new Vocabulary {
		JavaConversions.asSet(elements.asInstanceOf[java.util.Set[QName]]) ++ Protocol.messageMap.values.map(msg => QName.valueOf(msg.nodeName))
		JavaConversions.asSet(attributes.asInstanceOf[java.util.Set[QName]]) ++ Protocol.messageMap.values.toSeq.flatMap(_.attributes).distinct.sortWith((a,b) => a < b).map(QName.valueOf(_))
	}

//	implicit def file2RichFile(file: File): {def deleteAll} = new {
	implicit def file2RichFile(file: File) = new {
		def /(name: String) = new File(file, name)
		def deleteAll = Util.deleteAll(file)
	}
	def deleteAll(file: File) {
		if (file.exists) {
			if (file.isDirectory) file.listFiles.foreach(deleteAll(_))
			file.delete
		}
	}
	def randomInt(range: Int) = rand.nextInt(range)
	def str(o: Any): String = o.toString
	def str(o: BigInt): String = str(o.toByteArray)
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
	def strOpt(node: Node, attribute: String) = node.attribute(attribute).map(_.mkString)
	def intOpt(node: Node, attribute: String) = node.attribute(attribute).map(_.mkString.toInt)
	def bigIntOpt(node: Node, attribute: String) = node.attribute(attribute).map(att => BigInt(bytesFor(att.mkString)))
	def attributes(d: MetaData) = {
		val attrs = new AttributesHolder

		if (d != null) {
			for (attr <- d) {
				val sb = new StringBuilder()
				Utility.sequenceToXML(attr.value, TopScope, sb, true)
				findAlgorithm(sb.toString) match {
				case (_, -1, v: String) => attrs.addAttribute(new QualifiedName("", "", attr.key.toLowerCase), v)
				case (null, id: Int, data: Any) => attrs.addAttributeWithAlgorithmData(new QualifiedName("", "", attr.key.toLowerCase), null, id, data)
				case (uri: String, id: Int, data: Any) => attrs.addAttributeWithAlgorithmData(new QualifiedName("", "", attr.key.toLowerCase), uri, id, data)
				}
			}
		}
		attrs
	}
	def findAlgorithm(data: String): (String, Int, Any) = {
		val INT_PAT = "^([0-9]+)$".r

		data match {
		case INT_PAT(i) =>
			val v = BigInt(i)

			if (v > Math.MIN_SHORT.asInstanceOf[Int] && v < Math.MAX_SHORT.asInstanceOf[Int]) (null, EncodingAlgorithmIndexes.SHORT, Array(v.shortValue))
			else if (v > Math.MIN_INT && v < Math.MAX_INT) (null, EncodingAlgorithmIndexes.INT, Array(v.intValue))
			else if (v > Math.MIN_LONG && v < Math.MAX_LONG) (null, EncodingAlgorithmIndexes.LONG, Array(v.longValue))
			else (null, -1, data)
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
		serializer.startDocument()
		serializer.setVocabulary(new SerializerVocabulary(xusVocabulary, false))
		serialize(node, serializer)
		serializer.endDocument()
	}
	def parse(str: OpenByteArrayInputStream) = {
		val parser = new SAXDocumentParser
		val fac = new NoBindingFactoryAdapter
		val mem = str.memento

		parser.setContentHandler(fac)
		parser.setVocabulary(new ParserVocabulary(xusVocabulary))
		try {
			parser.parse(str)
		} catch {
		case x: Exception =>
			Console.err.println("Error parsing stream pos = "+mem._1+", count = "+mem._2+", buf size = "+mem._3.length)
			x.printStackTrace
		}
		fac.rootElem
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
	def str(key: Key): String = str(key.getEncoded)
	def str(bytes: Array[Byte]) = {
		val out = new StringBuffer

		(new BASE64EncodingAlgorithm).convertToCharacters(bytes, 0, bytes.length, out)
		out.toString
	}
	def bytesFor(str: String) = (new BASE64EncodingAlgorithm).convertFromCharacters(str.toCharArray, 0, str.length).asInstanceOf[Array[Byte]]
	def hexForBytes(bytes: Array[Byte]) = bytes.map("%02X".format(_)).mkString
	def hex(n: BigInt, width: Int = -1) = {
		val s = n.toString(16)

		if (width != -1 && width < s.length) {
			(1 to width - s.length).map(_=>'0').mkString + s
		} else {
			s
		}
	}
	def bytesForHex(str: String) = (for (i <- 0 until str.length by 2) yield java.lang.Byte.parseByte(str.slice(i, i + 2), 16)).toArray[Byte]
	def digestInt(string: String): BigInt = digestInt(string.getBytes())
	def digestInt(bytes: Array[Byte]) = {
		val i = BigInt(digest.digest(bytes))
		digest.reset
		i
	}
	def findDht(key: BigInt, peers: Seq[PeerConnection]) = {
		peers.foldLeft((peers.head,peers.head.peerId.abs + peers.last.peerId.abs)) {
		case ((member, distance), cur) =>
			val dist = (cur.peerId - key).abs

			if (dist < distance || (dist == distance && key < cur.peerId)) (cur, dist) else (member, distance)
		}._1
	}
	def nactor(name: String)(body: => Unit): Actor = {
		val a = new Actor {
			def act() = body
//			override final val scheduler: IScheduler = Actor.parentScheduler
			override def toString = "DaemonActor " + name
		}
		a.start()
		a
	}
	def daemonActor(name: String)(body: => Unit): Actor = {
		val a = new PropertyActor {
			def act() = body
//			override final val scheduler: IScheduler = Actor.parentScheduler
			override def toString = "DaemonActor(" + name + ")@" + System.identityHashCode(this)
		}
		a.start()
		a
	}
	def sign(node: Node, key: PrivateKey) = {
		val rsa = Signature.getInstance("SHA1withRSA")
		val xml = toXML(node).toString

		rsa.initSign(key)
		rsa.update(xml.getBytes, 0, xml.length)
		rsa.sign
	}
	def verify(node: Node, key: PublicKey, sig: Array[Byte]) = {
		val rsa = Signature.getInstance("SHA1withRSA")
		val xml = toXML(node).toString

		rsa.initVerify(key)
		rsa.update(xml.getBytes, 0, xml.length)
		rsa.verify(sig)
	}
	/** sorting also downcases attribute names */
	def toXML(
	    x: Node,
	    pscope: NamespaceBinding = TopScope,
	    sb: StringBuilder = new StringBuilder,
	    stripComments: Boolean = false,
	    decodeEntities: Boolean = true,
	    preserveWhitespace: Boolean = true,
	    sort: Boolean = true,
	    minimizeTags: Boolean = false): StringBuilder = {
		x match {
		case c: Comment if !stripComments => c buildString sb
		case x: SpecialNode               => x buildString sb
		case g: Group                     => for (c <- g.nodes) toXML(c, x.scope, sb) ; sb 
		case _  =>
			// print tag with namespace declarations
			sb.append('<')
			x.nameToString(sb)
			if (x.attributes ne null) (if (sort) sortedAttributes(x) else x.attributes).buildString(sb)
			x.scope.buildString(sb, pscope)
			if (x.child.isEmpty && minimizeTags)
				// no children, so use short form: <xyz .../>
				sb.append(" />")
			else {
				// children, so use long form: <xyz ...>...</xyz>
				sb.append('>')
				sequenceToXML(x.child, x.scope, sb, stripComments)
				sb.append("</")
				x.nameToString(sb)
				sb.append('>')
			}
		}
	}
	def sortedAttributes(node: Node) = node.attributes.toSeq.sortWith((a,b)=>a.key < b.key).foldRight(Null.asInstanceOf[MetaData]) {(attr, next) => new UnprefixedAttribute(attr.key.toLowerCase, attr.value, next)}
	private def isAtomAndNotText(x: Node) = x.isAtom && !x.isInstanceOf[Text]
	def sequenceToXML(
			children: Seq[Node],
			pscope: NamespaceBinding = TopScope,
			sb: StringBuilder = new StringBuilder,
			stripComments: Boolean = false): Unit = {
		if (children.isEmpty) return
		else if (children forall isAtomAndNotText) { // add space
			val it = children.iterator
			val f = it.next
			toXML(f, pscope, sb)
			while (it.hasNext) {
				val x = it.next
				sb.append(' ')
				toXML(x, pscope, sb)
			}
		}
		else children foreach { toXML(_, pscope, sb) }
	}
	
	def pathsDo(dir: File, filter: (File) => Boolean)(block: (File) => Any) {
		for (file <- dir.listFiles) {
			if (filter(file)) {
				if (file.isDirectory) {
					pathsDo(file, filter)(block)
				} else {
					println("FILE: "+file.getName)
					block(file)
				}
			}
		}
	}

	def commit(commitMsg: String, author: PersonIdent, committer: PersonIdent, repo: Repository) {
		// prepare the tree
		var repoPath = repo.getDirectory.getParentFile
		var index = repo.getIndex

		pathsDo(repoPath, _.getName != ".git") {f =>
			index.add(repoPath, f)
		}
		var id = index.writeTree
		index.write
		val root = repo.mapTree(id)
		// do the commit
		var currentHeadId = repo.resolve(Constants.HEAD)
		var parentIds = if (currentHeadId != null) {
			Array(currentHeadId)
		} else {
			Array[ObjectId]()
		}
		var commit = new Commit(repo, parentIds)
		commit.setTree(root)
		commit.setMessage(commitMsg)
		commit.setAuthor(author)
		commit.setCommitter(committer)
		var writer = new ObjectWriter(repo)
		commit.setCommitId(writer.writeCommit(commit))
		var refUpdate = repo.updateRef(Constants.HEAD)
		refUpdate.setNewObjectId(commit.getCommitId())
		refUpdate.setRefLogMessage(buildReflogMessage(commitMsg), false);
		if (refUpdate.forceUpdate() == RefUpdate.Result.LOCK_FAILURE) {
			throw new IOException("Failed to update " + refUpdate.getName + " to commit " + commit.getCommitId + ".");
		}
	}

	def chopPrefix(prefix: File, child: File) = child.getAbsolutePath.substring(prefix.getAbsolutePath.length + 1)

	def buildReflogMessage(commitMessage: String) = {
		var firstLine = commitMessage;
		var newlineIndex = commitMessage.indexOf("\n");

		if (newlineIndex > 0) {
			firstLine = commitMessage.substring(0, newlineIndex);
		}
		"commit: " + firstLine;
	}
}

class OpenByteArrayInputStream(bytes: Array[Byte], offset: Int, len: Int) extends ByteArrayInputStream(bytes, offset, len) {
	def this(bytes: Array[Byte]) = this(bytes, 0, bytes.length)
	def bytes = buf
	def memento = (pos, count, buf)
}
abstract class PropertyActor extends DaemonActor {
	var properties = HashMap[String,Any]()
}
object PropertyActor {
	def current = self.asInstanceOf[PropertyActor]
}
object CommitTest {
	import Util._
	import org.eclipse.jgit.lib.PersonIdent
	import org.eclipse.jgit.lib.Repository
	import org.eclipse.jgit.lib.RepositoryState
	import org.eclipse.jgit.dircache.DirCache

	val cache: File = new File("/tmp/xus")
	val repository: Repository = {
		val rep = new Repository(cache / ".git")

		if (!cache.exists) {
			rep.create()
		}
		if (rep.getRepositoryState != RepositoryState.SAFE) {
			throw new IOException("Repository state is not safe: "+rep.getRepositoryState)
		}
		rep
	}
	val index = repository.getIndex
	var author: PersonIdent = new PersonIdent("Bubba", "gump@shrimp.com")

	def main(args: Array[String]) {
		commit("Test commit", author, author, repository)
	}
}