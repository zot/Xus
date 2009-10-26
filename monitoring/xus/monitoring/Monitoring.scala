package xus.monitoring;

import xus.comm._
import Util._
import scala.xml.Node

object Monitoring extends ServiceFactory[MonitoringConnection,MonitoringMaster] {
	def createConnection(con: TopicConnection) = {
		println("Monitoring peer: "+con.owner)
		MonitoringView.setMaster(con.owner)
		new MonitoringConnection(con)
	}

	def createMaster(master: TopicMaster) = new MonitoringMaster(master)
}

class MonitoringConnection(topic: TopicConnection) extends ServiceConnection(topic) {
	override def joined(payload: Seq[Node]) {handleNodes(payload)}

	override def receive(msg: DelegatedBroadcast, node: Node) {handleNodes(node.child)}

	def handleNodes(nodes: Seq[Node]) = for (node <- nodes) node match {
	case <addconnection/> => for {peerid <- bigIntOpt(node, "peerid"); ip <- strOpt(node, "ip")} MonitoringView.addConnection(peerid, ip)
	case <removeconnection/> => for {peerid <- bigIntOpt(node, "peerid")} MonitoringView.removeConnection(peerid)
	case <jointopic/> =>
		for {
			peerid <- bigIntOpt(node, "peerid")
			space <- intOpt(node, "space")
			topic <- intOpt(node, "topic")
		} MonitoringView.joinTopic(peerid, space, topic)
	}
}

class MonitoringMaster(master: TopicMaster) extends ServiceMaster(master) with MonitorObserver {
	master.peer.addObserver(this)

	override def newMembersNode = Some(Monitoring(
			(master.peer.peerConnections.valuesIterator.map(nodeForAddPeerConnection(_))
					++ master.peer.topicsOwned.valuesIterator.flatMap(master => master.members.iterator.map(nodeForJoinTopic(_, master)))).toSeq: _*))

	def addPeerConnection(con: PeerConnection) = master.broadcast(Monitoring(nodeForAddPeerConnection(con)))

	def nodeForAddPeerConnection(con: PeerConnection) = <addconnection peerid={str(con.peerId)} ip={str(con.address)}/>

	def removePeerConnection(con: PeerConnection) = master.broadcast(Monitoring(<removeconnection peerid={str(con.peerId)}/>))

	def joinTopic(con: PeerConnection, topic: TopicMaster) = master.broadcast(Monitoring(nodeForJoinTopic(con, topic)))

	def nodeForJoinTopic(con: PeerConnection, topic: TopicMaster) = <jointopic peerid={str(con.peerId)} space={str(topic.space)} topic={str(topic.topic)}/>
}
