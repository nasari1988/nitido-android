package com.limpio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioAndPlayOrderTest {
    @Test fun sinPistaEsNone() = assertEquals(AudioState.NONE, AudioMath.classify(false, emptyList()))

    @Test fun silencioEsSilent() =
        assertEquals(AudioState.SILENT, AudioMath.classify(true, listOf(0.0, 0.001, 0.0035)))

    @Test fun unFragmentoConSonidoBasta() =
        assertEquals(AudioState.HAS, AudioMath.classify(true, listOf(0.0, 0.2, 0.0)))

    @Test fun sinPoderEscucharNoSeMarcaComoMudo() =
        assertEquals(AudioState.HAS, AudioMath.classify(true, emptyList()))

    @Test fun pico16() {
        assertEquals(0.5, AudioMath.peak16(shortArrayOf(0, -16384, 100)), 1e-9)
    }

    @Test fun posiciones() {
        assertEquals(listOf(0L), AudioMath.positionsUs(5_000_000))
        assertEquals(listOf(1_000_000L, 5_000_000L, 9_000_000L), AudioMath.positionsUs(10_000_000))
    }

    @Test fun codigosDeMemoria() {
        AudioState.entries.forEach { assertEquals(it, AudioState.of(it.code)) }
        assertNull(AudioState.of('?'))
        assertEquals("/a/b.mp4|10|20", AudioMath.key("/a/b.mp4", 10, 20))
    }

    @Test fun siguiente() {
        assertEquals(1, PlayOrder.next(0, 3, false))
        assertNull(PlayOrder.next(2, 3, false))
        assertEquals(0, PlayOrder.next(2, 3, true))
        assertNull(PlayOrder.next(0, 0, true))
    }

    @Test fun aleatorioConservaActualYTodos() {
        val l = (0 until 20).map { "x$it" }
        val s = PlayOrder.shuffled(l, 7, 42)
        assertEquals("x7", s.first())
        assertEquals(l.toSet(), s.toSet())
        assertEquals(20, s.size)
    }

    @Test fun mover() {
        assertEquals(listOf("b", "a", "c"), PlayOrder.move(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("c", "a", "b"), PlayOrder.move(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b"), PlayOrder.move(listOf("a", "b"), 0, 5))
    }
}
