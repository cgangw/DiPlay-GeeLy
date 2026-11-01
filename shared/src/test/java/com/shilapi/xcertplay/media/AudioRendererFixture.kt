package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioFormat

// tianwen | Share the private renderer constructor contract across codec, buffer and gain fixtures.
internal fun audioRendererFixture(
    format: AudioFormat,
    bufferMillis: Int,
    report: (String) -> Unit = {},
): Any {
    val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    return type.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible = true }
        .newInstance(format, false, false, 0, 0, AudioFocusCoordinator(null, false, false),
            0, bufferMillis, report, null, null, null, AudioOutputRoutes(), false,
            { _: Any -> }, 0L, false, null, false)
}
