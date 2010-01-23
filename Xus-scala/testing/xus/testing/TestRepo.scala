package xus.testing

import java.io.File
import org.eclipse.jgit.lib.Repository

object TestRepo {
	def main(args: Array[String]) {
		val repo = new Repository(new File("/tmp/tst/.git"))
		val obj = repo.openObject(ObjectId.fromString("ff74984cccd156a469afa7d9ab10e4777beb24"))

		println("type: "+obj.getType)
		println("done")
	}
}