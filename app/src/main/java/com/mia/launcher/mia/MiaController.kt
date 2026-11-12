package com.mia.launcher.mia

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.mia.launcher.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.Locale

/** Estado de Mia (pose, mensaje) + voz + reacciones a eventos del teléfono. */
object MiaController {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var pose by mutableStateOf(Pose.NORMAL)
        private set
    var message by mutableStateOf("¡Hola! Soy Mia. Pídeme lo que necesites.")
        private set
    var trigger by mutableStateOf("Lista para ayudarte")
        private set
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var battery by mutableIntStateOf(100)
        private set
    var charging by mutableStateOf(false)
        private set
    /** Cambia cada vez que Mia termina de responder (útil para cerrar la ventana de voz). */
    var replyCount by mutableIntStateOf(0)
        private set

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var lastChat = 0L
    private val lastEvent = HashMap<String, Long>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onSystemEvent(i)
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        greetByHour(force = true)
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("es", "MX"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale("es"))
                tts?.setPitch(1.15f)
                ttsReady = true
            }
        }
    }

    fun speak(text: String) {
        if (!Store.settings.voiceReplies) return
        ensureTts()
        val clean = text.replace(";)", "").replace("«", "").replace("»", "")
        if (ttsReady) tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "mia")
        else scope.launch { kotlinx.coroutines.delay(700); if (ttsReady) tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "mia") }
    }

    fun stopSpeaking() { tts?.stop() }

    // ---------------- Eventos del teléfono
    private fun canReact(key: String, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastChat < 25_000 || busy || listening) return false
        if (now - (lastEvent[key] ?: 0L) < cooldownMs) return false
        lastEvent[key] = now
        return true
    }

    private fun react(p: Pose, why: String, msg: String) {
        pose = p; trigger = why; message = msg
    }

    private fun onSystemEvent(i: Intent) {
        when (i.action) {
            Intent.ACTION_BATTERY_CHANGED -> {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (level >= 0) battery = (level * 100 / scale.coerceAtLeast(1))
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                if (!charging && battery <= 5 && canReact("bat5", 20 * 60_000))
                    react(Pose.ASUSTADA, "Batería al $battery%", "¡La batería está al $battery%! Conéctame ya, por favor.")
                else if (!charging && battery <= 15 && canReact("bat15", 60 * 60_000))
                    react(Pose.AGOTADA, "Batería al $battery%", "Batería al $battery%. Ahorra energía o conéctame pronto.")
            }
            Intent.ACTION_POWER_CONNECTED -> if (canReact("charge", 10 * 60_000))
                react(Pose.FELIZ, "Cargador conectado", "¡Gracias! Ya estoy cargando.")
            Intent.ACTION_USER_PRESENT -> greetByHour(force = false)
            Intent.ACTION_TIME_TICK -> {
                val h = LocalTime.now().hour
                if ((h >= 23 || h < 6) && pose != Pose.DESCANSANDO && canReact("noche", 30 * 60_000))
                    react(Pose.DESCANSANDO, "Son las ${LocalTime.now().hour}:%02d".format(LocalTime.now().minute), "Ya es tarde… Modo Noche activo. Solo te aviso si es importante.")
            }
        }
    }

    private fun greetByHour(force: Boolean) {
        if (!force && !canReact("saludo", 15 * 60_000)) return
        val n = Store.settings.userName.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
        val h = LocalTime.now().hour
        when {
            h < 6 || h >= 23 -> react(Pose.DESCANSANDO, "Desbloqueaste el teléfono", "¿Todavía despierto$n? Yo aquí te acompaño.")
            h < 12 -> react(Pose.FELIZ, "Desbloqueaste el teléfono", "¡Buenos días$n! ¿Qué hacemos hoy?")
            h < 19 -> react(Pose.COQUETA, "Desbloqueaste el teléfono", "¿Otra vez tú por aquí$n? Ya te extrañaba.")
            else -> react(Pose.NORMAL, "Desbloqueaste el teléfono", "¡Buenas noches$n! ¿Te ayudo con algo?")
        }
    }

    // ---------------- Conversación
    fun handleInput(text: String, activity: Activity?) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        lastChat = System.currentTimeMillis()
        busy = true
        trigger = "Le dijiste: «$clean»"
        pose = Pose.PENSANDO
        message = "Déjame ver…"
        scope.launch {
            val r = try { MiaBrain.process(app, clean) } catch (e: Exception) {
                Reply("Uy, algo falló. ¿Me lo repites?", Pose.DUDOSA)
            }
            pose = r.pose
            message = r.text
            busy = false
            lastChat = System.currentTimeMillis()
            replyCount++
            speak(r.text)
            r.action?.let { act -> runAction(act, r.needsUnlock, activity) }
        }
    }

    private fun runAction(act: (Context) -> Unit, needsUnlock: Boolean, activity: Activity?) {
        val km = app.getSystemService(KeyguardManager::class.java)
        if (needsUnlock && km.isKeyguardLocked && activity != null) {
            km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { act(activity) }
                override fun onDismissCancelled() {
                    pose = Pose.TRISTE; message = "Necesito que desbloquees para hacerlo."
                }
            })
        } else {
            try { act(activity ?: app) } catch (e: Exception) {
                pose = Pose.DUDOSA; message = "No pude hacerlo en este teléfono."
            }
        }
    }

    // ---------------- Escuchar (voz a texto del sistema)
    fun hasMic(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startListening(activity: Activity?) {
        if (!hasMic(app)) {
            pose = Pose.DUDOSA; message = "Necesito permiso del micrófono para escucharte."; return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            pose = Pose.TRISTE; message = "Este teléfono no tiene reconocimiento de voz instalado."; return
        }
        stopSpeaking()
        recognizer?.destroy()
        val rec = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = rec
        partial = ""
        listening = true
        pose = Pose.NORMAL
        trigger = "Escuchando…"
        message = "Te escucho…"
        lastChat = System.currentTimeMillis()
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                partial = text
                if (text.isBlank()) { pose = Pose.DUDOSA; message = "No te escuché bien… ¿me lo repites?" }
                else handleInput(text, activity)
            }
            override fun onError(error: Int) {
                listening = false
                pose = Pose.DUDOSA
                message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te escuché bien… ¿me lo repites?"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sin internet no te entiendo bien. Intenta de nuevo."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Necesito permiso del micrófono."
                    else -> "Algo pasó con el micrófono. Intenta otra vez."
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        rec.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
        listening = false
    }
}
