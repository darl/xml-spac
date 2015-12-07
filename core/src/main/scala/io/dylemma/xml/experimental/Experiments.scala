package io.dylemma.xml.experimental

import javax.xml.stream.events.{StartElement => Tag, XMLEvent}


import io.dylemma.xml.Result
import io.dylemma.xml.Result._
import io.dylemma.xml.event._
import io.dylemma.xml.{Chain => ~}

object Experiments extends App {

	val exampleXml = """<A>
	| <B>
	|   Hello
	|   <C>Hello2</C>
	| </B>
	| <B>
	|   Goodbye
	|   Goodbye2
	| </B>
	|</A>""".stripMargin

	//*****************************************************

	object TextParser extends Parser[XMLEvent, String] {
		def makeHandler(context: Any) = new Handler[XMLEvent, String, ParserState] {
			val sb = new StringBuilder
			def handleEvent(event: XMLEvent) = {
				event match {
					case Characters(text) => sb append text
					case _ =>
				}
				Working
			}
			def handleError(err: Throwable) = Done(Error(err))
			def handleEOF() = Done(Success(sb.result))
		}
	}

	//*****************************************************

	val mySplitter = new StackBasedSplitter[String] {
		override def matchStack(stack: List[Tag]): Option[(Result[String], List[Tag])] = stack match {
			case StartElement(Name("A"), _) :: StartElement(Name("B"), _) :: list => Some(Success("ab") -> list)
			case _ => None
		}
	}

	val tp: ParserForContext[Any, XMLEvent, String] = TextParser

	val combinedJoiner = TextParser & Parser.inContext[String, XMLEvent]

	val myTransformer = mySplitter.through(combinedJoiner)

	val myConsumer = Parser.foreachResult[String ~ String]{ result =>
		println(s"RESULT { $result }")
	}

	val result = Stream.ofXml(exampleXml)
		.logAs("stream")
		.transformWith(myTransformer)
		.drive(myConsumer)

	println(s"END WITH { $result }")

	//*****************************************************

}
