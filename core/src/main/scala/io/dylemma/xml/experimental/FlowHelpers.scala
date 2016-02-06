package io.dylemma.xml.experimental

import akka.stream.scaladsl.Flow
import akka.stream.stage.{Context, PushPullStage}
import io.dylemma.xml.Result
import io.dylemma.xml.Result.{Empty, Error, Success}

import scala.collection.generic.CanBuildFrom

/**
	* Created by dylan on 2/6/2016.
	*/
object FlowHelpers {

	trait HandlingStage[A, B] extends PushPullStage[Result[A], Result[B]] {
		protected def endResult: Result[B] = Empty

		def onPull(ctx: Context[Result[B]]) = {
			if(ctx.isFinishing) ctx.pushAndFinish(endResult)
			else ctx.pull()
		}
		override def onUpstreamFinish(ctx: Context[Result[B]]) = ctx.absorbTermination()
	}

	def fromHandlingStage[A, B](makeStage: () => HandlingStage[A, B]): Flow[Result[A], Result[B], Unit] = {
		Flow[Result[A]].transform(makeStage)
	}

	def first[A] = fromHandlingStage{() =>
		new HandlingStage[A, A] {
			def onPush(elem: Result[A], ctx: Context[Result[A]]) = {
				if(elem.isEmpty) ctx.pull()
				else ctx.pushAndFinish(elem)
			}
		}
	}

	def firstOption[A] = fromHandlingStage{() =>
		new HandlingStage[A, Option[A]] {
			override protected val endResult = Success(None)

			def onPush(elem: Result[A], ctx: Context[Result[Option[A]]]) = {
				if(elem.isEmpty) ctx.pull()
				else ctx.pushAndFinish(elem map (Some(_)))
			}
		}
	}

	def toList[A] = fromHandlingStage{() =>
		new HandlingStage[A, List[A]] {
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

	def concat[A, B, That](implicit t: A => TraversableOnce[B], bf: CanBuildFrom[A, B, That]) = fromHandlingStage{() =>
		new HandlingStage[A, That] {
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
}
