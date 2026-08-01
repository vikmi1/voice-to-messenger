package com.voicetomessenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicetomessenger.data.UsageByModel
import com.voicetomessenger.data.UsageGroup
import com.voicetomessenger.data.UsageReport
import com.voicetomessenger.data.UsageRow
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Вкладка «Расход»: во что обошлись запросы к провайдерам.
 *
 * Цифры целиком приходят с сервера — приложение ничего не считает само, иначе прайс
 * пришлось бы держать в APK и обновлять пересборкой. Раздел админский, поэтому
 * тексты русские, как и вся панель.
 */
@Composable
internal fun UsageTab(state: AdminUiState, viewModel: AdminViewModel) {
    val usage = state.usage

    // Первое открытие вкладки — единственный момент, когда сводку стоит тянуть само́й:
    // при загрузке настроек она не нужна, а запрос перебирает все строки за период.
    LaunchedEffect(Unit) { if (usage.report == null) viewModel.loadUsage() }

    Text("Период", style = MaterialTheme.typography.titleMedium)
    ChipRow(
        // «1 день» — последние сутки от текущего момента, а не календарный сегодняшний
        // день: за него отвечает карточка «Сегодня» ниже.
        options = listOf(
            1 to "1 день", 7 to "7 дней", 30 to "30 дней", 90 to "90 дней", 365 to "год",
        ),
        selected = usage.days,
        onSelect = viewModel::loadUsage,
    )

    when {
        usage.loading && usage.report == null -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }

        usage.error != null -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(usage.error, color = MaterialTheme.colorScheme.error)
                Text(
                    "Если бэкенд отвечает «не найдено» — на сервере старая версия, " +
                        "учёт расхода в ней ещё не появился.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { viewModel.loadUsage() }) { Text("Повторить") }
            }
        }

        usage.report != null -> UsageBody(usage.report, loading = usage.loading)
    }
}

@Composable
private fun UsageBody(report: UsageReport, loading: Boolean) {
    val total = report.total

    // Сегодня — над итогом за период и всегда: за месяц набегает сумма, по которой не понять,
    // много ли ушло прямо сейчас, а открывают вкладку обычно именно с этим вопросом.
    Text("Сегодня", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            val today = report.today
            if (today.requests == 0) {
                Text(
                    "Сегодня запросов не было",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Кредитов", today.credits.toString(), Modifier.weight(1f))
                    Stat("Стоимость", groupMoney(today), Modifier.weight(1f))
                    Stat("Запросов", today.requests.toString(), Modifier.weight(1f))
                }
            }
        }
    }

    Text("За период", style = MaterialTheme.typography.titleMedium)

    if (total.requests == 0) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("За этот период запросов не было", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Расход пишется на каждую диктовку и каждую генерацию, включая прогоны " +
                        "с вкладки «Тест».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Stat("Кредитов", total.credits.toString(), Modifier.weight(1f))
                Stat("Стоимость", groupMoney(total), Modifier.weight(1f))
                Stat("Запросов", total.requests.toString(), Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Stat("Вход", tokens(total.inputTokens), Modifier.weight(1f))
                Stat("Выход", tokens(total.outputTokens), Modifier.weight(1f))
                Stat("Аудио", duration(total.audioSeconds), Modifier.weight(1f))
            }

            val notes = buildList {
                add("1 кредит = $${report.creditUsd}")
                if (total.errors > 0) add("с ошибкой: ${total.errors}")
                if (total.unpriced > 0) add("без цены: ${total.unpriced}")
                if (total.cachedTokens > 0) add("из кэша: ${tokens(total.cachedTokens)}")
            }
            Text(
                notes.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (loading) {
        Text(
            "Обновляю…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text("По моделям", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            report.byModel.forEach { ModelLine(it) }
        }
    }

    Text("По дням", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Сутки нарезает сервер по поясу телефона (см. AdminClient.usage), поэтому
            // вечерняя диктовка попадает в тот день, в который она и была.
            // Последние дни сверху: свежее интереснее, а сервер отдаёт по возрастанию.
            report.byDay.asReversed().forEach { day ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(day.day, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${day.requests} · ${groupMoney(day)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Text("Последние запросы", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            report.recent.take(RECENT_LIMIT).forEach { RecentLine(it) }
        }
    }
    Text(
        "Токены хранятся сырыми, деньги считаются по текущему каталогу цен — правка цены " +
            "пересчитывает всю историю. Прочерк значит, что модели нет в каталоге и цена " +
            "неизвестна; звёздочка — что в группу попали и такие запросы, поэтому сумма занижена.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ModelLine(model: UsageByModel) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                model.key,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(groupMoney(model), style = MaterialTheme.typography.bodyLarge)
        }
        val details = buildList {
            add("${model.requests} запр.")
            if (model.inputTokens > 0 || model.outputTokens > 0) {
                add("${tokens(model.inputTokens)} → ${tokens(model.outputTokens)}")
            }
            if (model.audioSeconds > 0) add(duration(model.audioSeconds))
        }
        Text(
            details.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentLine(row: UsageRow) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${opTitle(row.op)} · ${row.model}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
                color = if (row.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            Text(
                row.credits?.let { "$it кред." } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val details = buildList {
            add(localTime(row.ts))
            if (row.inputTokens > 0 || row.outputTokens > 0) {
                add("${tokens(row.inputTokens)} → ${tokens(row.outputTokens)}")
            }
            row.audioSeconds?.takeIf { it > 0 }?.let { add(duration(it)) }
            if (!row.ok) add("ошибка")
        }
        Text(
            details.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Форматирование --------------------------------------------------------

private const val RECENT_LIMIT = 40

private val TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/** Сумма по группе: все запросы без цены — прочерк, часть — звёздочка. */
private fun groupMoney(group: UsageGroup): String = when {
    group.allUnpriced -> "—"
    group.unpriced > 0 -> money(group.usd) + " *"
    else -> money(group.usd)
}

/**
 * Всегда в долларах и всегда со знаком $. Раньше суммы меньше цента показывались в центах
 * («0.40 ¢»), и 01.08.2026 это прочиталось как 40 центов — то есть в сто раз дороже правды.
 * Мелкие суммы лучше показать лишними знаками после запятой, чем сменой единицы измерения.
 *
 * Locale.ROOT: в русской локали %f даёт запятую, и «$0,0040» читается как ещё один непонятный
 * формат. Точка — то, что стоит в прайсах провайдеров.
 */
private fun money(usd: Double): String = when {
    usd == 0.0 -> "$0"
    usd < 0.0001 -> "<$0.0001"
    // Порог 10 центов, а не 1: при двух знаках $0.0105 и $0.0140 оба схлопывались в «$0.01»,
    // то есть заметно разные суммы выглядели одинаково. Нынешние месячные итоги целиком
    // лежат ниже этого порога, так что подробный формат — основной, а не краевой случай.
    usd < 0.1 -> String.format(Locale.ROOT, "$%.4f", usd)
    else -> String.format(Locale.ROOT, "$%.2f", usd)
}

private fun tokens(count: Int): String =
    if (count >= 10_000) "${count / 1000}k" else count.toString()

private fun duration(seconds: Double): String {
    if (seconds <= 0) return "—"
    val minutes = (seconds / 60).toInt()
    return if (minutes > 0) "$minutes мин" else "${seconds.roundToInt()} с"
}

private fun opTitle(op: String): String = when (op) {
    "process" -> "обработка"
    "transcribe" -> "распознавание"
    "test" -> "тест"
    else -> op
}

/** Время в базе в UTC — показываем в поясе телефона. Мусор оставляем как есть. */
private fun localTime(ts: String): String = runCatching {
    OffsetDateTime.parse(ts).atZoneSameInstant(ZoneId.systemDefault()).format(TIME_FORMAT)
}.getOrDefault(ts)
