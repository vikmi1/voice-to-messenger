package com.voicetomessenger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.getSystemService
import com.voicetomessenger.R

/**
 * Копирование в буфер обмена.
 *
 * С Android 13 система сама показывает подтверждение — свой тост тогда не нужен.
 */
fun Context.copyToClipboard(text: String, toastMessage: String? = null) {
    val clipboard = getSystemService<ClipboardManager>() ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("voice-to-messenger", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, toastMessage ?: getString(R.string.copy_toast), Toast.LENGTH_SHORT).show()
    }
}

/** Системное «Поделиться» — отправку в мессенджере всегда подтверждает пользователь. */
fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, getString(R.string.share_chooser)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(chooser)
}
