package dev.localphoto.enhancer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localphoto.core.EnhanceSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun homeImportAndRestorationDefaultsAreAccessibleAndInteractive() {
        val settingsStore = (compose.activity.application as EnhancerApp).graph.settings
        val previous = runBlocking { settingsStore.defaults.first() }
        runBlocking { settingsStore.save(EnhanceSettings()) }
        try {
        compose.onNodeWithText("Add photos").assertIsDisplayed()
        compose.onNodeWithText("Browse files").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Quality").assertIsDisplayed()
        compose.onNodeWithText("Face restoration").performScrollTo().performClick()
        val faceSwitch = compose.onNodeWithContentDescription("Enable face restoration")
        faceSwitch.performScrollTo()
        compose.waitUntil(5_000) { runCatching { faceSwitch.assertIsOff() }.isSuccess }
        faceSwitch.performClick()
        // Preferences are persisted asynchronously, outside Compose's idling resources.
        compose.waitUntil(5_000) { runCatching { faceSwitch.assertIsOn() }.isSuccess }
        compose.onNodeWithText("Unavailable in this build").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Add photos").assertIsDisplayed()
        } finally { runBlocking { settingsStore.save(previous) } }
    }
}
