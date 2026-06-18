package org.epiphyte.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.epiphyte.app.controller.AppController
import org.epiphyte.app.ui.theme.EpiphyteTheme
import org.epiphyte.app.ui.screens.LoginScreen
import org.epiphyte.app.ui.screens.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val app = application as EpiphyteApplication
        val controller = app.controller

        setContent {
            EpiphyteTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "login") {
                    composable("login") {
                        LoginScreen(
                            controller = controller,
                            onLoginSuccess = {
                                navController.navigate("main") {
                                    popUpTo("login") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("main") {
                        MainScreen(controller = controller)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val app = application as EpiphyteApplication
        app.controller.shutdown()
    }
}
