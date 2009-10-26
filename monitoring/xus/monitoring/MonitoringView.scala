package xus.monitoring

import xus.comm._
import Util._
import scala.collection.immutable.Set
import scala.swing._
import java.awt.Color
import java.awt.SystemColor
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import java.awt.geom.Line2D

/**
 * Eventually, this should show monintoring for several spaces
 */
object MonitoringView {
	var myMaster: PeerRec = null
	var topics = Map[(Int, Int), TopicRec]()
	var connections = Map[BigInt, PeerRec]()
	val peerRect = new RoundRectangle2D.Float(0, 0, 64, 24, 10, 10)
	val stroke = new BasicStroke(2)

	val frame = new MainFrame {
		import javax.swing.KeyStroke
		import javax.swing.text.JTextComponent

		val diagram = new Panel {
			override def paintComponent(g: Graphics2D) {
				val r = peer.getBounds()
				val peerDiam = 32
				val midX = (r.width + 1) / 2
				val midY = (r.height + 1) / 2

//				g.setPaint(Color.blue)
				g.setPaint(SystemColor.control)
				g.fillRect(r.x, r.y, r.width, r.height)
				g.setPaint(Color.black)
				g.setStroke(stroke)
				val cons = List.fromIterator(connections.valuesIterator).sort((a, b) => a.peerId <= b.peerId)
				for (rot <- 0 until connections.size) {
					drawPeer(rot, g, midX, midY, peerDiam, cons(rot))
				}
				if (myMaster != null) {
					drawPeerShape(g, midX, midY, myMaster)
				}
			}
			
			def xFor(num: Int, diam: Int, midX: Int) = round(Math.cos(Math.Pi * 2 * num / connections.size) * diam) + midX

			def yFor(num: Int, diam: Int, midY: Int) = round(Math.sin(Math.Pi * 2 * num / connections.size) * diam) + midY
			
			def round(d: Double) = Math.round(d).asInstanceOf[Int]

  			def drawPeer(n: Int, g: Graphics2D, midX: Int, midY: Int, peerDiam: Int, peer: PeerRec) {
				val diam = Math.min(midX, midY) - peerDiam
  				val x = xFor(n, diam, midX)
  				val y = yFor(n, diam, midY)

  				g.draw(new Line2D.Float(midX, midY, x, y))
  				drawPeerShape(g, x, y, peer)
  			}

			def drawPeerShape(g: Graphics2D, x: Int, y: Int, peer: PeerRec) {
				val s = str(peer.peerId)
				val met = g.getFontMetrics
				val lmet = g.getFontMetrics.getLineMetrics(s, g)
				val sb = met.getStringBounds(s, g)

//				peerRect.setRoundRect(x - (peerRect.getWidth + 1) / 2, y - (peerRect.getHeight + 1) / 2, peerRect.getWidth, peerRect.getHeight, peerRect.getArcWidth, peerRect.getArcHeight)
				peerRect.setRoundRect(x - (sb.getWidth + 1) / 2 - 5, y - (sb.getHeight + 1) / 2 - 5, sb.getWidth + 10, sb.getHeight + 10, peerRect.getArcWidth, peerRect.getArcHeight)
//				g.setPaint(SystemColor.control)
				g.setPaint(Color.white)
				g.fill(peerRect)
				g.setPaint(Color.black)
				g.draw(peerRect)
				g.drawString(s, round(x - sb.getWidth / 2), round(y + sb.getHeight / 2) - met.getDescent)
			}
		}
		val output = new EditorPane {
			editable = false
		}
		
		contents = diagram
//		contents = new SplitPane(Orientation.Horizontal, diagram, output) {
//			dividerLocation = 0.75
//		}
		size = (800, 600)
		open
	}

	def show = {
		frame.visible = true
	}

	def addConnection(peerid: BigInt, ip: String) {
		if (myMaster == null || myMaster.peerId != peerid) {
			connections += peerid -> new PeerRec(peerid, ip)
			update
			println("Add connection: "+str(peerid)+": "+ip)
		}
	}

	def removeConnection(peerid: BigInt) {
		connections -= peerid
		update
		println("Remove connection: "+str(peerid))
	}

	def joinTopic(peerid: BigInt, space: Int, topic: Int) {
		val tp = topics.getOrElse((space, topic), {
			val t = new TopicRec(space, topic)

			topics += (space, topic) -> t
			t
		})
		for (peerRec <- connections.get(peerid)) peerRec.addTopic(tp)
		println("Join topic peer: "+str(peerid)+", space: "+space+", topic: "+topic)
		update
	}
	
	def update = frame.diagram.peer.repaint()

	def setMaster(con: PeerConnection) {
		myMaster = new PeerRec(con.peerId, str(con.address))
		update
	}
}

class TopicRec(val space: Int, val topic: Int) {
	var master: PeerRec = null
	var owners = Set[PeerRec]()
	var members = Set[PeerRec]()
}

class PeerRec(val peerId: BigInt, val addr: String) {
	var topics = Set[TopicRec]()

	def addTopic(topic: TopicRec) = topics += topic
}
