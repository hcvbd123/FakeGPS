package com.fakegps.app.models

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class KmlPlacemark(
    val name: String,
    val latitude: Double,   // WGS84
    val longitude: Double,
    val altitude: Double = 0.0
)

data class KmlData(
    val name: String,
    val placemarks: List<KmlPlacemark>
)

object KmlParser {

    fun parse(inputStream: InputStream): KmlData {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, "UTF-8")
        parser.nextTag()

        val placemarks = mutableListOf<KmlPlacemark>()
        var docName = "未命名"

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "name" -> {
                        val text = parser.nextText()
                        if (docName == "未命名") docName = text
                    }
                    "Placemark" -> {
                        parsePlacemark(parser)?.let { placemarks.add(it) }
                    }
                }
            }
            eventType = parser.next()
        }

        return KmlData(docName, placemarks)
    }

    private fun parsePlacemark(parser: XmlPullParser): KmlPlacemark? {
        var name = "无名称"
        var lat = 0.0
        var lon = 0.0
        var alt = 0.0
        var found = false

        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name == "Placemark")) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "name" -> {
                        name = parser.nextText()
                    }
                    "Point" -> {
                        val coords = parseCoords(parser)
                        if (coords != null) {
                            lon = coords[0]
                            lat = coords[1]
                            alt = if (coords.size > 2) coords[2] else 0.0
                            found = true
                        }
                    }
                }
            }
            eventType = parser.next()
            if (eventType == XmlPullParser.END_DOCUMENT) break
        }

        return if (found) KmlPlacemark(name, lat, lon, alt) else null
    }

    private fun parseCoords(parser: XmlPullParser): List<Double>? {
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name == "Point")) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "coordinates") {
                val text = parser.nextText().trim()
                // coordinates: lon,lat,alt (space separated for multiple)
                val first = text.split("\\s+".toRegex()).firstOrNull() ?: return null
                return first.split(",").map { it.trim().toDoubleOrNull() ?: 0.0 }
            }
            eventType = parser.next()
            if (eventType == XmlPullParser.END_DOCUMENT) break
        }
        return null
    }
}
