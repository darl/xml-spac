package io.dylemma.xml.experimental

import javax.xml.namespace.QName
import javax.xml.stream.events.StartElement

import akka.actor.ActorSystem
import akka.stream.{FlowShape, ActorMaterializer}
import akka.stream.scaladsl._
import io.dylemma.xml.Result
import io.dylemma.xml.Result.{Empty, Success}

import scala.concurrent.Await
import scala.concurrent.duration._

object Playground extends App {

	implicit val system = ActorSystem("reactive-playground")
	implicit val materializer = ActorMaterializer()

	val rawXml = """<?xml version="1.0" encoding="UTF-8"?>
		|<stuff>
		|    <a foo="doo" bar="flar">This is my A</a>
		|    <b>This is my B</b>
		|    <cs bloop="bleep">
		|        <c>This is the first C</c>
		|        <c>This is the second C</c>
		|    </cs>
		|    <cs>
		|        This is my jam!
		|        <c>Whoops</c>
		|    </cs>
		|</stuff>""".stripMargin

	def matchCSContext(stack: List[StartElement]): Result[String] = stack match {
		case e1 :: e2 :: _ =>
			if(e1.getName.getLocalPart == "stuff" && e2.getName.getLocalPart == "cs")
				Success("yay")
			else
				Empty
		case _ =>
			Empty
	}

	val xmlSrc = XmlEventPublisher(rawXml)

	// split when the current value matches and the previous didn't
	case class SplitterState[T](previousDidMatch: Boolean, currentMatches: Boolean, current: T) {
		def advance(item: T, matches: Boolean) = SplitterState(currentMatches, matches, item)
		def isNewlyMatched = currentMatches && !previousDidMatch
	}

	def groupByConsecutiveMatches[T] = Flow[XmlStackState[T]]
		.scan(SplitterState(false, false, XmlStackState.initial[T])){ (state, next) => state.advance(next, next.matchedContext.isSuccess) }
		.dropWhile(!_.currentMatches)
		.splitWhen(_.isNewlyMatched)
		.takeWhile(_.currentMatches)
		.map(_.current)

	def toListSink[T] = Sink.fold[List[T], T](Nil)(_ :+ _)
	def toListFlow[T] = Flow[T].fold[List[T]](Nil)(_ :+ _)

	def complexParserFlow[T] = Flow.fromGraph(GraphDSL.create(){ implicit b =>
		import GraphDSL.Implicits._

		// prepare graph elements
		val broadcast = b.add(Broadcast[XmlStackState[T]](2))
		val zip = b.add(ZipWith[Result[Option[String]], Result[String], String]{
			case (attr, text) => s"cs: attr=$attr, text=${text.map(_.replaceAllLiterally("\n", "\\n"))}"
		})

		val attrParser = Parser.forOptionalAttribute("bloop")
		val textParser = Parser.forText

		// connect the graph
		broadcast.out(0).via(attrParser.asFlow) ~> zip.in0
		broadcast.out(1).via(textParser.asFlow) ~> zip.in1

		FlowShape(broadcast.in, zip.out)
	})


	def xmlSplitter[T] = groupByConsecutiveMatches[T]
		.via(complexParserFlow)
		.concatSubstreams

	val result = xmlSrc via XmlStackState.scanner(matchCSContext) via xmlSplitter runForeach println

	Await.ready(result, 5.seconds)
	println(result.value)
	system.shutdown()
}
