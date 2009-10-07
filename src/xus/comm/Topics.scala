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

	def join: this.type = {
		peer.inputDo(peer.topicsJoined += (space, topic) -> this)
		if (owner == peer.selfConnection) {
			joined = true
			peer.ownedTopic(space, topic).foreach(_.addMember(peer.selfConnection))
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
	def setprop(name: String, value: String, persist: Boolean) =
		owner.broadcast(space, topic, <xus-setprop name={name} value={value} persist={persist.toString}/>)
	def delprop(name: String, value: String) =
		owner.broadcast(space, topic, <xus-delprop name={name}/>)

	//
	// peer-to-peer messages
	//
	// request: direct, response: completed or failed
	// empty direct message functions as a ping
	def direct(message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.direct(message, msgId)(block)

	//
	// peer-to-space messages
	//
	def broadcast(message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.broadcast(space, topic, message, msgId)(block)
	def unicast(message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.unicast(space, topic, message, msgId)(block)
	def dht(key: BigInt, message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.dht(space, topic, key, message, msgId)(block)
	def delegate(peer: Int, message: Any, msgId: Int = -1)(implicit block: (Response) => Unit) = owner.delegate(peer, space, topic, message, msgId)(block)

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
	def peer = owner.peer
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
	def process(broadcast: Broadcast) =
		members.foreach(_.broadcast(broadcast.sender, broadcast.space, broadcast.topic, broadcast.node.child, broadcast.msgId))
	def process(unicast: Unicast) = delegateResponse(members(randomInt(members.length)).unicast(unicast.sender, unicast.space, unicast.topic, unicast.node.child, unicast.msgId))
	def process(dht: DHT) = delegateResponse(findDht(dht).dht(dht.sender, dht.space, dht.topic, dht.key, dht.node.child, dht.msgId))
	def process(delegate: DelegateDirect) = {
		members.find(_.peerId == delegate.receiver) match {
		case Some(con) => delegateResponse(con.delegatedDirect(delegate.sender, delegate.space, delegate.topic, delegate.node.child, delegate.msgId))
		case None => delegate.con.failed(delegate.msgId, ())
		}
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