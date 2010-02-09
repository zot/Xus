package xus.comm

import Util._
import scala.xml.Node
import java.io.File
import java.io.IOException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.dircache.DirCache

/**
 * FileSharing - Xus peers share files as Git commits and keep track of them using tags
 * 
 * Peers store data as commits.  Each commit is stored as a commit chunk list,
 * which is a serialized Git commit, plus a chunk list of a commit manifest.
 * A commit manifest contains chunk ids for all of the trees and blobs in the
 * commit.  The chunks are listed by ID and rsync
 * CRC so that the peer can scavenge chunks from its own cache.
 * The manifest is a file and is itself broken
 * into chunks.  The list of manifest chunks is one chunk.  A tag is a
 * chunk that contains a serialized git tag, along with the id of the
 * manifest chunk list.  Peers store tags by name (i.e. an id computed
 * based on the tag name) and chunks by id.
 * 
 * The procedure to retrieve a commit is:
 * 
 * <ol><li>retrieve the tag by name and store it in the local git repository
 * <li>get the manifest chunk list ID from the tag
 * <li>retrieve the manifest chunk list and store them in the local git repository
 * <li>reconstruct the manifest from the chunks
 * <li>retrieve any <strong>outstanding</strong> chunks in the manifest
 * <li>reconstruct each tree node and blob from the chunks and store them in the local git repository
 * </ol>
 * 
 * <strong>Outstanding</strong> above means the peer first attempts to
 * scavenge the chunk if it already has a version of that file in its cache
 * similar to how rsync does it (chunks are identified by a rolling CRC in
 * addition to a SHA-1 hash).
 *
 * To construct a commit manifest:
 * <ol><li>break blobs into chunks and store them in the DHT
 * <li>do not break tree objects -- use one chunk for each and store it in the DHT
 * <li>store the chunk ids of the blobs and trees in the manifest as XML
 * <li>serialize the XML as a fastinfoset containing the commit data and the chunk list and store it in the DHT
 * <pre><commit data="...">
 * <tree id="..." crc="..."/>
 * <tree id="..." crc="..."/>
 * <blob id="...">
 *    <chunk id="..." crc="..."/>
 *    <chunk id="..." crc="..."/>
 *    ...
 * </blob>
 * <tree name="..." id="..." crc="..."/>
 * ...
 * </commit></pre>
 * <li>break the commit manifest into chunks and store them in the DHT
 * <li>create a commit chunk list and store it in the DHT
 * <pre><commitchunks id="...">
 *    <chunk id="..." crc="..."/>
 *    <chunk id="..." crc="..."/>
 *    <chunk id="..." crc="..."/>
 * </commit></pre>
 * </ol>
 * 
 * the protocol has 4 messages:
 * <pre>
 * @see #storeTag() storeTag()
 * @see #retrieveTag() retrieveTag()
 * @see #storeChunk() storeChunk()
 * @see #retrieveChunk() retrieveChunk()</pre>*/

object FileSharing extends ServiceFactory[FileSharingConnection,FileSharingMaster] {
	def createConnection(topic: TopicConnection) = new FileSharingConnection(topic)
	def createMaster(topic: TopicMaster) = new FileSharingMaster(topic)
}

class FileSharingConnection(topic: TopicConnection) extends ServiceConnection(topic) {
	if (topic.peer.props("prefs").get("FileSharing.cache").isEmpty) {
		throw new Exception("No ")
	}
	val cache: File = new File(new File(topic.peer.storageDirectory, "file-cache"), topic.space+"-"+topic.topic)
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

	override def receive(msg: DelegatedDHT, node: Node) {
		for (n <- node.child) n match {
		case Seq(<store-tag/>) =>
			for {
				tagId <- strOpt(n, "tagId")
				peerId <- strOpt(n, "taggerIdent")
				message <- strOpt(n, "message")
				tagType <- strOpt(n, "type")
				name <- strOpt(n, "name")
				objectId <- strOpt(n, "objectId")
				chunkListId <- strOpt(n, "chunkListId")
			} {
				receiveStoreTag(msg, tagId, peerId, message, tagType, name, objectId, chunkListId)
			}
		case Seq(<retrieve-tag>{tagId}</retrieve-tag>) => receiveRetrieveTag(msg, tagId.mkString)
		case Seq(<store-chunk>{data}</store-chunk>) => receiveStoreChunk(msg, bytesFor(data.mkString))
		case Seq(<retrieve-chunk>{data}</retrieve-chunk>) => receiveRetrieveChunk(msg)
		case _ =>
		}
	}
	def receiveStoreTag(msg: DelegatedDHT, tagId: String, peerId: String, message: String, tagType: String, name: String, objectId: String, chunkListId: String) {
	}
	def receiveRetrieveTag(msg: DelegatedDHT, tagId: String) {
	}
	def receiveStoreChunk(msg: DelegatedDHT, bytes: Array[Byte]) {
	}
	def receiveRetrieveChunk(msg: DelegatedDHT) {
	}
	def storeTag(tagId: BigInt, peerId: String, message: String, tagType: String, name: String, objectId: String, chunkListId: String) {
		topic.dht(tagId, <store-tag tagId={str(tagId)} taggerIdent={peerId} message={message} type={tagType} name={name} objectId={objectId} chunkListId={chunkListId}/>) {response =>}
	}
	def retrieveTag(msg: DelegatedDHT, tagId: BigInt) {
		topic.dht(tagId, <retrieve-tag tagId={str(tagId)}/>) {response =>}
	}
	def storeChunk() {
	}
	def retrieveChunk() {
	}

	// utilities
	def currentCommit = repository.mapCommit(repository.getFullBranch)
	def close = repository.close

	// services
	/**
	 * Add a file after it's been closed (i.e. it already exists, etc.)
	 */
	def add(file: File) {
		index.add(cache, file)
	}
	def commit {
		
	}
	def push {
	}
}

class FileSharingMaster(master: TopicMaster) extends ServiceMaster(master) {
	override def newMembersNode = None

	override def process(msg: DHT, node: Node) {
		for (n <- node.child) n match {
		case Seq(<store-tag/>) =>
			for {
				tagId <- strOpt(n, "tagId")
				peerId <- strOpt(n, "taggerIdent")
				message <- strOpt(n, "message")
				tagType <- strOpt(n, "type")
				name <- strOpt(n, "name")
				objectId <- strOpt(n, "objectId")
				chunkListId <- strOpt(n, "chunkListId")
			} {
				processStoreTag(msg, tagId, peerId, message, tagType, name, objectId, chunkListId)
			}
		case Seq(<retrieve-tag>{tagId}</retrieve-tag>) => processRetrieveTag(msg, tagId.mkString)
		case Seq(<store-chunk>{data}</store-chunk>) => processStoreChunk(msg, bytesFor(data.mkString))
		case Seq(<retrieve-chunk>{data}</retrieve-chunk>) => processRetrieveChunk(msg)
		case _ =>
		}
	}

	def processStoreTag(msg: DHT, tagId: String, peerId: String, message: String, tagType: String, name: String, objectId: String, chunkListId: String) {
		msg.completed()
	}

	def processRetrieveTag(msg: DHT, tagId: String) {
		msg.completed()
	}

	def processStoreChunk(msg: DHT, data: Array[Byte]) {
		msg.completed()
	}

	def processRetrieveChunk(msg: DHT) {
		msg.completed()
	}
}

