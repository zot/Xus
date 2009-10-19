package xus.comm

import scala.xml.Node
import Util._

object TopicManagement extends ServiceFactory[TopicManagementConnection,TopicManagementMaster] {
	def createConnection(con: TopicConnection) = new TopicManagementConnection(con)

	def createMaster(master: TopicMaster) = new TopicManagementMaster(master)
}

class TopicManagementConnection(val topic: TopicConnection) extends ServiceConnection {
	override def joined(nodes: Seq[Node]) = handlePayload(nodes)

	override def receive(msg: DelegatedBroadcast) = handlePayload(msg.payload)

	def handlePayload(nodes: Seq[Node]) {
		for (n <- nodes) n match {
		case TopicManagement(svcs) => for (svc <- svcs) supportService(svc)
		case <service/> => supportService(n)
		case _ =>
		}
	}

	def supportService(svc: Node) {
		for (name <- strOpt(svc, "name")) {
			topic.support(Class.forName(name).getDeclaredField("MODULE$").get(null).asInstanceOf[ServiceFactory[_ <: ServiceConnection,_ <: ServiceMaster]])
		}
	}
}

class TopicManagementMaster(val master: TopicMaster) extends ServiceMaster {
	def newMembersNode = Some(TopicManagement(for (svc <- master.services.keysIterator) yield serviceNode(svc)))

	def serviceNode(svc: ServiceFactory[_,_]) = <service name={svc.getClass.getName}/>

	def broadcastService(svc: ServiceFactory[_,_]) = master.broadcast(TopicManagement(serviceNode(svc)))
}
