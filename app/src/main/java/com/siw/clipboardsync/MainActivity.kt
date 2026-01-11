package com.siw.clipboardsync

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.siw.clipboardsync.presentation.auth.AuthViewModel
import com.siw.clipboardsync.presentation.auth.LoginScreen
import com.siw.clipboardsync.presentation.auth.RegisterScreen
import com.siw.clipboardsync.presentation.main.MainScreen
import com.siw.clipboardsync.presentation.system.SystemStatusScreen
import com.siw.clipboardsync.ui.theme.ClipboardSyncTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            ClipboardSyncTheme {
                ClipboardSyncApp()
            }
        }
    }
}

@Composable
fun ClipboardSyncApp() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState(initial = false)
    
    // 监听认证状态变化并自动导航
    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) {
            // Token过期或被清除，自动导航到登录页面
            navController.navigate("login") {
                popUpTo(0) { inclusive = true }
            }
        }
    }
    
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (isLoggedIn) "main" else "login",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("login") {
                LoginScreen(
                    onNavigateToRegister = { navController.navigate("register") },
                    onLoginSuccess = { navController.navigate("main") {
                        popUpTo("login") { inclusive = true }
                    }}
                )
            }
            
            composable("register") {
                RegisterScreen(
                    onNavigateToLogin = { navController.navigate("login") },
                    onRegisterSuccess = { navController.navigate("main") {
                        popUpTo("register") { inclusive = true }
                    }}
                )
            }
            
            composable("main") {
                MainScreen(
                    onNavigateToSystemStatus = { navController.navigate("system_status") }
                )
            }
            
            composable("system_status") {
                SystemStatusScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}