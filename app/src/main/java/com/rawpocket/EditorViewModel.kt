package com.rawpocket

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    enum class Status { EMPTY, LOADING, READY, ERROR }

    data class UiState(
        val status: Status = Status.EMPTY,
        val fileName: String = "",
        val info: PhotoInfo? = null,
        val params: EditParams = EditParams(),
        /** Vista previa en formato GPU; el revelado lo hace el shader, no la CPU. */
        val photo: GpuPhoto? = null,
        val showOriginal: Boolean = false,
        val exporting: Boolean = false,
        /** Aviso breve para mostrar en un Snackbar. */
        val message: String? = null,
        /** Error que impide mostrar la foto (pantalla de error). */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Protege el acceso al handle nativo (abrir, exportar y cerrar nunca se solapan). */
    private val lock = Mutex()
    private var handle = 0L

    @Volatile
    private var editKey: String? = null
    private var saveJob: Job? = null

    // ---------------------------------------------------------------- abrir

    fun open(uri: Uri) {
        viewModelScope.launch {
            _state.value = UiState(status = Status.LOADING)
            _state.value = withContext(Dispatchers.Default) { lock.withLock { openLocked(uri) } }
        }
    }

    private fun openLocked(uri: Uri): UiState {
        closeLocked()
        val app = getApplication<Application>()
        val name = displayName(app.contentResolver, uri)
        val file = File(app.cacheDir, "current.raw")

        try {
            val input = app.contentResolver.openInputStream(uri)
                ?: return failure("No se pudo abrir el archivo.")
            input.use { src -> file.outputStream().use { dst -> src.copyTo(dst) } }
        } catch (e: IOException) {
            return failure("No se pudo leer el archivo.")
        } catch (e: SecurityException) {
            return failure("Sin permiso para leer el archivo.")
        }

        val h = RawNative.nativeOpen(file.absolutePath, PREVIEW_LONG_EDGE)
        if (h <= 0L) return failure("No se pudo abrir el RAW: ${RawNative.nativeErrorText(h.toInt())}")

        val w = RawNative.nativePreviewWidth(h)
        val ht = RawNative.nativePreviewHeight(h)
        // La vista previa pasa a la GPU como textura RGBA half-float (8 bytes por píxel).
        val bytes = w.toLong() * ht * 8
        val data: ByteBuffer = try {
            if (bytes > Int.MAX_VALUE) throw OutOfMemoryError()
            ByteBuffer.allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder())
        } catch (e: OutOfMemoryError) {
            RawNative.nativeClose(h)
            return failure("Memoria insuficiente para la vista previa.")
        }
        if (!RawNative.nativeFillPreviewHalf(h, data)) {
            RawNative.nativeClose(h)
            return failure("No se pudo preparar la vista previa.")
        }
        handle = h

        val key = editKeyFor(name, file.length())
        editKey = key
        val params = loadEdits(key) ?: EditParams()

        return UiState(
            status = Status.READY,
            fileName = name,
            info = PhotoInfo.parse(RawNative.nativeInfo(h)),
            params = params,
            photo = GpuPhoto(w, ht, data),
        )
    }

    private fun failure(message: String) = UiState(status = Status.ERROR, error = message)

    private fun closeLocked() {
        if (handle > 0L) RawNative.nativeClose(handle)
        handle = 0L
        editKey = null
    }

    // ---------------------------------------------------------------- edición

    fun setParam(adjustment: Adjustment, value: Float) {
        _state.update {
            it.copy(params = adjustment.set(it.params, value.coerceIn(adjustment.min, adjustment.max)))
        }
        scheduleSave()
    }

    fun resetParam(adjustment: Adjustment) = setParam(adjustment, 0f)

    fun resetAll() {
        _state.update { it.copy(params = EditParams()) }
        scheduleSave()
    }

    /** Mantener pulsada la foto muestra el original sin ediciones. */
    fun setShowOriginal(show: Boolean) {
        if (_state.value.status != Status.READY || _state.value.showOriginal == show) return
        _state.update { it.copy(showOriginal = show) }
    }

    // ---------------------------------------------------------------- geometría

    /** Enderezado en grados (-45..45). El recorte se ajusta solo para no dejar esquinas vacías. */
    fun setAngle(degrees: Float) = editGeometry { p, w, h ->
        p.withGeometry(p.rotation, degrees, p.aspect, w, h)
    }

    /** Gira la foto en pasos de 90° (+1 = horario, -1 = antihorario). */
    fun rotate(quarterTurns: Int) = editGeometry { p, w, h ->
        p.withGeometry(p.rotation + quarterTurns, p.angle, p.aspect, w, h)
    }

    fun setAspect(index: Int) = editGeometry { p, w, h ->
        p.withGeometry(p.rotation, p.angle, index, w, h)
    }

    /** Quita giro, enderezado y recorte; conserva el resto de las ediciones. */
    fun resetGeometry() {
        _state.update { it.copy(params = it.params.withoutGeometry()) }
        scheduleSave()
    }

    private fun editGeometry(change: (EditParams, Int, Int) -> EditParams) {
        val photo = _state.value.photo ?: return
        _state.update { it.copy(params = change(it.params, photo.width, photo.height)) }
        scheduleSave()
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    // ---------------------------------------------------------------- exportar

    /** Revela el RAW a resolución completa y guarda un JPEG en Pictures/RawPocket. */
    fun export() {
        val s = _state.value
        if (s.status != Status.READY || s.exporting) return
        _state.update { it.copy(exporting = true) }
        val params = s.params
        val baseName = s.fileName.substringBeforeLast('.', s.fileName)

        viewModelScope.launch {
            val message = withContext(Dispatchers.Default) {
                lock.withLock {
                    val h = handle
                    if (h <= 0L) return@withLock "No hay ninguna foto abierta."
                    val tmp = File(getApplication<Application>().cacheDir, "export.jpg")
                    try {
                        val rc = RawNative.nativeExport(h, params.toFloatArray(), tmp.absolutePath, EXPORT_QUALITY, 0)
                        if (rc != 0) return@withLock "No se pudo exportar: ${RawNative.nativeErrorText(rc)}"
                        saveToGallery(tmp, "${baseName}_RawPocket.jpg")
                    } catch (e: Exception) {
                        "No se pudo guardar la imagen: ${e.message ?: "error desconocido"}"
                    } finally {
                        tmp.delete()
                    }
                }
            }
            _state.update { it.copy(exporting = false, message = message) }
        }
    }

    private fun saveToGallery(file: File, displayName: String): String {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RawPocket")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("la galería no aceptó el archivo")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("no se pudo escribir en la galería")
            out.use { dst -> file.inputStream().use { src -> src.copyTo(dst) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "Guardada en Pictures/RawPocket"
    }

    // ---------------------------------------------------------------- persistencia de ediciones

    private fun editsDir(): File = File(getApplication<Application>().filesDir, "edits").apply { mkdirs() }

    private fun editKeyFor(name: String, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$name:$size".toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun loadEdits(key: String): EditParams? {
        val f = File(editsDir(), "$key.json")
        return if (f.exists()) EditParams.fromJson(f.readText()) else null
    }

    private fun scheduleSave() {
        val key = editKey ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400)
            val params = _state.value.params
            val f = File(editsDir(), "$key.json")
            if (params.isDefault) f.delete() else f.writeText(params.toJson())
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCleared() {
        // No se bloquea el hilo principal: si hay una exportación en curso, el cierre espera al candado.
        CoroutineScope(Dispatchers.Default).launch { lock.withLock { closeLocked() } }
    }

    private companion object {
        const val PREVIEW_LONG_EDGE = 2048
        const val EXPORT_QUALITY = 95
    }
}

private fun displayName(resolver: ContentResolver, uri: Uri): String {
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.let { return it }
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "foto"
}
