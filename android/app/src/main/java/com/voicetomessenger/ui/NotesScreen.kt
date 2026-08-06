package com.voicetomessenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voicetomessenger.R
import com.voicetomessenger.data.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(viewModel: MainViewModel, onBack: () -> Unit, navBar: @Composable () -> Unit) {
    val notes by viewModel.notes.collectAsState()

    // Заметку правим и удаляем через диалоги: восстановить удалённую нечем,
    // а промахнуться по иконке в ряду — легко.
    var editing by remember { mutableStateOf<Note?>(null) }
    var deleting by remember { mutableStateOf<Note?>(null) }
    var clearingAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cd_notes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (notes.isNotEmpty()) {
                        IconButton(onClick = { clearingAll = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.notes_clear_cd))
                        }
                    }
                },
            )
        },
        bottomBar = navBar,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.notes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(notes, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    onEdit = { editing = note },
                    onDelete = { deleting = note },
                )
            }
        }
    }

    editing?.let { note ->
        EditNoteDialog(
            initial = note.text,
            onDismiss = { editing = null },
            onSave = { text ->
                viewModel.updateNote(note.id, text)
                editing = null
            },
        )
    }

    deleting?.let { note ->
        ConfirmDialog(
            title = stringResource(R.string.note_delete_title),
            body = stringResource(R.string.note_delete_body),
            onDismiss = { deleting = null },
            onConfirm = {
                viewModel.deleteNote(note.id)
                deleting = null
            },
        )
    }

    if (clearingAll) {
        ConfirmDialog(
            title = stringResource(R.string.notes_clear_title),
            body = stringResource(R.string.notes_clear_body, notes.size),
            onDismiss = { clearingAll = false },
            onConfirm = {
                viewModel.clearNotes()
                clearingAll = false
            },
        )
    }
}

@Composable
private fun NoteCard(note: Note, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = formatDate(note.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(note.text, style = MaterialTheme.typography.bodyMedium)
            // Удаление — единственное необратимое действие, поэтому уводим его в
            // противоположный угол от «Поделиться»: раньше по нему промахивались.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete))
                }
                Row {
                    IconButton(onClick = { context.copyToClipboard(note.text) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy))
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cd_edit))
                    }
                    IconButton(onClick = { context.shareText(note.text) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.cd_share))
                    }
                }
            }
        }
    }
}

@Composable
private fun EditNoteDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_edit_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                // Заметка — обычно несколько строк; выше поле уже не влезает над клавиатурой.
                minLines = 3,
                maxLines = 8,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private val dateFormat = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

private fun formatDate(millis: Long): String = dateFormat.format(Date(millis))
