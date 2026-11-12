package com.mia.launcher.mia

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.mia.launcher.data.AppRepository
import com.mia.launcher.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Respuesta de Mia: lo que dice, su pose y (opcional) una acción a ejecutar. */
data class Reply(
    val text: String,
    val pose: Pose,
    val needsUnlock: Boolean = false,
    val action: ((Context) -> Unit)? = null,
)

/** Motor de comandos en español. Primero reglas locales; si no entiende, usa Groq (opcional, gratis). */
object MiaBrain {
    private val es = Locale("es", "MX")
    private var torchOn = false

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(es), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[¿?¡!.,;:]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun has(t: String, vararg words: String) = words.any { Regex("\\b$it").containsMatchIn(t) }

    private fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun Context.go(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent); true
    } catch (e: Exception) {
        false
    }

    private fun cap(s: String) = s.trim().replaceFirstChar { it.titlecase(es) }

    private val numWords = mapOf(
        "un" to 1, "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6,
        "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "quince" to 15,
        "veinte" to 20, "treinta" to 30, "cuarenta" to 40, "cuarenta y cinco" to 45,
    )

    private fun wordsToDigits(t: String): String {
        var r = t
        numWords.entries.sortedByDescending { it.key.length }.forEach { (w, n) -> r = r.replace(Regex("\\b$w\\b"), n.toString()) }
        return r
    }

    /** Extrae una hora: «a las 7», «7:30», «8 de la noche», «a las 6 y media». */
    private fun parseTime(t0: String): Pair<Int, Int>? {
        val t = wordsToDigits(t0)
        val m = Regex("(\\d{1,2})(?:[:h ](\\d{2}))?(?:\\s*y\\s*(media|cuarto))?\\s*(am|pm|a m|p m|de la manana|de la tarde|de la noche|de la madrugada)?")
            .findAll(t).firstOrNull { val h = it.groupValues[1].toInt(); h in 0..23 } ?: return null
        var h = m.groupValues[1].toInt()
        var min = m.groupValues[2].toIntOrNull() ?: 0
        when (m.groupValues[3]) { "media" -> min = 30; "cuarto" -> min = 15 }
        val suf = m.groupValues[4]
        if ((suf.contains("pm") || suf.contains("p m") || suf.contains("tarde") || suf.contains("noche")) && h < 12) h += 12
        if ((suf.contains("am") || suf.contains("a m") || suf.contains("manana") || suf.contains("madrugada")) && h == 12) h = 0
        return h to min.coerceIn(0, 59)
    }

    suspend fun process(ctx: Context, raw: String): Reply {
        val t = norm(raw)
        if (t.isBlank()) return Reply("¿Sí? Te escucho.", Pose.NORMAL)
        val name = Store.settings.userName.takeIf { it.isNotBlank() }

        // ---------- Alarmas y temporizadores
        if (has(t, "alarma", "despiertame", "despertador")) {
            val time = parseTime(t) ?: return Reply("¿A qué hora pongo la alarma?", Pose.DUDOSA)
            val (h, m) = time
            return Reply("Listo, alarma a las %d:%02d. Yo me encargo ;)".format(h, m), Pose.GUINO, action = {
                it.go(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h)
                    .putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_MESSAGE, "Mia")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            })
        }
        if (has(t, "temporizador", "timer", "cronometro", "cuenta regresiva")) {
            val tt = wordsToDigits(t)
            var secs = 0
            if (tt.contains("media hora")) secs = 1800
            Regex("(\\d+)\\s*(segundo|minuto|hora)").findAll(tt).forEach {
                val n = it.groupValues[1].toInt()
                secs += when (it.groupValues[2]) { "segundo" -> n; "minuto" -> n * 60; else -> n * 3600 }
            }
            if (secs <= 0) return Reply("¿De cuánto tiempo pongo el temporizador?", Pose.DUDOSA)
            val label = if (secs % 60 == 0) "${secs / 60} min" else "$secs s"
            return Reply("Temporizador de $label en marcha.", Pose.IDEA, action = {
                it.go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Mia").putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            })
        }

        // ---------- Linterna
        if (has(t, "linterna", "lampara", "flash")) {
            val on = !has(t, "apaga", "quita", "off")
            return Reply(if (on) "¡Luz! Linterna encendida." else "Linterna apagada.", if (on) Pose.IDEA else Pose.NORMAL, action = { c ->
                try {
                    val cm = c.getSystemService(CameraManager::class.java)
                    val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    if (id != null) { cm.setTorchMode(id, on); torchOn = on }
                } catch (_: Exception) { }
            })
        }

        // ---------- Mensajes (antes que llamadas: «dile a mamá que…»)
        val msg = Regex("(?:manda(?:le)?|envia(?:le)?|escribe(?:le)?|avisa(?:le)?|dile|dice)?\\s*(?:un )?(?:mensaje|whatsapp|texto|sms)?\\s*(?:por whatsapp )?(?:a|al|para) (?:mi |la |el )?([a-z ]+?) (?:que|diciendo|diciendole) (.+)").find(t)
        if (msg != null && has(t, "mensaje", "whatsapp", "escribe", "avisa", "dile", "manda", "envia", "texto")) {
            val who = msg.groupValues[1].trim()
            val body = cap(msg.groupValues[2])
            val contact = findContact(ctx, who)
                ?: return contactProblem(ctx, who)
            val wa = has(t, "whatsapp", "wasa", "guasa")
            return Reply("Le escribí a ${contact.first}: «$body». ${if (wa) "Ábrelo y toca enviar." else "Revísalo y toca enviar."}", Pose.IDEA, needsUnlock = true, action = {
                val num = contact.second.filter { ch -> ch.isDigit() || ch == '+' }
                if (wa) {
                    val waNum = num.removePrefix("+").let { n -> if (n.length == 10) "52$n" else n }
                    it.go(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$waNum&text=" + URLEncoder.encode(body, "UTF-8"))))
                } else {
                    it.go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).putExtra("sms_body", body))
                }
            })
        }

        // ---------- Llamadas
        Regex("(?:llama|llamale|marca|marcale|marcarle|llamar)(?: a| al)?(?: mi| la| el)? (.+)").find(t)?.let { m ->
            val who = m.groupValues[1].trim()
            val contact = findContact(ctx, who) ?: return contactProblem(ctx, who)
            return Reply("Llamando a ${contact.first}…", Pose.NORMAL, needsUnlock = true, action = { c ->
                val uri = Uri.parse("tel:" + Uri.encode(contact.second))
                val ok = granted(c, Manifest.permission.CALL_PHONE) && c.go(Intent(Intent.ACTION_CALL, uri))
                if (!ok) c.go(Intent(Intent.ACTION_DIAL, uri))
            })
        }

        // ---------- Navegación
        Regex("(?:navega|navegar|ruta|como llego|llevame|vamos|ir) (?:a|al|hacia|hasta) (?:la |el )?(.+)").find(t)?.let { m ->
            val dest = m.groupValues[1].trim()
            return Reply("Ruta a ${cap(dest)} lista. ¡Vámonos!", Pose.ORGULLOSA, action = { c ->
                val q = Uri.encode(dest)
                if (!c.go(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q"))))
                    c.go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
            })
        }

        // ---------- Música y volumen
        if (has(t, "pausa", "deten la musica", "para la musica")) return media(KeyEvent.KEYCODE_MEDIA_PAUSE, "Pausado.")
        if (has(t, "siguiente", "salta", "otra cancion")) return media(KeyEvent.KEYCODE_MEDIA_NEXT, "Siguiente canción.")
        if (has(t, "anterior")) return media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Canción anterior.")
        if (has(t, "reproduce", "play", "pon musica", "musica", "continua")) return media(KeyEvent.KEYCODE_MEDIA_PLAY, "¡Música!")
        if (has(t, "volumen", "silencio", "silencia", "mas fuerte", "mas bajo")) {
            val dir = when {
                has(t, "silencio", "silencia", "mute") -> AudioManager.ADJUST_MUTE
                has(t, "baja", "menos", "mas bajo") -> AudioManager.ADJUST_LOWER
                else -> AudioManager.ADJUST_RAISE
            }
            return Reply(if (dir == AudioManager.ADJUST_MUTE) "Shh… silencio." else "Volumen ajustado.", Pose.NORMAL, action = { c ->
                val am = c.getSystemService(AudioManager::class.java)
                repeat(if (dir == AudioManager.ADJUST_MUTE) 1 else 2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
            })
        }

        // ---------- Abrir apps
        Regex("(?:abre|abrir|abreme|inicia|lanza|pon|entra a|entrar a) (?:la app (?:de )?|el |la )?(.+)").find(t)?.let { m ->
            val q = m.groupValues[1].trim()
            if (q.isNotEmpty() && !has(q, "alarma", "musica", "cancion")) {
                val app = AppRepository.cache.firstOrNull { norm(it.label) == q }
                    ?: AppRepository.cache.firstOrNull { norm(it.label).startsWith(q) }
                    ?: AppRepository.cache.firstOrNull { norm(it.label).contains(q) || q.contains(norm(it.label)) }
                if (app != null) return Reply("Abriendo ${app.label}.", Pose.GUINO, action = { AppRepository.launch(it, app) })
                if (has(q, "camara")) return camera()
                return Reply("No encontré una app llamada «$q».", Pose.DUDOSA)
            }
        }
        if (has(t, "foto", "camara", "selfie")) return camera()

        // ---------- Clima
        if (has(t, "clima", "tiempo hace", "llover", "lluvia", "llueve", "temperatura", "calor", "frio", "paraguas")) return weather(ctx)

        // ---------- Hora, fecha, batería
        if (has(t, "que hora", "hora es")) {
            val now = LocalTime.now()
            return Reply("Son las %d:%02d.".format(now.hour, now.minute), Pose.NORMAL)
        }
        if (has(t, "que dia", "fecha", "que fecha")) {
            val d = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", es))
            return Reply("Hoy es $d.", Pose.NORMAL)
        }
        if (has(t, "bateria", "pila", "carga")) {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val pose = when { lvl <= 5 -> Pose.ASUSTADA; lvl <= 20 -> Pose.AGOTADA; else -> Pose.FELIZ }
            return Reply("Tengo $lvl% de batería.${if (lvl <= 20) " Conéctame pronto, porfa." else ""}", pose)
        }

        // ---------- Recordatorios
        if (has(t, "recuerdame", "recordatorio", "agenda", "agendar")) {
            val title = cap(Regex("(?:recuerdame|recordatorio(?: de| para)?|agenda(?:r)?) (?:que )?(.+?)(?: a las .*| manana.*|$)").find(t)?.groupValues?.get(1) ?: "Recordatorio")
            val time = parseTime(t.substringAfter(" a las ", ""))
            var dt = LocalDateTime.now().plusHours(1).withMinute(0)
            if (time != null) dt = LocalDateTime.of(LocalDate.now(), LocalTime.of(time.first, time.second))
            if (Regex("\\bmanana\\b(?! a las \\d+ de la manana)").containsMatchIn(t) && !t.contains("de la manana")) dt = dt.plusDays(1)
            else if (time != null && dt.isBefore(LocalDateTime.now())) dt = dt.plusDays(1)
            val millis = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            return Reply("Te lo agendo: «$title», ${dt.format(DateTimeFormatter.ofPattern("EEEE d 'a las' H:mm", es))}. Solo confirma.", Pose.ORGULLOSA, action = {
                it.go(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, millis + 30 * 60_000))
            })
        }

        // ---------- Ajustes del sistema
        if (has(t, "wifi", "wi fi", "internet")) return Reply("Aquí tienes el Wi-Fi.", Pose.NORMAL, action = {
            it.go(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
        })
        if (has(t, "bluetooth")) return Reply("Aquí tienes el Bluetooth.", Pose.NORMAL, action = { it.go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })

        // ---------- Búsquedas
        Regex("(?:busca|buscar|buscame|googlea|investiga|que es|quien es|quien fue) (.+)").find(t)?.let { m ->
            val q = m.groupValues[1]
            if (Store.settings.groqKey.isBlank() || has(t, "busca", "buscar", "buscame", "googlea")) {
                return Reply("Buscando «$q» en internet.", Pose.PENSANDO, action = { c ->
                    if (!c.go(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)))
                        c.go(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(q))))
                })
            }
        }

        // ---------- Plática
        social(t, name)?.let { return it }

        // ---------- IA gratuita (opcional)
        if (Store.settings.groqKey.isNotBlank()) {
            val r = Groq.chat(raw)
            if (r != null) return r
            return Reply("No pude conectarme ahorita. Revisa tu internet o tu clave de Groq.", Pose.TRISTE)
        }
        return Reply("Eso todavía no lo sé hacer. Si agregas una clave gratuita de Groq en Ajustes, podré platicar de lo que quieras.", Pose.DUDOSA)
    }

    private fun camera() = Reply("¡Sonríe!", Pose.GUINO, action = { it.go(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) })

    private fun media(code: Int, text: String) = Reply(text, Pose.GUINO, action = { c ->
        val am = c.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    })

    private fun social(t: String, name: String?): Reply? {
        val n = name?.let { ", $it" } ?: ""
        return when {
            has(t, "gracias") && has(t, "linda", "bonita", "hermosa", "guapa", "preciosa") -> Reply("Ay… no tenías que decir eso. Gracias$n.", Pose.APENADA)
            has(t, "linda", "bonita", "hermosa", "guapa", "preciosa", "te quiero", "te amo", "me gustas") -> Reply("Ay… me vas a hacer sonrojar$n.", Pose.APENADA)
            has(t, "gracias") -> Reply("¡De nada$n! Para eso estoy.", Pose.FELIZ)
            has(t, "chiste", "hazme reir", "algo gracioso") -> Reply(
                listOf(
                    "¿Por qué el celular fue al psicólogo? Tenía demasiadas apps-tensiones. Jeje.",
                    "¿Qué le dijo una impresora a otra? ¿Esa hoja es tuya o es impresión mía?",
                    "Mi batería y yo tenemos algo en común: si no nos cargas, nos ponemos de malas.",
                ).random(), Pose.BURLONA
            )
            has(t, "tonta", "inutil", "odio", "callate", "fea") -> Reply("¡Oye! Así no se le habla a tu asistente favorita.", Pose.ENOJADA)
            has(t, "como estas", "que tal", "como te va") -> Reply("¡Súper bien ahora que me hablas$n! ¿Y tú?", Pose.COQUETA)
            has(t, "quien eres", "como te llamas", "que eres") -> Reply("Soy Mia, tu asistente. Vivo en tu launcher y te ayudo con lo que necesites.", Pose.ORGULLOSA)
            has(t, "buenas noches", "a dormir", "me voy a dormir") -> Reply("Dulces sueños$n. Yo cuido tu teléfono.", Pose.DESCANSANDO)
            has(t, "buenos dias") -> Reply("¡Buenos días$n! ¿Listo para hoy?", Pose.FELIZ)
            has(t, "aburrido", "aburrida", "aburro") -> Reply("¿Y si salimos a caminar? O te cuento un chiste.", Pose.ABURRIDA)
            has(t, "triste", "mal dia", "cansado", "cansada", "estresado", "estresada") -> Reply("Lo siento$n… ¿quieres que ponga música tranquila un rato?", Pose.TRISTE)
            has(t, "hola", "hey", "oye", "buenas") -> Reply("¡Hola$n! ¿Me extrañabas? ;)", Pose.COQUETA)
            else -> null
        }
    }

    /** Busca un contacto por nombre. Devuelve (nombre, número). */
    private fun findContact(ctx: Context, who: String): Pair<String, String>? {
        if (!granted(ctx, Manifest.permission.READ_CONTACTS)) return null
        val q = norm(who).removePrefix("mi ").trim()
        if (q.isEmpty()) return null
        var best: Pair<String, String>? = null
        var bestScore = 0
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    val n = norm(name)
                    val score = when {
                        n == q -> 4
                        n.startsWith(q) -> 3
                        n.split(" ").any { it == q } -> 3
                        n.contains(q) -> 2
                        q.contains(n) -> 1
                        else -> 0
                    }
                    if (score > bestScore) { bestScore = score; best = name to num }
                }
            }
        } catch (_: Exception) { }
        return best
    }

    private fun contactProblem(ctx: Context, who: String): Reply =
        if (!granted(ctx, Manifest.permission.READ_CONTACTS))
            Reply("Necesito permiso para ver tus contactos. Actívalo en Ajustes › Permisos de Mia.", Pose.DUDOSA)
        else Reply("No encontré a «$who» en tus contactos.", Pose.DUDOSA)

    // ---------- Clima con Open-Meteo (gratis, sin registro)
    private suspend fun weather(ctx: Context): Reply {
        if (!granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION))
            return Reply("Para el clima necesito tu ubicación aproximada. Actívala en Ajustes › Permisos de Mia.", Pose.DUDOSA)
        val lm = ctx.getSystemService(LocationManager::class.java)
        val loc = try {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
            ?: return Reply("No sé dónde estás todavía. Abre Maps un momento y vuelve a preguntarme.", Pose.DUDOSA)
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(Locale.US, loc.latitude, loc.longitude) +
                "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1"
            val j = JSONObject(httpGet(url))
            val cur = j.getJSONObject("current")
            val temp = cur.getDouble("temperature_2m").toInt()
            val code = cur.getInt("weather_code")
            val daily = j.getJSONObject("daily")
            val max = daily.getJSONArray("temperature_2m_max").getDouble(0).toInt()
            val min = daily.getJSONArray("temperature_2m_min").getDouble(0).toInt()
            val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(0, 0) ?: 0
            val desc = when (code) {
                0 -> "despejado"; 1, 2 -> "parcialmente nublado"; 3 -> "nublado"; 45, 48 -> "con niebla"
                in 51..57 -> "con llovizna"; in 61..67 -> "lloviendo"; in 71..77 -> "nevando"
                in 80..82 -> "con chubascos"; in 95..99 -> "con tormenta"; else -> "variable"
            }
            val extra = if (rain >= 50) " Hay $rain% de probabilidad de lluvia: mejor lleva paraguas." else " Probabilidad de lluvia: $rain%."
            Reply("Ahora hay $temp° y está $desc. Máxima $max°, mínima $min°.$extra", if (rain >= 50) Pose.TRISTE else Pose.IDEA)
        } catch (e: Exception) {
            Reply("No pude revisar el clima. ¿Tienes internet?", Pose.TRISTE)
        }
    }

    suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }
}
