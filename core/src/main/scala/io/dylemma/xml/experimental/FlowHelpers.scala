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

	trait ConsumerGraphStage[A, B] extends GraphStage[FlowShape[Result[A], Result[B]]] {
		val in: Inlet[Result[A]] = Inlet("ConsumerIn")
		val out: Outlet[Result[B]] = Outlet("ConsumerOut")
		override val shape = FlowShape(in, out)
	}

	trait TransformingStage[A, B] extends PushPullStage[Result[A], Result[B]] {
		def onPull(ctx: Context[Result[B]]) = ctx.pull()
	}

	def fromTransformingStage[A, B](makeStage: () => TransformingStage[A, B]): Flow[Result[A], Result[B], akka.NotUsed] = {
		Flow[Result[A]].transform(makeStage)
	}

	trait ConsumerHandler[A, B] {
		def onInput(input: Result[A]): Option[Result[B]]
		def onEnd(): Result[B]
	}

	def fromConsumerHandler[A, B](makeHandler: () => ConsumerHandler[A, B]) = Flow.fromGraph{
		new ConsumerGraphStage[A, B] {
			def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape){
				val handler = makeHandler()
				setHandler(in, new InHandler {
					def onPush() = {
						val a = grab(in)
						val bOpt = handler.onInput(a)
						if(bOpt.isDefined){
							push(out, bOpt.get)
							completeStage()
						} else {
							pull(in)
						}
					}
					override def onUpstreamFinish() = {
						push(out, handler.onEnd())
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

	def takeUntilNthError[A](n: Int) = fromTransformingStage{() =>
		new TransformingStage[A, A] {
			private var errorCount = 0
			def onPush(elem: Result[A], ctx: Context[Result[A]]) = {
				if(elem.isError){
					errorCount += 1
					if(errorCount >= n) ctx.finish()
					else ctx.push(elem)
				} else {
					ctx.push(elem)
				}
			}
		}
	}

	def takeThroughNthError[A](n: Int) = fromTransformingStage{() =>
		new TransformingStage[A, A] {
			private var errorCount = 0
			def onPush(elem: Result[A], ctx: Context[Result[A]]) = {
				if(elem.isError){
					errorCount += 1
					if(errorCount >= n) ctx.pushAndFinish(elem)
					else ctx.push(elem)
				} else {
					ctx.push(elem)
				}
			}
		}
	}
}
