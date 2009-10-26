package xus.monitoring

import xus.comm._
import Util._
import scala.collection.immutable.Set
import scala.swing._
import java.awt.Color
import java.awt.Graphics2D

object MonitoringView {
	var myMaster: PeerRec = null
	var topics = Map[(Int, Int), TopicRec]()
	var connections = Map[BigInt, PeerRec]()

	val frame = new MainFrame {
		import javax.swing.KeyStroke
		import javax.swing.text.JTextComponent

		val diagram = new Panel {
			override def paintComponent(g: Graphics2D) {
				val r = bounds

				g.setPaint(Color.blue)
				g.fillRect(r.x, r.y, r.width, r.height)
			}
		}
		val output = new EditorPane {
			editable = false
		}

		contents = new SplitPane(Orientation.Vertical, diagram, output) {
			dividerLocation = 0.75
		}
		size = (400, 300)
	}

	def show = {
		frame.visible = true
	}
	def addConnection(peerid: BigInt, ip: String) {
		println("Add connection: "+str(peerid)+": "+ip)
	}
	def removeConnection(peerid: BigInt) {
		println("Remove connection: "+str(peerid))
	}
	def joinTopic(peerid: BigInt, space: Int, topic: Int) {
		println("Join topic peer: "+str(peerid)+", space: "+space+", topic: "+topic)
	}
}

class TopicRec(val space: Int, val topic: Int) {
	var master: PeerRec = null
	var owners = Set[PeerRec]()
	var members = Set[PeerRec]()
}

class PeerRec(var addr: String, var master: Boolean, var owner: Boolean) {
	var topics = Set[TopicRec]()
}
