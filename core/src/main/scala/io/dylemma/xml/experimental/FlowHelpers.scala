package io.dylemma.xml.experimental

import akka.stream.{Attributes, Outlet, Inlet, FlowShape}
import akka.stream.scaladsl.Flow
import akka.stream.stage._
import io.dylemma.xml.Result
import io.dylemma.xml.Result.{Empty, Error, Success}

import scala.collection.generic.CanBuildFrom

/**
	* Created by dylan on 2/6/2016.
	*/
object FlowHelpers {

	trait ResultTransformingGraphStage[A, B] extends GraphStage[FlowShape[Result[A], Result[B]]] {
		val in: Inlet[Result[A]] = Inlet("ResultTransformerIn")
		val out: Outlet[Result[B]] = Outlet("ResultTransformerOut")
		override val shape = FlowShape(in, out)
	}

	trait ConsumerHandler[A, B] {
		def onInput(input: Result[A]): Option[Result[B]]
		def onEnd(): Result[B]
	}

	def fromConsumerHandler[A, B](makeHandler: () => ConsumerHandler[A, B]) = Flow.fromGraph{
		new ResultTransformingGraphStage[A, B] {
			def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape){
				val handler = makeHandler()
				var gotInput = false
				setHandler(in, new InHandler {
					def onPush() = {
						val a = grab(in)
						gotInput = true
						val bOpt = handler.onInput(a)
						if(bOpt.isDefined){
							push(out, bOpt.get)
							completeStage()
						} else {
							pull(in)
						}
					}
					override def onUpstreamFinish() = {
						if(gotInput) push(out, handler.onEnd())
						completeStage()
					}
				})

				setHandler(out, new OutHandler {
					def onPull() = pull(in)
				})
			}
		}
	}

	def first[A] = fromConsumerHandler{ () =>
		new ConsumerHandler[A, A] {
			def onInput(input: Result[A]) = {
				if(input.isEmpty) None
				else Some(input)
			}
			def onEnd() = Empty
		}
	}

	def firstOption[A] = fromConsumerHandler{ () =>
		new ConsumerHandler[A, Option[A]] {
			def onInput(input: Result[A]): Option[Result[Option[A]]] = {
				if(input.isEmpty) None
				else Some(input.map(Some(_)))
			}
			def onEnd() = Success.none
		}
	}

	def toList[A] = fromConsumerHandler{ () =>
		new ConsumerHandler[A, List[A]] {
			private val builder = List.newBuilder[A]
			def onInput(input: Result[A]) = input match {
				case Success(a) =>
					builder += a
					None
				case Empty =>
					None
				case e: Error =>
					Some(e)
			}
			def onEnd() = Result(builder.result)
		}
	}

	def concat[A, B, That](implicit t: A => TraversableOnce[B], bf: CanBuildFrom[A, B, That]) = fromConsumerHandler{ () =>
		new ConsumerHandler[A, That] {
			private val builder = bf()
			def onInput(input: Result[A]) = input match {
				case Success(a) =>
					builder ++= a
					None
				case Empty =>
					None
				case e: Error =>
					Some(e)
			}
			def onEnd() = Result(builder.result())
		}
	}

	def takeUntilNthError[A](n: Int) = Flow.fromGraph(new ResultTransformingGraphStage[A, A] {
		def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape){

			var errorCount = 0

			setHandler(in, new InHandler {
				def onPush() = {
					val e = grab(in)
					if(e.isError){
						errorCount += 1
						if(errorCount >= n) completeStage()
						else push(out, e)
					} else {
						push(out, e)
					}
				}
			})

			setHandler(out, new OutHandler {
				def onPull() = pull(in)
			})
		}
	})

	def takeThroughNthError[A](n: Int) = Flow.fromGraph(new ResultTransformingGraphStage[A, A] {
		def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape){

			var errorCount = 0

			setHandler(in, new InHandler {
				def onPush() = {
					val e = grab(in)
					if(e.isError){
						errorCount += 1
						push(out, e)
						if(errorCount >= n) completeStage()
					} else {
						push(out, e)
					}
				}
			})

			setHandler(out, new OutHandler {
				def onPull() = pull(in)
			})
		}
	})
}
