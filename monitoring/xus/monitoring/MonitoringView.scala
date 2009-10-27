package xus.monitoring

import xus.comm._
import Util._
import scala.collection.immutable.Set
import scala.swing._
import scala.swing.event.MouseClicked
import java.awt.Color
import java.awt.SystemColor
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.geom.Arc2D
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.Component

class PanelProps(panel: Component) {
	val minId = BigInt(Array[Byte](-128,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0))
	val maxId = BigInt(Array[Byte](127,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1))
	var nodeRec = new RoundRectangle2D.Double(0, 0, 64, 24, 10, 10)
	val nodeRadius = 6
	val peerDiam = 32
	val r = panel.getBounds()
	val midX = (r.width + 1) / 2
	val midY = (r.height + 1) / 2
	val diam = Math.min(midX, midY) - peerDiam
	val innerRadius = diam - 30
	val node = new Arc2D.Float(-nodeRadius, -nodeRadius, nodeRadius * 2, nodeRadius * 2, 0, 360, Arc2D.OPEN)
	var stringX: Int = 0
	var stringY: Int = 0

	def getBounds(peer: PeerRec, g: Graphics2D) {
		val angle = angleFor(peer)

		getBounds(xFor(angle, diam), yFor(angle, diam), peer, g)
	}
	def getBounds(x: Int, y: Int, peer: PeerRec, g: Graphics2D) {
		val s = str(peer.peerId)
		val met = g.getFontMetrics
		val lmet = g.getFontMetrics.getLineMetrics(s, g)
		val sb = met.getStringBounds(s, g)

		nodeRec = new RoundRectangle2D.Double(x - (sb.getWidth + 1) / 2 - 5, y - (sb.getHeight + 1) / 2 - 5, sb.getWidth + 10, sb.getHeight + 10, nodeRec.getArcWidth, nodeRec.getArcHeight)
		stringX = round(x - sb.getWidth / 2)
		stringY = round(y + sb.getHeight / 2) - met.getDescent
	}

	def nodePos(con: PeerRec) = {
		val angle = angleFor(con)

		(xFor(angle, innerRadius), yFor(angle, innerRadius))
	}

	def round(d: Double) = Math.round(d).asInstanceOf[Int]

	def xFor(angle: Double, diam: Int) = round(Math.cos(Math.Pi * 2 * angle) * diam) + midX

	def yFor(angle: Double, diam: Int) = round(Math.sin(Math.Pi * 2 * angle) * diam) + midY

	def angleFor(con: PeerRec) = ((con.peerId.abs * 10000 / maxId) * con.peerId.signum).intValue / 10000.0
}
/**
 * Eventually, this should show monintoring for several spaces
 */
object MonitoringView {
	var myMaster: PeerRec = null
	var topics = Map[(Int, Int), TopicRec]()
	var connections = Map[BigInt, PeerRec]()
	var zorder = List[PeerRec]()
	val stroke = new BasicStroke(2)
	val minId = BigInt(Array[Byte](-128,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0))
	val maxId = BigInt(Array[Byte](127,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1))
	var selected: PeerRec = null

	val frame = new MainFrame {
		import javax.swing.KeyStroke
		import javax.swing.text.JTextComponent

		val diagram = new Panel {
			override def paintComponent(g: Graphics2D) {
				val props = new PanelProps(peer)

				g.setPaint(SystemColor.control)
				g.fillRect(props.r.x, props.r.y, props.r.width, props.r.height)
				g.setPaint(Color.black)
				g.setStroke(stroke)
				for (con <- zorder.reverse) {
					drawPeer(g, props, con)
				}
				drawPeer(props.midX, props.midY, g, props, myMaster)
				g.draw(new Arc2D.Float(props.midX - props.innerRadius, props.midY - props.innerRadius, props.innerRadius * 2, props.innerRadius * 2, 0, 360, Arc2D.OPEN))
				for (con <- zorder.reverse) {
					val angle = props.angleFor(con)
					val (offsetX, offsetY) = props.nodePos(con)

					g.setPaint(if (con == selected) Color.lightGray else Color.black)
					g.translate(offsetX, offsetY)
					g.fill(props.node)
					g.translate(-offsetX, -offsetY)
				}
			}

  			def drawPeer(g: Graphics2D, props: PanelProps, peer: PeerRec) {
				val angle = props.angleFor(peer)
  				val x = props.xFor(angle, props.diam)
  				val y = props.yFor(angle, props.diam)

//				g.drawLine(props.midX, props.midY, x, y)
  				drawPeer(x, y, g, props, peer)
  			}
  			def drawPeer(x: Int, y: Int, g: Graphics2D, props: PanelProps, peer: PeerRec) {
				val s = str(peer.peerId)

				props.getBounds(x, y, peer, g)
				if (peer == selected) {
					g.setPaint(if (peer == myMaster) Color.red else Color.white)
				} else {
					g.setPaint(if (peer == myMaster) Color.pink else Color.lightGray)
				}
				g.fill(props.nodeRec)
				g.setPaint(Color.black)
				g.draw(props.nodeRec)
				g.drawString(s, props.stringX, props.stringY)
			}
		}
		val output = new EditorPane {
			editable = false
		}
		
		listenTo(diagram.Mouse.clicks)
		reactions += {
		case MouseClicked(_ , pt, _, _, _) =>
			var min = Math.MAX_INT
			var minCon: PeerRec = null
			val props = new PanelProps(diagram.peer)
			
			for (con <- zorder) {
				var (x, y) = props.nodePos(con)

				x -= pt.x
				y -= pt.y
				val dist = x * x + y * y
				if (dist < min) {
					min = dist
					minCon = con
				}
			}
			if (min > props.nodeRadius * props.nodeRadius) {
				def checkNode(con: PeerRec) {
					if (con == myMaster) {
						props.getBounds(props.midX, props.midY, con, diagram.peer.getGraphics.asInstanceOf[Graphics2D])
					} else {
						props.getBounds(con, diagram.peer.getGraphics.asInstanceOf[Graphics2D])
					}
					if (props.nodeRec.contains(pt.x, pt.y) && min >= 0) {
						min = -1
						minCon = con
					}
				}
				for (con <- zorder) checkNode(con)
				checkNode(myMaster)
			}
			if (min <= props.nodeRadius * props.nodeRadius && selected != minCon) {
				deselect(selected)
				selected = minCon
				select(selected)
				if (minCon != myMaster) {
					zorder = minCon :: (zorder - minCon)
				}
			} else {
				if (selected != null) {
					deselect(selected)
				}
				selected = null
				select(selected)
			}
			update
		}
		contents = diagram
		size = (800, 600)
		open
	}
	
	def deselect(peer: PeerRec) {
	}

	def select(peer: PeerRec) {
	}

	def show = {
		frame.visible = true
	}

	def addConnection(peerid: BigInt, ip: String) {
		Swing.onEDT {
			if (myMaster == null || myMaster.peerId != peerid) {
				val rec = new PeerRec(peerid, ip)

				connections += peerid -> rec
				zorder ::= rec
				update
				println("Add connection: "+str(peerid)+": "+ip)
			}
		}
	}

	def removeConnection(peerid: BigInt) {
		Swing.onEDT {
			connections -= peerid
			update
			println("Remove connection: "+str(peerid))
		}
	}

	def joinTopic(peerid: BigInt, space: Int, topic: Int) {
		Swing.onEDT {
			val tp = topics.getOrElse((space, topic), {
				val t = new TopicRec(space, topic)
	
				topics += (space, topic) -> t
				t
			})
			for (peerRec <- connections.get(peerid)) peerRec.addTopic(tp)
			println("Join topic peer: "+str(peerid)+", space: "+space+", topic: "+topic)
			update
		}
	}
	
	def update {frame.diagram.peer.repaint()}

	def setMaster(con: PeerConnection) {
		Swing.onEDT {
			myMaster = new PeerRec(con.peerId, str(con.address))
			frame.title = "Monitoring " + str(con.peerId)
//			zorder ::= myMaster
			update
		}
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
