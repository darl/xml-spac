package io.dylemma.spac

import io.dylemma.spac.xml.syntax._

/** `xml-spac` uses classes from `javax.xml.stream.events` to create parsing logic for XML.
  *
  * The fundamental building blocks of an XML parser in `xml-spac` are
  *
  *  - `[[io.dylemma.spac.xml.XMLParser]].forText` for gathering text content from XML elements
  *  - `[[io.dylemma.spac.xml.XMLParser]].forMandatoryAttribute`
  *  - `[[io.dylemma.spac.xml.XMLParser]].forOptionalAttribute` for gathering optional attributes from XML elements
  *  - `[[io.dylemma.spac.xml.SingleElementContextMatcher]]` for building context matchers based on the XML element stack
  *
  * There are several required implicits and optional conveniences included in this package object.
  * You can access all of them at once via
  * {{{
  * import io.dylemma.spac.xml._
  * }}}
  * or import them individually via
  * {{{
  * import io.dylemma.spac.xml.Implicits._
  * import io.dylemma.spac.xml.ContextMatcherSyntax._
  * }}}
  *
  * The imported conveniences provide methods like
  *
  *  - `[[ContextMatcherSyntax]].elem` for matching elements by name
  *  - `[[ContextMatcherSyntax]].attr` for extracting element attributes as context
  *  - `[[ContextMatcherSyntax]].*` and `[[ContextMatcherSyntax]].**` as wildcards for element matching
  *  - `[[io.dylemma.spac.xml.Parser]]` as an alias for `[[io.dylemma.spac.xml.XMLParser]]``
  */
package object xml
	extends ContextMatcherSyntax
	with Implicits
{

	/** Defines XML-specific conveniences for creating `ContextMatchers`.
	  *
	  * The contents of `ContextMatcherSyntax` are inherited by this package object, so you
	  * can choose to import them along with all of the other "syntax" helpers
	  * by importing `io.dylemma.spac.xml._`, or import the `Implicits` specifically
	  * via `io.dylemma.spac.xml.ContextMatcherSyntax._`.
	  */
	object ContextMatcherSyntax extends ContextMatcherSyntax

	/** Defines XML-specific instances for the core spac typeclasses.
	  *
	  * The contents of `Implicits` are inherited by this package object, so you
	  * can choose to import them along with all of the other "syntax" helpers
	  * by importing `io.dylemma.spac.xml._`, or import the `Implicits` specifically
	  * via `io.dylemma.spac.xml.Implicits._`.
	  */
	object Implicits extends Implicits

}
