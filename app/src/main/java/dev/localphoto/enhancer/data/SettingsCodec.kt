package dev.localphoto.enhancer.data

import dev.localphoto.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Stable, explicit project representation; unknown keys survive version migration by defaults. */
object SettingsCodec {
    fun encode(settings: EnhanceSettings): String {
        val a = settings.adjustments.normalized()
        val t = settings.transform.normalized()
        val r = settings.restoration.normalized()
        val adjustments = JSONObject().apply {
            put("scale", a.scale); put("denoise", a.denoise); put("deblur", a.deblur)
            put("sharpen", a.sharpen); put("detailPreservation", a.detailPreservation)
            put("exposure", a.exposure); put("brightness", a.brightness); put("contrast", a.contrast)
            put("highlights", a.highlights); put("shadows", a.shadows)
            put("whitePoint", a.whitePoint); put("blackPoint", a.blackPoint); put("gamma", a.gamma)
            put("temperature", a.temperature); put("tint", a.tint); put("saturation", a.saturation)
            put("vibrance", a.vibrance); put("hue", a.hue); put("redBalance", a.redBalance)
            put("greenBalance", a.greenBalance); put("blueBalance", a.blueBalance); put("fadedColor", a.fadedColor)
        }
        return JSONObject().apply {
            put("version", 2); put("auto", settings.auto); put("profile", settings.profile.name)
            put("adjustments", adjustments)
            put("transform", JSONObject().apply {
                put("rotationDegrees", t.rotationDegrees); put("flipHorizontal", t.flipHorizontal)
                put("flipVertical", t.flipVertical); put("straightenDegrees", t.straightenDegrees)
                put("cropLeft", t.cropLeft); put("cropTop", t.cropTop)
                put("cropRight", t.cropRight); put("cropBottom", t.cropBottom)
            })
            put("output", JSONObject().apply {
                put("format", settings.output.format.name); put("quality", settings.output.quality.coerceIn(1, 100))
                put("stripMetadata", settings.output.stripMetadata)
            })
            put("restoration", JSONObject().apply {
                put("faceStrength", r.faceStrength); put("colorize", r.colorize)
                put("colorStrength", r.colorStrength); put("scratchRepair", r.scratchRepair)
                put("repairStrength", r.repairStrength)
                put("maskStrokes", JSONArray().apply {
                    r.maskStrokes.forEach { stroke ->
                        put(JSONObject().apply {
                            put("radius", stroke.radius); put("erase", stroke.erase)
                            put("points", JSONArray().apply {
                                stroke.points.forEach { point ->
                                    put(JSONObject().apply { put("x", point.x); put("y", point.y) })
                                }
                            })
                        })
                    }
                })
            })
        }.toString()
    }

    fun decode(json: String): EnhanceSettings = runCatching {
        val root = JSONObject(json)
        val a = root.optJSONObject("adjustments") ?: JSONObject()
        val t = root.optJSONObject("transform") ?: JSONObject()
        val o = root.optJSONObject("output") ?: JSONObject()
        val restoration = if (root.optInt("version", 1) >= 2) decodeRestoration(root.optJSONObject("restoration"))
            else RestorationSettings()
        fun f(key: String, fallback: Double = 0.0) = a.optDouble(key, fallback).toFloat()
        EnhanceSettings(
            auto = root.optBoolean("auto", true),
            profile = runCatching { Profile.valueOf(root.optString("profile", "QUALITY")) }.getOrDefault(Profile.QUALITY),
            adjustments = Adjustments(
                scale = a.optInt("scale", 2), denoise = f("denoise"), deblur = f("deblur"),
                sharpen = f("sharpen", 15.0), detailPreservation = f("detailPreservation", 80.0),
                exposure = f("exposure"), brightness = f("brightness"), contrast = f("contrast"),
                highlights = f("highlights"), shadows = f("shadows"), whitePoint = f("whitePoint"),
                blackPoint = f("blackPoint"), gamma = f("gamma", 1.0), temperature = f("temperature"),
                tint = f("tint"), saturation = f("saturation"), vibrance = f("vibrance"), hue = f("hue"),
                redBalance = f("redBalance"), greenBalance = f("greenBalance"), blueBalance = f("blueBalance"),
                fadedColor = f("fadedColor"),
            ).normalized(),
            transform = TransformSettings(
                rotationDegrees = t.optInt("rotationDegrees"), flipHorizontal = t.optBoolean("flipHorizontal"),
                flipVertical = t.optBoolean("flipVertical"), straightenDegrees = t.optDouble("straightenDegrees").toFloat(),
                cropLeft = t.optDouble("cropLeft").toFloat(), cropTop = t.optDouble("cropTop").toFloat(),
                cropRight = t.optDouble("cropRight", 1.0).toFloat(), cropBottom = t.optDouble("cropBottom", 1.0).toFloat(),
            ).normalized(),
            output = OutputSettings(
                format = runCatching { OutputFormat.valueOf(o.optString("format", "JPEG")) }.getOrDefault(OutputFormat.JPEG),
                quality = o.optInt("quality", 97).coerceIn(1, 100), stripMetadata = o.optBoolean("stripMetadata", true),
            ),
            restoration = restoration,
        )
    }.getOrDefault(EnhanceSettings())

    private fun decodeRestoration(value: JSONObject?): RestorationSettings {
        if (value == null) return RestorationSettings()
        val strokesJson = value.optJSONArray("maskStrokes") ?: JSONArray()
        val strokes = ArrayList<RepairStroke>(minOf(strokesJson.length(), 256))
        var remainingPoints = 8_192
        for (strokeIndex in 0 until strokesJson.length()) {
            if (remainingPoints == 0 || strokes.size == 256) break
            val strokeJson = strokesJson.optJSONObject(strokeIndex) ?: continue
            val pointsJson = strokeJson.optJSONArray("points") ?: continue
            val points = ArrayList<RepairPoint>(minOf(pointsJson.length(), 2_048, remainingPoints))
            for (pointIndex in 0 until pointsJson.length()) {
                if (points.size == minOf(2_048, remainingPoints)) break
                val pointJson = pointsJson.optJSONObject(pointIndex) ?: continue
                val x = pointJson.optDouble("x", Double.NaN).toFloat()
                val y = pointJson.optDouble("y", Double.NaN).toFloat()
                if (x.isFinite() && y.isFinite()) points += RepairPoint(x, y)
            }
            if (points.isNotEmpty()) {
                strokes += RepairStroke(
                    points = points,
                    radius = strokeJson.optDouble("radius", 0.025).toFloat(),
                    erase = strokeJson.optBoolean("erase", false),
                )
                remainingPoints -= points.size
            }
        }
        return RestorationSettings(
            faceStrength = value.optDouble("faceStrength", 0.0).toFloat(),
            colorize = value.optBoolean("colorize", false),
            colorStrength = value.optDouble("colorStrength", 70.0).toFloat(),
            scratchRepair = value.optBoolean("scratchRepair", false),
            repairStrength = value.optDouble("repairStrength", 100.0).toFloat(),
            maskStrokes = strokes,
        ).normalized()
    }
}
