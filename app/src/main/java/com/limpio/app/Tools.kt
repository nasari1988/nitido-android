package com.limpio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class Tool(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Long,
    val ready: Boolean,
)

private val TOOLS = listOf(
    Tool("dup", "Duplicados", "Idénticos y parecidos", Icons.Filled.ContentCopy, 0xFF1E6FD9, true),
    Tool("org", "Organizar", "Cada archivo a su carpeta", Icons.Filled.AutoAwesome, 0xFF7B3FBF, true),
    Tool("large", "Archivos grandes", "Más de 100 MB", Icons.Filled.SdStorage, 0xFFC2610C, true),
    Tool("space", "Análisis de espacio", "Qué ocupa más", Icons.Filled.PieChart, 0xFFD6363B, false),
    Tool("apps", "Apps instaladas", "Tamaño y sin usar", Icons.Filled.Apps, 0xFF0B7A5F, false),
    Tool("safe", "Carpeta segura", "Cifrada, con huella", Icons.Filled.Lock, 0xFF4E5D6C, false),
    Tool("pc", "Transferir a PC", "Por Wi‑Fi, sin cables", Icons.Filled.Computer, 0xFF2E8B3E, false),
    Tool("drive", "Google Drive", "Subir y compartir enlaces", Icons.Filled.Cloud, 0xFF1A73E8, false),
)

/** Pestaña Herramientas: un tablero de herramientas; cada una se abre encima. */
@Composable
fun ToolsTab(
    tool: String?,
    onTool: (String?) -> Unit,
    dups: DupViewModel,
    organizer: OrganizerViewModel,
    onLargeFiles: () -> Unit,
) {
    when (tool) {
        "dup" -> DupTab(dups, onExit = { onTool(null) })
        "org" -> OrganizerTab(organizer, onExit = { onTool(null) })
        else -> ToolsHome { t ->
            when (t.id) {
                "large" -> onLargeFiles()
                else -> onTool(t.id)
            }
        }
    }
}

@Composable
private fun ToolsHome(onOpen: (Tool) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Text(
                "Herramientas",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }
        items(TOOLS, key = { it.id }) { t ->
            val color = Color(t.color)
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass()
                    .clickable(enabled = t.ready) { onOpen(t) }
                    .padding(16.dp)
                    .alpha(if (t.ready) 1f else 0.6f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(color.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) { Icon(t.icon, null, tint = color) }
                Column {
                    Text(t.title, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (t.ready) t.subtitle else "Próximamente",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
