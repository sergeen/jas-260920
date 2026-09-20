package com.kiosk.tvplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KioskMainActivity"
        private const val PREFS_NAME = "kiosk_player_prefs"
        private const val KEY_VIDEO_PATH = "saved_video_path"
        private const val DEFAULT_VIDEO_PATH = "/sdcard/Movies/video.mp4"
        private const val KEY_CACHED_SIZE = "cached_video_size"
        private const val KEY_CACHED_MODIFIED = "cached_video_modified"
        private const val KEY_CACHED_SOURCE_PATH = "cached_source_path"
        var isRunning: Boolean = false
    }

    private lateinit var playerView: PlayerView
    private lateinit var overlayContainer: LinearLayout
    private lateinit var tvOverlayTitle: TextView
    private lateinit var tvOverlayMessage: TextView
    private lateinit var btnSelectVideo: Button
    private lateinit var btnGrantPermission: Button

    private var exoPlayer: ExoPlayer? = null
    private lateinit var sharedPreferences: SharedPreferences
    private var activeDialog: FilePickerDialog? = null
    private var currentlyPlayingPath: String? = null

    // Permisos en runtime
    private val requestStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                checkAndPlayVideo()
            } else {
                showPermissionOverlay()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Detener inmediatamente cualquier servicio de arranque residual
        try {
            stopService(Intent(this, BootService::class.java))
        } catch (e: Exception) {
            // ignore
        }

        // Configurar pantalla completa inmersiva permanente y evitar apagado de pantalla
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        setContentView(R.layout.activity_main)

        val prefsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
        } else {
            this
        }
        sharedPreferences = prefsContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        playerView = findViewById(R.id.playerView)
        overlayContainer = findViewById(R.id.overlayContainer)
        tvOverlayTitle = findViewById(R.id.tvOverlayTitle)
        tvOverlayMessage = findViewById(R.id.tvOverlayMessage)
        btnSelectVideo = findViewById(R.id.btnSelectVideo)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)

        btnSelectVideo.setOnClickListener {
            openFilePicker()
        }

        btnGrantPermission.setOnClickListener {
            requestStoragePermissions()
        }

        initPlayer()
    }

    override fun onStart() {
        super.onStart()
        isRunning = true
    }

    override fun onStop() {
        super.onStop()
        isRunning = false
    }

    override fun onResume() {
        super.onResume()
        isRunning = true
        applyImmersiveMode()
        try {
            stopService(Intent(this, BootService::class.java))
        } catch (e: Exception) {
            // ignore
        }
        checkAndPlayVideo()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    /**
     * Requisito Kiosk: Ignorar completamente cualquier toque en la pantalla táctil.
     * Al devolver true, el evento es consumido y ninguna vista táctil reacciona.
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // Ignora toques accidentales o del público en la pantalla
        return true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        // Ignora eventos de ratón/puntero sobre la pantalla
        return true
    }

    override fun dispatchTrackballEvent(ev: MotionEvent?): Boolean {
        return true
    }

    /**
     * Manejo exclusivo de mando a distancia (D-Pad y teclas físicas del televisor).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Si el diálogo selector de archivos está abierto, permitir la navegación estándar de diálogo
        if (activeDialog?.isShowing == true) {
            return super.dispatchKeyEvent(event)
        }

        // Salir de la app con el botón atrás/salir del control remoto
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                Log.i(TAG, "Botón Atrás presionado: saliendo de la aplicación Kiosk")
                finishAffinity()
            }
            return true
        }

        // Abrir selector de video con la tecla MENU o pulsación prolongada de DPAD_CENTER / ENTER
        if (event.keyCode == KeyEvent.KEYCODE_MENU ||
            event.keyCode == KeyEvent.KEYCODE_SETTINGS
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                openFilePicker()
            }
            return true
        }

        // Si se mantiene presionado el botón central de la cruceta por más de 1.5 segundos
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.isLongPress) {
                openFilePicker()
                return true
            }
        }

        // Si el overlay de configuración está visible en pantalla, permitir que los botones reciban el foco
        if (overlayContainer.visibility == View.VISIBLE) {
            return super.dispatchKeyEvent(event)
        }

        return super.dispatchKeyEvent(event)
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun initPlayer() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(this).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Error durante la reproducción en bucle: ${error.message}", error)
                        showErrorOverlay("Error al reproducir el video. Verifique el formato del archivo.\n${error.message}")
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            hideOverlay()
                        }
                    }
                })
            }
            playerView.player = exoPlayer
            playerView.useController = false
        }
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun checkAndPlayVideo() {
        if (!hasStoragePermission()) {
            requestStoragePermissions()
            return
        }

        val videoFile = resolveVideoFile()
        if (videoFile != null && videoFile.exists() && videoFile.canRead()) {
            playVideoFile(videoFile)
        } else {
            val targetPath = getSavedVideoPath() ?: DEFAULT_VIDEO_PATH
            showNoVideoOverlay(targetPath)
        }
    }

    private fun resolveVideoFile(): File? {
        // 1. Buscar video.mp4 en la tarjeta SD externa o memoria USB montada
        try {
            val storageDir = File("/storage")
            if (storageDir.exists() && storageDir.isDirectory) {
                storageDir.listFiles()?.forEach { mount ->
                    if (mount.isDirectory && mount.name != "emulated" && mount.name != "self") {
                        // Buscar en la raíz de la SD externa: /storage/XXXX-XXXX/video.mp4
                        findCaseInsensitiveFile(mount, "video.mp4")?.let {
                            Log.i(TAG, "Encontrado video.mp4 en SD externa: ${it.absolutePath}")
                            return it
                        }
                        // Buscar en carpeta Movies de la SD externa
                        findCaseInsensitiveFile(File(mount, "Movies"), "video.mp4")?.let {
                            Log.i(TAG, "Encontrado video.mp4 en Movies de SD externa: ${it.absolutePath}")
                            return it
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error escaneando almacenamiento externo", e)
        }

        // 2. Buscar video.mp4 en la raíz de la SD interna (/sdcard/video.mp4)
        findCaseInsensitiveFile(Environment.getExternalStorageDirectory(), "video.mp4")?.let {
            Log.i(TAG, "Encontrado video.mp4 en raíz de SD: ${it.absolutePath}")
            return it
        }
        findCaseInsensitiveFile(File("/sdcard"), "video.mp4")?.let {
            Log.i(TAG, "Encontrado video.mp4 en /sdcard: ${it.absolutePath}")
            return it
        }

        // 3. Buscar en /sdcard/Movies/video.mp4
        val envMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        findCaseInsensitiveFile(envMovies, "video.mp4")?.let { return it }
        findCaseInsensitiveFile(File("/sdcard/Movies"), "video.mp4")?.let { return it }

        // 4. Buscar en /sdcard/Download/video.mp4
        val envDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        findCaseInsensitiveFile(envDownload, "video.mp4")?.let { return it }

        // 5. Revisar ruta personalizada guardada en SharedPreferences
        val savedPath = getSavedVideoPath()
        if (!savedPath.isNullOrBlank()) {
            val file = File(savedPath)
            if (file.exists() && file.isFile) return file
        }

        // 6. Fallback: cualquier video en la carpeta Movies
        if (envMovies.exists() && envMovies.isDirectory) {
            val candidates = envMovies.listFiles { f ->
                f.isFile && (f.extension.equals("mp4", ignoreCase = true) ||
                        f.extension.equals("mkv", ignoreCase = true))
            }
            if (!candidates.isNullOrEmpty()) {
                return candidates.first()
            }
        }

        // Fallback de compatibilidad local
        val fullyDir = File(Environment.getExternalStorageDirectory(), "fully")
        if (fullyDir.exists() && fullyDir.isDirectory) {
            val candidates = fullyDir.listFiles { f ->
                f.isFile && (f.extension.equals("mp4", ignoreCase = true) ||
                        f.extension.equals("mkv", ignoreCase = true))
            }
            if (!candidates.isNullOrEmpty()) {
                return candidates.first()
            }
        }

        return null
    }

    private fun findCaseInsensitiveFile(directory: File?, targetName: String): File? {
        if (directory == null || !directory.exists() || !directory.isDirectory) return null
        val direct = File(directory, targetName)
        if (direct.exists() && direct.isFile) return direct

        val match = directory.listFiles { f ->
            f.isFile && f.name.equals(targetName, ignoreCase = true)
        }
        return match?.firstOrNull()
    }

    private fun playVideoFile(file: File) {
        // Si el reproductor ya está activo y reproduciendo este mismo archivo, evitar reiniciar
        if (currentlyPlayingPath == file.absolutePath && exoPlayer != null) {
            hideOverlay()
            if (!exoPlayer!!.isPlaying) {
                exoPlayer?.play()
            }
            return
        }

        currentlyPlayingPath = file.absolutePath
        hideOverlay()
        initPlayer()

        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        exoPlayer?.let { player ->
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            Log.i(TAG, "Iniciada reproducción continua de: ${file.absolutePath}")
        }
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            return true
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    return
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                        return
                    } catch (e2: Exception) {
                        Log.w(TAG, "No se pudo abrir ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION", e2)
                    }
                }
            }
        }

        // Permiso tradicional o multimedia según la versión de Android
        val targetPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestStoragePermissionLauncher.launch(targetPermission)
    }

    private fun showNoVideoOverlay(path: String) {
        overlayContainer.visibility = View.VISIBLE
        tvOverlayTitle.text = getString(R.string.no_video_found_title)
        tvOverlayMessage.text = getString(R.string.no_video_found_message, path)
        btnSelectVideo.visibility = View.VISIBLE
        btnGrantPermission.visibility = View.GONE
        btnSelectVideo.requestFocus()
    }

    private fun showPermissionOverlay() {
        overlayContainer.visibility = View.VISIBLE
        tvOverlayTitle.text = "Permiso Requerido"
        tvOverlayMessage.text = getString(R.string.permission_required)
        btnSelectVideo.visibility = View.GONE
        btnGrantPermission.visibility = View.VISIBLE
        btnGrantPermission.requestFocus()
    }

    private fun showErrorOverlay(msg: String) {
        overlayContainer.visibility = View.VISIBLE
        tvOverlayTitle.text = "Error de Video"
        tvOverlayMessage.text = msg
        btnSelectVideo.visibility = View.VISIBLE
        btnSelectVideo.requestFocus()
    }

    private fun hideOverlay() {
        overlayContainer.visibility = View.GONE
    }

    private fun openFilePicker() {
        val currentPath = getSavedVideoPath() ?: DEFAULT_VIDEO_PATH
        val dialog = FilePickerDialog(this, currentPath) { selectedFile ->
            saveVideoPath(selectedFile.absolutePath)
            Toast.makeText(
                this,
                "Video seleccionado: ${selectedFile.name}",
                Toast.LENGTH_SHORT
            ).show()
            playVideoFile(selectedFile)
        }
        activeDialog = dialog
        dialog.show()
    }

    private fun getSavedVideoPath(): String? {
        return sharedPreferences.getString(KEY_VIDEO_PATH, null)
    }

    private fun saveVideoPath(path: String) {
        sharedPreferences.edit().putString(KEY_VIDEO_PATH, path).apply()
    }
}
