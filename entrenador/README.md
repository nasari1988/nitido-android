# Nítido · Entrenador

Entrena en tu PC tus propios modelos para Nítido:

- **Mi estilo**: aprende tu color, luz y contraste a partir de tus fotos editadas. Pesa menos de 3 MB.
- **Mi retoque de piel**: aprende tu retoque natural de piel (granos, manchas, rojeces; deja poros y textura) en toda la piel del cuerpo. Pesa unos 23 MB.

Todo corre en tu PC. **Tus fotos nunca salen de ella**: la página del entrenador solo se abre en tu propio equipo (`127.0.0.1`), y al exportar solo se crea el archivo del modelo.

## 1. Instalar (una sola vez)

Probado para Pop!_OS con una GTX 1650 (4 GB).

1. Controlador de NVIDIA (si aún no lo tienes):
   ```bash
   sudo apt install system76-driver-nvidia
   ```
   y reinicia.
2. Herramientas de Python:
   ```bash
   sudo apt install python3-venv python3-pip curl
   ```
3. Desde esta carpeta:
   ```bash
   bash instalar.sh
   ```
   Crea un entorno virtual en `.venv` (no toca el Python del sistema) e instala PyTorch con CUDA, MediaPipe (para encontrar la piel) y ONNX. Al final te dice si encontró tu tarjeta.

Para desinstalar todo basta con borrar la carpeta `.venv`.

## 2. Abrir el entrenador

```bash
bash entrenar.sh
```

Se abre en tu navegador en `http://127.0.0.1:8765`. Para cerrarlo usa `Ctrl+C` en la terminal. Si estaba entrenando, la próxima vez sigue desde la última vuelta guardada.

## 3. Tus pares

Prepara dos carpetas con fotos del **mismo nombre**:

```
~/Fotos/estilo/antes/IMG_0412.jpg      ← la foto sin editar
~/Fotos/estilo/despues/IMG_0412.jpg    ← tu edición
```

- **Mi estilo**: 100–300 pares variados (de día, de noche, interior, exterior, personas, paisajes).
- **Mi retoque de piel**: fotos donde se vea piel (cara y cuerpo) retocada a tu gusto. De cada foto salen varias piezas de 512 px; con 100–300 fotos ya hay más de mil piezas.
- No recortes ni enderezes la edición: el «después» debe tener el mismo encuadre que el «antes». La página avisa si un par está desalineado o tiene otra forma y lo excluye; puedes volver a incluirlo con un toque.
- Las fotos deben ser tuyas o de personas que te dieron permiso.

## 4. Ampliar pares

Cada vuelta crea variantes nuevas de tus pares, al vuelo y sin ocupar disco:

- **Igual a los dos lados**: voltear, recortes, giros leves. Cambian el encuadre, no tu edición.
- **Solo en el «antes»**: exposición, balance de blancos, contraste, saturación, ruido, JPEG. El modelo aprende a llegar a tu edición desde fotos peores o distintas.
- **Tu «después» nunca cambia de color.** En el retoque de piel los cambios de color se reducen al mínimo, porque ese retoque no es de color.

«Variantes por par» (20 de inicio) multiplica tus datos: 208 pares × 20 = 4,160 ejemplos por vuelta.

## 5. Entrenar

| | Mi estilo | Mi retoque de piel |
|---|---|---|
| Qué es | 3 tablas de color 3D (33³) que una red diminuta mezcla para cada foto | U-Net residual pequeña (~5.7 millones de pesos) |
| Piezas | 384 px, lote 8 | 512 px, lote 4, media precisión |
| Vueltas | 40 | 60 |
| Tiempo en GTX 1650 | menos de una hora | 6–12 h según tus fotos (déjalo de noche) |

- **Parecido a tu edición** es la medida principal. Se calcula con fotos que el modelo nunca ve al entrenar (un 10 % que se aparta). 100 % sería igual a tu edición y 0 % igual a no hacer nada.
- En cada vuelta se guardan unos ejemplos (antes · modelo · tu edición) y una copia del modelo. También se conserva aparte la mejor vuelta.
- Puedes pausar, seguir o detener. «Desde cero» borra lo entrenado de ese modelo.

## 6. Probar y exportar

- **Probar**: indica una carpeta con fotos nuevas y verás el antes y el resultado lado a lado, con el tiempo que tardó.
- **Exportar**: crea `proyectos/<modelo>/exportado/<modelo>_vN/` con:
  - `mi_estilo.onnx` (entrada `mini` 1×3×256×256, salida `tabla` 3×33×33×33) o `mi_piel.onnx` (entrada `pieza` 1×3×512×512, salida `retocada`);
  - `info.json` con la versión, el parecido y la revisión del ONNX contra PyTorch;
  - en Mi estilo, también `mi_estilo_ejemplo.cube`, una tabla de ejemplo que puedes abrir en otros programas.

El siguiente paso, mandar el modelo al teléfono y convertirlo para la NPU, se agrega en la app y en GitHub. Solo viaja ese archivo.

## Si algo falla

- **«CUDA out of memory»**: en `proyectos/piel/proyecto.json` agrega `"config": {"piel": {"lote": 2}}` y vuelve a empezar la vuelta.
- **«No encontré piel»**: revisa que exista `modelos_base/selfie_multiclass.tflite` (lo baja `instalar.sh`). Si no está, se usa el color de piel, que es menos preciso.
- **El navegador no se abre solo**: abre `http://127.0.0.1:8765` a mano.
- **Otro puerto**: `bash entrenar.sh --puerto 9000`.
