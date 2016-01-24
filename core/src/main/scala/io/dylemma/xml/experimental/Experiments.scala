package io.dylemma.xml.experimental

import javax.xml.stream.events.{StartElement => Tag, XMLEvent}


import io.dylemma.xml.Result
import io.dylemma.xml.Result._
import io.dylemma.xml.event._
import io.dylemma.xml.{Chain => ~}

object Experiments extends App with ParserCombinerOps {

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

	val mySplitter = new XmlContextSplitter[String] {
		override def matchStack(stack: List[Tag]): Option[(Result[String], List[Tag])] = stack match {
			case StartElement(Name("A"), _) :: StartElement(Name("B"), _) :: list => Some(Success("ab") -> list)
			case _ => None
		}
	}

	val tp: ParserForContext[Any, XMLEvent, String] = TextParser

	val combinedJoiner = TextParser & Parser.inContext[String, XMLEvent]
	val combinedJoiner2 = Parser.inContext[String, XMLEvent] & TextParser

	val myTransformer = mySplitter.through(combinedJoiner)

	val myConsumer = Parser.foreachResult[String ~ String]{ result =>
		println(s"RESULT { $result }")
	}

	case class Comment(postId: Int, user: String, text: String)
	;{
		import ParsingDSL.{ParserWithCombine => _, _}

		val rawXml = s"""<blog>
		| <post id="1">
		|  <comment user="bob">Hello there</comment>
		|  <comment user="alice">Oh, hi</comment>
		| </post>
		| <post id="2">
		|  <comment user="carla">Test comment!</comment>
		|  <comment user="dylan">I'm testing too!</comment>
		| </post>
		|</blog>""".stripMargin

		case class User(name: String)
		implicit val userParser = ("comment" % "user").map(User)

		case class Content(text: String)
		/*implicit*/ val contentParser = ("comment" % Text)//.map(Content)

		val splitter = (Root / "blog" / "post" / "comment")

		val userHandler = splitter.foreachResult[String]{
			user => println(s"Got content: $user")
		}(contentParser)

		implicit val CommentParser: ParserForContext[Int, XMLEvent, Comment] = (
			inContext[Int] &
			(* % "user") &
			(* % Text)
		).join(Comment.apply)

		val contextMatcher = Root / "blog" / ("post" & attr("id")) / "comment" map (_.toInt)

		val handler = contextMatcher.foreach[Comment](println)
		val result = Stream.ofXml(rawXml)/*.logAs("stream")*/.drive(handler)
		println(s"END WITH { $result }")
	}

//	val result = Stream.ofXml(exampleXml)
//		.logAs("stream")
//		.transformWith(myTransformer)
//		.drive(myConsumer)

//	println(s"END WITH { $result }")

	//*****************************************************

}
