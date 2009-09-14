package xus

import java.net._
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.channels._
import scala.actors.Actor._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ArrayStack
import scala.collection.mutable.ByteArrayVector
import scala.collection.JavaConversions._
import scala.collection.mutable.{Map => MMap}

object Connection {
	val buffers = new ArrayStack[ByteBuffer]
	val BUFFER_SIZE = 1024 * 1024
	val selector = Selector.open
	val connections = MMap[SelectableChannel, Connection[_]]()
	var waitTime = 1000
	val selectorThread = new Thread("Selector Thread") {
		override def run {
			try {
				while (true) {
					if (selector.select(waitTime) > 0) {
						for (key <- selector.selectedKeys) key match {
							case _ => connections(key.channel).handle(key)
						}
					}
				}
			} catch {
			case e => e.printStackTrace
			}
		}
	}
	
	def start {
		selectorThread.setDaemon(true)
		selectorThread.start
	}
	def get = {
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
}
object ClientConnection extends Connection {
	def apply(connection: SocketChannel, optAcceptor: Option[Acceptor])(inputHandler: (Array[Byte], Int, Int) => Unit) = new ClientConnection(connection, optAcceptor) {
		def handleInput(bytes: Array[Byte], offset: Int, length: Int) = inputHandler(bytes, offset, length)
	}
	def apply(host: String, port: Int)(inputHandler: (Array[Byte], Int, Int) => Unit) = {
		val chan = SocketChannel.open

		chan.configureBlocking(false)
		chan.register(selector, SelectionKey.OP_READ | SelectionKey.OP_READ | SelectionKey.OP_CONNECT)
		chan.connect(new InetSocketAddress(host, port))
		new ClientConnection(chan)(inputHandler)
	}
}

abstract class Connection[CHAN <: SelectableChannel](val chan: CHAN) {
	def close

	def handle(key: SelectionKey) = key match {
	case _ if !key.isValid => close
	}
}
abstract class ClientConnection(val clientChan: SocketChannel, optAcceptor: Option[Acceptor] = None) extends Connection[SocketChannel](clientChan) {
	def handleInput(bytes: Array[Byte], offset: Int, length: Int): Unit

	import Connection._
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
		loop {
			react {
			case 0 => doClose
			case _ if !chan.isOpen => doClose
			case 1 => writeOutput
			case (newOutput: Array[Byte], size: Int) => doAddOutput(newOutput, size)
			}
		}
	}
	val inputActor = actor {
		loop {
			react {
			case 0 => exit
			case _ if !chan.isOpen => outputActor ! 0
			case 1 => readInput
			}
		}
	}

	def addOutput(newOutput: Array[Byte]) = outputActor ! newOutput
	def doAddOutput(newOutput: Array[Byte], size: Int) {
		if (output.isEmpty) {
			output.append(get)
		} else {
			output.last.compact
			if (output.last.remaining < 5) {
				output.last.flip
				output.append(get)
			}
		}
		putLen(size)
		((output.last.remaining until size by BUFFER_SIZE) ++ List(newOutput.size)).foldLeft(0) {(s1, s2) =>
			output.last.put(newOutput, s1, s2 - s1)
			output.last.flip
			output.append(get)
			s2
		}
	}
	def writeOutput {
		if (chan.write(output.toArray, 0, output.size) > 0) {
			while (!output.isEmpty && output(0).remaining == 0) {
				release(output.remove(0))
			}
		}
	}
	def readInput {
		if (chan.read(input) > 0) {
			partialInput.write(input.array, 0, input.remaining)
			input.clear
			processInput
		}
	}
	def close {
		outputActor ! 0
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

			if (size + 4 <= partialInput.size) {
				val bytes = partialInput.bytes
				val oldSize = partialInput.size

				handleInput(bytes, 4, size)
				if (oldSize > size + 4) {
					System.arraycopy(bytes, size + 4, bytes, 0, oldSize - size - 4)
					partialInput.setSize(oldSize - size - 4)
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
	override def handle(key: SelectionKey) = key match {
	case _ if key.isReadable => inputActor ! 1
	case _ if key.isWritable => outputActor ! 1
	case _ if key.isConnectable => chan.finishConnect()
	case _ => super.handle(key)
	}
}