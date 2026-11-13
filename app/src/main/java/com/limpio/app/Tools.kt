package com.limpio.app

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
    Tool("test", "Autoprueba", "Revisa que todo funcione", Icons.Filled.Science, 0xFF00838F, true),
    Tool("dup", "Duplicados", "Idénticos y parecidos", Icons.Filled.ContentCopy, 0xFF1E6FD9, true),
    Tool("org", "Organizar", "Cada archivo a su carpeta", Icons.Filled.AutoAwesome, 0xFF7B3FBF, true),
    Tool("large", "Archivos grandes", "Más de 100 MB", Icons.Filled.SdStorage, 0xFFC2610C, true),
    Tool("space", "Análisis de espacio", "Qué ocupa más", Icons.Filled.PieChart, 0xFFD6363B, true),
    Tool("apps", "Apps instaladas", "Tamaño y sin usar", Icons.Filled.Apps, 0xFF0B7A5F, true),
    Tool("safe", "Carpeta segura", "Cifrada, con huella", Icons.Filled.Lock, 0xFF4E5D6C, true),
    Tool("pc", "Transferir a PC", "Por Wi‑Fi, sin cables", Icons.Filled.Computer, 0xFF2E8B3E, true),
    Tool("drive", "Google Drive", "Subir y compartir enlaces", Icons.Filled.Cloud, 0xFF1A73E8, false),
)

/** Pestaña Herramientas: un tablero de herramientas; cada una se abre encima. */
@Composable
fun ToolsTab(
    tool: String?,
    onTool: (String?) -> Unit,
    dups: DupViewModel,
    organizer: OrganizerViewModel,
    selfTest: SelfTestViewModel,
    space: SpaceViewModel,
    apps: AppsViewModel,
    browse: BrowseViewModel,
    onLargeFiles: () -> Unit,
    onExplore: (String?) -> Unit,
) {
    when (tool) {
        "space" -> SpaceScreen(space, onExit = { onTool(null) }, onOpenFolder = { onExplore(it) })
        "apps" -> AppsScreen(apps, onExit = { onTool(null) })
        "pc" -> TransferScreen(onExit = { onTool(null) })
        "safe" -> VaultScreen(browse, onExit = { onTool(null) }, onGoExplore = { onExplore(null) })
        "test" -> SelfTestScreen(selfTest, onExit = { onTool(null) })
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val dark = LocalGlass.current.dark
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                            .background(color.copy(alpha = if (dark) 0.30f else 0.16f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(t.icon, null, tint = if (dark) lerp(color, Color.White, 0.35f) else color) }
                    Spacer(Modifier.weight(1f))
                    if (!t.ready) {
                        Text(
                            "Próximamente",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Column {
                    Text(t.title, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        t.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
