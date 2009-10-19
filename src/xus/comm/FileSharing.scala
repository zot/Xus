package xus.comm

import Util._

object FileSharing extends ServiceFactory[FileSharingConnection,FileSharingMaster] {
	def createConnection(topic: TopicConnection) = new FileSharingConnection(topic)
	def createMaster(topic: TopicMaster) = new FileSharingMaster(topic)
}

class FileSharingConnection(val topic: TopicConnection) extends ServiceConnection {
	override def receive(msg: DelegatedDHT) {
		msg.payload match {
		case Seq(<store>{data}</store>) => store(msg, bytesFor(data.mkString))
		case Seq(<retrieve>{data}</retrieve>) => retrieve(msg)
		case _ =>
		}
	}

	def store(msg: DelegatedDHT, data: Array[Byte]) {
		msg.completed()
	}

	def retrieve(msg: DelegatedDHT) {
		msg.completed()
	}
}

class FileSharingMaster(val master: TopicMaster) extends ServiceMaster {
	def newMembersNode = None
}
