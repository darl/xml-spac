package io.dylemma.xml.experimental

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Flow, Source}

import scala.concurrent.Await
import scala.concurrent.duration._

object Playground extends App {

	implicit val system = ActorSystem("reactive-playground")
	implicit val materializer = ActorMaterializer()

	/*
	val rawXml = """<?xml version="1.0" encoding="UTF-8"?>
		|<stuff>
		|    <a foo="doo" bar="flar">This is my A</a>
		|    <b>This is my B</b>
		|    <cs>
		|        <c>This is the first C</c>
		|        <c>This is the second C</c>
		|    </cs>
		|</stuff>""".stripMargin

	val xmlSrc = XmlEventPublisher(rawXml)
	val result = xmlSrc.runForeach(println)
	*/

	val nums = Source(List(0, 0, 1, 0, 0, 2, 3, 4, 0, 0, 2, 6, 0, 0))

	// split when the current value matches and the previous didn't
	case class SplitterState(previousDidMatch: Boolean, currentMatches: Boolean, current: Int) {
		def advance(i: Int, matches: Boolean) = SplitterState(currentMatches, matches, i)
		def isNewlyMatched = currentMatches && !previousDidMatch
	}
	object SplitterState {
		val zero = SplitterState(false, false, 0)
	}

	def toListSink[T] = Sink.fold[List[T], T](Nil)(_ :+ _)
	def toListFlow[T] = Flow[T].fold[List[T]](Nil)(_ :+ _)

	val splitter = Flow[Int]
		.scan(SplitterState.zero){ (state, next) => state.advance(next, next != 0) }
		.dropWhile(!_.currentMatches)
		.splitWhen(_.isNewlyMatched)
		.takeWhile(_.currentMatches)
		.map(_.current)
		.via(toListFlow[Int])
		.mergeSubstreams

	val result = nums via splitter runForeach println

	Await.ready(result, 5.seconds)
	println(result.value)
	system.shutdown()
}
