package com.voicetomessenger.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/** Экраны внутри игр: в нижнюю панель они не выведены, попасть в них можно только из «Игр». */
private object GameRoutes {
    const val GAME_2048 = "game_2048"
    const val SCHULTE = "schulte"
    const val STROOP = "stroop"
}

@Composable
fun AppRoot(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val settings by viewModel.settings.collectAsState()

    /**
     * Переход по нижней панели.
     *
     * Стек намеренно не растёт: перед переходом снимаем всё до главного экрана, поэтому
     * системная «назад» с любой вкладки возвращает на запись, а не отматывает историю
     * блужданий. Состояние покинутого экрана не сохраняем — на этом держится живой
     * перевод: его ViewModel умирает вместе с экраном и закрывает сессию, иначе микрофон
     * и счётчик расхода остались бы работать в фоне.
     */
    val openTab: (AppTab) -> Unit = { tab ->
        navController.navigate(tab.route) {
            popUpTo(AppTab.MAIN.route)
            launchSingleTop = true
        }
    }

    val bottomBar: @Composable (AppTab) -> Unit = { current ->
        AppBottomBar(current = current, isAdmin = settings.isAdmin, onSelect = openTab)
    }

    Surface(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = AppTab.MAIN.route) {
            composable(AppTab.MAIN.route) {
                MainScreen(
                    viewModel = viewModel,
                    onOpenSettings = { openTab(AppTab.SETTINGS) },
                    navBar = { bottomBar(AppTab.MAIN) },
                )
            }
            composable(AppTab.LIVE.route) {
                // Своя ViewModel: сессия живого перевода обязана умереть вместе с экраном,
                // иначе микрофон и счётчик расхода остались бы работать в фоне.
                LiveScreen(
                    viewModel = viewModel(factory = LiveViewModel.Factory),
                    onBack = { navController.popBackStack() },
                    navBar = { bottomBar(AppTab.LIVE) },
                )
            }
            composable(AppTab.NOTES.route) {
                NotesScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    navBar = { bottomBar(AppTab.NOTES) },
                )
            }
            composable(AppTab.GAMES.route) {
                GamesScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpen2048 = { navController.navigate(GameRoutes.GAME_2048) },
                    onOpenSchulte = { navController.navigate(GameRoutes.SCHULTE) },
                    onOpenStroop = { navController.navigate(GameRoutes.STROOP) },
                    navBar = { bottomBar(AppTab.GAMES) },
                )
            }
            // Сами игры — без нижней панели: им нужен весь экран, а случайное нажатие
            // посреди партии выкинуло бы из неё.
            composable(GameRoutes.GAME_2048) {
                Game2048Screen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(GameRoutes.SCHULTE) {
                SchulteScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(GameRoutes.STROOP) {
                StroopScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(AppTab.SETTINGS.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenAdmin = { openTab(AppTab.ADMIN) },
                    navBar = { bottomBar(AppTab.SETTINGS) },
                )
            }
            composable(AppTab.ADMIN.route) {
                // Своя ViewModel: живёт, пока открыт экран, и тянет конфиг при входе.
                AdminScreen(
                    viewModel = viewModel(factory = AdminViewModel.Factory),
                    onBack = { navController.popBackStack() },
                    navBar = { bottomBar(AppTab.ADMIN) },
                )
            }
        }
    }
}
