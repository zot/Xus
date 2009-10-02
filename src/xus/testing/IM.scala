/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.testing

import scala.io.Source
import xus.comm._
import scala.actors.Actor._
import scala.swing._
import scala.swing.Action
import scala.util.Random

object IM extends Peer("IM") {
	import xus.comm.Util._

	var isServer = false
	var con: SimpyPacketConnectionAPI = null
	var frame = new MainFrame {
		import javax.swing.KeyStroke
		import javax.swing.text.JTextComponent

		val output = new EditorPane {
			border = Swing.EtchedBorder
		}
		val input = new EditorPane {
			val inputKm = JTextComponent.addKeymap("input", JTextComponent.getKeymap(JTextComponent.DEFAULT_KEYMAP))
			inputKm.addActionForKeyStroke(KeyStroke.getKeyStroke("ENTER"), Action("send") {
				peerConnections(con).sendBroadcast(0, 1, text)
				text = ""
			}.peer)
			border = Swing.EtchedBorder
			peer.setKeymap(inputKm)
		}
		input.maximumSize = new java.awt.Dimension(Integer.MAX_VALUE, 24)
		input.preferredSize = new java.awt.Dimension(Integer.MAX_VALUE, 24)
		val km = input.peer.getKeymap
		contents = new BoxPanel(Orientation.Vertical) {
			contents += output
			contents += input
		}
		listenTo(input)
		size = (400, 300)
		open
	}

	def main(args: Array[String]) {
		if (args.length == 1 || args(1) != "clean") {
			val file = new java.io.File("/tmp/" + args(0) + ".props")

			readStorage(file)
			if (!file.exists) {
				genId
			}
		}
		println("my peer id: " + str(peerId));
		if (args(0) == "server") {
			server(Integer.parseInt(args(1)))
		} else {
			client(Integer.parseInt(args(1)))
		}
	}
	override def receive(msg: Direct) {
		val joinReq = msg.payload(0)
		val space = msg.int("space")(joinReq)
		val topic = msg.int("topic")(joinReq)
		val pubKey = msg.string("pubkey")(joinReq)
		val peerCon = msg.con.asInstanceOf[PeerConnection]

		peerCon.setKey(bytesFor(pubKey))
		if (isServer) {
			println("peer joined space: " + space + ", topic: " + topic + ", id: " + str(peerCon.peerId));
			topicsOwned((msg.int("space")(joinReq), msg.int("topic")(joinReq))).addMember(peerCon)
			peerCon.sendDirect(<welcome space={space.toString} topic={topic.toString} pubKey={publicKeyString}/>)
		} else {
			println("connected to space: " + str(peerCon.peerId));
		}
//		println("Received direct message: " + msg.node)
	}
	def server(port: Int) {
		isServer = true
		Swing.onEDT {frame.title = "Server Chat " + peerId}
		topicsOwned((0, 0)) = new Topic(0, 0, this)
		val chatTopic = new ChatTopic(0, 1)
		topicsOwned((0, 1)) = chatTopic
		topicsJoined((0, 1)) = chatTopic
		chatTopic.addMember(selfConnection)
		con = selfConnection.con
		Acceptor.listen(port, this)
	}
	def client(port: Int) {
		Swing.onEDT {frame.title = "Client Chat " + peerId}
		topicsJoined((0, 1)) = new ChatTopic(0, 1)
		con = addConnection(SimpyPacketConnection("localhost", port, this))
		peerConnections(con).sendDirect(<join space="0" topic="1" pubKey={publicKeyString}/>)
	}
	class ChatTopic(override val space: Int, override val topic: Int) extends Topic(space, topic, this) {
		override def basicReceive(msg: SpaceToPeerMessage) {
			val doc = frame.output.peer.getDocument

			doc.insertString(doc.getLength, msg.payload.mkString + "\n", null)
			println("Basic receive: " + msg)
		}
	}
}