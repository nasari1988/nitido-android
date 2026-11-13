package com.limpio.app

import java.io.File

/** Rectángulo del mapa de bloques (en las unidades que se le den: px o fracción). */
data class Rect2(val x: Float, val y: Float, val w: Float, val h: Float)

object Treemap {
    /**
     * Acomodo "squarified" (Bruls, Huizing y van Wijk): bloques lo más cuadrados posible,
     * cada uno con área proporcional a su valor. [values] debe venir ordenado de mayor a menor.
     */
    fun squarify(values: List<Double>, x: Float, y: Float, w: Float, h: Float): List<Rect2> {
        val total = values.sum()
        if (values.isEmpty() || total <= 0 || w <= 0 || h <= 0) return values.map { Rect2(x, y, 0f, 0f) }
        val scale = (w.toDouble() * h) / total
        val areas = values.map { it * scale }
        val out = ArrayList<Rect2>(values.size)
        var rx = x.toDouble(); var ry = y.toDouble(); var rw = w.toDouble(); var rh = h.toDouble()
        var i = 0
        while (i < areas.size) {
            val side = minOf(rw, rh)
            val row = ArrayList<Double>()
            row += areas[i]
            var j = i + 1
            while (j < areas.size && worst(row + areas[j], side) <= worst(row, side)) {
                row += areas[j]; j++
            }
            val sum = row.sum()
            if (rw >= rh) {
                // Columna a la izquierda
                val colW = if (rh > 0) sum / rh else 0.0
                var cy = ry
                row.forEach { a ->
                    val hh = if (colW > 0) a / colW else 0.0
                    out += Rect2(rx.toFloat(), cy.toFloat(), colW.toFloat(), hh.toFloat())
                    cy += hh
                }
                rx += colW; rw -= colW
            } else {
                // Fila arriba
                val rowH = if (rw > 0) sum / rw else 0.0
                var cx = rx
                row.forEach { a ->
                    val ww = if (rowH > 0) a / rowH else 0.0
                    out += Rect2(cx.toFloat(), ry.toFloat(), ww.toFloat(), rowH.toFloat())
                    cx += ww
                }
                ry += rowH; rh -= rowH
            }
            i = j
        }
        return out
    }

    private fun worst(row: List<Double>, side: Double): Double {
        val s = row.sum()
        if (s <= 0 || side <= 0) return Double.MAX_VALUE
        val max = row.max(); val min = row.min()
        val s2 = s * s; val side2 = side * side
        return maxOf(side2 * max / s2, s2 / (side2 * min))
    }
}

object SpaceAnalyzer {
    /**
     * Tamaño total de cada carpeta bajo [root], en una sola pasada.
     * [skip] permite saltar carpetas del sistema. [onFile] sirve para mostrar avance.
     */
    fun folderSizes(root: File, skip: (File) -> Boolean = { false }, onFile: () -> Unit = {}): Map<String, Long> {
        val sizes = HashMap<String, Long>()
        fun walk(dir: File, depth: Int): Long {
            if (depth > 64 || skip(dir)) return 0
            var total = 0L
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) total += walk(f, depth + 1)
                else { total += f.length(); onFile() }
            }
            sizes[dir.absolutePath] = total
            return total
        }
        walk(root, 0)
        return sizes
    }
}
