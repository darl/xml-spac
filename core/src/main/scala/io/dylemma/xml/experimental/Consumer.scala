package io.dylemma.xml.experimental

import scala.language.higherKinds

import io.dylemma.xml.Result
import io.dylemma.xml.Result._

trait Consumer[-E, +A] {
	def makeHandler(): Handler[E, A, ConsumerState]
	// TODO: def makeHandler(context: C): ConsumerHandler[E, A]
}

trait Handler[-E, +A, +S[+ _]] {
	def handleEvent(event: E): S[A]
	def handleError(err: Throwable): S[A]
	def handleEOF(): S[A]
}

/** Specialization of Consumer that has no internal state, allowing it
	* to be its own handler. The `makeHandler` method for stateless consumers
	* simply returns a reference to itself.
	*/
trait StatelessConsumer[E, A] extends Consumer[E, A] with Handler[E, A, ConsumerState] {
	def makeHandler() = this
}
object StatelessConsumer {
	def apply[E, A](
		onEvent: E => ConsumerState[A],
		onEOF: () => ConsumerState[A],
		onError: Throwable => ConsumerState[A]
	): StatelessConsumer[E, A] = new StatelessConsumer[E, A] {
		def handleEvent(event: E): ConsumerState[A] = onEvent(event)
		def handleEOF(): ConsumerState[A] = onEOF()
		def handleError(err: Throwable): ConsumerState[A] = onError(err)
	}
}

object Consumer {

	def firstOrElse[E](alternate: => Result[E]) = StatelessConsumer[E, E](
		event => Done(Success(event)),
		() => Done(alternate),
		err => Done(Error(err))
	)

	def firstOption[E] = StatelessConsumer[E, Option[E]](
		event => Done(Success(Some(event))),
		() => Done(Success(None)),
		err => Done(Error(err))
	)

	def first[E] = firstOrElse[E]{
		Error(new NoSuchElementException("EOF.first"))
	}

	def foreachResult[E](thunk: Result[E] => Unit) = StatelessConsumer[E, Unit](
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

	def toList[E]: Consumer[E, List[E]] = new Consumer[E, List[E]] {
		def makeHandler() = new Handler[E, List[E], ConsumerState] {
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