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
    const val GAMES = "games"
    const val LIVE = "live"
    const val GAME_2048 = "game_2048"
    const val SCHULTE = "schulte"
    const val STROOP = "stroop"
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
                    onOpenGames = { navController.navigate(Routes.GAMES) },
                    onOpenLive = { navController.navigate(Routes.LIVE) },
                )
            }
            composable(Routes.LIVE) {
                // Своя ViewModel: сессия живого перевода обязана умереть вместе с экраном,
                // иначе микрофон и счётчик расхода остались бы работать в фоне.
                LiveScreen(
                    viewModel = viewModel(factory = LiveViewModel.Factory),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.NOTES) {
                NotesScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Routes.GAMES) {
                GamesScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpen2048 = { navController.navigate(Routes.GAME_2048) },
                    onOpenSchulte = { navController.navigate(Routes.SCHULTE) },
                    onOpenStroop = { navController.navigate(Routes.STROOP) },
                )
            }
            composable(Routes.GAME_2048) {
                Game2048Screen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Routes.SCHULTE) {
                SchulteScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Routes.STROOP) {
                StroopScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
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
