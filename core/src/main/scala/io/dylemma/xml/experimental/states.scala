package io.dylemma.xml.experimental

import io.dylemma.xml.Result

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
}

case class Emit[A](result: Result[A]) extends TransformerState[A] {
	def isDone = false
	def hasResult = true
}

/** The result returned by a parser's Handler after handling a single event.
	* The parser can either still be working (`Working`), or be finished with
	* some result value (`Done(result)`).
	*
	* @tparam A The type of the finished result
	*/
sealed trait ParserState[+A] extends TransformerState[A]

case object Working extends ParserState[Nothing] {
	def isDone = false
	def hasResult = false
	def result = throw new IllegalArgumentException("Working.result")
}

case class Done[A](result: Result[A]) extends ParserState[A] {
	def isDone = true
	def hasResult = true
}