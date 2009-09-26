package xus.comm

import java.nio.ByteBuffer
import java.nio.channels._
import scala.collection.mutable.ArrayStack
import scala.collection.mutable.{Map => MMap}
import scala.actors.Actor._
import scala.actors.TIMEOUT

object Connection {
	val buffers = new ArrayStack[ByteBuffer]
	val BUFFER_SIZE = 1024 * 1024
	val selector = Selector.open
	val connections = MMap[SelectableChannel, Connection[_ <: SelectableChannel]]()
	var waitTime = Integer.parseInt(System.getProperty("xus.waitTime", "1000"))
	val selectorThread = actor {
		self link Util.actorExceptions
		loop {
			receiveWithin(if (selector.keys.isEmpty) 1000 else 1) {
			case con: Connection[_] =>
			con.register 
			connections(con.chan) = con
			case TIMEOUT =>
			case other =>
			}
			if (!selector.keys.isEmpty) {
				val count = selector.select()
				val keys = selector.selectedKeys.iterator
				
				while (keys.hasNext) {
					val k = keys.next
					
					keys.remove
					if (k.isValid) {
						connections(k.channel).handle(k)
					}
				}
			}
		}
	}

	def guard(block: => Unit) {
		try {
			block
		} catch {
			case ex: Exception => ex.printStackTrace
		}
	}
	def queue(obj: Any) {
		selectorThread ! obj
		selector.wakeup
	}
	def newBuffer = {
		buffers synchronized {
			if (buffers.isEmpty) {
				ByteBuffer.allocateDirect(BUFFER_SIZE)
			} else {
				buffers.pop
			}
		}
	}
	def release(buf: ByteBuffer) {
		if (buf.capacity == BUFFER_SIZE) {
			buf.clear
			buffers synchronized {
				buffers.push(buf)
			}
		}
	}
	def addConnection[T](newCon: T) = {
		queue(newCon)
		newCon
	}
}

abstract class Connection[CHAN <: SelectableChannel](val chan: CHAN) {
	def register

	def close {
		chan.keyFor(Connection.selector).cancel
	}
	def handle(key: SelectionKey) = ()
}
