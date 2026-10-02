package dev.localphoto.enhancer.data

import dev.localphoto.core.*
import org.json.JSONObject

/** Stable, explicit project representation; unknown keys survive version migration by defaults. */
object SettingsCodec {
    fun encode(settings: EnhanceSettings): String {
        val a = settings.adjustments.normalized()
        val t = settings.transform.normalized()
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
            put("version", 1); put("auto", settings.auto); put("profile", settings.profile.name)
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
        }.toString()
    }

    fun decode(json: String): EnhanceSettings = runCatching {
        val root = JSONObject(json)
        val a = root.optJSONObject("adjustments") ?: JSONObject()
        val t = root.optJSONObject("transform") ?: JSONObject()
        val o = root.optJSONObject("output") ?: JSONObject()
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
        )
    }.getOrDefault(EnhanceSettings())
}
