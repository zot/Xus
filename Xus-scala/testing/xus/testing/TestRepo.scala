package xus.testing

import java.io.File
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId

object TestRepo {
	def main(args: Array[String]) {
		val repo = new Repository(new File("/tmp/tst/.git"))
		val obj = repo.openObject(ObjectId.fromString("78981922613b2afb6025042ff6bd878ac1994e85"))

		println("type: "+Constants.typeString(obj.getType))
		println("done")
	}
}