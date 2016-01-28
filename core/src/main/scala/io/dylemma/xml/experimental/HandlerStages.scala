package io.dylemma.xml.experimental

import javax.xml.namespace.QName

import akka.stream.stage.{TerminationDirective, SyncDirective, Context, PushPullStage}
import io.dylemma.xml.Result
import io.dylemma.xml.Result.{Error, Success, Empty}

object HandlerStages {

	def toList[T]: PushPullStage[T, List[T]] = new PushPullStage[T, List[T]] {
		var buffer = List.newBuilder[T]

		def onPush(elem: T, ctx: Context[List[T]]): SyncDirective = {
			println(s"receiving $elem")
			buffer += elem
			// Thread.sleep(500)
			ctx.pull()
		}
		def onPull(ctx: Context[List[T]]): SyncDirective = {
			if(ctx.isFinishing){
				println("finishing up from onPull")
				ctx.pushAndFinish(buffer.result)
			} else {
				ctx.pull()
			}
		}
		override def onUpstreamFinish(ctx: Context[List[T]]): TerminationDirective = {
			println("absorbing termination")
			ctx.absorbTermination()
		}
	}

	trait XmlHandlingStage[C, T] extends PushPullStage[XmlStackState[C], Result[T]]

	def getContext[T]: XmlHandlingStage[T, T] = new XmlHandlingStage[T, T] {
		def onPush(s: XmlStackState[T], ctx: Context[Result[T]]) = {
			ctx.pushAndFinish(s.matchedContext)
		}
		def onPull(ctx: Context[Result[T]]) = {
			if(ctx.isFinishing) ctx.pushAndFinish(Empty)
			else ctx.pull()
		}
		override def onUpstreamFinish(ctx: Context[Result[T]]) = ctx.absorbTermination()
	}

	def collectText: XmlHandlingStage[Any, String] = new XmlHandlingStage[Any, String] {
		var sb = new StringBuilder

		def onPush(s: XmlStackState[Any], ctx: Context[Result[String]]) = {
			if(s.currentEvent.isCharacters){
				sb append s.currentEvent.asCharacters.getData
			}
			ctx.pull()
		}

		def onPull(ctx: Context[Result[String]]) = {
			if(ctx.isFinishing) ctx.pushAndFinish(Result(sb.result))
			else ctx.pull()
		}

		override def onUpstreamFinish(ctx: Context[Result[String]]) = ctx.absorbTermination()
	}

	def getMandatoryAttribute(name: QName): XmlHandlingStage[Any, String] = new XmlHandlingStage[Any, String] {
		def onPush(s: XmlStackState[Any], ctx: Context[Result[String]]) = {
			if(s.currentEvent.isStartElement){
				val elem = s.currentEvent.asStartElement
				val attr = elem.getAttributeByName(name)
				if(attr == null){
					val msg = s"$elem missing mandatory '$name' attribute"
					ctx.pushAndFinish(Error(new IllegalArgumentException(msg)))
				} else {
					ctx.pushAndFinish(Result(attr.getValue))
				}
			} else {
				ctx.pull()
			}
		}
		def onPull(ctx: Context[Result[String]]) = {
			if(ctx.isFinishing) ctx.pushAndFinish(Empty)
			else ctx.pull()
		}
		override def onUpstreamFinish(ctx: Context[Result[String]]) = ctx.absorbTermination()
	}

	def getOptionalAttribute(name: QName): XmlHandlingStage[Any, Option[String]] = new XmlHandlingStage[Any, Option[String]] {
		def onPush(s: XmlStackState[Any], ctx: Context[Result[Option[String]]]) = {
			if(s.currentEvent.isStartElement){
				val attr = s.currentEvent.asStartElement.getAttributeByName(name)
				if(attr == null){
					ctx.pushAndFinish(Success(None))
				} else {
					ctx.pushAndFinish(Result(Some(attr.getValue)))
				}
			} else {
				ctx.pull()
			}
		}

		def onPull(ctx: Context[Result[Option[String]]]): SyncDirective = {
			if(ctx.isFinishing) ctx.pushAndFinish(Empty)
			else ctx.pull()
		}

		override def onUpstreamFinish(ctx: Context[Result[Option[String]]]) = ctx.absorbTermination()
	}
}
