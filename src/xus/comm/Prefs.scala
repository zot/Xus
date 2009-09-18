package xus.comm

import scala.io.File
import scala.io.Directory
import java.security.KeyPair
import java.security.KeyFactory
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import Util._

/**
 * Peer initialization code
 */
class Prefs(peer: Peer) {
	def publicKeyFile = peer.prefsDirectory / File("key.public")
	def privateKeyFile = peer.prefsDirectory / File("key.private")
	def load(password: String) {
		val pub = new X509EncodedKeySpec(publicKeyFile.toByteArray)
		val priv = new PKCS8EncodedKeySpec(privateKeyFile.toByteArray)
		val kf = KeyFactory.getInstance("RSA")

		peer.keyPair = new KeyPair(kf.generatePublic(pub), kf.generatePrivate(priv))
	}
	def store(password: String) {
		val dir = peer.prefsDirectory
		val pub = new X509EncodedKeySpec(peer.keyPair.getPublic.getEncoded)
		val priv = new PKCS8EncodedKeySpec(peer.keyPair.getPrivate.getEncoded)

		dir.createDirectory()
		publicKeyFile.outputStream().closeAfter(_.write(pub.getEncoded))
		privateKeyFile.outputStream().closeAfter(_.write(priv.getEncoded))
	}
	def initializePeer(dir: Directory, password: String) {
		peer.prefsDirectory = dir
		peer.keyPair = KeyPairGenerator.getInstance("RSA").genKeyPair
		store(password)
	}
}