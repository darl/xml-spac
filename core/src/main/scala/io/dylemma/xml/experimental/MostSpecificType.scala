package io.dylemma.xml.experimental

import scala.annotation.implicitNotFound

/** An instance of `A >:< (B1, B2)` witnesses that `A` is the most
	* specific common type between `B1` and `B2`.
	* @tparam A
	* @tparam BB
	*/
@implicitNotFound("There is no common type in ${BB}")
sealed abstract class >:<[A, BB] extends (A => BB)//(BB => (A, A))

object >:< {
	private def makeInstance[A, B1, B2](conv1: A => B1, conv2: A => B2): A >:< (B1, B2) = {
		new >:<[A, (B1, B2)] {
			def apply(a: A) = (conv1(a), conv2(a))
		}
	}

	implicit def sameType[A]: A >:< (A, A) = makeInstance(identity, identity)
	implicit def commonLeftType[B1, B2 <: B1]: B2 >:< (B1, B2) = makeInstance(identity, identity)
	implicit def commonRightType[B2, B1 <: B2]: B1 >:< (B1, B2) = makeInstance(identity, identity)
}
