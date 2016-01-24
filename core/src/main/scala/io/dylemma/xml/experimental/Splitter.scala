package io.dylemma.xml.experimental

import javax.xml.stream.events.{ StartElement => OpenTag, XMLEvent }

import io.dylemma.xml.{MapR, ContextCombiner, Result}
import io.dylemma.xml.Result.{ Success, Empty, Error }
import io.dylemma.xml.event.EndElement

trait Splitter[+In, E] {
	def through[A](parser: ParserForContext[In, E, A]): Transformer[E, A]

	@inline def as[T](implicit parser: ParserForContext[In, E, T]): Parser[E, T] = through(parser).parseSingle
	@inline def asOptional[T](implicit parser: ParserForContext[In, E, T]): Parser[E, Option[T]] = through(parser).parseOptional
	@inline def asList[T](implicit parser: ParserForContext[In, E, T]): Parser[E, List[T]] = through(parser).parseList
	@inline def foreach[T](f: T => Unit)(implicit parser: ParserForContext[In, E, T]): Parser[E, Unit] = through(parser).foreach(f)
	@inline def foreachResult[T](f: Result[T] => Unit)(implicit parser: ParserForContext[In, E, T]): Parser[E, Unit] = through(parser).foreachResult(f)

}

trait XmlSplitter[+In] extends Splitter[In, XMLEvent] {
	@inline def attr(attribute: String) = through(Parser parseMandatoryAttribute attribute).parseSingle
	@inline def %(attribute: String) = attr(attribute)

	@inline def attrOpt(attribute: String) = through(Parser parseOptionalAttribute attribute).parseSingle
	@inline def %?(attribute: String) = attrOpt(attribute)

	@inline def text = through(Parser.parseXmlText)
	@inline def textConcat = text.parseConcat()
	@inline def textOption = text.parseOptional
	@inline def textList = text.parseList
}

trait XmlContextSplitter[+In] extends XmlSplitter[In] { self =>

	/** If there's a match, return the remaining (tail) parts of the list
		* following the matched prefix. Returning `Some(Nil)` implies that
		* a matching scope has just opened.
		*/
	def matchStack(stack: List[OpenTag]): Option[(Result[In], List[OpenTag])]

	def through[A](parser: ParserForContext[In, XMLEvent, A]): Transformer[XMLEvent, A] = new Transformer[XMLEvent, A] {
		def makeHandler() = new Handler[XMLEvent, A, TransformerState] {
			var stack: List[OpenTag] = Nil
			var innerHandler: Option[Handler[XMLEvent, A, ParserState]] = None

			def handleEvent(event: XMLEvent): TransformerState[A] = event match {
				case _ if event.isStartElement =>
					// push to the stack
					stack :+= event.asStartElement
					if (innerHandler.isEmpty) {
						matchStack(stack) match {
							case Some((contextResult, Nil)) =>
								contextResult match {
									case Success(context) => innerHandler = Some(parser makeHandler context)
									case Empty => innerHandler = None
									case Error(err) => innerHandler = Some(Parser.done[A](Error(err)))
								}
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
									val eofResult = c.handleEOF() match {
										// finished after EOF - emit/finish with that result
										case Done(result) => if (stack.isEmpty) Done(result) else Emit(result)
										// still working after EOF - no results
										case Working => if (stack.isEmpty) Done(Empty) else Working
									}
									eofResult
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

	// TODO: implement the DSL methods to mirror the original Splitter api
}

object XmlContextSplitter {

	trait ByTag[+A] extends XmlContextSplitter[A] {
		self =>

		def matchSingle(tag: OpenTag): Result[A]

		def matchStack(stack: List[OpenTag]): Option[(Result[A], List[OpenTag])] = stack match {
			case Nil => None
			case head :: tail => Some(matchSingle(head) -> tail)
		}

		/** Create a new matcher that will match the same tag stack as this matcher,
			* except that it will return the matched tag's name instead of this matcher's
			* regular results.
			*/
		def extractName: ByTag[String] = new ByTag[String] {
			def matchSingle(tag: OpenTag) = {
				val innerResult = self matchSingle tag
				// throw out the actual result and return the tag's name
				innerResult map { _ => tag.getName.getLocalPart }
			}
		}

		/** Wrap this matcher so that matched results will additionally include the name
			* of the matched tag. Uses `ContextCombiner` rules to determine the resulting
			* context type by combining this matcher's type with `String`.
			*
			* @param rc The result combiner for this matcher's type (`A`), and `String`
			* @tparam AB The combination of `A` and `String`
			*/
		def andExtractName[AB](implicit rc: ContextCombiner[A, String, AB]): ByTag[AB] = {
			new ByTag[AB] {
				def matchSingle(tag: OpenTag): Result[AB] = {
					for {
						innerResult <- self matchSingle tag
						nameResult <- Result(tag.getName.getLocalPart)
					} yield rc.combine(innerResult, nameResult)
				}
			}
		}

		@inline def &[B, AB](that: ByTag[B])(implicit rc: ContextCombiner[A, B, AB]) = ByTag.and(this, that)
		@inline def and[B, AB](that: ByTag[B])(implicit rc: ContextCombiner[A, B, AB]) = ByTag.and(this, that)

		@inline def |[A1 >: A](that: ByTag[A1]) = ByTag.or(this, that)
		@inline def or[A1 >: A](that: ByTag[A1]) = ByTag.or(this, that)

		@inline def /[B, AB](next: ByTag[B])(implicit c: ContextCombiner[A, B, AB]) = {
			ByStack.inductive(ByStack single this, next)
		}
	}

	object ByTag {
		def apply[A](f: OpenTag => Option[A]): ByTag[A] = new ByTag[A] {
			def matchSingle(tag: OpenTag): Result[A] = Result fromOption { f(tag) }
		}

		// save one instance to avoid creating new ones during predicate.matchSingle
		private val blankSuccess = Success(())

		def predicate(f: OpenTag => Boolean): ByTag[Unit] = new ByTag[Unit]{
			def matchSingle(tag: OpenTag): Result[Unit] = blankSuccess.withFilter(_ => f(tag))
		}

		def and[A, B, AB](left: ByTag[A], right: ByTag[B])(implicit c: ContextCombiner[A, B, AB]): ByTag[AB] = new ByTag[AB] {
			def matchSingle(tag: OpenTag): Result[AB] = for {
				a <- left matchSingle tag
				b <- right matchSingle tag
			} yield c.combine(a, b)
		}

		def or[A](left: ByTag[A], right: ByTag[A]): ByTag[A] = new ByTag[A] {
			def matchSingle(tag: OpenTag): Result[A] = {
				left.matchSingle(tag) orElse right.matchSingle(tag)
			}
		}

		implicit object ByTagMapR extends MapR[ByTag] {
			def mapR[A, B](m: ByTag[A], f: (Result[A]) => Result[B]): ByTag[B] = new ByTag[B] {
				def matchSingle(tag: OpenTag): Result[B] = f(m.matchSingle(tag))
			}
		}
	}

	trait ByStack[+A] extends XmlContextSplitter[A] {
		@inline def /[B, AB](next: ByTag[B])(implicit c: ContextCombiner[A, B, AB]) = {
			ByStack.inductive(this, next)
		}
	}

	object ByStack {

		def single[A](byTag: ByTag[A]): ByStack[A] = new ByStack[A] {
			def matchStack(stack: List[OpenTag]) = byTag.matchStack(stack)
		}

		def inductive[A, B, AB](leading: ByStack[A], next: ByTag[B])(implicit c: ContextCombiner[A, B, AB])
			: ByStack[AB] = new ByStack[AB] {
			def matchStack(stack: List[OpenTag]) = for {
				(resultA, leadTail) <- leading matchStack stack
				(resultB, nextTail) <- next matchStack leadTail
			} yield {
				val combinedResult = for {
					a <- resultA
					b <- resultB
				} yield c.combine(a, b)
				combinedResult -> nextTail
			}
		}

		implicit object ByStackMapR extends MapR[ByStack] {
			def mapR[A, B](ma: ByStack[A], f: (Result[A]) => Result[B]) = new ByStack[B] {
				def matchStack(stack: List[OpenTag]) = {
					ma.matchStack(stack).map{
						case (resultA, stackTail) => f(resultA) -> stackTail
					}
				}
			}
		}
	}
}
