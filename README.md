# Kiosk TV Player - Android TV Signage

Reproductor de video en bucle continuo y modo quiosco (*digital signage*) para dispositivos **Android TV** (Allwinner H616/H618, BigDroidOS / Android 12).

---

## 1. Despliegue Rápido (Setup Nuevo Dispositivo)

Conectar la TV Box a la red, habilitar depuración ADB en el puerto 5555 y ejecutar en PowerShell:

```powershell
$ip    = "192.168.0.194:5555" # IP del TV Box
$apk   = "KioskTVPlayer-release.apk"
$video = "video_compressed.mp4"

# 1. Conexión e instalación
adb connect $ip
adb -s $ip install -r -g $apk

# 2. Permisos y configuración de Kiosk como Home Launcher
adb -s $ip shell "appops set com.kiosk.tvplayer SYSTEM_ALERT_WINDOW allow"
adb -s $ip shell "appops set com.kiosk.tvplayer MANAGE_EXTERNAL_STORAGE allow"
adb -s $ip shell "pm disable-user --user 0 com.google.android.tvlauncher"
adb -s $ip shell "pm disable-user --user 0 com.softwinner.provision"
adb -s $ip shell "cmd package set-home-activity com.kiosk.tvplayer/.MainActivity"

# 3. Transferencia de video al almacenamiento flash interno
adb -s $ip shell "mkdir -p /sdcard/Movies"
adb -s $ip push $video /sdcard/Movies/video.mp4
adb -s $ip shell "cp /sdcard/Movies/video.mp4 /sdcard/video.mp4"

# 4. Lanzamiento
adb -s $ip shell "am force-stop com.kiosk.tvplayer"
adb -s $ip shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
```

---

## 2. Puntos Técnicos Clave y Solución de Problemas

- **Home Launcher Exclusivo:** BigDroidOS prioriza de fábrica `com.google.android.tvlauncher` y `com.softwinner.provision`. Se deshabilitan a nivel de usuario para forzar el inicio directo de la app en cold boot.
- **Direct Boot (Android 12):** La app soporta `directBootAware="true"` y usa `createDeviceProtectedStorageContext()` para leer preferencias antes del desbloqueo de credenciales.
- **Bloqueo Táctil:** Los toques en pantalla se consumen silenciosamente para evitar que el público pause o cierre el video; el control remoto D-Pad permanece funcional.
- **Almacenamiento Interno vs SD:** El video se almacena en memoria flash interna (`/sdcard/Movies/video.mp4`). Las tarjetas SD extraíbles causan cuellos de botella y desconexiones mecánicas.
- **Recuperación ante Inestabilidad HDMI / Codec Allwinner (`0xffffffc2`):**
  - Desconexiones momentáneas del cable HDMI destruyen la superficie gráfica de Android, bloqueando el decodificador de hardware CedarC.
  - La app recupera la reproducción silenciosamente sin mostrar pantallas de error y cuenta con fallback dinámico a decodificación por software (`OMX.google.h264.decoder`).
- **Alimentación Eléctrica de Pantallas LED (Evitar Brownouts USB):**
  - Los puertos USB del TV Box entregan máximo 500 mA (2.5W). Una pantalla LED requiere entre 1.5A y 2.5A.
  - **Requisito:** Alimentar siempre la pantalla con una fuente externa de **5V (2A–3A)** dedicada. Alimentarla desde el USB del TV Box produce caídas de tensión (*brownouts*), parpadeo y desconexiones del HDMI.

---

## 3. Compresión de Video Recomendada (FFmpeg)

Para videos pesados o de cámaras profesionales, optimizar antes de transferir:

```powershell
ffmpeg -y -nostdin -i input.mp4 -c:v libx264 -crf 23 -preset ultrafast -c:a aac -b:a 192k -movflags +faststart -pix_fmt yuv420p output.mp4
```

---

## 4. Diagnóstico Rápido y Compilación

```powershell
# Verificar foco de la app
adb -s <IP>:5555 shell "dumpsys window | grep mCurrentFocus"

# Monitorear logs del reproductor
adb -s <IP>:5555 logcat -d -t 50 | Select-String "KioskMainActivity"

# Compilar APK de Release (requiere JDK 17 y Android SDK 34)
.\gradlew.bat assembleRelease
```
El APK firmado de producción se ubica en la raíz: [`KioskTVPlayer-release.apk`](KioskTVPlayer-release.apk).
