package com.mia.launcher.mia

import androidx.compose.ui.graphics.Color
import com.mia.launcher.R

enum class Anim(val durationMs: Int) {
    RESPIRAR(4000), BRINCO(1400), TRAVIESA(2200), PENSAR(3000),
    TEMBLOR(500), TRISTE(5000), ENOJO(900), DORMIR(6000)
}

enum class Pose(val res: Int, val label: String, val anim: Anim, val halo: Color, val status: String, val statusColor: Color) {
    NORMAL(R.drawable.mia_normal, "Normal", Anim.RESPIRAR, Color(0x998C6CFF), "Disponible", Color(0xFF8EE3C8)),
    FELIZ(R.drawable.mia_alegre, "Feliz", Anim.BRINCO, Color(0x99FFB547), "¡Celebrando!", Color(0xFF8EE3C8)),
    ORGULLOSA(R.drawable.mia_orgullosa, "Orgullosa", Anim.BRINCO, Color(0x8CFFB547), "Orgullosa de ti", Color(0xFF8EE3C8)),
    GUINO(R.drawable.mia_guino, "Guiño", Anim.TRAVIESA, Color(0x8CFF6B6B), "Traviesa", Color(0xFFFFB547)),
    COQUETA(R.drawable.mia_coqueta, "Coqueta", Anim.TRAVIESA, Color(0x99FF6B6B), "Coqueta", Color(0xFFFFB547)),
    BURLONA(R.drawable.mia_burlona, "Burlona", Anim.TRAVIESA, Color(0x8CFF6B6B), "Burlona", Color(0xFFFFB547)),
    IDEA(R.drawable.mia_idea, "¡Idea!", Anim.BRINCO, Color(0x99FFE08A), "Tuvo una idea", Color(0xFFFFE08A)),
    PENSANDO(R.drawable.mia_pensando, "Pensando", Anim.PENSAR, Color(0x8C8FB3FF), "Pensando…", Color(0xFF8FB3FF)),
    DUDOSA(R.drawable.mia_preocupada, "Dudosa", Anim.PENSAR, Color(0x808FB3FF), "No está segura", Color(0xFF8FB3FF)),
    SORPRENDIDA(R.drawable.mia_sorprendida, "Sorprendida", Anim.TEMBLOR, Color(0x99FFE08A), "¡Aviso!", Color(0xFFFFE08A)),
    ASUSTADA(R.drawable.mia_asustada, "Asustada", Anim.TEMBLOR, Color(0x99FF6B6B), "¡Alerta!", Color(0xFFFF8A8A)),
    TRISTE(R.drawable.mia_triste, "Triste", Anim.TRISTE, Color(0x668FB3FF), "Triste", Color(0xFF8FB3FF)),
    ENOJADA(R.drawable.mia_enojada, "Enojada", Anim.ENOJO, Color(0x99FF5A5A), "Molesta", Color(0xFFFF8A8A)),
    APENADA(R.drawable.mia_apenada, "Apenada", Anim.RESPIRAR, Color(0x99FF96B4), "Sonrojada", Color(0xFFFFB3C7)),
    ABURRIDA(R.drawable.mia_aburrida, "Aburrida", Anim.TRISTE, Color(0x66C9BEFF), "Aburrida", Color(0xFFC9BEFF)),
    AGOTADA(R.drawable.mia_agotada, "Agotada", Anim.TRISTE, Color(0x66FFB547), "Batería baja", Color(0xFFFFB547)),
    DESCANSANDO(R.drawable.mia_relajada, "Descansando", Anim.DORMIR, Color(0x4DC9BEFF), "Modo Noche", Color(0xFFC9BEFF));

    companion object {
        fun fromTag(tag: String?): Pose? {
            val t = MiaBrain.norm(tag ?: return null)
            return entries.firstOrNull { MiaBrain.norm(it.name) == t || MiaBrain.norm(it.label) == t }
                ?: when (t) {
                    "alegre", "contenta" -> FELIZ
                    "traviesa", "guino" -> GUINO
                    "preocupada", "duda" -> DUDOSA
                    "cansada" -> AGOTADA
                    "dormida", "noche" -> DESCANSANDO
                    else -> null
                }
        }
    }
}
