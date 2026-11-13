package com.limpio.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado de la carpeta segura: AES‑256‑GCM en bloques de 1 MB, para cifrar videos
 * enormes sin cargarlos completos en memoria.
 *
 * Formato: "ARCV1" + nonce base (12) + bloques [último? (1)][largo (4)][cifrado + etiqueta (16)].
 * Cada bloque usa su propio nonce (base ⊕ índice) y autentica su índice y si es el último,
 * así que reordenar, alterar o recortar el archivo se detecta al descifrar.
 *
 * No usa nada de Android: se prueba en cada compilación.
 */
object VaultCrypto {
    const val CHUNK = 1 shl 20
    private val MAGIC = "ARCV1".toByteArray()
    private val random = SecureRandom()

    fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun nonce(base: ByteArray, index: Int): ByteArray {
        val n = base.copyOf()
        val b = ByteBuffer.allocate(4).putInt(index).array()
        for (i in 0 until 4) n[8 + i] = (n[8 + i].toInt() xor b[i].toInt()).toByte()
        return n
    }

    private fun aad(index: Int, last: Boolean) = ByteBuffer.allocate(5).putInt(index).put(if (last) 1 else 0).array()

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    /** Cifra [input] en [output]. Devuelve el SHA‑256 del contenido original. */
    fun encrypt(key: SecretKey, input: InputStream, output: OutputStream): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val out = DataOutputStream(output)
        val base = ByteArray(12).also { random.nextBytes(it) }
        out.write(MAGIC)
        out.write(base)
        var cur = ByteArray(CHUNK)
        var next = ByteArray(CHUNK)
        var n = readFully(input, cur)
        var index = 0
        while (true) {
            // Se lee un bloque por adelantado para saber si el actual es el último.
            val m = if (n == CHUNK) readFully(input, next) else 0
            val last = m == 0
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(base, index)))
            c.updateAAD(aad(index, last))
            sha.update(cur, 0, n)
            val ct = c.doFinal(cur, 0, n)
            out.writeByte(if (last) 1 else 0)
            out.writeInt(ct.size)
            out.write(ct)
            if (last) break
            val t = cur; cur = next; next = t
            n = m
            index++
        }
        out.flush()
        return sha.digest()
    }

    /** Descifra [input] en [output]. Lanza excepción si el archivo fue alterado o recortado. */
    fun decrypt(key: SecretKey, input: InputStream, output: OutputStream): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val inp = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        inp.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "No es un archivo de la carpeta segura" }
        val base = ByteArray(12)
        inp.readFully(base)
        var index = 0
        while (true) {
            val flag = try { inp.readUnsignedByte() } catch (e: EOFException) {
                throw SecurityException("El archivo está incompleto")
            }
            val last = flag == 1
            val len = inp.readInt()
            require(len in 16..(CHUNK + 16)) { "Bloque inválido" }
            val ct = ByteArray(len)
            inp.readFully(ct)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(base, index)))
            c.updateAAD(aad(index, last))
            val pt = c.doFinal(ct)
            sha.update(pt)
            output.write(pt)
            if (last) break
            index++
        }
        if (inp.read() != -1) throw SecurityException("Hay datos de más al final")
        output.flush()
        return sha.digest()
    }

    fun encryptBytes(key: SecretKey, data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { encrypt(key, ByteArrayInputStream(data), it) }.toByteArray()

    fun decryptBytes(key: SecretKey, data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { decrypt(key, ByteArrayInputStream(data), it) }.toByteArray()
}
