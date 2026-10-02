package dev.localphoto.enhancer

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localphoto.core.EnhanceSettings
import dev.localphoto.core.RepairPoint
import dev.localphoto.core.RepairStroke
import dev.localphoto.core.RestorationSettings
import dev.localphoto.enhancer.data.SettingsCodec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class RestorationRecipeInstrumentedTest {
    @Test
    fun versionTwoRecipeRoundTripsRestorationAndVersionOneDefaultsItOff() {
        val restoration = RestorationSettings(
            faceStrength = 55f,
            colorize = true,
            colorStrength = 65f,
            scratchRepair = true,
            repairStrength = 80f,
            maskStrokes = listOf(
                RepairStroke(
                    points = listOf(RepairPoint(0.2f, 0.3f), RepairPoint(0.8f, 0.7f)),
                    radius = 0.04f,
                    erase = false,
                ),
            ),
        )
        val encoded = SettingsCodec.encode(EnhanceSettings(restoration = restoration))
        assertEquals(2, JSONObject(encoded).getInt("version"))
        val decoded = SettingsCodec.decode(encoded)
        assertEquals(restoration, decoded.restoration)

        val oldRecipe = """{"version":1,"auto":false,"profile":"FAST"}"""
        assertEquals(RestorationSettings(), SettingsCodec.decode(oldRecipe).restoration)
    }
}
