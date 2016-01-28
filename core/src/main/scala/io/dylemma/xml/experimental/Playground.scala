package io.dylemma.xml.experimental

import javax.xml.namespace.QName
import javax.xml.stream.events.StartElement

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink}
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


	def xmlSplitter[T] = groupByConsecutiveMatches[T]
		.transform(() => HandlerStages.getOptionalAttribute(new QName("bloop")))
		.concatSubstreams

	val result = xmlSrc via XmlStackState.scanner(matchCSContext) via xmlSplitter runForeach println

	Await.ready(result, 5.seconds)
	println(result.value)
	system.shutdown()
}
