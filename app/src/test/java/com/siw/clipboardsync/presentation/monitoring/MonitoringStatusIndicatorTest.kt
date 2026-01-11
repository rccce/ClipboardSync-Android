package com.siw.clipboardsync.presentation.monitoring

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.ui.theme.ClipboardSyncTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitoringStatusIndicatorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun monitoringStatusIndicator_activeMonitoring_displaysCorrectly() {
        // Given
        var settingsClicked = false

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringStatusIndicator(
                    isMonitoring = true,
                    currentMethod = MonitoringMethod.XPOSED_HOOKS,
                    hasPermissions = true,
                    onSettingsClick = { settingsClicked = true }
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Advanced Monitoring - Active").assertIsDisplayed()
        composeTestRule.onNodeWithText("Method: Xposed").assertIsDisplayed()
        
        // Click settings button
        composeTestRule.onNodeWithContentDescription("Monitoring Settings").performClick()
        assert(settingsClicked)
    }

    @Test
    fun monitoringStatusIndicator_inactiveMonitoring_displaysCorrectly() {
        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringStatusIndicator(
                    isMonitoring = false,
                    currentMethod = null,
                    hasPermissions = true,
                    onSettingsClick = {}
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Advanced Monitoring - Inactive").assertIsDisplayed()
        composeTestRule.onNodeWithText("Method: Xposed").assertDoesNotExist()
    }

    @Test
    fun monitoringStatusIndicator_noPermissions_displaysWarning() {
        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringStatusIndicator(
                    isMonitoring = false,
                    currentMethod = null,
                    hasPermissions = false,
                    onSettingsClick = {}
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Monitoring - Permissions Required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Grant permissions to enable advanced monitoring").assertIsDisplayed()
    }

    @Test
    fun compactMonitoringStatus_activeMonitoring_displaysCorrectly() {
        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                CompactMonitoringStatus(
                    isMonitoring = true,
                    currentMethod = MonitoringMethod.SHIZUKU
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Active (Shizuku)").assertIsDisplayed()
    }

    @Test
    fun compactMonitoringStatus_inactiveMonitoring_displaysCorrectly() {
        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                CompactMonitoringStatus(
                    isMonitoring = false,
                    currentMethod = null
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Inactive").assertIsDisplayed()
    }

    @Test
    fun monitoringMethodChip_activeMethod_displaysSelected() {
        // Given
        var chipClicked = false

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringMethodChip(
                    method = MonitoringMethod.XPOSED_HOOKS,
                    isActive = true,
                    onClick = { chipClicked = true }
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Xposed").assertIsDisplayed()
        
        // Click the chip
        composeTestRule.onNodeWithText("Xposed").performClick()
        assert(chipClicked)
    }

    @Test
    fun monitoringMethodChip_inactiveMethod_displaysUnselected() {
        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringMethodChip(
                    method = MonitoringMethod.FOREGROUND_SYNC,
                    isActive = false,
                    onClick = {}
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Foreground").assertIsDisplayed()
    }

    @Test
    fun monitoringStatusIndicator_differentMethods_displayCorrectNames() {
        // Test all 3 monitoring methods display correct names
        val methods = listOf(
            MonitoringMethod.XPOSED_HOOKS to "Xposed",
            MonitoringMethod.SHIZUKU to "Shizuku",
            MonitoringMethod.FOREGROUND_SYNC to "Foreground"
        )

        methods.forEach { (method, expectedName) ->
            composeTestRule.setContent {
                ClipboardSyncTheme {
                    MonitoringStatusIndicator(
                        isMonitoring = true,
                        currentMethod = method,
                        hasPermissions = true,
                        onSettingsClick = {}
                    )
                }
            }

            composeTestRule.onNodeWithText("Method: $expectedName").assertIsDisplayed()
        }
    }
}
