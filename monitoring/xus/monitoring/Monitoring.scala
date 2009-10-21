package xus.monitoring;

import xus.comm._

object Monitoring extends ServiceFactory[MonitoringConnection,MonitoringMaster] {
	def createConnection(con: TopicConnection) = new MonitoringConnection(con)

	def createMaster(master: TopicMaster) = new MonitoringMaster(master)
}

class MonitoringConnection(val topic: TopicConnection) extends ServiceConnection {
	
}

class MonitoringMaster(val master: TopicMaster) extends ServiceMaster {
	def newMembersNode = None
}
