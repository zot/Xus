package xus.testing

import scala.io.Source
import xus.comm._
import scala.actors.Actor._

object IM {
	var con: ClientConnection = null

	def main(args: Array[String]) {
		if (args(0) == "server") {
			server(Integer.parseInt(args(1)))
		} else {
			client(Integer.parseInt(args(1)))
		}
	}
	def handleInput(bytes: Array[Byte], offset: Int, len: Int) {
		System.err.println(new String(bytes, offset, len))
	}
	def server(port: Int) {
		Acceptor.listen(port) {(newCon: ClientConnection) =>
			con = newCon
			actor {processInput}
		} (handleInput)
	}
	def processInput {
		while (true) {
			if (con != null) {
				System.out.print("> ")
				System.out.flush
				for (line <- Source.stdin.getLines()) {
					if (!line.isEmpty) {
						con.addOutput(line.getBytes)
						System.out.print("> ")
						System.out.flush
					}
				}
			}
		}
	}
	def client(port: Int) {
		con = ClientConnection("localhost", port)(handleInput)
		actor {processInput}
	}
}