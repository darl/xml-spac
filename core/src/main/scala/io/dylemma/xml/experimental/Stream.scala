package io.dylemma.xml.experimental

import javax.xml.stream.XMLInputFactory
import javax.xml.stream.events.XMLEvent

import io.dylemma.xml.Result.{ Empty, Error, Success }
import io.dylemma.xml.{ AsInputStream, Result, XMLEventSource }

object Stream {
	def of[E](t: Iterable[E]): Stream[E] = new Stream[E] {
		def drive[A](consumer: Parser[E, A]) = {
			val handler = consumer.makeHandler()
			var state: ParserState[A] = Working
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
		def drive[A](consumer: Parser[XMLEvent, A]) = {
			val provider = implicitly[AsInputStream[R]]
			val resource = provider openResource source
			try {
				val stream = provider resourceToStream resource
				val eventReader = inputFactory createXMLEventReader stream
				val handler = consumer.makeHandler()

				var state: ParserState[A] = Working
				while (eventReader.hasNext && !state.isDone) {
					val event = eventReader.nextEvent
					state = handler handleEvent event
				}
				if (!state.isDone && !eventReader.hasNext) {
					state = handler.handleEOF()
				}
				if (state.hasResult) state.result
				else Empty
			} finally {
				provider.closeResource(resource)
			}
		}
	}
}

trait Stream[E] {self =>
	def drive[A](parser: Parser[E, A]): Result[A]

	def transformWith[B](transformer: Transformer[E, B]): Stream[B] = new Stream[B] {
		def drive[T](parser: Parser[B, T]) = {
			val betweenConsumer = new ParserWithTransformedInput(transformer, parser)

			self.drive(betweenConsumer)
		}
	}

	def logAs(label: String): Stream[E] = new Stream[E] {
		def drive[T](parser: Parser[E, T]) = {
			val wrappedConsumer = new Parser[E, T] {
				def makeHandler(context: Any) = new Handler[E, T, ParserState] {
					val innerHandler = parser.makeHandler()
					def handleEvent(event: E) = {
						val s = innerHandler handleEvent event
						println(s"$label: $event --> $s")
						s
					}
					def handleError(err: Throwable) = {
						val s = innerHandler.handleError(err)
						println(s"$label: <error: $err> --> $s")
						s
					}
					def handleEOF() = {
						val s = innerHandler.handleEOF()
						println(s"$label: <eof> --> $s")
						s
					}
				}
			}
			self drive wrappedConsumer
		}
	}

}

private class ParserWithTransformedInput[A, B, C](transformerA: Transformer[A, B], parser: Parser[B, C])
	extends Parser[A, C] {
	def makeHandler(context: Any) = new Handler[A, C, ParserState] {
		val innerHandler = parser.makeHandler()
		val transformHandler = transformerA.makeHandler()

		def feedTransformerState(s: TransformerState[B]) = s match {
			case Working => Working
			case Done(resultB) => resultB match {
				case Success(b) => innerHandler handleEvent b
				case Error(err) => innerHandler handleError err
				case Empty => Done(Empty)
			}
			case Emit(resultB) => resultB match {
				case Success(b) => innerHandler.handleEvent(b)
				case Error(err) => innerHandler.handleError(err)
				case Empty => Working
			}
		}

		def handleEvent(event: A) = feedTransformerState(transformHandler handleEvent event)
		def handleError(err: Throwable) = feedTransformerState(transformHandler handleError err)
		def handleEOF() = feedTransformerState(transformHandler.handleEOF())
	}
}