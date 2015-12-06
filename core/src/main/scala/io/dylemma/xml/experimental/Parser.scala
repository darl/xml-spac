package io.dylemma.xml.experimental

import scala.language.higherKinds

import io.dylemma.xml.Result
import io.dylemma.xml.Result._

trait Parser[-E, +A] {
	def makeHandler(): Handler[E, A, ParserState]
	// TODO: def makeHandler(context: C): ConsumerHandler[E, A]
}

trait Handler[-E, +A, +S[+ _]] {
	def handleEvent(event: E): S[A]
	def handleError(err: Throwable): S[A]
	def handleEOF(): S[A]
}

/** Specialization of Parser that has no internal state, allowing it
	* to be its own handler. The `makeHandler` method for stateless parsers
	* simply returns a reference to itself.
	*/
trait StatelessParser[E, A] extends Parser[E, A] with Handler[E, A, ParserState] {
	def makeHandler() = this
}
object StatelessParser {
	def apply[E, A](
		onEvent: E => ParserState[A],
		onEOF: () => ParserState[A],
		onError: Throwable => ParserState[A]
	): StatelessParser[E, A] = new StatelessParser[E, A] {
		def handleEvent(event: E): ParserState[A] = onEvent(event)
		def handleEOF(): ParserState[A] = onEOF()
		def handleError(err: Throwable): ParserState[A] = onError(err)
	}
}

object Parser {

	def firstOrElse[E](alternate: => Result[E]) = StatelessParser[E, E](
		event => Done(Success(event)),
		() => Done(alternate),
		err => Done(Error(err))
	)

	def firstOption[E] = StatelessParser[E, Option[E]](
		event => Done(Success(Some(event))),
		() => Done(Success(None)),
		err => Done(Error(err))
	)

	def first[E] = firstOrElse[E]{
		Error(new NoSuchElementException("EOF.first"))
	}

	def foreachResult[E](thunk: Result[E] => Unit) = StatelessParser[E, Unit](
		event => {
			thunk(Success(event))
			Working
		},
		() => Done(Success()),
		err => {
			thunk(Error(err))
			Working
		}
	)

	def toList[E]: Parser[E, List[E]] = new Parser[E, List[E]] {
		def makeHandler() = new Handler[E, List[E], ParserState] {
			val lb = List.newBuilder[E]
			def handleEvent(event: E) = {
				lb += event
				Working
			}
			def handleEOF() = Done(Success(lb.result))
			def handleError(err: Throwable) = Done(Error(err))
		}
	}

}
