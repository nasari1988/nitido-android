package com.limpio.app

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class VaultItem(
    val id: String,
    val name: String,
    val originalPath: String,
    val size: Long,
    val modified: Long,
    val added: Long,
) {
    val kind: FileKind get() = FileTypes.kindOf(name)
}

/**
 * Carpeta segura.
 * - Los archivos viven en la memoria privada de la app (ninguna otra app puede leerla).
 * - Se cifran con una llave de datos AES‑256; esa llave a su vez está cifrada con una llave del
 *   chip de seguridad del teléfono (Android Keystore) que solo se puede usar tras tu huella o PIN.
 * - Al bloquear, la llave de datos se olvida.
 */
object Vault {
    private const val ALIAS = "archivos_carpeta_segura"
    private const val AUTH_SECONDS = 20

    var unlocked by mutableStateOf(false)
        private set
    var items by mutableStateOf<List<VaultItem>>(emptyList())
        private set
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    /** Archivos elegidos en el explorador esperando a que desbloquees para guardarse. */
    var pendingImport by mutableStateOf<List<String>>(emptyList())

    private var dataKey: SecretKey? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "carpeta_segura").apply { mkdirs() }
    private fun wrappedKeyFile(ctx: Context) = File(dir(ctx), "llave.bin")
    private fun indexFile(ctx: Context) = File(dir(ctx), "indice.bin")
    private fun dataFile(ctx: Context, id: String) = File(dir(ctx), "$id.dat")
    private fun thumbFile(ctx: Context, id: String) = File(dir(ctx), "$id.min")
    fun tempDir(ctx: Context) = File(ctx.cacheDir, "vault_open").apply { mkdirs() }

    fun exists(ctx: Context) = wrappedKeyFile(ctx).exists()

    fun deviceSecure(ctx: Context) =
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    private fun authenticators() =
        if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun fingerprintReady(ctx: Context) =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    // ---------- Llave del chip de seguridad ----------

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun masterKey(): SecretKey? = keyStore().getKey(ALIAS, null) as? SecretKey

    @Suppress("DEPRECATION")
    private fun createMasterKey() {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) {
                    setUserAuthenticationParameters(
                        AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS)
                }
            }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    /** Pide tu huella (o PIN) y abre la carpeta. La primera vez la crea. */
    fun unlock(activity: FragmentActivity, onDone: (Boolean) -> Unit) {
        val ctx = activity.applicationContext
        if (!deviceSecure(ctx)) {
            message = "Primero configura un bloqueo de pantalla y tu huella en los ajustes del teléfono."
            onDone(false); return
        }
        try {
            if (!exists(ctx) && masterKey() == null) createMasterKey()
        } catch (e: Exception) {
            message = "No se pudo crear la llave segura: ${e.message}"
            onDone(false); return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onDone(openWithMasterKey(ctx))
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    code != BiometricPrompt.ERROR_CANCELED
                ) message = msg.toString()
                onDone(false)
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Carpeta segura")
                .setSubtitle("Usa tu huella para abrirla")
                .setAllowedAuthenticators(authenticators())
                .build()
        )
    }

    private fun openWithMasterKey(ctx: Context): Boolean = try {
        val master = masterKey() ?: throw IllegalStateException("Falta la llave del teléfono")
        val wrapped = wrappedKeyFile(ctx)
        val key = if (!wrapped.exists()) {
            val k = VaultCrypto.newKey()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, master)
            val ct = c.doFinal(k.encoded)
            wrapped.writeBytes(c.iv + ct)
            k
        } else {
            val bytes = wrapped.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            SecretKeySpec(c.doFinal(bytes.copyOfRange(12, bytes.size)), "AES")
        }
        dataKey = key
        items = readIndex(ctx)
        tempDir(ctx).listFiles()?.forEach { it.deleteRecursively() }
        unlocked = true
        true
    } catch (e: KeyPermanentlyInvalidatedException) {
        message = "La llave del teléfono ya no es válida (¿se quitó el bloqueo de pantalla?). El contenido anterior no se puede abrir."
        false
    } catch (e: Exception) {
        message = "No se pudo abrir: ${e.message}"
        false
    }

    fun lock() {
        dataKey = null
        unlocked = false
        items = emptyList()
    }

    // ---------- Índice (cifrado) ----------

    private fun readIndex(ctx: Context): List<VaultItem> {
        val key = dataKey ?: return emptyList()
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(String(VaultCrypto.decryptBytes(key, f.readBytes())))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VaultItem(o.getString("id"), o.getString("name"), o.getString("path"), o.getLong("size"), o.getLong("modified"), o.getLong("added"))
        }.sortedByDescending { it.added }
    }

    private fun writeIndex(ctx: Context, list: List<VaultItem>) {
        val key = dataKey ?: return
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("path", it.originalPath)
                .put("size", it.size).put("modified", it.modified).put("added", it.added))
        }
        val tmp = File(indexFile(ctx).absolutePath + ".tmp")
        tmp.writeBytes(VaultCrypto.encryptBytes(key, arr.toString().toByteArray()))
        tmp.renameTo(indexFile(ctx))
        items = list.sortedByDescending { it.added }
    }

    // ---------- Operaciones (llamar fuera del hilo principal) ----------

    /** Guarda los archivos cifrados y borra los originales SOLO si la copia se verificó. */
    fun import(ctx: Context, paths: List<String>, onProgress: (Int) -> Unit = {}): Pair<Int, Int> {
        val key = dataKey ?: return 0 to paths.size
        var ok = 0
        val list = items.toMutableList()
        val removed = ArrayList<String>()
        paths.forEachIndexed { i, p ->
            onProgress(i)
            val src = File(p)
            if (!src.isFile || Protection.isProtected(p)) return@forEachIndexed
            val id = UUID.randomUUID().toString()
            val out = dataFile(ctx, id)
            try {
                val sha = FileInputStream(src).use { input -> FileOutputStream(out).use { VaultCrypto.encrypt(key, input, it) } }
                // Verificación: descifrar y comparar la huella digital del contenido.
                val check = FileInputStream(out).use { VaultCrypto.decrypt(key, it, NullOutput) }
                if (!sha.contentEquals(check)) throw SecurityException("La verificación no coincidió")
                makeThumb(src)?.let { thumbFile(ctx, id).writeBytes(VaultCrypto.encryptBytes(key, it)) }
                list += VaultItem(id, src.name, p, src.length(), src.lastModified(), System.currentTimeMillis())
                writeIndex(ctx, list)
                if (FileOps.deletePermanently(src)) removed += p
                ok++
            } catch (e: Exception) {
                out.delete(); thumbFile(ctx, id).delete()
            }
        }
        FileOps.rescan(ctx, removed)
        return ok to paths.size
    }

    /** Descifra a un archivo temporal privado para verlo dentro de la app. */
    fun openTemp(ctx: Context, item: VaultItem): File? {
        val key = dataKey ?: return null
        val dest = FileOps.uniqueTarget(tempDir(ctx), item.name)
        return try {
            FileInputStream(dataFile(ctx, item.id)).use { input -> FileOutputStream(dest).use { VaultCrypto.decrypt(key, input, it) } }
            dest.setLastModified(item.modified)
            dest
        } catch (e: Exception) {
            dest.delete(); null
        }
    }

    /** Saca archivos de la carpeta segura a su ubicación original (o a Descargas). */
    fun restore(ctx: Context, sel: List<VaultItem>): Int {
        val key = dataKey ?: return 0
        var ok = 0
        val list = items.toMutableList()
        val restored = ArrayList<String>()
        for (item in sel) {
            val parent = File(item.originalPath).parentFile?.takeIf { it.isDirectory || it.mkdirs() }
                ?: File(Protection.rootPath, "Download/Restaurados").apply { mkdirs() }
            val dest = FileOps.uniqueTarget(parent, item.name)
            try {
                FileInputStream(dataFile(ctx, item.id)).use { input -> FileOutputStream(dest).use { VaultCrypto.decrypt(key, input, it) } }
                dest.setLastModified(item.modified)
                dataFile(ctx, item.id).delete(); thumbFile(ctx, item.id).delete()
                list.removeAll { it.id == item.id }
                restored += dest.absolutePath
                ok++
            } catch (e: Exception) {
                dest.delete()
            }
        }
        writeIndex(ctx, list)
        FileOps.rescan(ctx, restored)
        return ok
    }

    fun deleteForever(ctx: Context, sel: List<VaultItem>) {
        val ids = sel.map { it.id }.toSet()
        ids.forEach { dataFile(ctx, it).delete(); thumbFile(ctx, it).delete() }
        writeIndex(ctx, items.filterNot { it.id in ids })
    }

    fun thumb(ctx: Context, item: VaultItem): Bitmap? {
        val key = dataKey ?: return null
        val f = thumbFile(ctx, item.id)
        if (!f.exists()) return null
        return try {
            val b = VaultCrypto.decryptBytes(key, f.readBytes())
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (e: Exception) { null }
    }

    private fun makeThumb(src: File): ByteArray? = try {
        val bmp: Bitmap? = when (FileTypes.kindOf(src.name)) {
            FileKind.IMAGE -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(src.absolutePath, o)
                var s = 1
                while (minOf(o.outWidth, o.outHeight) / (s * 2) >= 256) s *= 2
                BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
            }
            FileKind.VIDEO -> {
                val m = MediaMetadataRetriever()
                try {
                    m.setDataSource(src.absolutePath)
                    if (Build.VERSION.SDK_INT >= 27) m.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                    else m.getFrameAtTime(1_000_000)
                } finally { m.release() }
            }
            else -> null
        }
        bmp?.let { b -> ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it); b.recycle() }.toByteArray() }
    } catch (e: Exception) { null }

    private object NullOutput : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    /** Para la autoprueba: ¿se puede crear una llave protegida en el chip de seguridad? */
    fun keystoreWorks(): Boolean = try {
        val alias = "archivos_autoprueba"
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        val k = keyStore().getKey(alias, null) as SecretKey
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, k)
        val ct = c.doFinal("hola".toByteArray()); val iv = c.iv
        val d = Cipher.getInstance("AES/GCM/NoPadding"); d.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
        val ok = String(d.doFinal(ct)) == "hola"
        keyStore().deleteEntry(alias)
        ok
    } catch (e: Exception) { false }

    @Suppress("unused")
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
}
