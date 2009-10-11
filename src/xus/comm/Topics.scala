/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.util.Sorting
import scala.collection.mutable.Set
import java.security.PublicKey
import Protocol._
import Util._

class TopicConnection(val space: Int, val topic: Int, var owner: PeerConnection) {
	var joined = false

	def peer = owner.peer
	def join: this.type = {
		peer.inputDo(peer.topicsJoined += (space, topic) -> this)
		if (owner == peer.selfConnection) {
			joined = true
			peer.inputDo(peer.ownedTopic(space, topic).foreach(_.addMember(peer.selfConnection)))
		} else {
			owner.direct(<xus-join space={str(space)} topic={str(topic)} pubKey={peer.publicKeyString}/>) {m =>
//				println("JOINED GROUP")
				peer.useProps(m.payload(0))
				joined = true
//				println(m)
			}
		}
		this
	}
	def getprop(name: String) = peer.getProp(propsKey, name)
	def setprop(name: String, value: String, persist: Boolean)(implicit block: (Response) => Unit) =
		broadcast(<xus-setprop name={name} value={value} persist={persist.toString}/>)(block)
	def delprop(name: String, value: String)(implicit block: (Response) => Unit) =
		broadcast(<xus-delprop name={name}/>)(block)

	//
	// peer-to-peer messages
	//
	// request: direct, response: completed or failed
	// empty direct message functions as a ping
	def direct(payload: Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.direct(payload, msgId)(block)

	//
	// peer-to-space messages
	//
	def broadcast(payload: Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.broadcast(space, topic, payload, msgId)(block)
	def unicast(payload: Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.unicast(space, topic, payload, msgId)(block)
	def dht(key: BigInt, payload: Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.dht(space, topic, key, payload, msgId)(block)
	def delegate(peer: Int, payload: Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.delegate(peer, space, topic, payload, msgId)(block)

	// hooks
	def receiveDeleteProp(msg: DelegatedBroadcast, name: String) {
		for {
			prop <- peer.deleteProp(propsKey, name)
			if prop.persist
			topic <- peer.ownedTopic(space, topic)
		} peer.storeProps(propsKey)
	}
	def receiveSetProp(msg: DelegatedBroadcast, name: String, value: String, persist: Boolean) {
		peer.setProp(propsKey, name, value, persist)
		// only owners really need to persist topic properties
		for {
			topic <- peer.ownedTopic(space, topic)
			if persist
		} peer.storeProps(propsKey)
	}
	def receive(msg: DelegatedBroadcast) {
		msg.payload match {
		case Seq(n @ <xus-setprop/>) =>
			for {
				name <- strOpt(n, "name")
				value <- strOpt(n, "value")
				persist <- strOpt(n, "persist")
			} receiveSetProp(msg, name, value, persist.toBoolean)
		case Seq(n @ <xus-delprop/>) =>
			for (name <- strOpt(n, "name")) receiveDeleteProp(msg, name)
		case _ =>
		}
		basicReceive(msg)
	}
	def receive(msg: DelegatedUnicast) = basicReceive(msg)
	def receive(msg: DelegatedDHT) = basicReceive(msg)
	def receive(msg: DelegatedDirect) = basicReceive(msg)
	def basicReceive(msg: SpaceToPeerMessage) {}
	val propsKey = (space, topic).toString
}

class Topic(val space: Int, val topic: Int, val peer: Peer) {
	var members = Array[PeerConnection]()

	//protocol
	/**
	 * by default, allow any peer to join
	 */
	def joinRequest(msg: Direct) = {
		addMember(msg.con)
		msg.con.completed(msg, peer.nodeForProps(propsKey))
	}
	/**
	 * by default, allow any peer to broadcast property settings
	 */
	def setPropRequest(msg: Broadcast) = process(msg)
	/**
	 * by default, allow any peer to broadcast property deletions
	 */
	def deletePropRequest(msg: Broadcast) = process(msg)

	//api
	def addMember(con: PeerConnection) {
		val newArray = new Array[PeerConnection](members.length + 1)

//		println("Topic "+this+" adding peer: " + str(con.peerId))
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
	def process(broadcast: Broadcast) = {
		authorize(broadcast) {
			members.foreach(_.delegatedBroadcast(broadcast.sender, broadcast.msgId, broadcast.space, broadcast.topic, broadcast.node.child))
			broadcast.completed("")
		}
	}
	def process(unicast: Unicast) = {
		authorize(unicast) {
			members(randomInt(members.length)).delegatedUnicast(unicast.sender, unicast.msgId, unicast.space, unicast.topic, unicast.node.child) {
			case c: Completed => unicast.completed(c.payload)
			case f: Failed => unicast.failed(f.payload)
			}
		}
	}
	def process(dht: DHT) = {
		authorize(dht) {
			findDht(dht).delegatedDht(dht.sender, dht.msgId, dht.space, dht.topic, dht.key, dht.node.child) {
			case c: Completed => dht.completed(c.payload)
			case f: Failed => dht.failed(f.payload)
			}
		}
	}
	def process(delegate: DelegateDirect) = {
		authorize(delegate) {
			members.find(_.peerId == delegate.receiver) match {
			case Some(con) => delegateResponse(con.delegatedDirect(delegate.sender, delegate.msgId, delegate.space, delegate.topic, delegate.node.child))
			case None => delegate.con.failed(delegate.msgId, ())
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
	override def toString = "Topic "+space+", "+topic
	def propsKey = (space, topic).toString
}

trait TopicManagement {
}