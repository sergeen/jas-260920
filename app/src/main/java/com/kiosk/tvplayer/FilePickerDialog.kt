package com.kiosk.tvplayer

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale

class FilePickerDialog(
    context: Context,
    private val initialPath: String?,
    private val onVideoSelected: (File) -> Unit
) : Dialog(context) {

    private lateinit var tvCurrentPath: TextView
    private lateinit var rvFileList: RecyclerView
    private lateinit var btnDefaultPath: Button
    private lateinit var btnCancelPicker: Button

    private var currentDirectory: File = getInitialDirectory()
    private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_file_picker)

        // Asignar dimensiones amplias para TV
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.85).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.85).toInt()
        )

        tvCurrentPath = findViewById(R.id.tvCurrentPath)
        rvFileList = findViewById(R.id.rvFileList)
        btnDefaultPath = findViewById(R.id.btnDefaultPath)
        btnCancelPicker = findViewById(R.id.btnCancelPicker)

        rvFileList.layoutManager = LinearLayoutManager(context)

        btnDefaultPath.setOnClickListener {
            val defaultFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "video.mp4"
            )
            onVideoSelected(defaultFile)
            dismiss()
        }

        btnCancelPicker.setOnClickListener {
            dismiss()
        }

        loadDirectory(currentDirectory)
    }

    private fun getInitialDirectory(): File {
        if (!initialPath.isNullOrBlank()) {
            val file = File(initialPath)
            if (file.exists()) {
                return if (file.isDirectory) file else file.parentFile ?: file
            }
        }

        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (moviesDir.exists()) return moviesDir

        val sdcard = Environment.getExternalStorageDirectory()
        if (sdcard.exists()) return sdcard

        return File("/storage")
    }

    private fun loadDirectory(directory: File) {
        currentDirectory = directory
        tvCurrentPath.text = "Ruta: ${directory.absolutePath}"

        val items = mutableListOf<FileItem>()

        // Opción para subir al directorio padre si no estamos en la raíz absoluta
        val parent = directory.parentFile
        if (parent != null && parent.canRead()) {
            items.add(FileItem(name = ".. (Subir nivel)", file = parent, isDirectory = true, isParent = true))
        }

        val files = directory.listFiles()
        if (files != null) {
            // Ordenar: primero carpetas, luego archivos de video
            val directories = files.filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
            val videoFiles = files.filter { file ->
                file.isFile && videoExtensions.contains(file.extension.lowercase(Locale.ROOT))
            }.sortedBy { it.name.lowercase(Locale.ROOT) }

            directories.forEach { dir ->
                items.add(FileItem(name = dir.name, file = dir, isDirectory = true))
            }
            videoFiles.forEach { vid ->
                val sizeMb = String.format(Locale.US, "%.1f MB", vid.length() / (1024.0 * 1024.0))
                items.add(FileItem(name = vid.name, file = vid, isDirectory = false, details = sizeMb))
            }
        }

        val adapter = FileAdapter(items) { selectedItem ->
            if (selectedItem.isDirectory) {
                loadDirectory(selectedItem.file)
            } else {
                onVideoSelected(selectedItem.file)
                dismiss()
            }
        }
        rvFileList.adapter = adapter
        rvFileList.requestFocus()
    }

    data class FileItem(
        val name: String,
        val file: File,
        val isDirectory: Boolean,
        val isParent: Boolean = false,
        val details: String = ""
    )

    class FileAdapter(
        private val items: List<FileItem>,
        private val onClick: (FileItem) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvIcon: TextView = view.findViewById(R.id.tvFileIcon)
            val tvName: TextView = view.findViewById(R.id.tvFileName)
            val tvDetails: TextView = view.findViewById(R.id.tvFileDetails)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name

            if (item.isDirectory) {
                holder.tvIcon.text = if (item.isParent) "⬆️" else "📁"
                holder.tvDetails.text = if (item.isParent) "Volver atrás" else "Carpeta"
            } else {
                holder.tvIcon.text = "🎬"
                holder.tvDetails.text = "Video • ${item.details}"
            }

            holder.itemView.setOnClickListener {
                onClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
