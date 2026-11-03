# Nítido (Android)

App para mejorar resolución y nitidez de fotos con IA, 100% en el teléfono.

- Modelo: Real-ESRGAN General x4v3 (versión TFLite optimizada por Qualcomm AI Hub), licencia BSD-3-Clause de xinntao/Real-ESRGAN.
- Motor: TensorFlow Lite con GPU, NPU (NNAPI) o CPU.
- Escala ×2 o ×4, comparación antes/después con zoom, guarda en Galería › Pictures/Nitido.
- También aparece en "Compartir" desde la galería.

## Cómo se compila

Cada vez que se sube un cambio a `main`, GitHub Actions descarga el modelo, compila el APK y lo publica en **Releases** como `Nitido.apk`.

## Instalar

En el teléfono: Releases → la versión más nueva → `Nitido.apk` → instalar (permite "instalar apps desconocidas" para el navegador si lo pide).
