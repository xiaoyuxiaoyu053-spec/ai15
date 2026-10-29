package com.deepline.privateapp

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Low-latency UI sounds (soft synthesized bells shipped in res/raw).
 * Call Sfx.init(context) once, then Sfx.play(Sfx.SEND) etc. Callers check the "sound" switch.
 */
object Sfx {
    const val SEND = 0
    const val RECEIVE = 1
    const val POP = 2
    const val SUCCESS = 3

    private var pool: SoundPool? = null
    private val ids = IntArray(4)
    private val ready = BooleanArray(4)

    fun init(context: Context) {
        if (pool != null) return
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val sp = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
            sp.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    for (i in ids.indices) {
                        if (ids[i] == sampleId) ready[i] = true
                    }
                }
            }
            val app = context.applicationContext
            ids[SEND] = sp.load(app, R.raw.dl_send, 1)
            ids[RECEIVE] = sp.load(app, R.raw.dl_receive, 1)
            ids[POP] = sp.load(app, R.raw.dl_pop, 1)
            ids[SUCCESS] = sp.load(app, R.raw.dl_success, 1)
            pool = sp
        } catch (_: Exception) {
            pool = null
        }
    }

    fun play(kind: Int, volume: Float = 0.7f) {
        val sp = pool ?: return
        if (kind < 0 || kind >= ids.size || !ready[kind]) return
        try {
            sp.play(ids[kind], volume, volume, 1, 0, 1f)
        } catch (_: Exception) {
        }
    }

    fun release() {
        try {
            pool?.release()
        } catch (_: Exception) {
        }
        pool = null
        ready.fill(false)
    }
}
