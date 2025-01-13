package io.github.dellisd.spatialk.turf

import io.github.dellisd.spatialk.geojson.GeoJson
import io.github.dellisd.spatialk.geojson.Polygon
import kotlin.jvm.JvmName

@OptIn(ExperimentalTurfApi::class)
@JvmName("TurfEnvelope")

public fun envelope(geoJson: GeoJson): Polygon {
    val bbox = when (geoJson) {
        is Polygon -> bbox(geoJson)
        else -> TODO("'${geoJson::class.simpleName}' is not yet supported")
    }
    return bboxPolygon(bbox)
}