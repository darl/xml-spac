package io.dylemma.xml.experimental

import javax.xml.stream.XMLInputFactory
import javax.xml.stream.events.XMLEvent

import io.dylemma.xml.{ XMLEventSource, AsInputStream, Result }
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

	def ofXml[R: AsInputStream](
		source: R,
		inputFactory: XMLInputFactory = XMLEventSource.defaultInputFactory
	): Stream[XMLEvent] = new Stream[XMLEvent] {
		def drive[A](consumer: Consumer[XMLEvent, A]) = {
			val provider = implicitly[AsInputStream[R]]
			val resource = provider openResource source
			try {
				val stream = provider resourceToStream resource
				val eventReader = inputFactory createXMLEventReader stream
				val handler = consumer.makeHandler()

				var state: ConsumerState[A] = Working
				while(eventReader.hasNext && !state.isDone){
					val event = eventReader.nextEvent
					state = handler handleEvent event
				}
				if(!state.isDone && !eventReader.hasNext){
					state = handler.handleEOF()
				}
				if(state.hasResult) state.result
				else Empty
			} finally {
				provider.closeResource(resource)
			}
		}
	}
}

private trait SingleUseConsumer[E, A] extends Consumer[E, A] with Handler[E, A, ConsumerState] {
	def makeHandler() = this
}

class ConsumerWithTransformedInput[A, B, C](transformerA: Transformer[A, B], consumer: Consumer[B, C])
	extends Consumer[A, C] {
	def makeHandler() = new Handler[A, C, ConsumerState]{
		val consumerB = consumer.makeHandler()
		val transformer = transformerA.makeHandler()

			def feedTransformerState(s: TransformerState[B]) = s match {
				case Working => Working
				case Done(resultB) => resultB match {
					case Success(b) => consumerB handleEvent b
					case Error(err) => consumerB handleError err
					case Empty => Done(Empty)
				}
				case Emit(resultB) => resultB match {
					case Success(b) => consumerB.handleEvent(b)
					case Error(err) => consumerB.handleError(err)
					case Empty => Working
				}
			}

			def handleEvent(event: A) = feedTransformerState(transformer handleEvent event)
			def handleError(err: Throwable) = feedTransformerState(transformer handleError err)
			def handleEOF() = feedTransformerState(transformer.handleEOF())
	}
}

trait Stream[E] {stream =>
	def drive[A](handler: Consumer[E, A]): Result[A]

	def transformWith[B](transformer: Transformer[E, B]): Stream[B] = new Stream[B] {
		def drive[T](consumer: Consumer[B, T]) = {
			val betweenConsumer = new ConsumerWithTransformedInput(transformer, consumer)

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