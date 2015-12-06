package io.dylemma.xml.experimental

import io.dylemma.xml.Result
import io.dylemma.xml.Result._

trait Consumer[-E, A] {
	def makeHandler(): ConsumerHandler[E, A]
	// TODO: def makeHandler(context: C): ConsumerHandler[E, A]
}

trait ConsumerHandler[-E, +A] {
	def handleError(err: Throwable): ConsumerState[A]
	def handleEvent(event: E): ConsumerState[A]
	def handleEOF(): ConsumerState[A]
}

object Consumer {

	def firstOrElse[E](alternate: => Result[E]): Consumer[E, E] = new Consumer[E, E] {
		def makeHandler() = new ConsumerHandler[E, E] {
			def handleError(err: Throwable) = Done(Error(err))
			def handleEvent(event: E) = Done(Success(event))
			def handleEOF() = Done(alternate)
		}
	}

	def first[E] = firstOrElse[E]{
			Error(new NoSuchElementException("EOF.first"))
		}

	def toList[E]: Consumer[E, List[E]] = new Consumer[E, List[E]] {
		def makeHandler() = new ConsumerHandler[E, List[E]] {
			val lb = List.newBuilder[E]
			def handleEvent(event: E) = {
				lb += event
				Working
			}
			def handleEOF() = Done(Success(lb.result))
			def handleError(err: Throwable) = Done(Error(err))
		}
	}

	def foreachResult[E](thunk: Result[E] => Unit): Consumer[E, Unit] = new Consumer[E, Unit] {
		def makeHandler() = new ConsumerHandler[E, Unit] {
			def handleEvent(event: E) = {
				thunk(Success(event))
				Working
			}
			def handleError(err: Throwable) = {
				thunk(Error(err))
				Working
			}
			def handleEOF() = Done(Success())
		}
	}
}