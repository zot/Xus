package xus.monitoring

import xus.comm._
import scala.swing._
import java.awt.Color
import java.awt.Graphics2D

object MonitoringView {
	var myMaster: PeerRec = null
	var topics = Map[(Int, Int), TopicRec]()

	val frame = new MainFrame {
		import javax.swing.KeyStroke
		import javax.swing.text.JTextComponent

		val diagram = new MonitoringDiagram
		val output = new EditorPane {
			editable = false
		}
//		val

		contents = new SplitPane(Orientation.Vertical, diagram, output) {
			dividerLocation = 0.75
		}
		size = (400, 300)
	}

	def show = {
		frame.visible = true
	}
}

class TopicRec(val space: Int, val topic: Int) {
	var master: PeerRec = null
	var owners = Set[PeerRec]()
	var members = Set[PeerRec]()
}

class PeerRec(var name: String, var master: Boolean, var owner: Boolean)

class MonitoringDiagram extends Panel {
	val peerDiam = 64

	override def paintComponent(g: Graphics2D) {
		val r = bounds

		g.setPaint(Color.blue)
		g.fillRect(r.x, r.y, r.width, r.height)
	}
}
