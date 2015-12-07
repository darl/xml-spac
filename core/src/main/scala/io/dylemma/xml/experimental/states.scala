package io.dylemma.xml.experimental

import scala.util.control.NonFatal

import io.dylemma.xml.Result
import io.dylemma.xml.Result.Error

/** The result returned by a transformer's Handler after handling a single event.
	* A transformer's Handler can either `Emit` a new event as a result, or be in one of
	* the "parser" states i.e. Working or Done.
	*
	* @tparam A The type of the results in `Emit` or `Done`.
	*/
sealed trait TransformerState[+A] {
	def isDone: Boolean
	def hasResult: Boolean
	def result: Result[A]
	def mapR[B](f: Result[A] => Result[B]): TransformerState[B]
}

case class Emit[A](result: Result[A]) extends TransformerState[A] {
	def isDone = false
	def hasResult = true
	def mapR[B](f: Result[A] => Result[B]) = {
		try {Emit(f(result))}
		catch {case NonFatal(err) => Emit(Error(err))}
	}
}

/** The result returned by a parser's Handler after handling a single event.
	* The parser can either still be working (`Working`), or be finished with
	* some result value (`Done(result)`).
	*
	* @tparam A The type of the finished result
	*/
sealed trait ParserState[+A] extends TransformerState[A] {
	override def mapR[B](f: Result[A] => Result[B]): ParserState[B]
}

case object Working extends ParserState[Nothing] {
	def isDone = false
	def hasResult = false
	def result = throw new IllegalArgumentException("Working.result")
	@inline def mapR[B](f: Result[Nothing] => Result[B]) = this
}

case class Done[A](result: Result[A]) extends ParserState[A] {
	def isDone = true
	def hasResult = true
	def mapR[B](f: Result[A] => Result[B]) = {
		try {Done(f(result))}
		catch {case NonFatal(err) => Done(Error(err))}
	}
}