package com.mia.launcher.mia

import android.Manifest
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mia.launcher.data.Store
import com.mia.launcher.ui.MiaTheme
import com.mia.launcher.ui.StatusDot
import com.mia.launcher.ui.glass
import com.mia.launcher.ui.glassLight
import kotlinx.coroutines.delay

/** Ventana de voz de Mia: aparece con «Oye Mia», incluso sobre la pantalla de bloqueo. */
class MiaActivity : ComponentActivity() {
    companion object { const val EXTRA_FROM_WAKE = "from_wake" }

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) MiaController.startListening(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        enableEdgeToEdge()
        setContent { MiaTheme { Overlay() } }
        startEars()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        startEars()
    }

    private fun startEars() {
        if (MiaController.hasMic(this)) MiaController.startListening(this)
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroy() {
        MiaController.stopListening()
        if (Store.settings.wakeWord) WakeWordService.resume(this)
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun Overlay() {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        // Se cierra sola unos segundos después de responder.
        LaunchedEffect(MiaController.replyCount, MiaController.listening, MiaController.busy) {
            if (!MiaController.listening && !MiaController.busy && MiaController.replyCount > 0) {
                delay(9000); finish()
            }
        }
        val km = getSystemService(KeyguardManager::class.java)
        Box(
            Modifier.fillMaxSize().background(Color(0x99100E26))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() }
        ) {
            Text(
                if (km.isKeyguardLocked) "Bloqueado · Mia" else "Mia",
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
            )
            AnimatedVisibility(shown, enter = slideInVertically { it / 3 } + fadeIn(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MiaFigure(MiaController.pose, Modifier.fillMaxWidth().height(400.dp))
                    Column(
                        Modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp), 0.6f).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                StatusDot(MiaController.pose.statusColor, if (MiaController.listening) "Escuchando…" else MiaController.pose.status)
                            }
                            Box(Modifier.size(36.dp).glassLight(CircleShape).clickable { finish() }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, "Cerrar", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (MiaController.partial.isNotBlank())
                            Text("«${MiaController.partial}»", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 25.sp)
                        if (MiaController.listening) ListeningWave(Modifier.align(Alignment.CenterHorizontally))
                        else MiaBubble(MiaController.message)
                        MiaInputBar(this@MiaActivity)
                    }
                }
            }
        }
    }
}
