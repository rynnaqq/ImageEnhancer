package dev.localphoto.enhancer

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localphoto.core.EnhanceSettings
import dev.localphoto.core.RepairPoint
import dev.localphoto.core.RepairStroke
import dev.localphoto.enhancer.data.ImageFiles
import dev.localphoto.enhancer.ui.EnhancerTheme
import dev.localphoto.enhancer.ui.RepairGesturePath
import dev.localphoto.enhancer.ui.RestorationControls
import dev.localphoto.enhancer.ui.advanceRepairGesture
import dev.localphoto.enhancer.ui.fitImageBounds
import dev.localphoto.enhancer.ui.normalizedRepairPoint
import dev.localphoto.enhancer.ui.repairMaskHasSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestorationUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun restorationOptionsChangeTheRealRecipeWithoutAnUnavailableDialog() {
        var latest = EnhanceSettings()
        compose.activity.setContent {
            EnhancerTheme {
                var settings by remember { mutableStateOf(EnhanceSettings()) }
                SideEffect { latest = settings }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RestorationControls(settings, { settings = it }, null, ImageFiles(compose.activity))
                }
            }
        }

        compose.onNodeWithText("Face restoration").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Enable face restoration").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(latest.restoration.faceStrength > 0f) }
        compose.onNodeWithText("Face restoration").performClick()

        compose.onNodeWithText("Black-and-white colorization").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Enable black-and-white colorization").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(latest.restoration.colorize) }
        compose.onNodeWithText("Black-and-white colorization").performClick()

        compose.onNodeWithText("Old photo, scratch & dust restoration").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Enable automatic scratch repair").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(latest.restoration.scratchRepair) }
        compose.onNodeWithText("Old photo, scratch & dust restoration").performClick()
        compose.onNodeWithText("Unavailable in this build").assertDoesNotExist()
        compose.onNodeWithText("Missing-area reconstruction").performScrollTo().performClick()
        compose.onNodeWithText("Draw over missing or damaged areas", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun repairCoordinatesExcludeLetterboxAndNormalizeInsideImage() {
        val bounds = fitImageBounds(containerWidth = 1000f, containerHeight = 500f, imageWidth = 100, imageHeight = 100)

        assertEquals(250f, bounds.left, 0.001f)
        assertEquals(750f, bounds.right, 0.001f)
        assertNull(normalizedRepairPoint(Offset(249f, 250f), bounds))
        val center = normalizedRepairPoint(Offset(500f, 250f), bounds)!!
        assertEquals(0.5f, center.x, 0.001f)
        assertEquals(0.5f, center.y, 0.001f)
    }

    @Test fun repairSelectionProbePreservesPortraitAndLandscapeAspect() {
        val portrait = listOf(
            RepairStroke(listOf(RepairPoint(0.5f, 0.5f)), radius = 0.025f),
            RepairStroke(listOf(RepairPoint(0.5f, 0.54f)), radius = 0.08f, erase = true),
        )
        val landscape = listOf(
            RepairStroke(listOf(RepairPoint(0.5f, 0.5f)), radius = 0.025f),
            RepairStroke(listOf(RepairPoint(0.54f, 0.5f)), radius = 0.08f, erase = true),
        )

        assertTrue(repairMaskHasSelection(portrait, imageWidth = 512, imageHeight = 2048))
        assertTrue(repairMaskHasSelection(landscape, imageWidth = 2048, imageHeight = 512))
    }

    @Test fun repairSelectionProbeRejectsFullyErasedMasksForEitherAspect() {
        val fullyErased = listOf(
            RepairStroke(listOf(RepairPoint(0.5f, 0.5f)), radius = 0.025f),
            RepairStroke(listOf(RepairPoint(0.5f, 0.5f)), radius = 0.08f, erase = true),
        )

        assertFalse(repairMaskHasSelection(fullyErased, imageWidth = 512, imageHeight = 2048))
        assertFalse(repairMaskHasSelection(fullyErased, imageWidth = 2048, imageHeight = 512))
        assertFalse(repairMaskHasSelection(fullyErased))
    }

    @Test fun leavingTheImageEndsAStrokeBeforeReentry() {
        val bounds = fitImageBounds(containerWidth = 1000f, containerHeight = 500f, imageWidth = 100, imageHeight = 100)
        var path = RepairGesturePath(listOf(RepairPoint(0.5f, 0.5f)))

        path = advanceRepairGesture(path, Offset(200f, 250f), bounds)
        path = advanceRepairGesture(path, Offset(600f, 250f), bounds)

        assertTrue(path.ended)
        assertEquals(listOf(RepairPoint(0.5f, 0.5f)), path.points)
    }
}
