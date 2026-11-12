package com.mia.launcher.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mia.launcher.LauncherViewModel
import com.mia.launcher.data.Store
import com.mia.launcher.mia.MiaController
import com.mia.launcher.mia.WakeWordService

@Composable
fun SettingsScreen(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    val s = Store.settings
    var showKey by remember { mutableStateOf(false) }
    var hiddenOpen by remember { mutableStateOf(false) }

    fun open(intent: Intent) = try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
        Toast.makeText(ctx, "No se pudo abrir en este teléfono", Toast.LENGTH_SHORT).show()
    }

    fun enableWake() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "Activa «Mostrar sobre otras apps» para que Mia aparezca al decir su nombre", Toast.LENGTH_LONG).show()
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
        }
        if (!vm.modelReady) {
            vm.downloadModel { ok ->
                if (ok) { Store.updateSettings { it.copy(wakeWord = true) }; WakeWordService.start(ctx) }
                else Toast.makeText(ctx, "No se pudo descargar el modelo de voz. Revisa tu internet.", Toast.LENGTH_LONG).show()
            }
        } else {
            Store.updateSettings { it.copy(wakeWord = true) }; WakeWordService.start(ctx)
        }
    }

    val wakePerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) enableWake()
        else Toast.makeText(ctx, "Mia necesita el micrófono para escucharte", Toast.LENGTH_LONG).show()
    }
    val miaPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    Box(Modifier.fillMaxSize().background(Color(0xCC14112E))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ajustes del launcher", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box(Modifier.size(44.dp).glassLight(CircleShape).clickable { vm.settingsOpen = false }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Cerrar", tint = Color.White)
                }
            }

            Card {
                RowButton("Usar como launcher predeterminado", "Elige «Mia Launcher» como app de inicio") { open(Intent(Settings.ACTION_HOME_SETTINGS)) }
                RowButton("Fondo de pantalla", "Los colores del vidrio se ven sobre tu fondo") {
                    open(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Fondo de pantalla"))
                }
            }

            SectionTitle("Apariencia")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Estilo de iconos", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.12f)).padding(3.dp)) {
                        listOf("vidrio" to "Vidrio", "color" to "Original").forEach { (id, label) ->
                            val sel = s.iconStyle == id
                            Text(label, color = if (sel) Ink else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(19.dp)).background(if (sel) Color.White else Color.Transparent)
                                    .clickable { Store.updateSettings { it.copy(iconStyle = id) } }.padding(horizontal = 18.dp, vertical = 10.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Intensidad del vidrio: ${(s.glass * 100).toInt()}%", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Slider(
                        value = s.glass, onValueChange = { v -> Store.updateSettings { it.copy(glass = v) } }, valueRange = 0.15f..0.85f,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent)
                    )
                    Text("Columnas: ${s.columns}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Slider(
                        value = s.columns.toFloat(), onValueChange = { v -> Store.updateSettings { it.copy(columns = v.toInt()) } },
                        valueRange = 3f..6f, steps = 2,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent)
                    )
                }
                ToggleRow("Mostrar nombres de apps", "Debajo de cada icono", s.labels) { v -> Store.updateSettings { it.copy(labels = v) } }
            }

            SectionTitle("Cajón de apps")
            Card {
                RowButton("Apps ocultas", "${Store.hidden.size} ocultas · toca para ver") { hiddenOpen = true }
                RowButton("Pestañas y grupos", "Mantén presionada una pestaña para renombrarla o borrarla") { vm.settingsOpen = false; vm.openDrawer() }
            }

            SectionTitle("Mia")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("¿Cómo te llamas?", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Field(s.userName, "Tu nombre (opcional)") { v -> Store.updateSettings { it.copy(userName = v) } }
                }
                ToggleRow("Mia habla en voz alta", "Lee sus respuestas con la voz del teléfono", s.voiceReplies) { v ->
                    Store.updateSettings { it.copy(voiceReplies = v) }
                    if (v) MiaController.speak("¡Hola! Así sueno.")
                }
                ToggleRow(
                    "«Oye Mia»",
                    when {
                        vm.modelProgress in 0..99 -> "Descargando modelo de voz… ${vm.modelProgress}%"
                        s.wakeWord -> "Escuchando su nombre (sin internet). Usa algo de batería."
                        else -> "Descarga un modelo de 40 MB la primera vez"
                    },
                    s.wakeWord
                ) { v ->
                    if (v) {
                        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                        wakePerms.launch(perms.toTypedArray())
                    } else {
                        Store.updateSettings { it.copy(wakeWord = false) }; WakeWordService.stop(ctx)
                    }
                }
                RowButton("Permisos de Mia", "Micrófono, contactos, llamadas, ubicación y calendario") {
                    miaPerms.launch(arrayOf(
                        Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
                        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_CALENDAR,
                    ))
                }
                RowButton("Mostrar sobre otras apps", "Necesario para que aparezca al decir «Oye Mia»") {
                    open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                }
                RowButton("Mia como asistente del teléfono", "Ajustes › Apps predeterminadas › Asistente digital") {
                    open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                }
            }

            SectionTitle("Plática con IA (opcional, gratis)")
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Sin clave, Mia hace todos los comandos. Con una clave gratuita de Groq también puede platicar de cualquier tema. " +
                            "Créala en console.groq.com › API Keys y pégala aquí.",
                        color = Muted, fontSize = 13.sp, lineHeight = 18.sp
                    )
                    Field(s.groqKey, "Clave de Groq (gsk_…)", secret = !showKey) { v -> Store.updateSettings { it.copy(groqKey = v.trim()) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(if (showKey) "Ocultar clave" else "Ver clave") { showKey = !showKey }
                        ActionChip("Abrir console.groq.com") { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) }
                    }
                    Text("Modelo", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Field(s.groqModel, "llama-3.3-70b-versatile") { v -> Store.updateSettings { it.copy(groqModel = v.trim()) } }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (hiddenOpen) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { hiddenOpen = false }) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), 0.85f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Apps ocultas", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                val hidden = vm.apps.filter { it.key in Store.hidden }
                if (hidden.isEmpty()) Text("No hay apps ocultas.", color = Muted, fontSize = 14.sp)
                hidden.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(a.icon, null, Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(a.label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        ActionChip("Mostrar") { Store.toggleHidden(a.key) }
                    }
                }
                ActionChip("Cerrar") { hiddenOpen = false }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp))) { content() }
}

@Composable
private fun RowButton(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(sub, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Muted, fontSize = 12.sp)
        }
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.2f))
        )
    }
}

@Composable
private fun Field(value: String, hint: String, secret: Boolean = false, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().height(50.dp).glassLight(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(hint, color = Muted, fontSize = 15.sp)
        BasicTextField(
            value, onChange, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
