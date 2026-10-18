package org.deer.borrowai.data.service.speech

/**
 * Loads the sherpa-onnx native runtime shipped in the AAR. Recognizers are created in later
 * layers once a model is downloaded; this only proves the runtime links on the device.
 */
object SherpaOnnxRuntime {
    private val loadError: Throwable? = runCatching {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("sherpa-onnx-jni")
    }.exceptionOrNull()

    val isAvailable: Boolean get() = loadError == null

    val failureReason: String? get() = loadError?.message
}
