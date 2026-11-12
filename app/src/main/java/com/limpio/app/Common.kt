package com.limpio.app

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

fun formatSize(context: android.content.Context, bytes: Long): String =
    Formatter.formatFileSize(context, bytes)

private val dateFormat: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
fun formatDate(ms: Long): String = dateFormat.format(Date(ms))

/**
 * Lista para revisar antes de actuar: cada elemento con su miniatura (tócala para abrir
 * el archivo), casilla de selección y un botón final con confirmación.
 */
@Composable
fun ReviewList(
    header: String?,
    items: List<CleanItem>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSetAll: (Boolean) -> Unit,
    grouped: Boolean,
    destructive: Boolean,
    actionLabel: (count: Int, size: Long) -> String,
    confirmTitle: (count: Int) -> String,
    confirmText: (size: Long) -> String,
    onConfirm: () -> Unit,
) {
    val ctx = LocalContext.current
    val chosen = items.filter { it.path in selected }
    val chosenSize = chosen.sumOf { it.size }
    val allOn = items.isNotEmpty() && chosen.size == items.size
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable { onSetAll(!allOn) }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = allOn, onCheckedChange = { onSetAll(it) })
            Text("Seleccionar todo (${items.size})", modifier = Modifier.weight(1f))
            Text(
                "Toca la miniatura para abrir",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
        HorizontalDivider()

        if (grouped) {
            val groups = items.groupBy { it.group }.values.toList()
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(groups, key = { it.first().group }) { g ->
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            if (g.all { it.size == g.first().size })
                                "Grupo ${g.first().group + 1} · ${g.size} copias de " +
                                    formatSize(ctx, g.first().size)
                            else "Grupo ${g.first().group + 1} · ${g.size} videos parecidos",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp)
                        )
                        g.forEach { item ->
                            ItemRow(item, item.path in selected) { onToggle(item.path) }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(items, key = { it.path }) { item ->
                    ItemRow(item, item.path in selected) { onToggle(item.path) }
                }
            }
        }

        HorizontalDivider()
        Button(
            onClick = { confirm = true },
            enabled = chosen.isNotEmpty(),
            colors = if (destructive) ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ) else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)
        ) {
            Text(actionLabel(chosen.size, chosenSize))
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(confirmTitle(chosen.size)) },
            text = { Text(confirmText(chosenSize)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onConfirm() }) {
                    Text(if (destructive) "Eliminar" else "Aceptar")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
fun ItemRow(item: CleanItem, checked: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val location = Protection.relative(item.path).substringBeforeLast('/', "")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Box(
            Modifier.clickable(enabled = !item.isDir) { FileOps.open(ctx, item.path) }
        ) {
            FileThumb(item.path, item.isDir, 56.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            if (location.isNotEmpty()) {
                Text(
                    location, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                listOfNotNull(item.note, formatDate(item.lastModified)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.note?.startsWith("Original") == true || item.note?.startsWith("El más antiguo") == true) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        if (!(item.isDir && item.size == 0L)) {
            Text(formatSize(ctx, item.size), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(12.dp))
    }
}
