/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.testing

import scala.io.Source
import scala.xml.Node
import xus.comm._
import xus.monitoring._
import scala.actors.Actor._
import scala.swing._
import scala.swing.Action
import scala.util.Random
import Peer._

object IM extends Peer("IM") {
	import xus.comm.Util._

	var isServer = false
	var topic: TopicConnection = null
	var chat: ChatConnection = null
	var frame = new MainFrame {
		import javax.swing.KeyStroke
		import javax.swing.text.JTextComponent

		val output = new EditorPane
		val input = new EditorPane {
			val inputKm = JTextComponent.addKeymap("input", JTextComponent.getKeymap(JTextComponent.DEFAULT_KEYMAP))
			inputKm.addActionForKeyStroke(KeyStroke.getKeyStroke("ENTER"), Action("send") {
				println("TOPIC: "+topic)
				chat.sendChat(text) {response => println("RECEIVED RESPONSE: "+response)}
				text = ""
			}.peer)
			border = Swing.EtchedBorder
			peer.setKeymap(inputKm)
		}
		input.maximumSize = new java.awt.Dimension(Integer.MAX_VALUE, 24)
		input.preferredSize = new java.awt.Dimension(0, 24)
		contents = new BoxPanel(Orientation.Vertical) {
			contents += new ScrollPane(output)
			contents += input
		}
		size = (400, 300)
		open
		input.peer.grabFocus
	}

	def main(args: Array[String]) {
		genId
		if (args.length < 3 || args(2) != "clean") {
			val file = new java.io.File("/tmp/" + args(0) + ".props")

			readStorage(file)
		}
		println("my peer id: " + str(peerId));
		if (args(0) == "server") {
			server(args(1).toInt)
		} else {
			client(args(1).toInt)
		}
	}
	def server(port: Int) {
		isServer = true
		Swing.onEDT {frame.title = "Server Chat " + str(peerId)}
		own(-1, -1, null).support(Monitoring)
		own(0, 0)
		topic = new TopicConnection(0, 1, selfConnection)
		own(0, 1, topic).support(Chat)
		Acceptor.listen(port, this)
	}
	def client(port: Int) {
		Swing.onEDT {frame.title = "Client Chat " + str(peerId)}
		connect("localhost", port, 0) {m =>
			println("Connected")
//			topic = join(new ChatTopicConnection(0, 1, m.con))
			topic = join(0, 1, m.con)
			join(-1, -1, m.con)
		}
	}
	override def basicReceive(msg: Message) {
		println(msg)
	}
	object Chat extends ServiceFactory[ChatConnection, ServiceMaster] {
		def createConnection(con: TopicConnection) = {
			chat = new ChatConnection(con)
			chat
		}
		def createMaster(master: TopicMaster) = new ServiceMaster(master)
	}
	class ChatConnection(topic: TopicConnection) extends ServiceConnection(topic) {
		def sendChat(msg: String)(implicit block: (Response) => Unit) = topic.broadcast(Chat(<chat>{msg}</chat>))(block)

		override def receive(msg: DelegatedBroadcast, node: Node) {
			for (n <- node.child) n match {
			case <chat>{contents}</chat> =>
				val doc = frame.output.peer.getDocument

				doc.insertString(doc.getLength, contents + "\n", null)
				println("Basic receive: " + msg)
			}
		}
	}
//	class ChatTopicConnection(override val space: Int, override val topic: Int, con: PeerConnection) extends TopicConnection(space, topic, con) {
//		override def basicReceive(msg: SpaceToPeerMessage) {
//			val doc = frame.output.peer.getDocument
//
//			doc.insertString(doc.getLength, msg.payload.mkString + "\n", null)
//			println("Basic receive: " + msg)
//		}
//	}
}