package io.dylemma.xml.experimental

import javax.xml.stream.events.StartElement

import io.dylemma.xml.Result.{Success, Empty}
import io.dylemma.xml.{ContextCombiner, Result}

/** A function that attempts to extract a "context" value from an XML stack.
  * Context values are extract as `Result` instances (i.e. a non-match should
  * return `Empty` and a match should return a `Success`).
  *
  * @tparam A The type of the extracted context.
  */
trait ContextMatcher[+A] extends Splitter[A] with (List[StartElement] => Result[A]) { self =>

	def rmapContext[B](f: Result[A] => Result[B]): ContextMatcher[B] = new ContextMatcher[B] {
		def apply(stack: List[StartElement]) = f(self(stack))
	}

	def through[Out](parser: Parser[A, Out]): Transformer[Out] = {
		Transformer.fromSplitterAndJoiner(this, parser)
	}
}

/** Specialization of ContextMatcher that allows combination with other matchers, forming a chain
  * which inductively matches the XML stack and combines individual results.
  *
  * @tparam A The type of the extracted context.
  */
trait ChainingContextMatcher[+A] extends ContextMatcher[A] { self =>
	protected def matchSegment(stack: List[StartElement]): Result[(A, List[StartElement])]

	def apply(stack: List[StartElement]) = matchSegment(stack).map(_._1)

	def /[B, AB](next: ChainingContextMatcher[B])(implicit c: ContextCombiner[A, B, AB]): ChainingContextMatcher[AB] = {
		new ChainingContextMatcher[AB] {
			protected def matchSegment(stack: List[StartElement]) = {
				for {
					(leadMatch, leadTail) <- self.matchSegment(stack)
					(nextMatch, remainingStack) <- next.matchSegment(leadTail)
				} yield c.combine(leadMatch, nextMatch) -> remainingStack
			}
		}
	}
}

/** A further specialization of ChainingContextMatcher which matches exactly one element
  * from the XML stack.
  *
  * @tparam A The type of the extracted context.
  */
trait SingleElementContextMatcher[+A] extends ChainingContextMatcher[A] { self =>
	protected def matchElement(elem: StartElement): Result[A]

	protected def matchSegment(stack: List[StartElement]) = stack match {
		case Nil => Empty
		case head :: tail => matchElement(head).map(_ -> tail)
	}

	// import alias this trait because of the long class name
	import io.dylemma.xml.experimental.{SingleElementContextMatcher => Match1}

	def &[B, AB](that: Match1[B])(implicit c: ContextCombiner[A, B, AB]): Match1[AB] = new Match1[AB] {
		protected def matchElement(elem: StartElement): Result[AB] = {
			for{
				a <- self.matchElement(elem)
				b <- that.matchElement(elem)
			} yield c.combine(a, b)
		}
	}

	def |[A1 >: A](that: Match1[A1]): Match1[A1] = new Match1[A1] {
		protected def matchElement(elem: StartElement): Result[A1] = {
			self.matchElement(elem) orElse that.matchElement(elem)
		}
	}

	override def mapContext[B](f: A => B): Match1[B] = new Match1[B] {
		protected def matchElement(elem: StartElement) = self.matchElement(elem).map(f)
	}
	override def rmapContext[B](f: Result[A] => Result[B]): Match1[B] = new Match1[B] {
		protected def matchElement(elem: StartElement) = f(self matchElement elem)
	}

	// common functionality for the [and]extract[q]name functions
	protected def expandMatch[B](f: (A, StartElement) => B): Match1[B] = new Match1[B] {
		protected def matchElement(elem: StartElement) = {
			self.matchElement(elem).map{ a => f(a, elem) }
		}
	}

	def extractQName = expandMatch { case (_, elem) => elem.getName }
	def extractName = expandMatch { case (_, elem) => elem.getName.getLocalPart }

	def andExtractQName = expandMatch { case (a, elem) => a -> elem.getName }
	def andExtractName = expandMatch { case (a, elem) => a -> elem.getName.getLocalPart }
}

object SingleElementContextMatcher {
	def predicate(f: StartElement => Boolean): SingleElementContextMatcher[Unit] = {
		new SingleElementContextMatcher[Unit] {
			protected def matchElement(elem: StartElement) = {
				if(f(elem)) Success.unit else Empty
			}
		}
	}

	def apply[A](f: StartElement => Option[A]): SingleElementContextMatcher[A] = {
		new SingleElementContextMatcher[A] {
			protected def matchElement(elem: StartElement) = Result fromOption f(elem)
		}
	}
}