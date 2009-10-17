/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.xml.Node
import scala.util.Sorting
import scala.collection.mutable.Set
import scala.collection.immutable.HashMap
import java.security.PublicKey
import Protocol._
import Util._

class TopicConnection(val space: Int, val topic: Int, var owner: PeerConnection) {
	var joined = false
	var services = HashMap[ServiceFactory[_,_],Service](TopicManagementFactory -> new TopicManagement(this))
	val propsKey = (space, topic).toString

	def peer = owner.peer

	def support[S <: Service](fac: ServiceFactory[S, _ <: ServiceMaster]): S = this(fac)

	def apply[S <: Service](fac: ServiceFactory[S, _ <: ServiceMaster]): S = {
		services.getOrElse(fac, {
			val svc = fac.createConnection(this)

			services += fac -> svc
			svc
		}).asInstanceOf[S]
	}

	def join: this.type = {
		peer.inputDo(peer.topicsJoined += (space, topic) -> this)
		if (owner == peer.selfConnection) {
			joined = true
			peer.inputDo {
				peer.ownedTopic(space, topic).foreach {master =>
					master.addMember(peer.selfConnection)
					handleJoin(master.nodesForJoinResponse)
				}
			}
		} else {
			owner.direct(<xus-join space={str(space)} topic={str(topic)} pubKey={peer.publicKeyString}/>) {response =>
//				println("JOINED GROUP")
				joined = true
//				println(m)
				handleJoin(response.payload)
			}
		}
		this
	}

	def handleJoin(nodes: Seq[Node]) = for (s <- services.values) s.joined(nodes)

	//
	// peer-to-peer messages
	//
	// request: direct, response: completed or failed
	// empty direct message functions as a ping
	def direct(payload: Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.direct(payload, msgId)(block)

	//
	// peer-to-space messages
	//
	def broadcast(payload: Any, service: ServiceFactory[_ <: Service, _ <: ServiceMaster] = null, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.broadcast(space, topic, payload, service, msgId)(block)

	def unicast(payload: Any, service: ServiceFactory[_ <: Service, _ <: ServiceMaster] = null, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.unicast(space, topic, payload, service, msgId)(block)

	def dht(key: BigInt, payload: Any, service: ServiceFactory[_ <: Service, _ <: ServiceMaster] = null, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.dht(space, topic, key, payload, service, msgId)(block)

	def delegate(peer: Int, payload: Any, service: ServiceFactory[_ <: Service, _ <: ServiceMaster] = null, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.delegate(peer, space, topic, payload, service, msgId)(block)

	// hooks
	def receive(msg: DelegatedBroadcast) = {
		for (s <- services.values) s.receive(msg)
		basicReceive(msg)
	}

	def receive(msg: DelegatedUnicast) = {
		for (s <- services.values) s.receive(msg)
		basicReceive(msg)
	}

	def receive(msg: DelegatedDHT) = {
		for (s <- services.values) s.receive(msg)
		basicReceive(msg)
	}

	def receive(msg: DelegatedDirect) = {
		for (s <- services.values) s.receive(msg)
		basicReceive(msg)
	}

	def basicReceive(msg: SpaceToPeerMessage) {}
}

class TopicMaster(val space: Int, val topic: Int, val peer: Peer) {
	val management = new TopicManagementMaster(this)
	var members = Array[PeerConnection]()
	var services = HashMap[ServiceFactory[_,_], ServiceMaster](TopicManagementFactory -> management)
	var servicesByName = HashMap[String, ServiceMaster](TopicManagementFactory.getClass.getName -> management)

	//protocol
	/**
	 * by default, allow any peer to join
	 */
	def joinRequest(msg: Direct) = {
		addMember(msg.con)
		msg.con.completed(msg, nodesForJoinResponse)
	}
	def nodesForJoinResponse = services.values.foldLeft(List[Node]()) {(list, svc) => svc.newMembersNode :: list}

	//api
	def addMember(con: PeerConnection) {
		val newArray = new Array[PeerConnection](members.length + 1)

//		println("TopicMaster "+this+" adding peer: " + str(con.peerId))
		newArray(0) = con
		members.copyToArray(newArray, 1)
		Sorting.quickSort(newArray)
		members = newArray
//		println("Members of space " + space + ", topic " + topic)
//		members.foreach(p => println(str(p.peerId)))
	}

	def removeMember(con: PeerConnection) {
		members = members.filter(_ != con)
	}

	def serviceFor(msg: PeerToSpaceMessage) = strOpt(msg.node, "service").flatMap(servicesByName.get(_))

	def process(broadcast: Broadcast) = {
		authorize(broadcast) {
			serviceFor(broadcast).map(_.process(broadcast)).getOrElse {
				this.broadcast(broadcast.sender, broadcast.node.child)
			}
			broadcast.completed("")
		}
	}
	
	def broadcast(payload: Any) {broadcast(peer.peerId, payload)}
	def broadcast(sender: BigInt, payload: Any) {
		members.foreach(_.delegatedBroadcast(sender, space, topic, payload))
	}

	def process(unicast: Unicast) = {
		authorize(unicast) {
			serviceFor(unicast).map(_.process(unicast)).getOrElse {
				members(randomInt(members.length)).delegatedUnicast(unicast.sender, unicast.space, unicast.topic, unicast.node.child) {
				case c: Completed => unicast.completed(c.payload)
				case f: Failed => unicast.failed(f.payload)
				}
			}
		}
	}

	def process(dht: DHT) = {
		authorize(dht) {
			serviceFor(dht).map(_.process(dht)).getOrElse {
				findDht(dht).delegatedDht(dht.sender, dht.space, dht.topic, dht.key, dht.node.child) {
				case c: Completed => dht.completed(c.payload)
				case f: Failed => dht.failed(f.payload)
				}
			}
		}
	}

	def process(delegate: DelegateDirect) = {
		authorize(delegate) {
			serviceFor(delegate).map(_.process(delegate)).getOrElse {
				members.find(_.peerId == delegate.receiver) match {
				case Some(con) => delegateResponse(con.delegatedDirect(delegate.sender, delegate.space, delegate.topic, delegate.node.child))
				case None => delegate.con.failed(delegate.msgId, ())
				}
			}
		}
	}

	/**
	 * allow unlimited broadcasting by default
	 * override this to fail if the message is not authorized
	 */
	def authorize(message: Message)(block: => Any) = {
		if (members.contains(message.con)) block
		else message.failed("Peer not a member of this topic")
	}

	// Services
	def support[M <: ServiceMaster](fac: ServiceFactory[_ <: Service,M]) = this(fac)

	def apply[M <: ServiceMaster](fac: ServiceFactory[_ <: Service,M]) = {
		services.getOrElse(fac, {
			val svc = fac.createMaster(this)

			services += fac -> svc
			servicesByName += fac.getClass.getName -> svc
			management.broadcastService(fac)
			svc
		}).asInstanceOf[M]
	}

	// utils
	def delegateResponse(msg: Message) = peer.onResponseDo(msg)(peer.delegateResponse(msg, _))

	def findDht(dht: DHT) = {
		val key = dht.key

		members.foldLeft((members.head,members.head.peerId.abs + members.last.peerId.abs)) {
		case ((member, distance), cur) =>
			val dist = (cur.peerId - key).abs;

			if (dist < distance) (cur, dist) else (member, distance)
		}._1
	}

	override def toString = "TopicMaster "+space+", "+topic

	def propsKey = (space, topic).toString
}

trait ServiceFactory[S <: Service, M <: ServiceMaster] {
	def createConnection(con: TopicConnection): S

	def createMaster(master: TopicMaster): M
}

trait Service {
	def peer = topic.peer

	def topic: TopicConnection

	def joined(payload: Seq[Node]) {}

	def receive(msg: DelegatedBroadcast) {}

	def receive(msg: DelegatedUnicast) {}

	def receive(msg: DelegatedDHT) {}

	def receive(msg: DelegatedDirect) {}
}

trait ServiceMaster {
	def newMembersNode: Node

	def process(msg: Broadcast) {}

	def process(msg: Unicast) {}

	def process(msg: DHT) {}

	def process(msg: DelegateDirect) {}
}

object TopicManagementFactory extends ServiceFactory[TopicManagement,TopicManagementMaster] {
	def createConnection(con: TopicConnection) = new TopicManagement(con)

	def createMaster(master: TopicMaster) = new TopicManagementMaster(master)
}

class TopicManagement(val topic: TopicConnection) extends Service {
	override def joined(nodes: Seq[Node]) = handlePayload(nodes)

	override def receive(msg: DelegatedBroadcast) = handlePayload(msg.payload)

	def handlePayload(nodes: Seq[Node]) {
		for (n <- nodes) n match {
		case <xus-services>{svcs}</xus-services> => for (svc <- svcs) supportService(svc)
		case <service/> => supportService(n)
		case _ =>
		}
	}

	def supportService(svc: Node) {
		for (name <- strOpt(svc, "name")) {
			topic.support(Class.forName(name).getDeclaredField("MODULE$").get(null).asInstanceOf[ServiceFactory[_ <: Service,_ <: ServiceMaster]])
		}
	}
}

class TopicManagementMaster(val master: TopicMaster) extends ServiceMaster {
	def newMembersNode = <xus-services>{for (svc <- master.services.keys) yield serviceNode(svc)}</xus-services>

	def serviceNode(svc: ServiceFactory[_,_]) = <service name={svc.getClass.getName}/>

	def broadcastService(svc: ServiceFactory[_,_]) = master.broadcast(serviceNode(svc))
}
