/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import java.net._
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.channels._
import scala.actors.TIMEOUT
import scala.actors.Exit
import scala.actors.Actor
import scala.actors.Actor._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ArrayStack
import scala.collection.JavaConversions._
import scala.collection.mutable.{Map => MMap}

object Connection {
	val buffers = new ArrayStack[ByteBuffer]
	val BUFFER_SIZE = 1024 * 1024
	val selector = Selector.open
	val connections = MMap[SelectableChannel, Connection[_ <: SelectableChannel]]()
	var waitTime = Integer.parseInt(System.getProperty("xus.waitTime", "1000"))
	val actorExceptions = actor {
		self.trapExit = true
		react {
			case Exit(from: Actor, ex: Exception) =>
				ex.printStackTrace
		}
	}
	val selectorThread = actor {
		self link actorExceptions
		loop {
			receiveWithin(1) {
			case con: Connection[_] =>
			con.register 
			connections(con.chan) = con
			case TIMEOUT =>
			case other =>
			}
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
object SimpyPacketConnection {
	import Connection._

	def apply(connection: SocketChannel, peer: SimpyPacketPeerProtocol, optAcceptor: Option[Acceptor]) = new SimpyPacketConnection(connection, peer, optAcceptor)
	def apply(host: String, port: Int, peer: SimpyPacketPeerProtocol): SimpyPacketConnection = {
		val chan = SocketChannel.open

		chan.configureBlocking(false)
		val con = addConnection(this(chan, peer, None))
		chan.connect(new InetSocketAddress(host, port))
		con
	}
}

abstract class Connection[CHAN <: SelectableChannel](val chan: CHAN) {
	def register

	def close {
		chan.keyFor(Connection.selector).cancel
	}
	def handle(key: SelectionKey) = ()
}
object foo {
	var tot = 0
}
class SimpyPacketConnection(clientChan: SocketChannel, peer: SimpyPacketPeerProtocol, optAcceptor: Option[Acceptor] = None) extends Connection[SocketChannel](clientChan) with SimpyPacketConnectionProtocol {
	import Connection._
	var msgId = -1
	val outputLenBuf = new Array[Byte](4)
	val inputLenBuf = new Array[Byte](4)
	val output = new ArrayBuffer[ByteBuffer]
	val input = ByteBuffer.allocate(BUFFER_SIZE)
	val partialInput = new ByteArrayOutputStream {
		def bytes = buf
		def setSize(newSize: Int) {
			count = newSize
		}
	}
	val outputActor = actor {
		self link actorExceptions
		loop {
			react {
			case 1 => writeOutput
			case (newOutput: Array[Byte], offset: Int, len: Int) => doSend(newOutput, offset, len)
			case 0 => doClose
			case _ if !chan.isOpen => doClose
			case x => println("unhandled case: " + x)
			}
		}
	}
	val inputActor = actor {
		self link actorExceptions
		loop {
			react {
			case 0 => exit
			case _ if !chan.isOpen => outputActor ! 0
			case 1 => readInput
			}
		}
	}

	def nextOutgoingMsgId = {
		msgId += 1
		msgId
	}
	def register {
		chan.configureBlocking(false)
		chan.register(selector, SelectionKey.OP_READ | /* SelectionKey.OP_WRITE | */ (if (optAcceptor == None) SelectionKey.OP_CONNECT else 0))
	}
	def send(newOutput: Array[Byte], offset: Int, len: Int) {outputActor ! (newOutput, offset, len)}
	def doSend(newOutput: Array[Byte], offset: Int, len: Int) {
		if (output.isEmpty) {
			output.append(newBuffer)
		} else {
			output.last.compact
			if (output.last.remaining < 5) {
				output.last.flip
				output.append(newBuffer)
			}
		}
		putLen(len)
		((offset + output.last.remaining until offset + len by BUFFER_SIZE) ++ List(offset + len)).foldLeft(offset) {(s1, s2) =>
			output.last.put(newOutput, s1, s2 - s1)
			output.last.flip
			output.append(newBuffer)
			s2
		}
		output.last.flip
		if (output.last.remaining == 0) {
			output.remove(output.size - 1)
		}
		writeOutput
	}
	def writeOutput {
		while (!output.isEmpty && output.last.remaining > 0) {
			chan.write(output.toArray)
			while (!output.isEmpty && output(0).remaining == 0) {
				release(output.remove(0))
			}
		}
	}
	def readInput {
		chan.read(input) match {
		case -1 => outputActor ! 0
		case 0 =>
		case _ => 
			partialInput.write(input.array, 0, input.position)
			input.clear
			processInput
		}
	}
	override def close {
		outputActor ! 0
		super.close
	}
	def doClose {
		inputActor ! 0
		try {chan.socket.shutdownInput} catch {case _ =>}
		try {chan.socket.shutdownOutput} catch {case _ =>}
		try {chan.close} catch {case _ =>}
		for (a <- optAcceptor) {
			a.remove(this)
		}
	}
	def processInput {
		if (partialInput.size > 4) {
			val size = getSize
			val totalInput = partialInput.size

			if (size + 4 <= totalInput) {
				val bytes = partialInput.bytes

				peer.receiveInput(this, bytes, 4, size)
				if (totalInput > size + 4) {
					System.arraycopy(bytes, size + 4, bytes, 0, totalInput - size - 4)
					partialInput.setSize(totalInput - size - 4)
				} else {
					partialInput.reset
				}
			}
		}
	}
	def putLen(size: Int) {
		outputLenBuf(0) = (size & 0xFF).asInstanceOf[Byte]
		outputLenBuf(1) = ((size >> 8) & 0xFF).asInstanceOf[Byte]
		outputLenBuf(2) = ((size >> 16) & 0xFF).asInstanceOf[Byte]
		outputLenBuf(3) = ((size >> 24) & 0xFF).asInstanceOf[Byte]
		output.last.put(outputLenBuf)
	}
	def getSize = {
		val bytes = partialInput.bytes

		(bytes(3) << 24) | (bytes(2) << 16) | (bytes(1) << 8) | bytes(0)
	}
	override def handle(key: SelectionKey) = {
		if (key.isReadable) {
			if (!chan.isOpen) {
				inputActor ! 0
			} else if (chan.isConnected) {
				inputActor ! 1
			}
		}
		if (key.isWritable) outputActor ! 1
		if (key.isConnectable && chan.isConnectionPending) chan.finishConnect()
		super.handle(key)
	}
}