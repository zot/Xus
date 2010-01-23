package xus.comm

import Util._
import scala.xml.Node

object FileSharing extends ServiceFactory[FileSharingConnection,FileSharingMaster] {
	def createConnection(topic: TopicConnection) = new FileSharingConnection(topic)
	def createMaster(topic: TopicMaster) = new FileSharingMaster(topic)
}

class FileSharingConnection(topic: TopicConnection) extends ServiceConnection(topic) {
	override def receive(msg: DelegatedDHT, node: Node) {
		for (n <- node.child) n match {
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

class FileSharingMaster(master: TopicMaster) extends ServiceMaster(master) {
	override def newMembersNode = None
}
