package io.dylemma.xml.experimental

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

	object TextConsumer extends Consumer[Event, String] {
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
