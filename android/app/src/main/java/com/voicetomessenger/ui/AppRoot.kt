package com.voicetomessenger.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private object Routes {
    const val MAIN = "main"
    const val NOTES = "notes"
    const val SETTINGS = "settings"
    const val ADMIN = "admin"
    const val GAME = "game"
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
                    onOpenGame = { navController.navigate(Routes.GAME) },
                )
            }
            composable(Routes.NOTES) {
                NotesScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Routes.GAME) {
                Game2048Screen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenAdmin = { navController.navigate(Routes.ADMIN) },
                )
            }
            composable(Routes.ADMIN) {
                // Своя ViewModel: живёт, пока открыт экран, и тянет конфиг при входе.
                AdminScreen(
                    viewModel = viewModel(factory = AdminViewModel.Factory),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
