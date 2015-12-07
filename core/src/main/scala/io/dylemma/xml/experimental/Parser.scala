package io.dylemma.xml.experimental

import scala.language.higherKinds

import io.dylemma.xml.{ Chain => ~, MapR, Result }
import io.dylemma.xml.Result._

trait ParserForContext[-In, -E, +A] { self =>
	def makeHandler(context: In): Handler[E, A, ParserState]
}

trait ParserCombinerOps {
	implicit class ParserWithCombine[In1, E, A1](parser1: ParserForContext[In1, E, A1]){
		def &[In2, A2, C](
			parser2: ParserForContext[In2, E, A2])(
			implicit ev: C >:< (In1, In2)
		): ParserForContext[C, E, A1 ~ A2] = new ParserForContext[C, E, A1 ~ A2] {
			def makeHandler(context: C) = {
				val (context1, context2) = ev(context)
				val handler1 = parser1 makeHandler context1
				val handler2 = parser2 makeHandler context2
				new CombinedParserHandler(handler1, handler2)
			}
		}
	}
}

trait Parser[-E, +A] extends ParserForContext[Any, E, A] { self =>
	def makeHandler(): Handler[E, A, ParserState] = makeHandler(())

	def &[E2 <: E, A2](parser2: Parser[E2, A2]): Parser[E2, A ~ A2] = new Parser[E2, A ~ A2] {
		def makeHandler(context: Any) = {
			val handler1 = self makeHandler context
			val handler2 = parser2 makeHandler context
			new CombinedParserHandler(handler1, handler2)
		}
	}

	def &[C, E2 <: E, A2](parser2: ParserForContext[C, E2, A2]): ParserForContext[C, E2, A ~ A2] = {
		new ParserForContext[C, E2, A ~ A2] {
			def makeHandler(context: C) = {
				val handler1 = self makeHandler context
				val handler2 = parser2 makeHandler context
				new CombinedParserHandler(handler1, handler2)
			}
		}
	}
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
	def makeHandler(context: Any) = this
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

	def done[A](result: Result[A]) = StatelessParser[Any, A](
		event => Done(result),
		() => Done(result),
		err => Done(result)
	)



	def inContext[In, E]: ParserForContext[In, E, In] = new ParserForContext[In, E, In] {
		def makeHandler(context: In) = new Handler[E, In, ParserState] {
			// ignore all inputs, just return the result
			val state = Done(Success(context))
			def handleEvent(event: E) = state
			def handleEOF(): ParserState[In] = state
			def handleError(err: Throwable) = state
		}
	}

	def toList[E]: Parser[E, List[E]] = new Parser[E, List[E]] {
		def makeHandler(context: Any) = new Handler[E, List[E], ParserState] {
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

private class CombinedParserHandler[E, A1, A2](
	handler1: Handler[E, A1, ParserState],
	handler2: Handler[E, A2, ParserState]
) extends Handler[E, A1 ~ A2, ParserState] {
	var state1: ParserState[A1] = Working
	var state2: ParserState[A2] = Working

	def combinedResult = (state1, state2) match {
		case (Done(r1), Done(r2)) => Done(for (a1 <- r1; a2 <- r2) yield new ~(a1, a2))
		case (Done(e: Error), _) => Done(e)
		case (_, Done(e: Error)) => Done(e)
		case (Done(Empty), _) | (_, Done(Empty)) => Done(Empty)
		case (Working, _) | (_, Working) => Working
	}
	def handleEvent(event: E): ParserState[A1 ~ A2] = {
		state1 = handler1 handleEvent event
		state2 = handler2 handleEvent event
		combinedResult
	}
	def handleEOF(): ParserState[A1 ~ A2] = {
		state1 = handler1.handleEOF()
		state2 = handler2.handleEOF()
		combinedResult
	}
	def handleError(err: Throwable): ParserState[A1 ~ A2] = {
		state1 = handler1.handleError(err)
		state2 = handler2.handleError(err)
		combinedResult
	}
}
