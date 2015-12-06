package io.dylemma.xml.experimental

import javax.xml.stream.events.XMLEvent

import io.dylemma.xml.Result.{ Empty, Error }
import io.dylemma.xml.event.EndElement

trait Splitter[E] {
	def through[A](parser: Parser[E, A]): Transformer[E, A]
}

trait StackBasedSplitter extends Splitter[XMLEvent] {

	/** If there's a match, return the remaining (tail) parts of the list
		* following the matched prefix. Returning `Some(Nil)` implies that
		* a matching scope has just opened.
		*/
	def matchStack(stack: List[Tag]): Option[List[Tag]]

	def through[A](parser: Parser[XMLEvent, A]): Transformer[XMLEvent, A] = new Transformer[XMLEvent, A] {
		def makeHandler() = new Handler[XMLEvent, A, TransformerState] {
			var stack: List[Tag] = Nil
			var innerHandler: Option[Handler[XMLEvent, A, ParserState]] = None

			def handleEvent(event: XMLEvent): TransformerState[A] = event match {
				case _ if event.isStartElement =>
					// push to the stack
					val tag = new Tag(event.asStartElement)
					stack :+= tag
					if (innerHandler.isEmpty) {
						matchStack(stack) match {
							case Some(Nil) => innerHandler = Some(parser.makeHandler())
							case _ =>
							// Some(list) implies a previous parser already finished
							// None implies no match for the stack
						}
					}

					innerHandler match {
						case None => Working
						case Some(c) => c.handleEvent(event) match {
							case Working => Working
							case Done(result) =>
								innerHandler = None
								Emit(result)
						}
					}

				case end @ EndElement(_) =>
					stack = stack dropRight 1

					innerHandler match {
						/*
						With no inner parser active, we get the simplest behavior:
						If the stack ran out, we are done with no result.
						Otherwise we are still "working"
						 */
						case None => if (stack.isEmpty) Done(Empty) else Working

						case Some(c) => c.handleEvent(end) match {
							/*
							If the inner parser finished, emit its result...
							Unless there is no more stack, in which we are done,
							so finish with the parser's result.
							 */
							case Done(result) =>
								innerHandler = None
								if (stack.isEmpty) Done(result) else Emit(result)

							/*
							If the inner parser is still working, we still might need
							to feed it an EOF if we left the 'matched' portion of the stack.
							 */
							case Working =>
								if (matchStack(stack).isEmpty) {
									// force the parser to finish by passing EOF
									innerHandler = None
									c.handleEOF() match {
										// finished after EOF - emit/finish with that result
										case Done(result) => if (stack.isEmpty) Done(result) else Emit(result)
										// still working after EOF - no results
										case Working => if (stack.isEmpty) Done(Empty) else Working
									}
								} else {
									Working
								}
						}
					}

				case _ =>
					innerHandler match {
						case None => Working
						case Some(c) => c.handleEvent(event) match {
							case Done(result) =>
								innerHandler = None
								Emit(result)
							case Working => Working
						}
					}
			}

			def handleEOF() = {
				innerHandler match {
					case None => Done(Empty)
					case Some(c) =>
						innerHandler = None
						c.handleEOF() match {
							case Working => Done(Empty)
							case Done(result) => Done(result)
						}
				}
			}

			def handleError(err: Throwable) = {
				innerHandler = None
				Done(Error(err))
			}
		}
	}
}