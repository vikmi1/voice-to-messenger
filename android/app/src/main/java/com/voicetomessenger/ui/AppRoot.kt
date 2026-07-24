package com.voicetomessenger.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private object Routes {
    const val MAIN = "main"
    const val NOTES = "notes"
    const val SETTINGS = "settings"
}

@Composable
fun AppRoot(viewModel: MainViewModel) {
    val navController = rememberNavController()

    Surface(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = Routes.MAIN) {
            composable(Routes.MAIN) {
                MainScreen(
                    viewModel = viewModel,
                    onOpenNotes = { navController.navigate(Routes.NOTES) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.NOTES) {
                NotesScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}
