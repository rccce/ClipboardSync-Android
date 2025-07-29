package com.siw.clipboardsync.presentation.monitoring

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.ui.theme.ClipboardSyncTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(AndroidJUnit4::class)
class MonitoringSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockViewModel = mock<MonitoringSettingsViewModel>()

    @Test
    fun monitoringSettingsScreen_displaysCurrentStatus() {
        // Given
        val uiState = MonitoringSettingsUiState(
            isLoading = false,
            isMonitoring = true,
            currentMethod = MonitoringMethod.SYSTEM_HOOKS,
            availableMethods = listOf(
                MonitoringMethod.SYSTEM_HOOKS,
                MonitoringMethod.ACCESSIBILITY_SERVICE
            )
        )
        whenever(mockViewModel.uiState).thenReturn(MutableStateFlow(uiState))

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringSettingsScreen(
                    onNavigateBack = {},
                    viewModel = mockViewModel
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Monitoring Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Active").assertIsDisplayed()
        composeTestRule.onNodeWithText("Method: System Hooks").assertIsDisplayed()
    }

    @Test
    fun monitoringSettingsScreen_displaysInactiveStatus() {
        // Given
        val uiState = MonitoringSettingsUiState(
            isLoading = false,
            isMonitoring = false,
            currentMethod = null,
            availableMethods = listOf(MonitoringMethod.ACCESSIBILITY_SERVICE)
        )
        whenever(mockViewModel.uiState).thenReturn(MutableStateFlow(uiState))

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringSettingsScreen(
                    onNavigateBack = {},
                    viewModel = mockViewModel
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Monitoring Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Inactive").assertIsDisplayed()
    }

    @Test
    fun monitoringSettingsScreen_toggleMonitoring_callsViewModel() {
        // Given
        val uiState = MonitoringSettingsUiState(
            isLoading = false,
            isMonitoring = false,
            availableMethods = listOf(MonitoringMethod.ACCESSIBILITY_SERVICE)
        )
        whenever(mockViewModel.uiState).thenReturn(MutableStateFlow(uiState))

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringSettingsScreen(
                    onNavigateBack = {},
                    viewModel = mockViewModel
                )
            }
        }

        // Find and click the monitoring toggle switch
        composeTestRule.onNode(hasTestTag("monitoring_toggle") or hasClickAction())
            .filterToOne(hasClickAction())
            .performClick()

        // Then
        verify(mockViewModel).toggleMonitoring()
    }

    @Test
    fun monitoringSettingsScreen_selectPreferredMethod_callsViewModel() {
        // Given
        val uiState = MonitoringSettingsUiState(
            isLoading = false,
            availableMethods = listOf(
                MonitoringMethod.SYSTEM_HOOKS,
                MonitoringMethod.ACCESSIBILITY_SERVICE
            ),
            preferredMethod = null
        )
        whenever(mockViewModel.uiState).thenReturn(MutableStateFlow(uiState))

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringSettingsScreen(
                    onNavigateBack = {},
                    viewModel = mockViewModel
                )
            }
        }

        // Click on System Hooks radio button
        composeTestRule.onNodeWithText("System Hooks").performClick()

        // Then
        verify(mockViewModel).setPreferredMethod(MonitoringMethod.SYSTEM_HOOKS)
    }

    @Test
    fun monitoringSettingsScreen_toggleMethodEnabled_callsViewModel() {
        // Given
        val uiState = MonitoringSettingsUiState(
            isLoading = false,
            enabledMethods = mapOf(
                MonitoringMethod.SYSTEM_HOOKS to true,
                MonitoringMethod.ACCESSIBILITY_SERVICE to false
            )
        )
        whenever(mockViewModel.uiState).thenReturn(MutableStateFlow(uiState))

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringSettingsScreen(
                    onNavigateBack = {},
                    viewModel = mockViewModel
                )
            }
        }

        // Find and toggle accessibility service switch
        composeTestRule.onAllNodes(hasClickAction())
            .filterToOne(hasAnyAncestor(hasText("Accessibility Service")))
            .performClick()

        // Then
        verify(mockViewModel).toggleMethod(MonitoringMethod.ACCESSIBILITY_SERVICE, true)
    }

    @Test
    fun monitoringSettingsScreen_displayPerformanceMetrics() {
        // Given
        val uiState = MonitoringSettingsUiState(
            isLoading = false,
            cpuUsage = 0.5,
            memoryUsage = 50 * 1024 * 1024, // 50 MB
            batteryImpact = "Low"
        )
        whenever(mockViewModel.uiState).thenReturn(MutableStateFlow(uiState))

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringSettingsScreen(
                    onNavigateBack = {},
                    viewModel = mockViewModel
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Performance Metrics").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.50%").assertIsDisplayed()
        composeTestRule.onNodeWithText("50 MB").assertIsDisplayed()
        composeTestRule.onNodeWithText("Low").assertIsDisplayed()
    }

    @Test
    fun monitoringSettingsScreen_refreshMetrics_callsViewModel() {
        // Given
        val uiState = MonitoringSettingsUiState(isLoading = false)
        whenever(mockViewModel.uiState).thenReturn(MutableStateFlow(uiState))

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringSettingsScreen(
                    onNavigateBack = {},
                    viewModel = mockViewModel
                )
            }
        }

        // Find and click refresh button in performance metrics
        composeTestRule.onNode(
            hasContentDescription("Refresh") and 
            hasAnyAncestor(hasText("Performance Metrics"))
        ).performClick()

        // Then
        verify(mockViewModel).refreshMetrics()
    }

    @Test
    fun monitoringSettingsScreen_runDiagnostics_callsViewModel() {
        // Given
        val uiState = MonitoringSettingsUiState(isLoading = false)
        whenever(mockViewModel.uiState).thenReturn(MutableStateFlow(uiState))

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringSettingsScreen(
                    onNavigateBack = {},
                    viewModel = mockViewModel
                )
            }
        }

        // Click run diagnostics button
        composeTestRule.onNodeWithText("Run Diagnostics").performClick()

        // Then
        verify(mockViewModel).runDiagnostics()
    }

    @Test
    fun monitoringSettingsScreen_displayError_showsErrorMessage() {
        // Given
        val uiState = MonitoringSettingsUiState(
            isLoading = false,
            lastError = "Failed to start monitoring"
        )
        whenever(mockViewModel.uiState).thenReturn(MutableStateFlow(uiState))

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringSettingsScreen(
                    onNavigateBack = {},
                    viewModel = mockViewModel
                )
            }
        }

        // Then
        composeTestRule.onNodeWithText("Last Error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Failed to start monitoring").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear Errors").assertIsDisplayed()
    }

    @Test
    fun monitoringSettingsScreen_clearErrors_callsViewModel() {
        // Given
        val uiState = MonitoringSettingsUiState(
            isLoading = false,
            lastError = "Some error"
        )
        whenever(mockViewModel.uiState).thenReturn(MutableStateFlow(uiState))

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringSettingsScreen(
                    onNavigateBack = {},
                    viewModel = mockViewModel
                )
            }
        }

        // Click clear errors button
        composeTestRule.onNodeWithText("Clear Errors").performClick()

        // Then
        verify(mockViewModel).clearErrors()
    }

    @Test
    fun monitoringSettingsScreen_advancedSettings_togglesWork() {
        // Given
        val uiState = MonitoringSettingsUiState(
            isLoading = false,
            autoFallback = true,
            enableNotifications = false,
            enableErrorRecovery = true
        )
        whenever(mockViewModel.uiState).thenReturn(MutableStateFlow(uiState))

        // When
        composeTestRule.setContent {
            ClipboardSyncTheme {
                MonitoringSettingsScreen(
                    onNavigateBack = {},
                    viewModel = mockViewModel
                )
            }
        }

        // Then verify advanced settings are displayed
        composeTestRule.onNodeWithText("Advanced Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto Fallback").assertIsDisplayed()
        composeTestRule.onNodeWithText("Error Notifications").assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto Recovery").assertIsDisplayed()

        // Test toggling notifications
        composeTestRule.onAllNodes(hasClickAction())
            .filterToOne(hasAnyAncestor(hasText("Error Notifications")))
            .performClick()

        verify(mockViewModel).toggleNotifications(true)
    }
}