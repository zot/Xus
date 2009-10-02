/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.util.Sorting
import scala.collection.mutable.Set
import Protocol._
import Util._

class Topic(val space: Int, val topic: Int, val peer: Peer) {
	var members = Array[PeerConnection]()

	//protocol
	def joinRequest(msg: Direct) = addMember(msg.con)

	//api
	def addMember(con: PeerConnection) {
		val newArray = new Array[PeerConnection](members.length + 1)

		println("Topic adding peer: " + str(con.peerId))
		newArray(0) = con
		members.copyToArray(newArray, 1)
		Sorting.quickSort(newArray)
		members = newArray
		println("Members of space " + space + ", topic " + topic)
		members.foreach(p => println(str(p.peerId)))
	}
	def removeMember(con: PeerConnection) {
		members = members.filter(_ != con)
	}
	def process(broadcast: Broadcast) =
		members.foreach(_.sendBroadcast(broadcast.sender, broadcast.space, broadcast.topic, broadcast.node.child, broadcast.msgId))
	def process(unicast: Unicast) = delegateResponse(members(randomInt(members.length)).sendUnicast(unicast.sender, unicast.space, unicast.topic, unicast.node.child, unicast.msgId))
	def process(dht: DHT) = delegateResponse(findDht(dht).sendDHT(dht.sender, dht.space, dht.topic, dht.key, dht.node.child, dht.msgId))
	def process(delegate: DelegateDirect) = {
		members.find(_.peerId == delegate.receiver) match {
		case Some(con) => delegateResponse(con.sendDelegatedDirect(delegate.sender, delegate.space, delegate.topic, delegate.node.child, delegate.msgId))
		case None => delegate.con.sendFailed(delegate.msgId, ())
		}
	}
	//hooks
	def receive(msg: DelegatedBroadcast) = basicReceive(msg)
	def receive(msg: DelegatedUnicast) = basicReceive(msg)
	def receive(msg: DelegatedDHT) = basicReceive(msg)
	def receive(msg: DelegatedDirect) = basicReceive(msg)
	def basicReceive(msg: SpaceToPeerMessage) {}
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
}

trait TopicManagement {
}