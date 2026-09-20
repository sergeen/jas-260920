# Kiosk TV Player - Sistema de Señalización Digital para Android TV (Allwinner / BigDroidOS)

Este repositorio contiene la solución completa de desarrollo, configuración y aprovisionamiento para quioscos digitales y cartelería publicitaria (*digital signage*) sobre dispositivos **Android TV** basados en chipsets **Allwinner**.

---

## 1. Especificaciones de Hardware y Entorno Objetivo

El desarrollo y las configuraciones de este proyecto fueron calibrados y validados para el siguiente perfil de dispositivo:

- **Dispositivo:** NOGAPC PRO (y TV boxes genéricas equivalentes).
- **SoC:** Allwinner H616 / H618 (Quad-core ARM Cortex-A53).
- **VPU (Unidad de Video):** Allwinner CedarC Video Engine (`OMX.allwinner.video.decoder.avc` / `omx_vdec_aw`).
- **Sistema Operativo:** BigDroidOS 2.0.1 (Android 12, API 31/32).
- **Kernel:** Linux 5.4.125.
- **Red:** Wi-Fi / Ethernet con depuración remota ADB sobre TCP/IP (puerto 5555).

---

## 2. Decisiones Técnicas Críticas y Aprendizajes Clave

Durante el desarrollo e integración con BigDroidOS 2.0.1 surgieron particularidades críticas del sistema operativo de Allwinner que requirieron soluciones específicas:

### 2.1. Bloqueo de Inicio en Frío (Prioridad de Launchers de Fábrica)
* **Problema:** En BigDroidOS, el sistema incluye launchers propios con prioridad hardcodeada en el framework (`com.google.android.tvlauncher` con prioridad 2 y `com.softwinner.provision` con prioridad 1). Al reiniciar el dispositivo, el sistema forzaba la apertura del launcher nativo e ignoraba la app de cartelería.
* **Solución definitiva:** Deshabilitar los paquetes propietarios a nivel usuario y forzar la actividad de la app como la única actividad Home del sistema:
  ```bash
  adb shell "pm disable-user --user 0 com.google.android.tvlauncher"
  adb shell "pm disable-user --user 0 com.softwinner.provision"
  adb shell "cmd package set-home-activity com.kiosk.tvplayer/.MainActivity"
  ```

### 2.2. Compatibilidad con Android 12 y Direct Boot
* **Problema:** En Android 12, cuando el dispositivo realiza un arranque en frío (Cold Boot), los componentes que no están declarados explícitamente como compatibles con Direct Boot no pueden recibir broadcasts ni leer almacenamiento cifrado hasta que se desbloquee la interfaz por primera vez.
* **Solución:**
  1. En `AndroidManifest.xml`, declarar `android:directBootAware="true"` tanto en `MainActivity` como en `BootService`.
  2. En código Kotlin, utilizar `createDeviceProtectedStorageContext()` para acceder a `SharedPreferences` antes de que el almacenamiento estándar de credenciales se desbloquee.

### 2.3. Bloqueo de Interfaz Táctil Preservando el Control Remoto
* **Problema:** Pantallas táctiles o ratones conectados accidentalmente podían pausar el video, desplegar menús o cerrar la app.
* **Solución:** Sobrescribir los eventos táctiles en la actividad para consumirlos silenciosamente sin afectar los eventos de teclado/D-Pad:
  ```kotlin
  override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = true
  override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean = true
  override fun dispatchTrackballEvent(ev: MotionEvent?): Boolean = true
  ```
  Los eventos de control remoto (`KeyEvent.KEYCODE_DPAD_*`, `KEYCODE_ENTER`) siguen procesándose en `dispatchKeyEvent`, permitiendo funciones de administración (p. ej., pulsación prolongada de OK/Enter para abrir selector manual si fuera necesario).

### 2.4. Prevención de Parpadeo (*Flickering*) en el Arranque
* **Problema:** Durante el ciclo de arranque de Android TV conviven varios emisores de inicio (`BOOT_COMPLETED`, reinicio de actividad tras montaje de almacenamiento, `onResume`). Esto causaba que ExoPlayer se instanciara y re-preparara múltiples veces, produciendo parpadeos negros en pantalla.
* **Solución:** 
  1. Guardar la ruta en reproducción actual (`currentlyPlayingPath`). Si la actividad pasa por `onResume()` y el reproductor ya está reproduciendo ese mismo archivo, se ignora la recarga.
  2. Apagar de inmediato el servicio secundario (`BootService.stopSelf()`) una vez que `MainActivity` toma el foco de pantalla.

### 2.5. Estrategia de Almacenamiento: Interno vs Tarjeta SD Física
* **Problema:** Leer videos pesados directamente desde tarjetas MicroSD físicas extraíbles resultó inestable: velocidades de lectura lentas, desconexiones aleatorias en puertos de bajo costo y riesgo de ANR (App Not Responding) si se intentaban copiar archivos pesados en el hilo principal.
* **Solución:** Almacenar el contenido en el almacenamiento flash interno del televisor (`/storage/emulated/0/Movies/video.mp4`, accesible vía el symlink `/sdcard/Movies/video.mp4`). La memoria flash interna ofrece lectura inmediata con latencia <1ms y es inmune a desconexiones mecánicas.

---

## 3. Estrategia de Compresión y Optimización de Video (FFmpeg)

Los videos exportados directamente de cámaras profesionales (como Sony XAVC-S a 50–60 Mbps con audio PCM sin compresión) provocan retrasos prolongados en la transferencia por red y pueden exceder el perfil de decodificación del hardware CedarC.

### Comando de Conversión Óptimo
Ejecutar en PowerShell / terminal local:

```powershell
$ffmpeg = "ffmpeg.exe" # o la ruta completa al binario
$input  = "ruta/al/video_original.mp4"
$output = "ruta/al/video_compressed.mp4"

& $ffmpeg -y -nostdin -i $input `
    -c:v libx264 `
    -crf 23 `
    -preset ultrafast `
    -c:a aac -b:a 192k `
    -movflags +faststart `
    -pix_fmt yuv420p `
    -nostats -loglevel error `
    $output
```

### Justificación de Parámetros
| Parámetro | Propósito |
|---|---|
| `-nostdin` | **Indispensable en Windows:** evita que el proceso de FFmpeg se congele esperando entrada por teclado en tareas en segundo plano. |
| `-c:v libx264 -crf 23` | Balance óptimo entre calidad visual 1080p indistinguible y peso reducido (~70-75% de ahorro de tamaño). |
| `-preset ultrafast` | Permite codificar a 60-120 fps en la PC local minimizando el tiempo previo al despliegue. |
| `-c:a aac -b:a 192k` | Convierte audio PCM no soportado a AAC estándar compatible con el decodificador de audio del SoC. |
| `-movflags +faststart` | Reubica el átomo `moov` al inicio del contenedor MP4, permitiendo que el reproductor comience la reproducción instantáneamente sin esperar a leer el archivo completo. |
| `-pix_fmt yuv420p` | Garantiza compatibilidad universal con el decodificador por hardware CedarC de Allwinner. |

---

## 4. Flujo Automatizado de Despliegue en Nuevos Dispositivos

Para preparar cualquier TV Box nueva con BigDroidOS desde cero, seguir este procedimiento:

### Paso 1: Autorización Inicial de Red ADB
1. Conectar la TV Box a la misma red Wi-Fi / Ethernet de la computadora.
2. Identificar la IP del dispositivo (en Ajustes de Red de Android TV, ej. `172.20.10.7`).
3. Conectar vía ADB:
   ```powershell
   adb connect 172.20.10.7:5555
   ```
4. **En la pantalla de la TV:** Marcar *"Permitir siempre desde esta computadora"* y pulsar **Aceptar**.
5. Validar estado:
   ```powershell
   adb devices
   # Debe mostrar: 172.20.10.7:5555  device
   ```

### Paso 2: Script Automatizado de Provisión Completa
Guardar y ejecutar el siguiente script de PowerShell (ajustando `$IP`):

```powershell
$adb   = "adb.exe"
$ip    = "172.20.10.7:5555"
$apk   = "c:\Users\sergi\Proyectos\Jas\critaturas\KioskTVPlayer-release.apk"
$video = "c:\Users\sergi\Proyectos\Jas\critaturas\video 1\video_compressed.mp4"

Write-Host "--- 1. Conectando con $ip ---"
& $adb connect $ip

Write-Host "--- 2. Instalando KioskTVPlayer APK ---"
& $adb -s $ip install -r -g $apk

Write-Host "--- 3. Otorgando permisos de sistema ---"
& $adb -s $ip shell "appops set com.kiosk.tvplayer SYSTEM_ALERT_WINDOW allow"
& $adb -s $ip shell "appops set com.kiosk.tvplayer MANAGE_EXTERNAL_STORAGE allow"

Write-Host "--- 4. Deshabilitando launchers nativos conflictivos ---"
& $adb -s $ip shell "pm disable-user --user 0 com.google.android.tvlauncher"
& $adb -s $ip shell "pm disable-user --user 0 com.softwinner.provision"

Write-Host "--- 5. Estableciendo como Home Launcher principal ---"
& $adb -s $ip shell "cmd package set-home-activity com.kiosk.tvplayer/.MainActivity"

Write-Host "--- 6. Creando directorio de medios y transfiriendo video ---"
& $adb -s $ip shell "mkdir -p /sdcard/Movies"
& $adb -s $ip push $video /sdcard/Movies/video.mp4

Write-Host "--- 7. Copiando en raíz de almacenamiento interno (/sdcard) ---"
& $adb -s $ip shell "cp /sdcard/Movies/video.mp4 /sdcard/video.mp4"

Write-Host "--- 8. Iniciando reproductor en modo kiosco ---"
& $adb -s $ip shell "am force-stop com.kiosk.tvplayer"
Start-Sleep -Seconds 1
& $adb -s $ip shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
Start-Sleep -Seconds 2

Write-Host "--- 9. Verificando estado en pantalla ---"
& $adb -s $ip shell "dumpsys window | grep -E 'mCurrentFocus'"
```

---

## 5. Comandos de Diagnóstico y Monitoreo

### Verificar Foco de la Ventana Activa
Confirma que la app esté al frente en pantalla completa y sea el launcher en ejecución:
```powershell
adb -s 172.20.10.X:5555 shell "dumpsys window | grep mCurrentFocus"
# Salida esperada: mCurrentFocus=Window{... com.kiosk.tvplayer/com.kiosk.tvplayer.MainActivity}
```

### Verificar Decodificación por Hardware Allwinner en Tiempo Real
Permite ver los fotogramas entregados por el motor CedarC de Allwinner:
```powershell
adb -s 172.20.10.X:5555 logcat -d -t 30 | Select-String "cedarc|omx_vdec"
# Salida esperada:
# omx_vdec_aw: drainOutBufferProgressive: video pts(...)
# cedarc: <RequestVideoStreamBuffer>: stream buffer 0 ring back.
```

### Probar Arranque en Frío (Reinicio Completo)
Para certificar que el dispositivo arranca directamente en la aplicación sin mostrar menús ni escritorios intermedios:
```powershell
adb -s 172.20.10.X:5555 reboot
```
Tras 25-35 segundos de inicio de BigDroidOS, la pantalla iniciará el video automáticamente en bucle infinito.

---

## 6. Estructura del Proyecto

```
critaturas/
├── app/
│   ├── src/main/
│   │   ├── java/com/kiosk/tvplayer/
│   │   │   ├── MainActivity.kt        # Control del reproductor, inmersión, bloqueo táctil y loop
│   │   │   ├── BootReceiver.kt        # Receptor de inicio automático en arranque
│   │   │   └── BootService.kt         # Servicio de arranque seguro en segundo plano
│   │   ├── res/layout/
│   │   │   └── activity_main.xml      # Layout inmersivo con ExoPlayer StyledPlayerView
│   │   └── AndroidManifest.xml        # Declaración de Home, DirectBoot y permisos de almacenamiento
│   └── build.gradle.kts               # Configuración de ExoPlayer 2.19.1 y SDK 34
├── KioskTVPlayer-release.apk          # APK de producción firmado y listo para distribución
└── README.md                          # Este manual técnico de despliegue
```

---

## 7. Instrucciones de Compilación (Build)

### Requisitos Previos
1. **Java JDK 17:** Se requiere OpenJDK 17 configurado en la variable de entorno `JAVA_HOME`.
   ```powershell
   java -version # Debe reportar versión 17
   ```
2. **Android SDK:** Con `platforms;android-34` y `build-tools;34.0.0` instalados. Definir la ruta en `local.properties`:
   ```properties
   sdk.dir=C:\\Users\\<usuario>\\AppData\\Local\\Android\\Sdk
   ```

### Compilar el APK de Lanzamiento (Release)
Para generar el APK ejecutable directamente desde la consola:

```powershell
# Compilar variante Release
.\gradlew.bat assembleRelease

# El binario compilado se genera en:
# app/build/outputs/apk/release/app-release-unsigned.apk
```

### Compilar variante Debug (para pruebas locales)
```powershell
.\gradlew.bat assembleDebug

# El binario se genera en:
# app/build/outputs/apk/debug/app-debug.apk
```

El APK de producción precompilado y firmado con claves de distribución se encuentra versionado directamente en la raíz como [`KioskTVPlayer-release.apk`](KioskTVPlayer-release.apk).

