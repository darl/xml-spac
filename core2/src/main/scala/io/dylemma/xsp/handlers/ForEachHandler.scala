package io.dylemma.xsp.handlers

import io.dylemma.xsp.{Handler, Result}

import scala.util.Try

class ForEachHandler[A](f: A => Any)
	extends Handler[A, Unit]
	with ManualFinish
{
	override def toString = s"ForEach($f)"
	def handleEnd() = ()
	def handleError(error: Throwable) = throw error
	def handleInput(input: A) = {
		if(!isFinished) f(input)
		None
	}
}


class SideEffectHandler[A, Out](f: A => Any, val downstream: Handler[A, Out]) extends TransformerHandler[A, A, Out]
{
	override def toString = s"SideEffect($f) >> $downstream"
	protected def transformInput(input: A): Option[A] = {
		f(input)
		Some(input)
	}
}