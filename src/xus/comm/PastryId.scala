/*******************************************************************************

"FreePastry" Peer-to-Peer Application Development Substrate

Copyright 2002-2007, Rice University. Copyright 2006-2007, Max Planck Institute 
for Software Systems.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

- Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

- Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

- Neither the name of Rice  University (RICE), Max Planck Institute for Software 
Systems (MPI-SWS) nor the names of its contributors may be used to endorse or 
promote products derived from this software without specific prior written 
permission.

This software is provided by RICE, MPI-SWS and the contributors on an "as is" 
basis, without any representations or warranties of any kind, express or implied 
including, but not limited to, representations or warranties of 
non-infringement, merchantability or fitness for a particular purpose. In no 
event shall RICE, MPI-SWS or contributors be liable for any direct, indirect, 
incidental, special, exemplary, or consequential damages (including, but not 
limited to, procurement of substitute goods or services; loss of use, data, or 
profits; or business interruption) however caused and on any theory of 
liability, whether in contract, strict liability, or tort (including negligence
or otherwise) arising in any way out of the use of this software, even if 
advised of the possibility of such damage.

*******************************************************************************/ 

/**
 * Partly ported to Scala and stripped of Pastry depencencies by Bill Burdick
 * as part of the Xus project
 * Added use of MessageDigest in build()
 */

package xus.comm;

import java.io._
import java.lang.ref._
import java.util._
import java.security.MessageDigest

/**
 * Represents a Pastry identifier for a node, object or key. A single identifier and the bit length
 * for Ids is stored in this class. Ids are stored little endian.  NOTE: Ids are immutable, and are
 * coalesced for memory efficiency.  New Ids are to be constructed from the build() methods, which
 * ensure that only one copy of each Id is in memory at a time.
 *
 * @version $Id: Id.java 3613 2007-02-15 14:45:14Z jstewart $
 * @author Andrew Ladd
 * @author Peter Druschel
 * @author Alan Mislove
 */
object PastryId {
	val TYPE: Short = 1
	/**
	 * The static translation array
	 */
	val tran = Array("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C", "D", "E", "F")
	val digest = MessageDigest.getInstance("SHA1")

	/**
	 * This is the bit length of the node ids. If it is n, then there are 2^n possible different Ids.
	 * We currently assume that it is divisible by 32.
	 */
	val IdBitLength = 160
//	val IdBitLength = 1312 // roughly the number of bits in an RSA public key
	val nlen = IdBitLength / 32

	/**
	 * serialver for backwards compatibility
	 */
	val serialVersionUID = 2166868464271508935L

	/**
	 * return the number of digits in a given base
	 *
	 * @param base the number of bits in the base
	 * @return the number of digits in that base
	 */
	def numDigits(base: Int) = IdBitLength / base

	/**
	 * Creates a random Id. For testing purposed only -- should NOT be used to generate real node or
	 * object identifiers (low quality of random material).
	 *
	 * @param rng random number generator
	 * @return a random Id
	 */
	def makeRandomId(rng: Random) = {
		val material = new Array[byte](IdBitLength / 8)

		rng.nextBytes(material)
		build(material)
	}

	/**
	 * Constructor.
	 *
	 * @param material an array of length at least IdBitLength/32 containing raw Id material.
	 */
	def build(material: Array[Byte]) = {
		val digestedMaterial = digest.digest(material)
		digest.reset()
		val bits = new Array[Int](nlen)
		for (i <- 0 until Math.min(nlen, digestedMaterial.length)) {
			bits(i) = digestedMaterial(i);
		}
		new PastryId(bits)
	}
}

class PastryId(val bits: Array[Int]) {
	import PastryId._

	/**
	 * Equality operator for Ids.
	 *
	 * @param obj a Id object
	 * @return true if they are equal, false otherwise.
	 */
	override def equals(obj: Any) = obj.isInstanceOf[PastryId] && equals(obj.asInstanceOf[PastryId])

	/**
	 * Equivalence relation for Ids.
	 *
	 * @param nid the other node id.
	 * @return true if they are equal, false otherwise.
	 */
	def equals(nid: PastryId): Boolean = {
		if (nid == null) {
			return false;
		}
		for (i <- 0 until nlen) {
			if (bits(i) != nid.bits(i)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Comparison operator for Ids. The comparison that occurs is a numerical comparison.
	 *
	 * @param obj the Id to compare with.
	 * @return negative if this < obj, 0 if they are equal and positive if this > obj.
	 */
	def compareTo(oth: PastryId): Int = {
		for (i <- nlen - 1 to 0 by -1) {
			if (bits(i) != oth.bits(i)) {
				val t = bits(i) & 0x0ffffffffL
				val o = oth.bits(i) & 0x0ffffffffL
				if (t < o) {
					return -1;
				} else {
					return 1;
				}
			}
		}
		return 0;
	}
	/**
	 * Hash codes for Ids.
	 *
	 * @return a hash code.
	 */
	override def hashCode() = {
		var h = 0;

		/// Hash function is computed by XORing the bits of the Id.
		for (i <- 0 until nlen) {
			h ^= bits(i)
		}
		h
	}
		
	/**
	 * Returns a string representation of the Id in base 16. The string is a byte string from most to
	 * least significant.
	 * 
	 * This was updated by Jeff because it was becomming a performance bottleneck.
	 *
	 * @return A String representation of this Id, abbreviated
	 */
	override def toString = {
		val buffer = new StringBuffer();

		buffer.append("<0x");
		val n = IdBitLength / 4;
		for (i <- n - 1 to n - 6 by -1) {
			buffer.append(tran(getDigit(i, 4)));
		}
		buffer.append("..>");
		buffer.toString
	}
	/**
	 * Gets the ith digit in base 2^b. i = 0 is the least significant digit.
	 *
	 * @param i which digit to get.
	 * @param b which power of 2 is the base to get it in.
	 * @return the ith digit in base 2^b.
	 */
	def getDigit(i: Int, b: Int) = {
		val bitIndex = b * i + (IdBitLength % b)
		val index = bitIndex / 32
		val shift = bitIndex % 32
		var value: Long = bits(index)
	
		if (shift + b > 32) {
			value = (value & 0xffffffffL) | ((bits(index + 1).asInstanceOf[Long]) << 32)
		}
		((value >> shift).asInstanceOf[Int]) & ((1 << b) - 1)
	}
}