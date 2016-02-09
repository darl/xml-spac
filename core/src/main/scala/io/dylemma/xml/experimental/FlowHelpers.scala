package io.dylemma.xml.experimental

import akka.stream.scaladsl.Flow
import akka.stream.stage.{SyncDirective, Context, PushPullStage}
import io.dylemma.xml.Result
import io.dylemma.xml.Result.{Empty, Error, Success}

import scala.collection.generic.CanBuildFrom

/**
	* Created by dylan on 2/6/2016.
	*/
object FlowHelpers {

	trait ParsingStage[A, B] extends PushPullStage[Result[A], Result[B]] {
		protected def endResult: Result[B] = Empty

		def onPull(ctx: Context[Result[B]]) = {
			if(ctx.isFinishing) ctx.pushAndFinish(endResult)
			else ctx.pull()
		}
		override def onUpstreamFinish(ctx: Context[Result[B]]) = ctx.absorbTermination()
	}

	def fromParsingStage[A, B](makeStage: () => ParsingStage[A, B]): Flow[Result[A], Result[B], akka.NotUsed] = {
		Flow[Result[A]].transform(makeStage)
	}

	trait TransformingStage[A, B] extends PushPullStage[Result[A], Result[B]] {
		def onPull(ctx: Context[Result[B]]) = ctx.pull()
	}

	def fromTransformingStage[A, B](makeStage: () => TransformingStage[A, B]): Flow[Result[A], Result[B], akka.NotUsed] = {
		Flow[Result[A]].transform(makeStage)
	}

	def first[A] = fromParsingStage{() =>
		new ParsingStage[A, A] {
			def onPush(elem: Result[A], ctx: Context[Result[A]]) = {
				if(elem.isEmpty) ctx.pull()
				else ctx.pushAndFinish(elem)
			}
		}
	}

	def firstOption[A] = fromParsingStage{() =>
		new ParsingStage[A, Option[A]] {
			override protected val endResult = Success.none

			def onPush(elem: Result[A], ctx: Context[Result[Option[A]]]) = {
				if(elem.isEmpty) ctx.pull()
				else ctx.pushAndFinish(elem map (Some(_)))
			}
		}
	}

	def toList[A] = fromParsingStage{() =>
		new ParsingStage[A, List[A]] {
			private val builder = List.newBuilder[A]
			override protected def endResult = Result(builder.result())
			def onPush(elem: Result[A], ctx: Context[Result[List[A]]]) = elem match {
				case Success(a) =>
					builder += a
					ctx.pull()
				case Empty =>
					ctx.pull()
				case e: Error =>
					ctx.pushAndFinish(e)
			}
		}
	}

	def concat[A, B, That](implicit t: A => TraversableOnce[B], bf: CanBuildFrom[A, B, That]) = fromParsingStage{() =>
		new ParsingStage[A, That] {
			private val builder = bf()
			override protected def endResult = Result(builder.result())
			def onPush(elem: Result[A], ctx: Context[Result[That]]) = elem match {
				case Success(a) =>
					builder ++= a
					ctx.pull()
				case Empty =>
					ctx.pull()
				case e: Error =>
					ctx.pushAndFinish(e)
			}
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
