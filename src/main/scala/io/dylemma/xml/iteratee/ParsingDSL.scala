package io.dylemma.xml.iteratee

import javax.xml.stream.XMLStreamException
import javax.xml.stream.events.XMLEvent
import scala.concurrent.ExecutionContext

import io.dylemma.xml.event._
import io.dylemma.xml.iteratee.IterateeHelpers._
import play.api.libs.functional.{ FunctionalCanBuild, Functor, ~ }
import play.api.libs.iteratee.{ Enumeratee, Iteratee }

/**
 * Created by dylan on 10/10/2015.
 */
object ParsingDSL {

	/*
	Eventually want to support wildcard paths e.g.
	__ \ foo \ * \ bar
	__ \ foo \ ** \ baz
	__ \ ** \ baz
	__ \ foo \ **
	 */

	trait PathSpecification {
		def lastSegment: String
		def asFilter: List[String] => Boolean

		def \(segment: String): PathSpecification

		def \(text: Text.type): Parser[String] = new PreTextParser(this).parseConsume() //makeConcatParser
		def \(text: Text.asList.type): Parser[List[String]] = new PreTextParser(this).parseList //makeListParser

		def %(attribute: String): Parser[String] = new MandatoryAttributeParser(this, attribute).parseSingle
		def %?(attribute: String): Parser[Option[String]] = new OptionalAttributeParser(this, attribute).parseSingle

		def as[T: Parser] = DelegateParser[T](this, implicitly).parseSingle
		def asOptional[T: Parser] = DelegateParser[T](this, implicitly).parseOptional
		def asList[T: Parser] = DelegateParser[T](this, implicitly).parseList
		def asProducer[T: Parser](consumer: Iteratee[ParserResult[T], Unit]) = DelegateParser[T](this, implicitly).parseSideEffect(consumer)

		def asEnumeratee[To](consumer: Iteratee[XMLEvent, To])(implicit ec: ExecutionContext): Enumeratee[XMLEvent, To] = {
			subdivideOnState(TagStackAccumulator, asFilter).combineWith(consumer)
		}
	}

	class SimplePathSpec(startingSegments: List[String]) extends PathSpecification {
		def lastSegment = startingSegments.last
		def asFilter = _ startsWith startingSegments
		def \(segment: String): PathSpecification = new SimplePathSpec(startingSegments :+ segment)
	}


	class SkipOnePathSpec(startingSegments: List[String]) extends PathSpecification {
		def lastSegment = startingSegments.last
		def asFilter = {
			case Nil => false
			case _ :: path => path startsWith startingSegments
		}
		def \(segment: String): PathSpecification = new SkipOnePathSpec(startingSegments :+ segment)
	}

	object XML extends SimplePathSpec(Nil)
	object Elem extends SkipOnePathSpec(Nil)

	/** This object allows a Parser to be combined with other parsers via
		* `and` or `~` when you import `play.api.libs.functional.syntax._`
		*/
	implicit object FunctionCanBuildParser extends FunctionalCanBuild[Parser] {
		def apply[A, B](pa: Parser[A], pb: Parser[B]) = new Parser[A ~ B]{
			def toIteratee(implicit ec: ExecutionContext): Iteratee[XMLEvent, ParserResult[A ~ B]] = {
				Enumeratee.zipWith(pa.toIteratee, pb.toIteratee) { (ra, rb) =>
					import play.api.libs.functional.{ ~ => Tilde }
					//println(s"Combine $ra + $rb")
					for (a <- ra; b <- rb) yield Tilde(a, b)
				}
			}
		}
	}

	implicit object FunctorForParser extends Functor[Parser] {
		def fmap[A, B](m: Parser[A], f: A => B): Parser[B] = new Parser[B] {
			def toIteratee(implicit ec: ExecutionContext) = {
				m.toIteratee.map{ result => result.map(f) }
			}
		}
	}

	case class ParserExample(attrThing: String, fooText: String, barText: String)
	object ParserExample {
		import play.api.libs.functional.syntax._

		val parser: Parser[ParserExample] = (
			(XML \ "foo" % "attrThing") and
			(XML \ "foo" \ Text) and
			(XML \ "bar" \ Text)
		)(ParserExample.apply _)

	}

	class OptionalAttributeParser(pathSpec: PathSpecification, attribute: String) extends ParserCreator[Option[String]] {
		def toEnumeratee(implicit ec: ExecutionContext) = {
			subdivideOnState(TagStackAccumulator, pathSpec.asFilter).combineWith[ParserResult[Option[String]]] {
				val lookupAttr = Enumeratee.collect[XMLEvent] { case StartElement(_, attrs) => Success(attrs get attribute) }
				lookupAttr &>> Iteratee.head.map {
					// if the *head* was None, it means we never even encountered an element, so give an `Empty` result
					case None => Empty
					// otherwise, some element was encountered, and the result is whatever optional attribute value was there
					case Some(result) => result
				}
			}
		}
	}

	class MandatoryAttributeParser(pathSpec: PathSpecification, attribute: String) extends ParserCreator[String] {
		def toEnumeratee(implicit ec: ExecutionContext) = {
			subdivideOnState(TagStackAccumulator, pathSpec.asFilter).combineWith[ParserResult[String]] {
				val lookupAttr = Enumeratee.collect[XMLEvent]{
					case e @ StartElement(_, attrs) => attrs.get(attribute) match {
						case None =>
							val msg = s"Expected a value for the '$attribute' attribute, but none was found"
							Error(new XMLStreamException(msg, e.getLocation))
						case Some(value) =>
							Success(value)
					}
				}
				// if no elements were found, return Empty rather than an error
				lookupAttr &>> Iteratee.head.map { _ getOrElse Empty }
			}
		}
	}

	object Text {
		object asList
	}

	class PreTextParser(pathSpec: PathSpecification) extends ParserCreator[String] {
		def toEnumeratee(implicit ec: ExecutionContext) = subdivideOnState(TagStackAccumulator, pathSpec.asFilter).combineWith{
			val collectText = Enumeratee.collect[XMLEvent] { case Characters(text) => text.trim }
			val consumeTextAsSuccess = Iteratee.consume[String]().map(Success(_))
			collectText &>> consumeTextAsSuccess
		}
	}

	case class DelegateParser[T](pathSpec: PathSpecification, innerParser: Parser[T]) extends ParserCreator[T] {
		def toEnumeratee(implicit ec: ExecutionContext) = {
			subdivideOnState(TagStackAccumulator, pathSpec.asFilter).combineWith(innerParser.toIteratee)
		}
	}

	object TagStackAccumulator extends StateAccumulator[List[String], XMLEvent] {
		def init = Nil
		def update(stack: List[String], event: XMLEvent) = event match {
			case StartElement(Name(tag), _) => stack :+ tag
			case EndElement(_) => stack dropRight 1
			case _ => stack
		}
	}
}
