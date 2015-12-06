package io.dylemma.xml.experimental

import io.dylemma.xml.Result
import io.dylemma.xml.Result.{ Empty, Error, Success }

object Stream {
	def of[E](t: Iterable[E]): Stream[E] = new Stream[E] {
		def drive[A](consumer: Consumer[E, A]) = {
			val handler = consumer.makeHandler()
			var state: ConsumerState[A] = Working
			val itr = t.iterator
			while (itr.hasNext && !state.isDone) {
				val event = itr.next
				state = handler.handleEvent(event)
			}
			if (!state.isDone && !itr.hasNext) {
				state = handler.handleEOF()
			}
			if (state.hasResult) state.result
			else Empty
		}
	}
}

private trait SingleUseConsumer[E, A] extends Consumer[E, A] with ConsumerHandler[E, A] {
	def makeHandler() = this
}

trait Stream[E] {stream =>
	def drive[A](handler: Consumer[E, A]): Result[A]

	def transformWith[B](transformerMaker: Transformer[E, B]): Stream[B] = new Stream[B] {
		def drive[T](makerOfConsumerB: Consumer[B, T]) = {
			val consumerB = makerOfConsumerB.makeHandler()
			val transformer = transformerMaker.makeHandler()
			val betweenConsumer = new SingleUseConsumer[E, T] {
				def handleEvent(event: E) = {
					transformer.handle(event) match {
						case Working => Working
						case Done(resultB) => resultB match {
							case Success(b) => consumerB.handleEvent(b)
							case Error(err) => consumerB.handleError(err)
							case Empty => Done(Empty)
						}
						case Emit(resultB) => resultB match {
							case Success(b) => consumerB.handleEvent(b)
							case Error(err) => consumerB.handleError(err)
							case Empty => Working
						}
					}
				}
				def handleError(err: Throwable) = consumerB.handleError(err)
				def handleEOF() = consumerB.handleEOF()
			}

			stream.drive(betweenConsumer)
		}
	}

	def logAs(label: String): Stream[E] = new Stream[E] {
		def drive[T](consumerMaker: Consumer[E, T]) = {
			val consumer = consumerMaker.makeHandler()
			val betweenConsumer = new SingleUseConsumer[E, T] {
				def handleEvent(event: E) = {
					val s = consumer.handleEvent(event)
					println(s"$label: $event --> $s")
					s
				}
				def handleError(err: Throwable) = {
					val s = consumer.handleError(err)
					println(s"$label: <error: $err> --> $s")
					s
				}
				def handleEOF() = {
					val s = consumer.handleEOF()
					println(s"$label: <eof> --> $s")
					s
				}
			}
			stream.drive(betweenConsumer)
		}
	}

}