# Mia Launcher

Launcher para Android con estilo vidrio y Mia, una asistente con 17 poses que responde por voz.

## Funciones
- **Inicio:** fecha, pila de widgets propios (reloj, batería, agenda, Mia), apps favoritas, dock y búsqueda.
- **Cajón de apps:** desliza hacia arriba. Pestañas personalizadas, grupos (carpetas), búsqueda y apps ocultas.
- **Widgets:** página a la derecha, con widgets de cualquier app.
- **Mia:** página a la izquierda. Reacciona a la batería, la hora y al desbloqueo. Le puedes escribir o hablar.
- **«Oye Mia»:** palabra de activación sin internet (Vosk). Aparece incluso con la pantalla bloqueada.
- **Comandos:** alarmas, temporizadores, linterna, llamar, mensajes (SMS o WhatsApp), navegación, abrir apps, música, volumen, clima (Open-Meteo), hora, fecha, batería, recordatorios, Wi-Fi, Bluetooth, búsquedas web y plática.
- **IA opcional y gratuita:** pega una clave de Groq en Ajustes para conversar de cualquier tema.

## Compilar
GitHub Actions compila el APK en cada push a `main` (pestaña **Actions** › artefacto **MiaLauncher**).
En local: abre el proyecto en Android Studio y ejecuta `assembleDebug`.
