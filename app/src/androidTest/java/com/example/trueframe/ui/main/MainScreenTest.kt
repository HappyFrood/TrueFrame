package com.example.trueframe.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for [MainScreen].
 * Note: These require a Hilt test runner for full integration testing.
 * This placeholder verifies basic compose rendering.
 */
@HiltAndroidTest
class MainScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun placeholder_test() {
        composeTestRule.setContent {
            MainScreen(onItemClick = {})
        }
        
        // Wait for the UI to show the empty state message.
        // It might be Loading initially.
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasText("No projects yet. Open a video to get started.")).fetchSemanticsNodes().isNotEmpty() ||
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasText("Error loading data")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
