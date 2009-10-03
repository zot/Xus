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
import Peer._

object IM extends Peer("IM") {
	import xus.comm.Util._

	var isServer = false
	var topic: TopicConnection = null
	var frame = new MainFrame {
		import javax.swing.KeyStroke
		import javax.swing.text.JTextComponent

		val output = new EditorPane {
			border = Swing.EtchedBorder
		}
		val input = new EditorPane {
			val inputKm = JTextComponent.addKeymap("input", JTextComponent.getKeymap(JTextComponent.DEFAULT_KEYMAP))
			inputKm.addActionForKeyStroke(KeyStroke.getKeyStroke("ENTER"), Action("send") {
				topic.broadcast(text)
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
	def server(port: Int) {
		isServer = true
		Swing.onEDT {frame.title = "Server Chat " + peerId}
		topic = new ChatTopicConnection(0, 0, selfConnection)
		own(0, 0)
		own(0, 1, topic)
		Acceptor.listen(port, this)
	}
	def client(port: Int) {
		Swing.onEDT {frame.title = "Client Chat " + peerId}
		connect("localhost", port) {m =>
			println("Connected")
			topic = join(new ChatTopicConnection(0, 1, m.con))
		}
	}
	class ChatTopicConnection(override val space: Int, override val topic: Int, con: PeerConnection) extends TopicConnection(space, topic, con) {
		override def basicReceive(msg: SpaceToPeerMessage) {
			val doc = frame.output.peer.getDocument

			doc.insertString(doc.getLength, msg.payload.mkString + "\n", null)
			println("Basic receive: " + msg)
		}
	}
}