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

    private val recentStore = RecentStore(File(app.filesDir, "recents.json"))
    private val _recents = MutableStateFlow(recentStore.load())
    val recents: StateFlow<List<RecentItem>> = _recents.asStateFlow()
    val thumbDir: File = File(app.filesDir, "thumbs").apply { mkdirs() }

    /** Protege el acceso al handle nativo (abrir, exportar y cerrar nunca se solapan). */
    private val lock = Mutex()
    private var handle = 0L

    @Volatile
    private var editKey: String? = null
    private var saveJob: Job? = null

    // ---------------------------------------------------------------- abrir

    /** Abre una foto. Si es una reciente que ya no se puede leer, se quita de la lista. */
    fun open(uri: Uri) {
        viewModelScope.launch {
            _state.value = UiState(status = Status.LOADING)
            val result = withContext(Dispatchers.Default) { lock.withLock { openLocked(uri) } }
            _state.value = result
            if (result.status == Status.ERROR) {
                _recents.value = RecentList.remove(_recents.value, uri.toString())
                saveRecents()
            }
        }
    }

    /** Vuelve a la pantalla de inicio y suelta la foto abierta. */
    fun closePhoto() {
        if (_state.value.status == Status.LOADING) return
        _state.value = UiState()
        viewModelScope.launch(Dispatchers.Default) { lock.withLock { closeLocked() } }
    }

    fun removeRecent(item: RecentItem) {
        val before = _recents.value
        _recents.value = RecentList.remove(before, item.uri)
        deleteThumbs(RecentList.dropped(before, _recents.value))
        saveRecents()
    }

    private fun saveRecents() {
        val snapshot = _recents.value
        viewModelScope.launch(Dispatchers.IO) { runCatching { recentStore.save(snapshot) } }
    }

    private fun deleteThumbs(items: List<RecentItem>) {
        viewModelScope.launch(Dispatchers.IO) { items.forEach { it.thumb?.let { t -> File(thumbDir, t).delete() } } }
    }

    private fun openLocked(uri: Uri): UiState {
        closeLocked()
        val app = getApplication<Application>()
        val name = displayName(app.contentResolver, uri)
        val file = File(app.cacheDir, "current.src")

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
        if (h <= 0L) return failure("No se pudo abrir la foto: ${RawNative.nativeErrorText(h.toInt())}")

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
        rememberRecent(uri, name, key, h)
        val params = loadEdits(key) ?: EditParams()

        return UiState(
            status = Status.READY,
            fileName = name,
            info = PhotoInfo.parse(RawNative.nativeInfo(h)),
            params = params,
            photo = GpuPhoto(w, ht, data),
        )
    }

    /** Añade la foto a los recientes con su miniatura (se llama con el candado tomado). */
    private fun rememberRecent(uri: Uri, name: String, key: String, h: Long) {
        val thumbName = "$key.jpg"
        val ok = RawNative.nativeThumbnail(h, File(thumbDir, thumbName).absolutePath, THUMB_EDGE) == 0
        val before = _recents.value
        val after = RecentList.add(before, RecentItem(uri.toString(), name, System.currentTimeMillis(), if (ok) thumbName else null))
        _recents.value = after
        // Una miniatura solo se borra si ya ninguna entrada la usa (dos fotos pueden compartir clave).
        val used = after.mapNotNull { it.thumb }.toSet()
        deleteThumbs(RecentList.dropped(before, after).filter { it.thumb != null && it.thumb !in used })
        saveRecents()
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

    /** Revela la foto a resolución completa y guarda un JPEG en Pictures/Efoto. */
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
                        saveToGallery(tmp, "${baseName}_Efoto.jpg")
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
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Efoto")
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
        return "Guardada en Pictures/Efoto"
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
        const val THUMB_EDGE = 320
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
