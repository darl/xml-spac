package io.dylemma.xml.experimental

import javax.xml.stream.events.XMLEvent

import io.dylemma.xml.Result.{ Empty, Error }
import io.dylemma.xml.event.EndElement

trait Splitter[E] {
	def through[A](consumer: Consumer[E, A]): Transformer[E, A]
}

trait StackBasedSplitter extends Splitter[XMLEvent] {

	/** If there's a match, return the remaining (tail) parts of the list
		* following the matched prefix. Returning `Some(Nil)` implies that
		* a matching scope has just opened.
		*/
	def matchStack(stack: List[Tag]): Option[List[Tag]]

	def through[A](makeConsumer: Consumer[XMLEvent, A]): Transformer[XMLEvent, A] = new Transformer[XMLEvent, A] {
		def makeHandler() = new Handler[XMLEvent, A, TransformerState] {
			var stack: List[Tag] = Nil
			var consumer: Option[Handler[XMLEvent, A, ConsumerState]] = None

			def handleEvent(event: XMLEvent): TransformerState[A] = event match {
				case _ if event.isStartElement =>
					// push to the stack
					val tag = new Tag(event.asStartElement)
					stack :+= tag
					if (consumer.isEmpty) {
						matchStack(stack) match {
							case Some(Nil) => consumer = Some(makeConsumer.makeHandler())
							case _ =>
							// Some(list) implies a previous consumer already finished
							// None implies no match for the stack
						}
					}

					consumer match {
						case None => Working
						case Some(c) => c.handleEvent(event) match {
							case Working => Working
							case Done(result) =>
								consumer = None
								Emit(result)
						}
					}

				case end @ EndElement(_) =>
					stack = stack dropRight 1

					consumer match {
						/*
						With no inner consumer active, we get the simplest behavior:
						If the stack ran out, we are done with no result.
						Otherwise we are still "working"
						 */
						case None => if (stack.isEmpty) Done(Empty) else Working

						case Some(c) => c.handleEvent(end) match {
							/*
							If the inner consumer finished, emit its result...
							Unless there is no more stack, in which we are done,
							so finish with the consumer's result.
							 */
							case Done(result) =>
								consumer = None
								if (stack.isEmpty) Done(result) else Emit(result)

							/*
							If the inner consumer is still working, we still might need
							to feed it an EOF if we left the 'matched' portion of the stack.
							 */
							case Working =>
								if (matchStack(stack).isEmpty) {
									// force the consumer to finish by passing EOF
									consumer = None
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
					consumer match {
						case None => Working
						case Some(c) => c.handleEvent(event) match {
							case Done(result) =>
								consumer = None
								Emit(result)
							case Working => Working
						}
					}
			}

			def handleEOF() = {
				consumer match {
					case None => Done(Empty)
					case Some(c) =>
						consumer = None
						c.handleEOF() match {
							case Working => Done(Empty)
							case Done(result) => Done(result)
						}
				}
			}

			def handleError(err: Throwable) = {
				consumer = None
				Done(Error(err))
			}
		}
	}
}