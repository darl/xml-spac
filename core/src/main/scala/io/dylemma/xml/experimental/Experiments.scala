package io.dylemma.xml.experimental

import javax.xml.stream.events.XMLEvent

import io.dylemma.xml.Result._
import io.dylemma.xml.event._

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

	object TextConsumer extends Consumer[XMLEvent, String] {
		def makeHandler() = new Handler[XMLEvent, String, ConsumerState] {
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

	val mySplitter = new StackBasedSplitter {
		override def matchStack(stack: List[Tag]): Option[List[Tag]] = stack match {
			case Tag("A") :: Tag("B") :: list => Some(list)
			case _ => None
		}
	}

	val myTransformer = mySplitter.through(TextConsumer)

	val myConsumer = Consumer.foreachResult[String]{ result =>
		println(s"RESULT { $result }")
	}


	val result = Stream.ofXml(exampleXml)
		.logAs("stream")
		.transformWith(myTransformer)
		.drive(myConsumer)

	println(s"END WITH { $result }")

	//*****************************************************

}
