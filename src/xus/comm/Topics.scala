/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.xml.Elem
import scala.xml.Node
import scala.xml.TopScope
import scala.util.Sorting
import scala.collection.mutable.Set
import scala.collection.immutable.HashMap
import java.security.PublicKey
import Protocol._
import Util._

class TopicConnection(val space: Int, val topic: Int, var owner: PeerConnection) {
	var joined = false
	var management = new TopicManagementConnection(this)
	var services = HashMap[ServiceFactory[_,_],ServiceConnection](TopicManagement -> management)
	var servicesByName = HashMap[String, ServiceConnection](TopicManagement.getClass.getName.toLowerCase -> management)
	val propsKey = (space, topic).toString

	def peer = owner.peer

	def support[S <: ServiceConnection](fac: ServiceFactory[S, _ <: ServiceMaster]): S = this(fac)

	def apply[S <: ServiceConnection](fac: ServiceFactory[S, _ <: ServiceMaster]): S = {
		services.getOrElse(fac, {
			val svc = fac.createConnection(this)

			services += fac -> svc
			servicesByName += fac.getClass.getName.toLowerCase -> svc
			svc
		}).asInstanceOf[S]
	}
	
	def isOwner = owner == peer.selfConnection

	def join: this.type = {
		peer.inputDo(peer.topicsJoined += (space, topic) -> this)
		if (isOwner) {
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
	def broadcast(payload: => Any, msgId: Int = -1)(implicit block: (Response) => Unit): Unit = owner.broadcast(space, topic, payload, msgId)(block)

	def unicast(payload: => Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.unicast(space, topic, payload, msgId)(block)

	def dht(key: BigInt, payload: => Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.dht(space, topic, key, payload, msgId)(block)

	def delegate(peer: Int, payload: => Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.delegate(peer, space, topic, payload, msgId)(block)

	def optBroadcast(payload: => Any, msgId: Int = -1)(elseBlock: => Any)(implicit block: (Response) => Unit): Unit = ifAnyoneElse(broadcast(payload, msgId)(block), elseBlock)

	def optUnicast(payload: => Any, msgId: Int = -1)(elseBlock: => Any)(implicit block: (Response) => Unit) = ifAnyoneElse(unicast(payload, msgId)(block), elseBlock)

	def optDht(key: BigInt, payload: => Any, msgId: Int = -1)(elseBlock: => Any)(implicit block: (Response) => Unit) = ifAnyoneElse(dht(key, payload, msgId)(block), elseBlock)

	// hooks
	def receive(msg: DelegatedBroadcast) = {
		servicesFor(msg).foreach(_.receive(msg))
		basicReceive(msg)
	}

	def receive(msg: DelegatedUnicast) = {
		servicesFor(msg).foreach(_.receive(msg))
		basicReceive(msg)
	}

	def receive(msg: DelegatedDHT) = {
		servicesFor(msg).foreach(_.receive(msg))
		basicReceive(msg)
	}

	def receive(msg: DelegatedDirect) = {
		servicesFor(msg).foreach(_.receive(msg))
		basicReceive(msg)
	}

	def servicesFor(msg: SpaceToPeerMessage) = msg.payload match {case Seq(n: Node) => servicesByName.get(n.label); case _ => None}

	def basicReceive(msg: SpaceToPeerMessage) {}

	/**
	 * true if this peer does not own the topic or if it does own it and there are recipients other than itself
	 */
	def ifAnyoneElse(expr: => Any, elseBlock: => Any) =
		if (peer.ownedTopic(space, topic).map(_.members match {case Seq(c) => c != peer.selfConnection; case m => !m.isEmpty}) getOrElse true) {
			expr
		} else {
			elseBlock
		}
}

class TopicMaster(val space: Int, val topic: Int, val peer: Peer) {
	val management = new TopicManagementMaster(this)
	var members = Array[PeerConnection]()
	var services = HashMap[ServiceFactory[_,_], ServiceMaster](TopicManagement -> management)
	var servicesByName = HashMap[String, ServiceMaster](TopicManagement.getClass.getName.toLowerCase -> management)

	//protocol
	/**
	 * by default, allow any peer to join
	 */
	def joinRequest(msg: Direct) = {
		addMember(msg.con)
		msg.con.completed(msg, nodesForJoinResponse)
	}
	def nodesForJoinResponse = services.values.foldLeft(List[Node]()) {(list, svc) => svc.newMembersNode.map(_ :: list) getOrElse list}

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

	def servicesFor(msg: PeerToSpaceMessage) = msg.payload match {case Seq(n: Node) => servicesByName.get(n.label); case _ => None}

	def process(broadcast: Broadcast) = {
		authorize(broadcast) {
			servicesFor(broadcast).map(_.process(broadcast)).getOrElse {
				this.broadcast(broadcast)
			}
			broadcast.completed("")
		}
	}

	def broadcast(msg: Broadcast) {broadcast(msg.sender, msg.node.child)}
	def broadcast(payload: Any) {broadcast(peer.peerId, payload)}
	def broadcast(sender: BigInt, payload: Any) {
		members.foreach(_.delegatedBroadcast(sender, space, topic, payload))
	}

	def process(unicast: Unicast) = authorize(unicast) {servicesFor(unicast).map(_.process(unicast)).getOrElse {this.unicast(unicast)}}

	def unicast(msg: Unicast) {
		members(randomInt(members.length)).delegatedUnicast(msg.sender, msg.space, msg.topic, msg.node.child) {
			case c: Completed => msg.completed(c.payload)
			case f: Failed => msg.failed(f.payload)
		}
	}

	def process(dht: DHT) = {
		authorize(dht) {
			servicesFor(dht).map(_.process(dht)).getOrElse {
				findDht(dht).delegatedDht(dht.sender, dht.space, dht.topic, dht.key, dht.node.child) {
				case c: Completed => dht.completed(c.payload)
				case f: Failed => dht.failed(f.payload)
				}
			}
		}
	}
	
	def dht(msg: DHT) {
		findDht(msg).delegatedDht(msg.sender, msg.space, msg.topic, msg.key, msg.node.child) {
		case c: Completed => msg.completed(c.payload)
		case f: Failed => msg.failed(f.payload)
		}
	}

	def process(delegate: DelegateDirect) = authorize(delegate) {servicesFor(delegate).map(_.process(delegate)).getOrElse {this.delegate(delegate)}}

	def delegate(msg: DelegateDirect) {
		members.find(_.peerId == msg.receiver) match {
		case Some(con) => delegateResponse(con.delegatedDirect(msg.sender, msg.space, msg.topic, msg.node.child))
		case None => msg.con.failed(msg.msgId, ())
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
	def support[M <: ServiceMaster](fac: ServiceFactory[_ <: ServiceConnection,M]) = this(fac)

	def apply[M <: ServiceMaster](fac: ServiceFactory[_ <: ServiceConnection,M]) = {
		services.getOrElse(fac, {
			val svc = fac.createMaster(this)

			services += fac -> svc
			servicesByName += fac.getClass.getName.toLowerCase -> svc
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

trait ServiceFactory[S <: ServiceConnection, M <: ServiceMaster] {
	def createConnection(con: TopicConnection): S

	def createMaster(master: TopicMaster): M
	
	def apply(children: Iterator[Node]) = Elem(null, getClass.getName.toLowerCase, null, TopScope, children.toSeq: _*)

	def apply(children: Seq[Node]) = Elem(null, getClass.getName.toLowerCase, null, TopScope, children: _*)

	def unapply(n: Node) = if (n.label.toLowerCase == getClass.getName.toLowerCase) Some(n.child) else None
}

trait ServiceConnection {
	def peer = topic.peer

	def topic: TopicConnection

	def joined(payload: Seq[Node]) {}

	def receive(msg: DelegatedBroadcast) {}

	def receive(msg: DelegatedUnicast) {}

	def receive(msg: DelegatedDHT) {}

	def receive(msg: DelegatedDirect) {}
}

trait ServiceMaster {
	def newMembersNode: Option[Node]
	def master: TopicMaster

	def process(msg: Broadcast) {master.broadcast(msg)}

	def process(msg: Unicast) {master.unicast(msg)}

	def process(msg: DHT) {master.dht(msg)}

	def process(msg: DelegateDirect) {master.delegate(msg)}
}
