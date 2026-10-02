package dev.localphoto.enhancer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun homeImportAndLocalModelDependencyStatusAreAccessible() {
        compose.onNodeWithText("Add photos").assertIsDisplayed()
        compose.onNodeWithText("Browse files").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Quality").assertIsDisplayed()
        compose.onNodeWithText("Face restoration").performScrollTo().performClick()
        compose.onNodeWithText("Unavailable in this build").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Add photos").assertIsDisplayed()
    }
}
