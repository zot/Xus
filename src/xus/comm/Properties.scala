package xus.comm

import scala.xml.Node
import Util._

object Properties extends ServiceFactory[PropertiesConnection,PropertiesMaster] {
	def createConnection(topic: TopicConnection) = new PropertiesConnection(topic)
	def createMaster(topic: TopicMaster) = new PropertiesMaster(topic)
}

class PropertiesConnection(val topic: TopicConnection) extends Service {
	override def joined(nodes: Seq[Node]) {
		for (n <- nodes) n match {
		case <xus-props/> => peer.useProps(n)
		case _ =>
		}
	}
	def getprop(name: String) = peer.getProp(topic.propsKey, name)
	def setprop(name: String, value: String, persist: Boolean)(implicit block: (Response) => Unit) =
		topic.broadcast(<setprop name={name} value={value} persist={persist.toString}/>, Properties)(block)
	def delprop(name: String, value: String)(implicit block: (Response) => Unit) =
		topic.broadcast(<delprop name={name}/>, Properties)(block)
	def receiveDeleteProp(msg: DelegatedBroadcast, name: String) {
		for {
			prop <- peer.deleteProp(topic.propsKey, name)
			if prop.persist
			topic <- peer.ownedTopic(topic.space, topic.topic)
		} peer.storeProps(topic.propsKey)
	}
	def receiveSetProp(msg: DelegatedBroadcast, name: String, value: String, persist: Boolean) {
		peer.setProp(topic.propsKey, name, value, persist)
		// only owners really need to persist topic properties
		for {
			topic <- peer.ownedTopic(topic.space, topic.topic)
			if persist
		} peer.storeProps(topic.propsKey)
	}
	override def receive(msg: DelegatedBroadcast) {
		msg.payload match {
		case Seq(n @ <setprop/>) =>
		for {
			name <- strOpt(n, "name")
			value <- strOpt(n, "value")
			persist <- strOpt(n, "persist")
		} receiveSetProp(msg, name, value, persist.toBoolean)
		case Seq(n @ <delprop/>) =>
		for (name <- strOpt(n, "name")) receiveDeleteProp(msg, name)
		case _ =>
		}
	}
}

class PropertiesMaster(topic: TopicMaster) extends ServiceMaster {
	def newMembersNode = topic.peer.nodeForProps(topic.propsKey)
}
