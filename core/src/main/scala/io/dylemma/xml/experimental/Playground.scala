package io.dylemma.xml.experimental

import javax.xml.stream.events.{StartElement, XMLEvent}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Flow, Source}
import akka.stream.stage.{TerminationDirective, SyncDirective, Context, PushPullStage}
import io.dylemma.xml.Result
import io.dylemma.xml.Result.{Success, Empty}

import scala.concurrent.Await
import scala.concurrent.duration._

object Playground extends App {

	implicit val system = ActorSystem("reactive-playground")
	implicit val materializer = ActorMaterializer()

	val rawXml = """<?xml version="1.0" encoding="UTF-8"?>
		|<stuff>
		|    <a foo="doo" bar="flar">This is my A</a>
		|    <b>This is my B</b>
		|    <cs>
		|        <c>This is the first C</c>
		|        <c>This is the second C</c>
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

	def innerTransformer = new PushPullStage[Int, List[Int]] {
		var buffer = List.newBuilder[Int]

		def onPush(elem: Int, ctx: Context[List[Int]]): SyncDirective = {
			println(s"receiving $elem")
			buffer += elem
			Thread.sleep(500)
			ctx.pull()
		}
		def onPull(ctx: Context[List[Int]]): SyncDirective = {
			if(ctx.isFinishing){
				println("finishing up from onPull")
				ctx.pushAndFinish(buffer.result)
			} else {
				ctx.pull()
			}
		}
		override def onUpstreamFinish(ctx: Context[List[Int]]): TerminationDirective = {
			println("absorbing termination")
			ctx.absorbTermination()
		}
	}

	case class XmlStackState[T](currentEvent: XMLEvent, stack: List[StartElement], matchedContext: Result[T], matchDepth: Int)

	def xmlStackScanner[T](matchContext: List[StartElement] => Result[T]) = {
		val initialState = XmlStackState[T](null, Nil, Empty, -1)

		Flow[XMLEvent].scan(initialState){ (state, event) =>
			// If the event is a StartElement, the context may be growing, so we should check for context matches.
			// If the event is an EndElement, we should check if the stack shrank below the matchDepth, implying the context no longer matches.
			// Other events don't affect the stack, and therefore will not affect the matched context

			if(event.isStartElement){

				// update the stack, and possibly the match state
				val start = event.asStartElement
				val newStack = state.stack :+ start
				if(state.matchedContext.isEmpty){
					val newMatch = matchContext(newStack)
					val newDepth = if(newMatch.isSuccess) newStack.size else state.matchDepth
					XmlStackState(event, newStack, newMatch, newDepth)
				} else {
					XmlStackState(event, newStack, state.matchedContext, state.matchDepth)
				}
			} else if(event.isEndElement){

				// update the stack, possibly clearing the match state
				val newStack = state.stack dropRight 1
				val newDepth = newStack.length
				if(newDepth < state.matchDepth){
					XmlStackState(event, newStack, Empty, -1)
				} else {
					XmlStackState(event, newStack, state.matchedContext, state.matchDepth)
				}
			} else {

				// just update the currentEvent, not touching the stack or match states
				state.copy(currentEvent = event)
			}
		}
	}

	val splitter = Flow[Int]
		.scan(SplitterState.zero){ (state, next) => state.advance(next, next != 0) }
		.dropWhile(!_.currentMatches)
		.splitWhen(_.isNewlyMatched)
		.takeWhile(_.currentMatches)
		.map(_.current)
		.transform(() => innerTransformer)
		.concatSubstreams

	// val result = nums via splitter runForeach println
	val result = xmlSrc via xmlStackScanner(matchCSContext) runForeach println

	Await.ready(result, 5.seconds)
	println(result.value)
	system.shutdown()
}
