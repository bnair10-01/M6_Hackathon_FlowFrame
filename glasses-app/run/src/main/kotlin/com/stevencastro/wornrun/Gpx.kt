package com.stevencastro.wornrun

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Reads GPX tracks/routes (for desk replays) and writes finished runs. */
object Gpx {
    /** Track points if present, else route points, else waypoints. */
    fun read(input: InputStream): Route? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val byTag = mapOf("trkpt" to ArrayList<Pair<Double, Double>>(),
            "rtept" to ArrayList(), "wpt" to ArrayList())
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            val list = byTag[parser.name] ?: continue
            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: continue
            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: continue
            list.add(lat to lon)
        }
        val pts = listOf("trkpt", "rtept", "wpt").map { byTag.getValue(it) }.firstOrNull { it.size >= 2 } ?: return null
        val lats = pts.map { it.first }.toDoubleArray()
        val lons = pts.map { it.second }.toDoubleArray()
        return Route(lats, lons, TurnDetector.detect(lats, lons))
    }

    /** Writes a recorded run; returns the file. */
    fun write(dir: File, fixes: List<Fix>, startWallMs: Long): File {
        dir.mkdirs()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val name = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(startWallMs))
        val file = File(dir, "run_$name.gpx")
        file.bufferedWriter().use { w ->
            w.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
            w.write("""<gpx version="1.1" creator="Worn Run" xmlns="http://www.topografix.com/GPX/1/1">""" + "\n")
            w.write("<trk><name>Run ${iso.format(Date(startWallMs))}</name><trkseg>\n")
            for (f in fixes) {
                w.write("""<trkpt lat="${f.lat}" lon="${f.lon}"><ele>${f.altM}</ele><time>${iso.format(Date(f.timeMs))}</time></trkpt>""")
                w.write("\n")
            }
            w.write("</trkseg></trk></gpx>\n")
        }
        return file
    }
}
