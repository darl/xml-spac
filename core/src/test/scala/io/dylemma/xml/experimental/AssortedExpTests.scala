package io.dylemma.xml.experimental

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import io.dylemma.xml.TestBase
import io.dylemma.xml.experimental.ParsingDSL._
import io.dylemma.xml.Result._
import org.scalatest.BeforeAndAfter

class AssortedExpTests extends TestBase with BeforeAndAfter{

	implicit var actorSystem: ActorSystem = null
	implicit var materializer: Materializer = null

	before {
		actorSystem = ActorSystem("xml-stream-tests")
		materializer = ActorMaterializer()
	}
	after {
		actorSystem.terminate()
	}

	describe("The 'Text' parser") {
		val rawXml = "<foo>Hello<bar>World</bar><bar>Floopy</bar>Doop</foo>"

		it("should concatenate text events") {
			whenReady(Root % Text parse rawXml) {
				assertResult(Success("HelloWorldFloopyDoop"))
			}
		}

		it("should preserve whitespace") {
			val rawXml = "<foo>\n\tHello\n</foo>"
			whenReady(Root % Text parse rawXml) {
				assertResult(Success("\n\tHello\n"))
			}
		}
	}

	describe("The 'attribute' parser") {
		it("should return the first attribute seen") {
			val rawXml = """<foo a="123"/>"""
			whenReady(Root % "a" parse rawXml) {
				assertResult(Success("123"))
			}
		}

		it("should result in an error if the attribute is missing") {
			val rawXml = "<foo/>"
			whenReady(Root % "a" parse rawXml) { result =>
				assert(result.isError)
			}
		}

		it("should give an Empty result if no elements were found") {
			whenReady(Root / "bar" % "a" parse "<foo/>") {
				assertResult(Empty)
			}
		}
	}

	describe("The 'optional attribute' parser") {
		it("should return the first attribute seen as a Some") {
			val rawXml = """<foo a="123"/>"""
			whenReady(Root %? "a" parse rawXml) {
				assertResult(Success(Some("123")))
			}
		}

		it("should return a None if the attribute was missing") {
			val rawXml = """<foo/>"""
			whenReady(Root %? "a" parse "<foo/>") {
				assertResult(Success(None))
			}
		}

		it("should give an Empty result if no elements were found") {
			whenReady(Root / "bar" %? "a" parse "<foo/>") {
				assertResult(Empty)
			}
		}
	}

	describe("Mapped parsers") {
		it("should be mapped") {
			val rawXml = "<foo>123</foo>"
			whenReady(Root % Text map (_.toInt) parse rawXml) {
				assertResult(Success(123))
			}
		}

		it("should give an Error result if the mapping throws an exception") {
			val rawXml = "<foo>ABC</foo>"
			whenReady(Root % Text map (_.toInt) parse rawXml) { result =>
				assert(result.isError)
			}
		}
	}

	describe("Splitters"){
		it("should filter out unmatched events"){
			val rawXml = "<foo><bar>Hello</bar><baz>World</baz></foo>"
			whenReady(* / "bar" % Text parse rawXml){
				assertResult(Success("Hello"))
			}
		}

		it("should split the event stream into substreams"){
			val rawXml = "<foo><bar>Hello</bar><bar>World</bar></foo>"
			whenReady(* / "bar" % Text.asList parse rawXml){
				assertResult(Success("Hello" :: "World" :: Nil))
			}
		}

		it("should be combined with parsers to create a transformer"){
			val rawXml = "<foo><bar>Hello</bar><bar>World</bar></foo>"
			val barParser = Root / "bar" % Text
			val barSplitter = Root / "foo" / "bar"
			val barTransformer = barSplitter through barParser

			whenReady(barTransformer.parseFirst parse rawXml){
				assertResult(Success("Hello"))
			}
			whenReady(barTransformer.parseToList parse rawXml){
				assertResult(Success("Hello" :: "World" :: Nil))
			}
		}
	}

	describe("Combined Parsers"){
		it("should provide successful results in normal scenarios"){
			val rawXml = """<foo a="123">hello world</foo>"""
			val parser = ((* % "a") ~ (* % Text)).tupled

			whenReady(parser parse rawXml){
				assertResult(Success("123" -> "hello world"))
			}
		}

		it("should give Error results if one of the inner parsers does"){
			val rawXml = """<foo a="abc">hello world</foo>"""
			val parser = ((* % "a").map(_.toInt) ~ (* % Text)).tupled
			whenReady(parser parse rawXml){ result =>
				assert(result.isError)
			}
		}
	}

	describe("Context matchers") {
		it("should provide context to attached parsers") {
			val rawXml1 = """<foo a="abc"><bar>Hello</bar></foo>"""
			val rawXml2 = """<foo a="def"><bar>Hello</bar></foo>"""
			val splitter = "foo" & attr("a")
			val barParser = (inContext[String] ~ (* % Text)).tupled
			val parser = splitter.through(barParser).parseFirst

			whenReady(parser parse rawXml1) {assertResult(Success("abc" -> "Hello"))}
			whenReady(parser parse rawXml2) {assertResult(Success("def" -> "Hello"))}
		}

		it("should cause Error results when the context match throws an exception"){
			val rawXml = """<foo a="abc"><bar>Hello</bar></foo>"""
			val splitter = attr("a").mapContext(_.toInt)
			val barParser = (inContext[Int] ~ (* % Text)).tupled
			val parser = splitter.through(barParser).parseFirst

			whenReady(parser parse rawXml){ result => assert(result.isError) }
		}

		it("should not allow parsers with different context types to be attached"){
			val splitter: Splitter[String] = attr("a")
			val parser: Parser[Int, Int] = inContext[Int]
			assertDoesNotCompile("splitter through parser")
		}
	}

	describe("Parsers with required context") {
		it("should share context when combined with other context-required parsers"){
			val rawXml = """<foo a="123"><bar>BAR</bar><baz>BAZ</baz></foo>"""
			val barParser = (inContext[Int] ~ (* % Text)).join{ _ + _ }
			var bazParser = (inContext[Int] ~ (* % Text)).join{ _ + _ }
			val splitter = attr("a").mapContext(_.toInt)
			val barbazParser = (
				(splitter / "bar" through barParser).parseFirst ~
					(splitter / "baz" through bazParser).parseFirst
				).tupled
			whenReady(barbazParser parse rawXml){
				assertResult(Success("123BAR" -> "123BAZ"))
			}
		}

		it("should not be able to combine with other context-required parsers of different types"){
			val barParser = inContext[String]
			val bazParser = inContext[Int]
			assertDoesNotCompile("barParser ~ bazParser")
		}

		it("should combine context-required parsers with similar contexts"){
			val barParser = inContext[Option[String]]
			val bazParser = inContext[Some[String]]
			assertCompiles("val combinedParser: Parser[Some[String], _] = (barParser ~ bazParser).tupled")
		}
	}

	describe("Transformer error handling"){
		val rawXml = """<foo>
		| <bar>1</bar>
		| <bar>ABC</bar>
		| <bar>2</bar>
		| <bar>DEF</bar>
		| <bar>3</bar>
		|</foo>""".stripMargin

		val barParser = (* % Text).map(_.toInt)
		val barTransformer = "foo" / "bar" through barParser

		def matchResultsFor(transformer: Transformer[Int])(matcher: PartialFunction[Any, _]) = {

			val futureResult = transformer.parseResultsToList parse rawXml
			whenReady(futureResult){
				case Success(results) => results should matchPattern(matcher)
				case _ => fail("unexpected non-success value from allResultsCollector")
			}
		}

		it("should pass along errors by default"){
			matchResultsFor(barTransformer){
				case Success(1) :: Error(_) :: Success(2) :: Error(_) :: Success(3) :: Nil =>
			}
		}

		it("should be able to kill the stream after the first error"){
			matchResultsFor(barTransformer.takeThroughFirstError){
				case Success(1) :: Error(_) :: Nil =>
			}
		}

		it("should be able to kill the stream before the first error"){
			matchResultsFor(barTransformer.takeUntilFirstError){
				case Success(1) :: Nil =>
			}
		}

		it("should be able to kill the stream after the Nth error"){
			matchResultsFor(barTransformer takeThroughNthError 2){
				case Success(1) :: Error(_) :: Success(2) :: Error(_) :: Nil =>
			}
		}

		it("should be able to kill the stream before the Nth error"){
			matchResultsFor(barTransformer takeUntilNthError 2){
				case Success(1) :: Error(_) :: Success(2) :: Nil =>
			}
		}
	}

	describe("Multiplexed parsers"){
		val rawXml = """<foo>
		|	<a>1</a>
		|	<a>2</a>
		|	<b>3</b>
		|	<a>4</a>
		|	<b>5</b>
		|</foo>""".stripMargin

		sealed trait AB
		case class A(i: Int) extends AB
		case class B(i: Int) extends AB
		val parserA = (* % Text).map(s => A(s.toInt))
		val parserB = (* % Text).map(s => B(s.toInt))

		def testAB(parser: Parser[String, AB]) = {
			val scopedParser = (Root / "foo" / ("a" | "b").extractName through parser).parseToList
			whenReady(scopedParser parse rawXml){ result =>
				result should matchPattern {
					case Success(A(1) :: A(2) :: B(3) :: A(4) :: B(5) :: Nil) =>
				}
			}
		}

		it("should delegate to different sub-parsers based on the context"){
			testAB {
				Parser.demultiplexed[String](
					parserA -> { _ == "a" },
					parserB -> { _ == "b" }
				)
			}
		}

		it("should behave the same way with the DSL syntax"){
			testAB {
				import Parser.demuxSyntax._
				for {
					context <- Demux[String]
					a <- parserA if context === "a"
					b <- parserB if context === "b"
				} yield a | b
			}
		}
	}
}
