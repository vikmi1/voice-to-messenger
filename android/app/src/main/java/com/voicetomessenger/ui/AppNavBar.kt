package com.voicetomessenger.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.voicetomessenger.R

/**
 * Экраны нижней панели. Порядок в перечислении = порядок кнопок слева направо:
 * сначала то, ради чего приложение открывают (запись и живой перевод), потом остальное.
 *
 * Маршруты живут здесь, а не в навигации: панель и `NavHost` должны называть экраны
 * одинаково, иначе подсветка текущей вкладки разъедется с тем, что открыто.
 */
enum class AppTab(val route: String, val icon: ImageVector, val labelRes: Int) {
    MAIN("main", Icons.Rounded.Mic, R.string.nav_main),
    LIVE("live", Icons.Rounded.Translate, R.string.nav_live),
    GAMES("games", Icons.Rounded.Casino, R.string.nav_games),
    NOTES("notes", Icons.AutoMirrored.Rounded.List, R.string.nav_notes),
    SETTINGS("settings", Icons.Rounded.Settings, R.string.nav_settings),
    ADMIN("admin", Icons.Rounded.Dns, R.string.nav_admin),
}

/**
 * Нижняя панель навигации — одна на все экраны.
 *
 * [isAdmin] — разблокирован ли админ-раздел токеном. Без него экран настроек сервера
 * показать нечего, поэтому кнопки просто нет: пять пунктов вместо шести.
 *
 * Подписи набраны мелким стилем осознанно: шесть пунктов делят ширину экрана, и на
 * телефоне 360 dp «Настройки» обычным размером уже не помещаются.
 */
@Composable
fun AppBottomBar(current: AppTab, isAdmin: Boolean, onSelect: (AppTab) -> Unit) {
    NavigationBar {
        for (tab in AppTab.entries) {
            if (tab == AppTab.ADMIN && !isAdmin) continue
            NavigationBarItem(
                selected = tab == current,
                // Повторное нажатие на текущую вкладку ничего не делает: перезапуск
                // экрана оборвал бы запись или сессию перевода.
                onClick = { if (tab != current) onSelect(tab) },
                icon = {
                    // Без contentDescription: подпись рядом, и TalkBack прочитал бы её дважды.
                    Icon(tab.icon, contentDescription = null)
                },
                label = {
                    Text(
                        stringResource(tab.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}
