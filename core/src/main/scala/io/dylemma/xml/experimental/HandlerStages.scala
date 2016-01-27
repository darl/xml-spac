package io.dylemma.xml.experimental

import akka.stream.stage.{TerminationDirective, SyncDirective, Context, PushPullStage}

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

	def collectText: PushPullStage[XmlStackState[_], String] = new PushPullStage[XmlStackState[_], String] {
		var sb = new StringBuilder

		def onPush(s: XmlStackState[_], ctx: Context[String]) = {
			if(s.currentEvent.isCharacters){
				sb append s.currentEvent.asCharacters.getData
			}
			ctx.pull()
		}

		def onPull(ctx: Context[String]) = {
			if(ctx.isFinishing) ctx.pushAndFinish(sb.result)
			else ctx.pull()
		}

		override def onUpstreamFinish(ctx: Context[String]) = ctx.absorbTermination()
	}
}
