package io.dylemma.xml.experimental

import javax.xml.namespace.QName
import javax.xml.stream.events.{ Attribute, StartElement }
import collection.JavaConverters._

class Tag(val opening: StartElement) extends AnyVal {
	def name = opening.getName
	def location = opening.getLocation
	def attr(qname: QName): Option[String] = {
		val a = opening getAttributeByName qname
		if(a == null) None else Some(a.getValue)
	}
	def attr(localName: String): Option[String] = attr(new QName(localName))
	def attributes = {
		opening.getAttributes.asScala.map{ obj =>
			val a = obj.asInstanceOf[Attribute]
			a.getName -> a.getValue
		}
	}
}

object Tag {
	def unapply(tag: Tag): Option[String] = Some(tag.name.getLocalPart)
}