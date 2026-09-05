package com.fongmi.android.tv.reader.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.net.URLEncoder
import java.util.Locale

/**
 * TTS 朗读引擎：本机 TextToSpeech（离线兜底）或 在线 HTTP 接口（GET 模板，{text} 会被 URL 编码替换）。
 * - 逐段落串读；段落过长（>200 字）按句切分，避免在线接口超时
 * - 回调全部回到主线程
 * - [onDone]：整章朗读完毕（由外部决定是切下一章还是结束）
 */
class TtsSpeaker(private val context: Context) {

    companion object {
        private const val TAG = "TtsSpeaker"
        private const val SEG_MAX = 200
    }

    /** 正文段落（由阅读页 htmlToParagraphs 后的净化文本传入）。 */
    private var paras: List<String> = emptyList()
    private var idx = 0
    /** 会话是否进行中（start 后直到 stop/结束）。 */
    private var active = false
    /** 用户暂停。 */
    private var paused = false
    private var speed = 1.0f
    private var engine = "online"
    private var onlineUrl = "https://dict.youdao.com/dictvoice?audio={text}&type=2"

    private var local: TextToSpeech? = null
    private var localInit = false
    private var localPending: (() -> Unit)? = null
    private var mp: MediaPlayer? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 整章读完。 */
    var onDone: (() -> Unit)? = null
    /** 播放状态（active 且 未暂停）。 */
    var onState: ((Boolean) -> Unit)? = null
    /** 进度：(段落索引, 段落总数)。 */
    var onProgress: ((Int, Int) -> Unit)? = null

    fun start(list: List<String>, from: Int, spd: Float, eng: String, url: String) {
        stopInternal(false)
        paras = list
        idx = from.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        speed = spd
        engine = eng
        onlineUrl = url
        active = true
        paused = false
        onState?.invoke(true)
        speakCurrent()
    }

    val isActive: Boolean get() = active && !paused

    fun pause() {
        paused = true
        mp?.pause()
        local?.stop()
        onState?.invoke(false)
    }

    fun resume() {
        if (!active) return
        paused = false
        val p = mp
        if (p != null) {
            onState?.invoke(true)
            p.start()
        } else {
            onState?.invoke(true)
            speakCurrent()
        }
    }

    fun toggle() {
        if (isActive) pause() else resume()
    }

    fun stop() {
        stopInternal(true)
    }

    private fun stopInternal(notify: Boolean) {
        active = false
        paused = false
        local?.stop()
        releaseMediaPlayer()
        if (notify) onState?.invoke(false)
    }

    fun changeSpeed(v: Float) {
        speed = v
        local?.setSpeechRate(v)
    }

    fun release() {
        stopInternal(false)
        local?.shutdown()
        local = null
        localInit = false
    }

    // ------------------------------------------------------------ 章节推进

    private fun speakCurrent() {
        if (!active || paused) return
        if (idx < 0 || idx >= paras.size) {
            onDone?.invoke()
            return
        }
        onProgress?.invoke(idx, paras.size)
        val text = paras[idx]
        if (text.isBlank()) {
            idx++
            handler.post { speakCurrent() }
            return
        }
        if (engine == "local") speakLocal(text) else speakOnline(text)
    }

    /** 在线朗读：段落过长按句切分，逐段请求 mp3 顺序播放。 */
    private fun speakOnline(text: String) {
        playSegs(splitSeg(text), 0)
    }

    private fun playSegs(segs: List<String>, si: Int) {
        if (!active || paused) return
        if (si >= segs.size) {
            idx++
            handler.post { speakCurrent() }
            return
        }
        val say = segs[si]
        if (say.isBlank()) {
            playSegs(segs, si + 1)
            return
        }
        try {
            val url = onlineUrl
                .replace("{text}", URLEncoder.encode(say, "UTF-8").replace("+", "%20"))
            val p = MediaPlayer()
            mp = p
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            p.setDataSource(url)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener {
                if (mp === p) mp = null
                try {
                    p.release()
                } catch (_: Exception) {
                }
                handler.post { playSegs(segs, si + 1) }
            }
            p.setOnErrorListener { _, _, _ ->
                if (mp === p) mp = null
                try {
                    p.release()
                } catch (_: Exception) {
                }
                handler.post { playSegs(segs, si + 1) }
                true
            }
            p.prepareAsync()
        } catch (e: Throwable) {
            Log.w(TAG, "online tts failed", e)
            idx++
            handler.post { speakCurrent() }
        }
    }

    /** 本机朗读：TextToSpeech 串读，读完一段进下一段。 */
    private fun speakLocal(text: String) {
        val tts = local
        if (tts == null) {
            val pending = { speakLocal(text) }
            localPending = pending
            local = TextToSpeech(context) { status ->
                val t = local
                if (status != TextToSpeech.SUCCESS || t == null) {
                    // 本机无可用引擎：跳过该段继续
                    idx++
                    handler.post { speakCurrent() }
                    return@TextToSpeech
                }
                try {
                    t.language = Locale.CHINESE
                } catch (_: Exception) {
                }
                localInit = true
                val cb = localPending
                localPending = null
                cb?.invoke()
            }
            return
        }
        if (!localInit) {
            // 引擎仍在初始化：等 init 回调触发 pending
            return
        }
        localPending = null
        try {
            tts.setSpeechRate(speed)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    idx++
                    handler.post { speakCurrent() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    idx++
                    handler.post { speakCurrent() }
                }
            })
            tts.speak(stripToSpeakable(text), TextToSpeech.QUEUE_FLUSH, null, "seg" + System.nanoTime())
        } catch (e: Throwable) {
            Log.w(TAG, "local tts failed", e)
            idx++
            handler.post { speakCurrent() }
        }
    }

    private fun releaseMediaPlayer() {
        val p = mp
        mp = null
        try {
            p?.release()
        } catch (_: Exception) {
        }
    }

    /** 去掉换行/多余空白（部分引擎对换行敏感）。 */
    private fun stripToSpeakable(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /** 长段落按句切分：。！？；…等标点切，仍超长硬切。 */
    fun splitSeg(text: String): List<String> {
        val t = text.trim()
        if (t.length <= SEG_MAX) return listOf(t)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in t) {
            sb.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '…' || ch == '」' || ch == '”') {
                if (sb.length >= 24) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
            } else if (sb.length >= SEG_MAX) {
                out.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return if (out.isEmpty()) listOf(t) else out
    }
}