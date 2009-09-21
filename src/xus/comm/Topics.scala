/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import scala.collection.mutable.Set

class Topic(val space: Int, val topic: Int, val peer: Peer) {
	val members = Set[PeerConnectionProtocol]()

	def process(broadcast: Broadcast) =
		members.foreach(_.sendBroadcast(broadcast.sender, broadcast.space, broadcast.topic, broadcast.node.child, broadcast.msgId))
	def process(unicast: Unicast) =
		members.foreach(_.sendUnicast(unicast.sender, unicast.space, unicast.topic, unicast.node.child, unicast.msgId))
	def process(dht: DHT) =
		members.foreach(_.sendDHT(dht.sender, dht.space, dht.topic, dht.node.child, dht.msgId))
	def process(delegate: DelegateDirect) =
		members.foreach(_.sendDelegatedDirect(delegate.sender, delegate.space, delegate.topic, delegate.node.child, delegate.msgId))
	def receive(msg: DelegatedBroadcast) = basicReceive(msg)
	def receive(msg: DelegatedUnicast) = basicReceive(msg)
	def receive(msg: DelegatedDHT) = basicReceive(msg)
	def receive(msg: DelegatedDirect) = basicReceive(msg)
	def basicReceive(msg: SpaceToPeerMessage) {}
}

trait TopicManagement {
}