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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        /** Histograma 4x256 (R, G, B, luminancia) de la foto revelada. */
        val histogram: IntArray? = null,
        /** Sube cuando llegan las capas de detalle, para pedir un nuevo dibujo. */
        val auxVersion: Int = 0,
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

    private val presetStore = PresetStore(File(app.filesDir, "presets.json"))
    private val userPresets = MutableStateFlow(presetStore.load())
    /** Presets propios primero y luego los de fábrica. */
    val presets: StateFlow<List<Preset>> = userPresets
        .map { it + FactoryPresets.all }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FactoryPresets.all + userPresets.value)
    private var clipboard: Map<String, Float>? = null
    private val _canPaste = MutableStateFlow(false)
    val canPaste: StateFlow<Boolean> = _canPaste.asStateFlow()

    /** Protege el acceso al handle nativo (abrir, exportar y cerrar nunca se solapan). */
    private val lock = Mutex()
    private var handle = 0L

    @Volatile
    private var editKey: String? = null
    private var saveJob: Job? = null
    private var histJob: Job? = null

    // Historial de ediciones. Los cambios seguidos del mismo control (arrastrar un deslizador)
    // se agrupan en un solo paso de deshacer.
    private val undoStack = ArrayDeque<EditParams>()
    private val redoStack = ArrayDeque<EditParams>()
    private var lastEditKey = -1
    private var lastEditTime = 0L

    // ---------------------------------------------------------------- abrir

    /** Abre una foto. Si es una reciente que ya no se puede leer, se quita de la lista. */
    fun open(uri: Uri) {
        viewModelScope.launch {
            _state.value = UiState(status = Status.LOADING)
            val result = withContext(Dispatchers.Default) { lock.withLock { openLocked(uri) } }
            _state.value = result
            if (result.status == Status.READY) {
                refreshHistogram()
                ensureAux()  // ediciones guardadas que ya usan detalle
            }
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
        clearHistory()
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
        if (h <= 0L) return failure("No se pudo abrir la foto: ${RawNative.nativeErrorText(h.toInt())} (código $h, archivo \"$name\", ${file.length()} bytes)")

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
        clearHistory()

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
        remember(adjustment.index)
        _state.update {
            it.copy(params = adjustment.set(it.params, value.coerceIn(adjustment.min, adjustment.max)))
        }
        onEdited()
    }

    fun resetParam(adjustment: Adjustment) {
        remember(null)
        setParam(adjustment, 0f)
    }

    fun resetAll() {
        remember(null)
        _state.update { it.copy(params = EditParams()) }
        onEdited()
    }

    /** Restablece todos los ajustes de una pestaña (un solo paso de deshacer). */
    fun resetGroup(group: Group) {
        remember(null)
        _state.update { st ->
            var p = st.params
            if (group == Group.CROP) p = p.withoutGeometry()
            else if (group == Group.LOCAL) for (m in 0 until EditParams.MASK_COUNT) p = p.withMask(m, LocalMask())
            else Adjustment.entries.filter { it.group == group }.forEach { p = it.set(p, 0f) }
            st.copy(params = p)
        }
        onEdited()
    }

    // ---------------------------------------------------------------- ediciones locales

    /** Crea una máscara nueva (lineal o radial) en el primer hueco libre y devuelve su posición, o -1 si no caben más. */
    fun addMask(type: Int): Int {
        if (_state.value.status != Status.READY) return -1
        val params = _state.value.params
        val slot = (0 until EditParams.MASK_COUNT).firstOrNull { !params.mask(it).isActive } ?: return -1
        remember(null)
        _state.update { it.copy(params = it.params.withMask(slot, LocalMask.create(type))) }
        onEdited()
        return slot
    }

    fun removeMask(slot: Int) {
        if (slot !in 0 until EditParams.MASK_COUNT) return
        remember(null)
        _state.update { it.copy(params = it.params.withMask(slot, LocalMask())) }
        onEdited()
    }

    /** Cambia una máscara. [key] agrupa cambios seguidos (arrastrar) en un solo paso de deshacer. */
    fun setMask(slot: Int, mask: LocalMask, key: Int?) {
        if (slot !in 0 until EditParams.MASK_COUNT) return
        remember(key)
        _state.update { it.copy(params = it.params.withMask(slot, mask)) }
        onEdited()
    }

    // ---------------------------------------------------------------- presets y portapapeles de ajustes

    fun applyPreset(preset: Preset) {
        if (_state.value.status != Status.READY) return
        remember(null)
        _state.update { it.copy(params = PresetLogic.apply(it.params, preset)) }
        onEdited()
    }

    fun saveCurrentAsPreset(name: String) {
        val values = PresetLogic.capture(_state.value.params)
        if (values.isEmpty()) return
        val unique = PresetLogic.uniqueName(name, userPresets.value.map { it.name } + FactoryPresets.all.map { it.name })
        userPresets.value = listOf(Preset("user:${System.currentTimeMillis()}", unique, values)) + userPresets.value
        persistPresets()
        _state.update { it.copy(message = "Preset “$unique” guardado.") }
    }

    fun deletePreset(preset: Preset) {
        if (preset.builtIn) return
        userPresets.value = userPresets.value.filter { it.id != preset.id }
        persistPresets()
    }

    private fun persistPresets() {
        val snapshot = userPresets.value
        viewModelScope.launch(Dispatchers.IO) { runCatching { presetStore.save(snapshot) } }
    }

    /** Copia los ajustes de esta foto (sin giro ni recorte) para pegarlos en otra. */
    fun copySettings() {
        val values = PresetLogic.capture(_state.value.params)
        if (values.isEmpty()) return
        clipboard = values
        _canPaste.value = true
        _state.update { it.copy(message = "Ajustes copiados.") }
    }

    fun pasteSettings() {
        val values = clipboard ?: return
        if (_state.value.status != Status.READY) return
        remember(null)
        _state.update { it.copy(params = PresetLogic.applyValues(it.params, values)) }
        onEdited()
    }

    // ---------------------------------------------------------------- deshacer / rehacer

    /** Guarda el estado actual antes de un cambio. [key] agrupa cambios seguidos del mismo control. */
    private fun remember(key: Int?) {
        val now = System.currentTimeMillis()
        val merge = key != null && key == lastEditKey && now - lastEditTime < MERGE_MS
        lastEditKey = key ?: -1
        lastEditTime = now
        if (merge) return
        undoStack.addLast(_state.value.params)
        if (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        syncHistoryFlags()
    }

    private fun syncHistoryFlags() {
        _state.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.params)
        lastEditKey = -1
        _state.update { it.copy(params = previous) }
        syncHistoryFlags()
        onEdited()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.params)
        lastEditKey = -1
        _state.update { it.copy(params = next) }
        syncHistoryFlags()
        onEdited()
    }

    private fun clearHistory() {
        undoStack.clear(); redoStack.clear(); lastEditKey = -1
    }

    // ---------------------------------------------------------------- automático y cuentagotas

    /** Aplica el ajuste automático (exposición, contraste, luces, sombras, blancos y negros). */
    fun autoAdjust() {
        if (_state.value.status != Status.READY) return
        viewModelScope.launch {
            val out = FloatArray(6)
            val ok = withContext(Dispatchers.Default) {
                lock.withLock { handle > 0L && RawNative.nativeAuto(handle, out) }
            }
            if (!ok) return@launch
            remember(null)
            val targets = listOf(
                Adjustment.EXPOSURE, Adjustment.CONTRAST, Adjustment.HIGHLIGHTS,
                Adjustment.SHADOWS, Adjustment.WHITES, Adjustment.BLACKS,
            )
            _state.update { st ->
                var p = st.params
                targets.forEachIndexed { i, adj -> p = adj.set(p, out[i].coerceIn(adj.min, adj.max)) }
                st.copy(params = p)
            }
            onEdited()
        }
    }

    /** Cuentagotas: toma el punto (u, v) de la foto mostrada como gris neutro. */
    fun pickWhiteBalance(u: Float, v: Float) {
        if (_state.value.status != Status.READY) return
        val params = _state.value.params
        viewModelScope.launch {
            val out = FloatArray(2)
            val ok = withContext(Dispatchers.Default) {
                lock.withLock { handle > 0L && RawNative.nativeSampleWb(handle, params.toFloatArray(), u, v, out) }
            }
            if (!ok) {
                _state.update { it.copy(message = "No se pudo medir ese punto.") }
                return@launch
            }
            remember(null)
            _state.update { st ->
                val p = Adjustment.TEMPERATURE.set(
                    Adjustment.TINT.set(st.params, out[1].coerceIn(Adjustment.TINT.min, Adjustment.TINT.max)),
                    out[0].coerceIn(Adjustment.TEMPERATURE.min, Adjustment.TEMPERATURE.max),
                )
                st.copy(params = p)
            }
            onEdited()
        }
    }

    // ---------------------------------------------------------------- histograma

    private fun refreshHistogram() {
        histJob?.cancel()
        histJob = viewModelScope.launch {
            delay(50)  // si siguen llegando cambios, solo se calcula el último
            val params = _state.value.params.toFloatArray()
            val bins = IntArray(1024)
            val ok = withContext(Dispatchers.Default) {
                lock.withLock { handle > 0L && RawNative.nativeHistogram(handle, params, bins) }
            }
            if (ok) _state.update { it.copy(histogram = bins) }
        }
    }

    /** Todo cambio de ediciones: guarda en disco y actualiza el histograma. */
    private fun onEdited() {
        scheduleSave()
        refreshHistogram()
        ensureAux()
    }

    private var auxJob: Job? = null

    /** Calcula las capas de los efectos con vecinos la primera vez que alguno está activo. */
    private fun ensureAux() {
        val st = _state.value
        val photo = st.photo ?: return
        if (photo.aux != null || auxJob?.isActive == true) return
        val v = st.params.values
        if ((EFFECT_FIRST..EFFECT_LAST).none { v[it] != 0f }) return
        auxJob = viewModelScope.launch {
            val px = photo.width.toLong() * photo.height
            val ok = withContext(Dispatchers.Default) {
                lock.withLock {
                    if (handle <= 0L || px * 8 > Int.MAX_VALUE) return@withLock false
                    try {
                        val a = ByteBuffer.allocateDirect((px * 8).toInt()).order(ByteOrder.nativeOrder())
                        val b = ByteBuffer.allocateDirect((px * 4).toInt()).order(ByteOrder.nativeOrder())
                        if (!RawNative.nativeFillAux(handle, a, b)) return@withLock false
                        photo.aux = GpuAux(a, b)
                        true
                    } catch (e: OutOfMemoryError) {
                        false
                    }
                }
            }
            if (ok) _state.update { it.copy(auxVersion = it.auxVersion + 1) }  // fuerza un nuevo dibujo
            else _state.update { it.copy(message = "No hay memoria para los efectos de detalle.") }
        }
    }

    /** Mantener pulsada la foto muestra el original sin ediciones. */
    fun setShowOriginal(show: Boolean) {
        if (_state.value.status != Status.READY || _state.value.showOriginal == show) return
        _state.update { it.copy(showOriginal = show) }
    }

    // ---------------------------------------------------------------- geometría

    /** Enderezado en grados (-45..45). El recorte se ajusta solo para no dejar esquinas vacías. */
    fun setAngle(degrees: Float) = editGeometry(GEO_ANGLE_KEY) { p, w, h ->
        p.withGeometry(p.rotation, degrees, p.aspect, w, h)
    }

    /** Gira la foto en pasos de 90° (+1 = horario, -1 = antihorario). */
    fun rotate(quarterTurns: Int) = editGeometry(null) { p, w, h ->
        p.withGeometry(p.rotation + quarterTurns, p.angle, p.aspect, w, h)
    }

    fun setAspect(index: Int) = editGeometry(null) { p, w, h ->
        p.withGeometry(p.rotation, p.angle, index, w, h)
    }

    /** Quita giro, enderezado y recorte; conserva el resto de las ediciones. */
    fun resetGeometry() {
        remember(null)
        _state.update { it.copy(params = it.params.withoutGeometry()) }
        onEdited()
    }

    private fun editGeometry(key: Int?, change: (EditParams, Int, Int) -> EditParams) {
        val photo = _state.value.photo ?: return
        remember(key)
        _state.update { it.copy(params = change(it.params, photo.width, photo.height)) }
        onEdited()
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    // ---------------------------------------------------------------- exportar

    /** Revela la foto a resolución completa y guarda un JPEG en Pictures/Efoto. */
    fun export(options: ExportOptions = ExportOptions()) {
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
                    val tmp = File(getApplication<Application>().cacheDir, "export.${options.extension}")
                    try {
                        val rc = RawNative.nativeExport(h, params.toFloatArray(), tmp.absolutePath, options.quality, options.maxEdge, options.format)
                        if (rc != 0) return@withLock "No se pudo exportar: ${RawNative.nativeErrorText(rc)}"
                        saveToGallery(tmp, "${baseName}_Efoto.${options.extension}", options.mime)
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

    // ---------------------------------------------------------------- exportación por lotes

    private val _batch = MutableStateFlow<BatchState?>(null)
    val batch: StateFlow<BatchState?> = _batch.asStateFlow()
    private var batchJob: Job? = null

    /** Exporta varias fotos recientes con sus ediciones guardadas, una tras otra. Solo desde el inicio. */
    fun exportBatch(items: List<RecentItem>, options: ExportOptions) {
        if (items.isEmpty() || batchJob?.isActive == true || _state.value.status != Status.EMPTY) return
        batchJob = viewModelScope.launch {
            var failed = 0
            for ((i, item) in items.withIndex()) {
                _batch.value = BatchState(items.size, i, item.name, failed)
                val ok = withContext(Dispatchers.Default) { exportOne(item, options) }
                if (!ok) failed++
            }
            _batch.value = BatchState(items.size, items.size, failed = failed, finished = true)
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
        _batch.value = null
        _state.update { it.copy(message = "Exportación cancelada.") }
    }

    fun dismissBatch() {
        _batch.value = null
    }

    private fun exportOne(item: RecentItem, options: ExportOptions): Boolean {
        val app = getApplication<Application>()
        val src = File(app.cacheDir, "batch.src")
        val tmp = File(app.cacheDir, "batch_out.${options.extension}")
        var h = 0L
        try {
            val input = app.contentResolver.openInputStream(Uri.parse(item.uri)) ?: return false
            input.use { i -> src.outputStream().use { o -> i.copyTo(o) } }
            h = RawNative.nativeOpen(src.absolutePath, BATCH_PREVIEW_EDGE)
            if (h <= 0L) return false
            val params = loadEdits(editKeyFor(item.name, src.length())) ?: EditParams()
            val rc = RawNative.nativeExport(h, params.toFloatArray(), tmp.absolutePath, options.quality, options.maxEdge, options.format)
            if (rc != 0) return false
            saveToGallery(tmp, "${item.name.substringBeforeLast('.', item.name)}_Efoto.${options.extension}", options.mime)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            if (h > 0L) RawNative.nativeClose(h)
            src.delete()
            tmp.delete()
        }
    }

    private fun saveToGallery(file: File, displayName: String, mime: String = "image/jpeg"): String {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
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
        const val BATCH_PREVIEW_EDGE = 256
        const val MERGE_MS = 1000L
        const val EFFECT_FIRST = Adjustment.FX_START  // claridad .. ruido (color)
        const val EFFECT_LAST = Adjustment.FX_START + 5
        const val HISTORY_LIMIT = 60
        const val GEO_ANGLE_KEY = 100000
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
