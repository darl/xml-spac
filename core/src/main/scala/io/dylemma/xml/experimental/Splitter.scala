package io.dylemma.xml.experimental

import io.dylemma.xml.Result.{ Empty, Error }
import io.dylemma.xml.experimental.Experiments.{ End, Event, Start }

trait Splitter[E] {
	def joinWith[A](consumer: Consumer[E, A]): Transformer[E, A]
}

trait StackBasedSplitter extends Splitter[Event] {

	/** If there's a match, return the remaining (tail) parts of the list
		* following the matched prefix. Returning `Some(Nil)` implies that
		* a matching scope has just opened.
		*/
	def matchStack(stack: List[String]): Option[List[String]]

	def joinWith[A](makeConsumer: Consumer[Event, A]): Transformer[Event, A] = new Transformer[Event, A] {
		def makeHandler() = new TransformerHandler[Event, A] {
			var stack: List[String] = Nil
			var consumer: Option[ConsumerHandler[Event, A]] = None

			def handle(event: Event): TransformerState[A] = event match {
				case Start(name) =>
					// push to the stack
					stack :+= name
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

				case End =>
					stack = stack dropRight 1

					consumer match {
						/*
						With no inner consumer active, we get the simplest behavior:
						If the stack ran out, we are done with no result.
						Otherwise we are still "working"
						 */
						case None => if (stack.isEmpty) Done(Empty) else Working

						case Some(c) => c.handleEvent(End) match {
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