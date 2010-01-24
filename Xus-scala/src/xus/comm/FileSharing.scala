package xus.comm

import Util._
import scala.xml.Node

/**
 * FileSharing - Xus peers share files as Git commits and keep track of them using tags
 * 
 * Peers store Git commits like using commit manifests and chunks
 * a commit manifest lists all of the chunks for its commit and all
 * of its trees and blobs.  The chunks are listed by ID and rsync CRC
 * so that the peer can scavenge chunks from its own cache.
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
	def storeTag(tagId: BigInt, peerId: String, message: String, tagType: String, name: String, objectId: String, chunkListId: String) {
		topic.dht(tagId, <store-tag tagId={str(tagId)} taggerIdent={peerId} message={message} type={tagType} name={name} objectId={objectId} chunkListId={chunkListId}/>) {response =>}
	}

	def retrieveTag(tagId: BigInt) {
		topic.dht(tagId, <retrieve-tag tagId={str(tagId)}/>) {response =>}
	}
	
	def storeChunk() {
		
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

