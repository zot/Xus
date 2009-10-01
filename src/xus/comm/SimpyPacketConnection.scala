/*******
 * Copyright (c) 2009 Bill Burdick and the TEAM CTHULHU development team
 * Licensed under the ZLIB license: http://www.opensource.org/licenses/zlib-license.php
 *******/

package xus.comm

import java.net._
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels._
import scala.actors.Actor._
import scala.collection.mutable.ArrayBuffer
import Util._

/**
 * SimpyPacketConnection is a layer 1 connection to a peer
 */
trait SimpyPacketConnectionAPI {
	//outgoing message id starts at 0 and increments with each call
	def nextOutgoingMsgId: Int
	def send(bytes: Array[Byte], offset: Int, len: Int): Unit
	def send(bytes: Array[Byte]) {send(bytes, 0, bytes.length)}
}
trait SimpyPacketPeerAPI {
	def receiveInput(con: SimpyPacketConnectionAPI, bytes: Array[Byte], offset: Int, length: Int): Unit
	def closed(con: SimpyPacketConnectionAPI)
}

object SimpyPacketConnection {
	import Connection._
	
	def apply(connection: SocketChannel, peer: SimpyPacketPeerAPI, optAcceptor: Option[Acceptor]) = new SimpyPacketConnection(connection, peer, optAcceptor)
	def apply(host: String, port: Int, peer: SimpyPacketPeerAPI): SimpyPacketConnection = {
		val chan = SocketChannel.open
		
		chan.configureBlocking(false)
		val con = addConnection(this(chan, peer, None))
		chan.connect(new InetSocketAddress(host, port))
		con
	}
}

class DirectSimpyPacketConnection(peer: SimpyPacketPeerAPI, var otherEnd: DirectSimpyPacketConnection) extends SimpyPacketConnectionAPI {
	var msgId = -1

	if (otherEnd == null) {
		otherEnd = this
	} else {
		otherEnd.otherEnd = this
	}

	def this(peer: SimpyPacketPeerAPI) = this(peer, null.asInstanceOf[DirectSimpyPacketConnection])
	def this(peer: SimpyPacketPeerAPI, otherPeer: SimpyPacketPeerAPI) = this(peer, new DirectSimpyPacketConnection(otherPeer))
	def nextOutgoingMsgId: Int = {
		msgId += 1
		msgId
	}
	def send(bytes: Array[Byte], offset: Int, len: Int) = otherEnd.receive(bytes, 0, bytes.length)
	def receive(bytes: Array[Byte], offset: Int, len: Int) = peer.receiveInput(this, bytes, 0, bytes.length)
}

class SimpyPacketConnection(clientChan: SocketChannel, peer: SimpyPacketPeerAPI, optAcceptor: Option[Acceptor] = None) extends Connection[SocketChannel](clientChan) with SimpyPacketConnectionAPI {
	import Connection._
	var msgId = -1
	val outputLenBuf = new Array[Byte](4)
	val inputLenBuf = new Array[Byte](4)
	val output = new ArrayBuffer[ByteBuffer]
	val input = ByteBuffer.allocate(BUFFER_SIZE)
	var closed = false
	val partialInput = new ByteArrayOutputStream {
		def bytes = buf
		def setSize(newSize: Int) {
			count = newSize
		}
	}
	val outputActor = daemonActor("Connection output") {
		self link Util.actorExceptions
		loop {
			react {
			case 1 => writeOutput
			case newOutput: Array[Byte] => doSend(newOutput, 0, newOutput.length)
			case (newOutput: Array[Byte], offset: Int, len: Int) => doSend(newOutput, offset, len)
			case 0 => doClose
			case _ if !chan.isOpen => doClose
			case x => new Exception("unhandled case: " + x).printStackTrace
			}
		}
	}
	val inputActor = daemonActor("Connection input") {
		self link Util.actorExceptions
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
	def send(newOutput: Array[Byte], offset: Int, len: Int) {outputActor ! newOutput.slice(offset, offset + len)}
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
//			if (chan.isConnected) {
				chan.write(output.toArray)
				while (!output.isEmpty && output(0).remaining == 0) {
					release(output.remove(0))
				}
//			} else {
//				Thread.sleep(100)
//			}
		}
	}
	def readInput {
		try {
			var last = 1
			
			while (last > 0) {
				last = chan.read(input)
				last match {
				case -1 => close
				case 0 =>
				case _ => 
					partialInput.write(input.array, 0, input.position)
					input.clear
				}
			}
			processInput
		} catch {
		case _: java.nio.channels.ClosedChannelException => close
		}
	}
	override def close {
		outputActor ! 0
		super.close
	}
	def doClose {
		if (!closed) {
			closed = true
			inputActor ! 0
			try {chan.socket.shutdownInput} catch {case _ =>}
			try {chan.socket.shutdownOutput} catch {case _ =>}
			try {chan.close} catch {case _ =>}
			for (a <- optAcceptor) {
				a.remove(this)
			}
			peer.closed(this)
		}
		exit
	}
	def processInput {
		while (partialInput.size > 4) {
			val totalInput = partialInput.size
			val size = getSize

			if (size + 4 <= totalInput) {
				val bytes = partialInput.bytes

				peer.receiveInput(this, bytes, 4, size)
				if (size + 4 == totalInput) {
					partialInput.reset
				} else {
					System.arraycopy(bytes, size + 4, bytes, 0, totalInput - size - 4)
					partialInput.setSize(totalInput - size - 4)
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

		((bytes(3) & 0xff) << 24) | ((bytes(2) & 0xff) << 16) | ((bytes(1) & 0xff) << 8) | (bytes(0) & 0xff)
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