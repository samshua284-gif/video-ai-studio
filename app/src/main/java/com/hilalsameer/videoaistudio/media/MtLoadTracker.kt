package com.hilalsameer.videoaistudio.media

/**
 * Turns the MT worker's per-file load events into an honest
 * "file x of y" progress snapshot (TD-019).
 *
 * Transformers.js reports load progress per file (statuses
 * initiate / download / progress / done, with a per-file percent
 * that depends on the asset server reporting byte lengths — which
 * the WebView asset bridge does not guarantee). What is always
 * knowable is WHICH files finished, and the total is fixed: the
 * worker loads exactly the NLLB file set pinned by fetchMtDeps in
 * app/build.gradle.kts —
 *
 *   config.json, generation_config.json, tokenizer.json,
 *   tokenizer_config.json, special_tokens_map.json,
 *   onnx/encoder_model_quantized.onnx,
 *   onnx/decoder_model_merged_quantized.onnx
 *
 * — [MODEL_FILE_TOTAL] files. No percentage is ever invented:
 * the snapshot is a count of files the worker itself reported
 * done (a progress event at 100% also counts as done), and the
 * bar the UI draws from it is that count over that total.
 */
class MtLoadTracker(val totalFiles: Int = MODEL_FILE_TOTAL) {

    data class Snapshot(val done: Int, val total: Int, val currentFile: String?)

    private val doneFiles = LinkedHashSet<String>()
    private var current: String? = null

    @Synchronized
    fun onEvent(file: String, status: String, progressPct: Double?): Snapshot {
        if (file.isNotEmpty()) {
            val name = file.substringAfterLast('/')
            val finished = status == "done" ||
                (progressPct != null && progressPct >= 100.0)
            if (finished) {
                doneFiles.add(name)
                if (current == name) current = null
            } else if (status == "initiate" || status == "download" ||
                status == "progress"
            ) {
                if (name !in doneFiles) current = name
            }
        }
        return snapshot()
    }

    @Synchronized
    fun snapshot(): Snapshot =
        Snapshot(minOf(doneFiles.size, totalFiles), totalFiles, current)

    companion object {
        /** Mirrors the fetchMtDeps NLLB file list — change both together. */
        const val MODEL_FILE_TOTAL = 7
    }
}
