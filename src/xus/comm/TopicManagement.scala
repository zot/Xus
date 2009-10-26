package xus.comm

import scala.xml.Node
import Util._

object TopicManagement extends ServiceFactory[TopicManagementConnection,TopicManagementMaster] {
	def createConnection(con: TopicConnection) = new TopicManagementConnection(con)

	def createMaster(master: TopicMaster) = new TopicManagementMaster(master)
}

class TopicManagementConnection(topic: TopicConnection) extends ServiceConnection(topic) {
	override def joined(nodes: Seq[Node]) = handlePayload(nodes)

	override def receive(msg: DelegatedBroadcast, n: Node) = handlePayload(n.child)

	def handlePayload(nodes: Seq[Node]) = for (svc <- nodes) supportService(svc)

	def supportService(svc: Node) {
		for (name <- strOpt(svc, "name")) {
			topic.support(Class.forName(name).getDeclaredField("MODULE$").get(null).asInstanceOf[ServiceFactory[_ <: ServiceConnection,_ <: ServiceMaster]])
		}
	}
}

class TopicManagementMaster(master: TopicMaster) extends ServiceMaster(master) {
	override def newMembersNode = Some(TopicManagement((for ((fac, svc) <- master.services) yield serviceNode(fac, svc)).toSeq: _*))

	def serviceNode(fac: ServiceFactory[_,_], svc: ServiceMaster) = <service name={fac.getClass.getName}></service>

	def broadcastService(fac: ServiceFactory[_,_], svc: ServiceMaster) = master.broadcast(TopicManagement(serviceNode(fac, svc)))
}
