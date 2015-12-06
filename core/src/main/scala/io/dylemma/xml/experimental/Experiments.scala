package io.dylemma.xml.experimental

import io.dylemma.xml.Result
import io.dylemma.xml.Result._

object Experiments extends App {

	//*****************************************************

	sealed trait Event

	case class Start(name: String) extends Event
	case class Text(text: String) extends Event
	case object End extends Event

	//*****************************************************

	val exampleEvents = List[Event](
		Start("A"),
		Start("B"),
		Text("Hello"),
		Start("C"),
		Text("Hello2"),
		End,
		End,
		Start("B"),
		Text("Goodbye"),
		Text("Goodbye2"),
		End,
		End
	)

	//*****************************************************

	sealed trait TransformerState[+A]{
		def isDone: Boolean
		def hasResult: Boolean
		def result: Result[A]
	}
	case class Emit[A](result: Result[A]) extends TransformerState[A] {
		def isDone = false
		def hasResult = true
	}

	sealed trait ConsumerState[+A] extends TransformerState[A]
	case object Working extends ConsumerState[Nothing] {
		def isDone = false
		def hasResult = false
		def result = throw new IllegalArgumentException("Working.result")
	}
	case class Done[A](result: Result[A]) extends ConsumerState[A] {
		def isDone = true
		def hasResult = true
	}

	//*****************************************************

	trait Stream[E] {
		def drive[A](handler: Consumer[E, A]): Result[A]
	}

	trait Consumer[-E, A] {
		def makeHandler(): ConsumerHandler[E, A]
		// TODO: def makeHandler(context: C): ConsumerHandler[E, A]
	}

	trait ConsumerHandler[-E, +A] {
		def handleError(err: Throwable): ConsumerState[A]
		def handleEvent(event: E): ConsumerState[A]
		def handleEOF(): ConsumerState[A]
	}

	trait Transformer[E, A] {
		def handle(event: E): TransformerState[A]
	}

	//*****************************************************

	private trait SingleUseConsumer[E, A] extends Consumer[E, A] with ConsumerHandler[E, A] {
		def makeHandler() = this
	}

	object Stream {
		def of[E](t: Iterable[E]): Stream[E] = new Stream[E] {
			def drive[A](consumer: Consumer[E, A]) = {
				val handler = consumer.makeHandler()
				var state: ConsumerState[A] = Working
				val itr = t.iterator
				while(itr.hasNext && !state.isDone){
					val event = itr.next
					state = handler.handleEvent(event)
				}
				if(!state.isDone && !itr.hasNext){
					state = handler.handleEOF()
				}
				if(state.hasResult) state.result
				else Empty
			}
		}
	}

	//*****************************************************

	implicit class StreamWithTransform[A](stream: Stream[A]) {
		def transformWith[B](transformer: Transformer[A, B]): Stream[B] = new Stream[B] {
			def drive[T](makerOfConsumerB: Consumer[B, T]) = {
				val consumerB = makerOfConsumerB.makeHandler()
				val betweenConsumer = new SingleUseConsumer[A, T] {
					def handleEvent(event: A) = {
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
					def handleError(err: Throwable) =  consumerB.handleError(err)
					def handleEOF() =  consumerB.handleEOF()
				}

				stream.drive(betweenConsumer)
			}
		}

		def logAs(label: String): Stream[A] = new Stream[A] {
			def drive[T](consumerMaker: Consumer[A, T]) = {
				val consumer = consumerMaker.makeHandler()
				val betweenConsumer = new SingleUseConsumer[A, T] {
					def handleEvent(event: A) = {
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

	//*****************************************************
	object Consumer {

		def firstOrElse[E](alternate: => Result[E]): Consumer[E, E] = new Consumer[E, E]{
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

	object TextConsumer extends Consumer[Event, String]{
		def makeHandler() = new ConsumerHandler[Event, String] {
			val sb = new StringBuilder
			def handleEvent(event: Event) = {
				event match {
					case Text(text) => sb append text
					case _ =>
				}
				Working
			}
			def handleError(err: Throwable) = Done(Error(err))
			def handleEOF() = Done(Success(sb.result))
		}
	}

	//*****************************************************

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
			var stack: List[String] = Nil
			var consumer: Option[ConsumerHandler[Event, A]] = None

			def handle(event: Event): TransformerState[A] = event match {
				case Start(name) =>
					// push to the stack
					stack :+= name
					if(consumer.isEmpty){
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
						case None => if(stack.isEmpty) Done(Empty) else Working

						case Some(c) => c.handleEvent(End) match {
							/*
							If the inner consumer finished, emit its result...
							Unless there is no more stack, in which we are done,
							so finish with the consumer's result.
							 */
							case Done(result) =>
								consumer = None
								if(stack.isEmpty) Done(result) else Emit(result)

							/*
							If the inner consumer is still working, we still might need
							to feed it an EOF if we left the 'matched' portion of the stack.
							 */
							case Working =>
								if(matchStack(stack).isEmpty){
									// force the consumer to finish by passing EOF
									consumer = None
									c.handleEOF() match {
										// finished after EOF - emit/finish with that result
										case Done(result) => if(stack.isEmpty) Done(result) else Emit(result)
										// still working after EOF - no results
										case Working => if(stack.isEmpty) Done(Empty) else Working
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

	//*****************************************************

	val myStream = Stream.of(exampleEvents)

	val mySplitter = new StackBasedSplitter {
		override def matchStack(stack: List[String]): Option[List[String]] = stack match {
			case "A" :: "B" :: list => Some(list)
			case _ => None
		}
	}

	val myTransformer = mySplitter.joinWith(TextConsumer)

	val myConsumer = Consumer.foreachResult[String]{ result =>
		println(s"RESULT { $result }")
	}

	val result = myStream
		.logAs("stream")
		.transformWith(myTransformer)
		 .logAs("transformed")
		.drive(myConsumer)
	println(s"END WITH { $result }")

	//*****************************************************

}
