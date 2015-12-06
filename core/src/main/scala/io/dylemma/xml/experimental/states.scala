package io.dylemma.xml.experimental

import io.dylemma.xml.Result

/** The result returned by a TransformerHandler after handling a single event.
	* A transformer can either `Emit` a new event as a result, or be in one of
	* the "consumer" states i.e. Working or Done.
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

/** The result returned by a ConsumerHandler after handling a single event.
	* The handler can either still be working (`Working`), or be finished with
	* some result value (`Done(result)`).
	*
	* @tparam A The type of the finished result
	*/
sealed trait ConsumerState[+A] extends TransformerState[A]

case object Working extends ConsumerState[Nothing] {
	def isDone = false
	def hasResult = false
	def result = throw new IllegalArgumentException("Working.result")
}

case class Done[A](result: Result[A]) extends ConsumerState[A] {
	def isDone = true
	def hasResult = true
}