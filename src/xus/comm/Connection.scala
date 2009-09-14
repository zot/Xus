package xus.comm

import java.net._
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.channels._
import scala.actors.TIMEOUT
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
	val selectorThread = actor {
		loop {
			receiveWithin(1) {
			case con: Connection[_] =>
				con.register 
				connections(con.chan) = con
			case TIMEOUT =>
			case other =>
			}
			val count = selector.select(100)
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

	def queue(obj: Any) {
		selectorThread ! obj
		selector.wakeup
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
	def addConnection[T](newCon: T) = {
		queue(newCon)
		newCon
	}
}
object ClientConnection {
	import Connection._

	def apply(connection: SocketChannel, optAcceptor: Option[Acceptor])(inputHandler: (Array[Byte], Int, Int) => Unit): ClientConnection = new ClientConnection(connection, optAcceptor) {
		def handleInput(bytes: Array[Byte], offset: Int, length: Int) = inputHandler(bytes, offset, length)
	}
	def apply(host: String, port: Int)(inputHandler: (Array[Byte], Int, Int) => Unit): ClientConnection = {
		val chan = SocketChannel.open

		chan.configureBlocking(false)
		val con = addConnection(ClientConnection(chan, None)(inputHandler))
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
abstract class ClientConnection(clientChan: SocketChannel, optAcceptor: Option[Acceptor] = None) extends Connection[SocketChannel](clientChan) {
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
			case _ if !chan.isOpen => doClose
			case 0 => doClose
			case 1 => writeOutput
			case (newOutput: Array[Byte], offset: Int, len: Int) => doAddOutput(newOutput, offset, len)
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

	def register {
		chan.configureBlocking(false)
		chan.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE | (if (optAcceptor == None) SelectionKey.OP_CONNECT else 0))
	}
	def addOutput(newOutput: Array[Byte]) {addOutput(newOutput, 0, newOutput.length)}
	def addOutput(newOutput: Array[Byte], offset: Int, len: Int) {outputActor ! (newOutput, offset, len)}
	def doAddOutput(newOutput: Array[Byte], offset: Int, len: Int) {
		if (output.isEmpty) {
			output.append(get)
		} else {
			output.last.compact
			if (output.last.remaining < 5) {
				output.last.flip
				output.append(get)
			}
		}
		putLen(len)
		((offset + output.last.remaining until offset + len by BUFFER_SIZE) ++ List(newOutput.size)).foldLeft(offset) {(s1, s2) =>
			output.last.put(newOutput, s1, s2 - s1)
			output.last.flip
			output.append(get)
			s2
		}
		output.last.flip
		writeOutput
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
	override def handle(key: SelectionKey) = {
		if (key.isReadable) inputActor ! 1
		if (key.isWritable) outputActor ! 1
		if (key.isConnectable) chan.finishConnect()
		super.handle(key)
	}
}