package com.voicetomessenger

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.voicetomessenger.ui.AppRoot
import com.voicetomessenger.ui.MainViewModel
import com.voicetomessenger.ui.theme.VoiceToMessengerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    // Применяем выбранный в приложении язык до создания UI (приложение — одна Activity).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Без этого декор сам съедает инсет клавиатуры: WindowInsets.ime в Compose
        // всегда 0, и поле ввода нечем поднять над клавиатурой.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VoiceToMessengerTheme {
                AppRoot(viewModel)
            }
        }
    }
}
