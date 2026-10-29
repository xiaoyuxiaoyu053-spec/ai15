package com.deepline.privateapp

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ───────────────────────────── helpers ─────────────────────────────

private val ATTACH_RE = Regex("\\[\\[(IMAGE|GIF):(.*?)]]", RegexOption.DOT_MATCHES_ALL)
private val IMAGE_RE = Regex("\\[\\[IMAGE:(.*?)]]", RegexOption.DOT_MATCHES_ALL)
private val GIF_RE = Regex("\\[\\[GIF:(.*?)]]", RegexOption.DOT_MATCHES_ALL)

internal fun parseColorOr(hex: String, fallback: Color): Color =
    try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { fallback }

private data class ChatEntry(
    val msg: JSONObject, val key: String, val showHeader: Boolean, val showMeta: Boolean,
    val startRun: Boolean, val endRun: Boolean, val timeLabel: String?
)

private fun isAiBubble(m: JSONObject?): Boolean =
    m != null && m.optString("role") == "assistant" && m.optString("type") != "summary"

private fun sameRun(a: JSONObject?, b: JSONObject?): Boolean =
    a != null && b != null && a.optString("type") != "summary" && b.optString("type") != "summary" &&
        a.optString("role") == b.optString("role")

private fun buildEntries(arr: JSONArray): List<ChatEntry> {
    val msgs = ArrayList<JSONObject>()
    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { msgs.add(it) }
    val seen = HashSet<String>()
    val out = ArrayList<ChatEntry>(msgs.size)
    for (i in msgs.indices) {
        val m = msgs[i]
        val prev = msgs.getOrNull(i - 1)
        val next = msgs.getOrNull(i + 1)
        val ai = isAiBubble(m)
        var key = m.optString("id").ifBlank { "idx$i" }
        while (!seen.add(key)) key += "_"
        val ts = m.optLong("time", 0L)
        val prevTs = prev?.optLong("time", 0L) ?: 0L
        val label = if (ts > 0L && m.optString("type") != "summary" && (prev == null || prevTs <= 0L || ts - prevTs > 20L * 60_000L)) formatSeparator(ts) else null
        out.add(ChatEntry(m, key, ai && !isAiBubble(prev), ai && !isAiBubble(next), !sameRun(prev, m) || label != null, !sameRun(m, next), label))
    }
    return out
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier.height(52.dp).bounceClick(enabled = enabled, pressedScale = 0.95f) { onClick() }
            .clip(RoundedCornerShape(26.dp)).background(accent.copy(alpha = if (enabled) 1f else 0.4f)),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
}

@Composable
fun GlassField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier.padding(vertical = 4.dp),
        label = label, placeholder = placeholder, singleLine = singleLine, minLines = minLines, maxLines = maxLines,
        visualTransformation = visualTransformation, shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Color.White.copy(alpha = 0.45f),
            focusedContainerColor = Color.White.copy(alpha = 0.62f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.85f),
            focusedBorderColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun PanelItem(label: String, icon: ImageVector, color: Color, ink: Color, delay: Int, onClick: () -> Unit) {
    Column(
        Modifier.bounceIn(delayMillis = delay, initialScale = 0.5f, rise = 10f).bounceClick(pressedScale = 0.88f) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(56.dp).glassSurface(RoundedCornerShape(18.dp), 1.4f), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = color, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = ink)
    }
}

// ───────────────────────────── iMessage building blocks ─────────────────────────────

/** iMessage bubble outline: rounded body with the signature curled tail on the last bubble of a run. */
internal class IMessageShape(private val outgoing: Boolean, private val tail: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val d = density.density
        val w = size.width
        val h = size.height
        val t = 7f * d
        val r = minOf(18f * d, h / 2f)
        val mirror = !outgoing
        fun px(x: Float): Float = if (mirror) w - x else x
        val path = Path()
        fun arc(l: Float, top: Float, rt: Float, bt: Float, start: Float, sweep: Float) {
            if (!mirror) path.arcTo(Rect(l, top, rt, bt), start, sweep, false)
            else path.arcTo(Rect(w - rt, top, w - l, bt), 180f - start, -sweep, false)
        }
        val right = w - t
        path.moveTo(px(r), 0f)
        path.lineTo(px(right - r), 0f)
        arc(right - 2f * r, 0f, right, 2f * r, -90f, 90f)
        if (tail && h > 24f * d) {
            path.lineTo(px(right), h - 10f * d)
            path.cubicTo(px(right), h - 3f * d, px(right + 1.5f * d), h - 0.5f * d, px(w), h)
            path.cubicTo(px(w - 3f * d), h, px(right - 3f * d), h, px(right - 9f * d), h)
        } else {
            path.lineTo(px(right), h - r)
            arc(right - 2f * r, h - 2f * r, right, h, 0f, 90f)
        }
        path.lineTo(px(r), h)
        arc(0f, h - 2f * r, 2f * r, h, 90f, 90f)
        path.lineTo(px(0f), r)
        arc(0f, 0f, 2f * r, 2f * r, 180f, 90f)
        path.close()
        return Outline.Generic(path)
    }
}

@Composable
private fun IMessageBubble(
    text: String, outgoing: Boolean, tail: Boolean, dark: Boolean, textColor: Color,
    sentHex: String, receivedHex: String, backgroundUri: String,
    modifier: Modifier = Modifier, typing: Boolean = false
) {
    val shape = remember(outgoing, tail) { IMessageShape(outgoing, tail) }
    val fill: Brush = if (outgoing) {
        val base = parseColorOr(sentHex, Color(0xFF0A84FF))
        Brush.verticalGradient(listOf(lerp(base, Color.White, 0.16f).copy(alpha = 0.93f), base.copy(alpha = 0.95f)))
    } else {
        val custom = !receivedHex.equals("#FFFFFF", true)
        val base = if (custom) parseColorOr(receivedHex, Color(0xFFE9E9EB)) else if (dark) Color(0xFF3A3A3F) else Color(0xFFE9E9EB)
        Brush.verticalGradient(listOf(base.copy(alpha = if (dark) 0.80f else 0.95f), base.copy(alpha = if (dark) 0.68f else 0.90f)))
    }
    Box(modifier.clip(shape).background(fill).border(0.5.dp, Color.White.copy(alpha = if (dark) 0.14f else 0.45f), shape)) {
        if (backgroundUri.isNotBlank()) {
            PickedPhoto(backgroundUri, Modifier.matchParentSize().graphicsLayer { alpha = 0.40f }, rounded = false, maxPx = 600)
        }
        Box(
            Modifier.padding(
                start = if (outgoing) 14.dp else 21.dp, end = if (outgoing) 21.dp else 14.dp, top = 8.dp, bottom = 8.dp
            )
        ) {
            if (typing) FloatingTypingIndicator()
            else Text(text, fontSize = 17.sp, lineHeight = 22.sp, color = textColor)
        }
    }
}

@Composable
private fun BoxScope.ReactionBadge(emoji: String, mine: Boolean, dark: Boolean, accent: Color) {
    Box(
        Modifier.align(if (mine) Alignment.TopEnd else Alignment.TopStart)
            .offset(x = if (mine) 10.dp else (-10).dp, y = (-13).dp).size(28.dp)
            .bounceIn(initialScale = 0.3f).clip(CircleShape)
            .background(if (mine) accent else if (dark) Color(0xFF48484C) else Color(0xFFF2F2F7))
            .border(1.5.dp, if (dark) Color(0xFF1C1C1E) else Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) { Text(emoji, fontSize = 14.sp) }
}

@Composable
private fun TapbackMenu(
    text: String, canReact: Boolean, current: String, ink: Color, accent: Color,
    onReact: (String) -> Unit, onCopy: () -> Unit, onQuote: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().bounceClick(pressedScale = 1f) { onDismiss() }, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (canReact) {
                    Row(
                        Modifier.bounceIn(initialScale = 0.6f).glassSurface(RoundedCornerShape(30.dp), 2.2f, 8.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("❤️", "👍", "👎", "😂", "‼️", "❓").forEachIndexed { i, emoji ->
                            Box(
                                Modifier.size(42.dp).bounceIn(delayMillis = i * 35, initialScale = 0.3f).bounceClick(pressedScale = 0.8f) { onReact(emoji) }
                                    .clip(CircleShape).background(if (emoji == current) accent.copy(alpha = 0.30f) else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) { Text(emoji, fontSize = 22.sp) }
                        }
                    }
                }
                Text(
                    text.take(160), color = ink, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.bounceIn(initialScale = 0.8f).widthIn(max = 280.dp).glassSurface(RoundedCornerShape(22.dp), 2f, 6.dp).padding(horizontal = 16.dp, vertical = 10.dp)
                )
                Column(Modifier.bounceIn(delayMillis = 60, initialScale = 0.8f).width(220.dp).glassSurface(RoundedCornerShape(18.dp), 2.2f, 8.dp)) {
                    TapbackRow("复制", Icons.Default.ContentCopy, ink, onCopy)
                    TapbackRow("引用", Icons.Default.FormatQuote, ink, onQuote)
                    TapbackRow("删除", Icons.Default.Delete, Color(0xFFFF453A), onDelete)
                }
            }
        }
    }
}

@Composable
private fun TapbackRow(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().bounceClick(pressedScale = 0.97f) { onClick() }.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = color, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
    }
}

/** True for 1–3 emoji (or one ZWJ sequence): iMessage shows these big, without a bubble. */
internal fun isEmojiOnly(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty() || t.length > 24) return false
    var count = 0
    var zwj = false
    var i = 0
    while (i < t.length) {
        val cp = t.codePointAt(i)
        i += Character.charCount(cp)
        val ok = cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 || cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF ||
            cp in 0x1F1E6..0x1F1FF || cp == 0x203C || cp == 0x2049 || cp == 0x2B50 || cp == 0x2B55 || cp == 0x00A9 || cp == 0x00AE
        if (!ok) return false
        if (cp == 0x200D) zwj = true
        if (cp != 0x200D && cp != 0xFE0F && cp != 0x20E3 && cp !in 0x1F3FB..0x1F3FF) count++
    }
    return count in 1..3 || (zwj && count in 1..8)
}

internal fun formatSeparator(ts: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val clock = clockText(ts)
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return "今天 $clock"
    val y = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    if (y.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && y.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)) return "昨天 $clock"
    val pattern = if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm"
    return java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(java.util.Date(ts))
}

// ───────────────────────────── Chat screen ─────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    p: JSONObject, revision: Int, myName: String, myAvatar: String,
    myTextColor: String, aiTextColor: String,
    composer: String, onComposer: (String) -> Unit, busy: Boolean, error: String, onDismissError: () -> Unit,
    backgroundUri: String, backgroundAlpha: Float, backgroundBlur: Float,
    bubbleBackgroundUri: String, conversationSummaryEnabled: Boolean,
    bubbleHex: String, myBubbleHex: String, bubbleShape: String, inputHint: String,
    onBack: () -> Unit, onQuote: (String) -> Unit, onDeleteMessage: (Int) -> Unit,
    onPickImage: () -> Unit, onPickGif: () -> Unit,
    onPaymentDecision: (String, Boolean) -> Unit, onSend: (String) -> Unit, onEditPersona: () -> Unit,
    chatDark: Boolean = false, unreadElsewhere: Int = 0, onReact: (Int, String) -> Unit = { _, _ -> },
    assistantEnabled: Boolean = false, onAssistant: () -> Unit = {}
) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val arr = p.optJSONArray("messages") ?: JSONArray()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var panelOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var composerExpanded by remember { mutableStateOf(false) }
    var callReplyText by remember { mutableStateOf("") }
    var callRequestStarted by remember { mutableStateOf(false) }
    var paymentDialogOpen by remember { mutableStateOf(false) }
    var paymentKind by remember { mutableStateOf("redpacket") }
    var paymentAmount by remember { mutableStateOf("10.00") }
    var paymentNote by remember { mutableStateOf("一点心意") }
    var callDialog by remember { mutableStateOf(false) }
    var voiceActive by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("点击麦克风，用中文说话；AI 将以文字回复") }
    var recognizedText by remember { mutableStateOf("") }
    val expandedReasoning = remember { mutableStateListOf<String>() }
        val latestVoiceActive by rememberUpdatedState(voiceActive)
    val latestOnSend by rememberUpdatedState(onSend)
    val startListeningHolder = remember { mutableStateOf<(() -> Unit)?>(null) }
    val recognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    val gifLoader = remember(context) { ImageLoader.Builder(context).components { if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }.build() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            voiceActive = true
            voiceStatus = "正在准备中文语音识别…"
            Handler(Looper.getMainLooper()).postDelayed({ startListeningHolder.value?.invoke() }, 250L)
        } else voiceStatus = "未获得麦克风权限，无法识别你说的中文。"
    }

    val entries = remember(revision, arr.length()) { buildEntries(arr) }
    val pid = p.optString("id")
    val doneKeys = remember(pid) { HashSet<String>().also { set -> entries.forEach { set.add(it.key) } } }
    val lastUserKey = entries.lastOrNull { it.msg.optString("role") == "user" }?.key

    LaunchedEffect(entries.size, revision) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }
    LaunchedEffect(pid, revision) {
        for (i in 0 until arr.length()) {
            val msg = arr.optJSONObject(i) ?: continue
            if (msg.optString("role") == "assistant" && msg.optString("content") != "---") msg.put("read", true)
        }
    }

        DisposableEffect(recognizer) {
        if (recognizer != null) {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { voiceStatus = "正在聆听，请说中文…" }
                override fun onBeginningOfSpeech() { voiceStatus = "检测到你正在说话…" }
                override fun onRmsChanged(rmsdB: Float) { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEndOfSpeech() { voiceStatus = "正在识别中文…" }
                override fun onError(error: Int) {
                    if (!latestVoiceActive) return
                    voiceStatus = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "暂时没听清，请继续说话…"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络暂时不可用，请检查网络。"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请允许 DeepLine 使用麦克风。"
                        else -> "语音识别暂时中断（$error），请再点一次麦克风。"
                    }
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Handler(Looper.getMainLooper()).postDelayed({ if (latestVoiceActive) startListeningHolder.value?.invoke() }, 700L)
                    }
                }
                override fun onResults(results: Bundle?) {
                    val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                    if (result.isNotBlank()) {
                        recognizedText = result
                        callRequestStarted = true
                        voiceStatus = "你说：$result"
                        latestOnSend(result)
                    } else voiceStatus = "没听清，请再说一次。"
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val result = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (result.isNotBlank()) recognizedText = result
                }
                override fun onEvent(eventType: Int, params: Bundle?) { }
            })
        }
        onDispose { try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) { } }
    }

    val startListening: () -> Unit = {
        val r = recognizer
        if (r == null) {
            voiceStatus = "这台设备没有可用的语音识别服务。请安装或启用系统语音识别。"
        } else if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceStatus = "需要麦克风权限。"
        } else {
            try {
                r.cancel()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }
                voiceStatus = "正在聆听，请说中文…"
                r.startListening(intent)
            } catch (e: Exception) { voiceStatus = "无法启动语音识别：${e.message ?: "请重试"}" }
        }
    }
    SideEffect { startListeningHolder.value = startListening }

    val attach = ATTACH_RE.find(composer)
    val typedText = composer.replace(ATTACH_RE, "")
    val canSend = !busy && composer.isNotBlank()
    // iMessage look: dark or light conversation surface. Local Ink/Muted shadow the app-wide colours so every
    // composable below (including the voice-call dialog) adapts to the wallpaper automatically.
    val fg = if (chatDark) Color.White else Color(0xFF111111)
    val Ink = fg
    val Muted = fg.copy(alpha = 0.55f)
    val glassTint = if (chatDark) Color(0xFF2B2B2E) else Color.White
    val sentTextColor = if (myTextColor.equals("#243047", true)) Color.White else parseColorOr(myTextColor, Color.White)
    val receivedTextColor = if (aiTextColor.equals("#263247", true)) fg else parseColorOr(aiTextColor, fg)
    var menuKey by remember { mutableStateOf("") }
    val detailKeys = remember { mutableStateListOf<String>() }

    CompositionLocalProvider(LocalGlassTint provides glassTint) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ── iMessage header: back pill (+unread count) · avatar with name capsule · call button ──
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp)) {
            Row(
                Modifier.align(Alignment.TopStart).height(44.dp).bounceClick(pressedScale = 0.92f) { onBack() }
                    .glassSurface(RoundedCornerShape(22.dp), 1.8f, 6.dp)
                    .padding(start = 6.dp, end = if (unreadElsewhere > 0) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ChevronLeft, "返回", tint = Ink, modifier = Modifier.size(30.dp))
                if (unreadElsewhere > 0) {
                    Box(
                        Modifier.defaultMinSize(24.dp, 24.dp).clip(CircleShape).background(if (chatDark) Color.White else Color(0xFF111111)).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (unreadElsewhere > 99) "99+" else unreadElsewhere.toString(), color = if (chatDark) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.bounceClick(pressedScale = 0.92f) { moreMenuOpen = true }) { Avatar(p.optString("avatar"), p.optString("name"), 66) }
                Spacer(Modifier.height(6.dp))
                Box {
                    Row(
                        Modifier.widthIn(max = 190.dp).bounceClick(pressedScale = 0.94f) { moreMenuOpen = true }
                            .glassSurface(RoundedCornerShape(16.dp), 1.8f, 4.dp).padding(start = 14.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.optString("name", "AI"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("编辑人设") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { moreMenuOpen = false; onEditPersona() })
                        DropdownMenuItem(text = { Text("语音通话") }, leadingIcon = { Icon(Icons.Default.Call, null) }, onClick = { moreMenuOpen = false; callReplyText = ""; recognizedText = ""; callRequestStarted = false; callDialog = true })
                        DropdownMenuItem(text = { Text("返回信息列表") }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) }, onClick = { moreMenuOpen = false; onBack() })
                    }
                }
                if (conversationSummaryEnabled) {
                    Text("记忆自动总结已开启", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Box(
                Modifier.align(Alignment.TopEnd).size(44.dp).bounceClick(pressedScale = 0.9f) {
                    callReplyText = ""; recognizedText = ""; callRequestStarted = false; callDialog = true
                }.glassSurface(RoundedCornerShape(22.dp), 1.8f, 6.dp),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Videocam, "语音通话", tint = Ink, modifier = Modifier.size(25.dp)) }
        }

        // ── messages ──
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().fadingEdges(16.dp, 12.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(entries, key = { it.key }) { e ->
                val m = e.msg
                val user = m.optString("role") == "user"
                val content = m.optString("content")
                val animate = remember(e.key) {
                    val fresh = e.key !in doneKeys
                    doneKeys.add(e.key)
                    fresh
                }
                if (m.optString("type") == "summary") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("对话阶段总结 · 第 ${m.optInt("summaryThrough", 50)} 条", fontSize = 10.sp, color = Muted)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            content, fontSize = 11.sp, lineHeight = 16.sp, color = Ink.copy(alpha = 0.7f),
                            modifier = Modifier.glassSurface(RoundedCornerShape(18.dp), 0.9f).padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                } else {
                    val type = m.optString("type")
                    val typing = content == "---"
                    val reaction = m.optString("reaction")
                    val expanded = detailKeys.contains(e.key)
                    val reasoning = m.optString("reasoning")
                    val reasoningOpen = expandedReasoning.contains(e.key)
                    Column(Modifier.fillMaxWidth().bounceIn(enabled = animate, origin = TransformOrigin(if (user) 1f else 0f, 1f), rise = 22f)) {
                        if (e.timeLabel != null) {
                            Text(
                                e.timeLabel, fontSize = 11.sp, color = Muted, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)
                            )
                        }
                        if (!user && e.startRun && reasoning.isNotBlank()) {
                            Row(
                                Modifier.padding(start = 4.dp, top = 6.dp, bottom = 5.dp).bounceClick(pressedScale = 0.92f) {
                                    if (reasoningOpen) expandedReasoning.remove(e.key) else expandedReasoning.add(e.key)
                                }.glassSurface(RoundedCornerShape(16.dp), 1.4f).padding(start = 10.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("思考摘要", fontSize = 13.sp, color = Muted)
                                Icon(if (reasoningOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = Muted, modifier = Modifier.size(18.dp))
                            }
                            AnimatedVisibility(visible = reasoningOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                Text(
                                    reasoning.take(1800), fontSize = 12.sp, lineHeight = 17.sp, color = Ink.copy(alpha = 0.75f),
                                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp).glassSurface(RoundedCornerShape(18.dp), 1.2f).padding(horizontal = 12.dp, vertical = 9.dp)
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = if (e.startRun && e.timeLabel == null) 8.dp else 0.dp).padding(top = if (reaction.isNotBlank()) 12.dp else 0.dp),
                            horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
                        ) {
                            Box(Modifier.widthIn(max = 300.dp)) {
                                val openMenu: () -> Unit = { if (!typing) menuKey = e.key }
                                val toggleDetails: () -> Unit = {
                                    if (!typing) { if (expanded) detailKeys.remove(e.key) else detailKeys.add(e.key) }
                                }
                                when {
                                    typing -> IMessageBubble("", false, true, chatDark, receivedTextColor, myBubbleHex, bubbleHex, "", typing = true)
                                    type == "payment" -> {
                                        PaymentMessageCard(
                                            modifier = Modifier.bounceClick(pressedScale = 0.98f, onLongClick = openMenu) { toggleDetails() },
                                            type = m.optString("paymentType", "redpacket"), amount = m.optString("amount", "0.00"),
                                            note = m.optString("note", "一点心意"), status = m.optString("status", "pending"),
                                            outgoing = user, userMayDecide = !user && m.optString("status", "pending") == "pending",
                                            onAccept = { onPaymentDecision(m.optString("id"), true) },
                                            onReject = { onPaymentDecision(m.optString("id"), false) }, caption = content
                                        )
                                    }
                                    content.contains("[[IMAGE:") -> {
                                        val path = IMAGE_RE.find(content)?.groupValues?.getOrNull(1).orEmpty()
                                        val caption = content.substringBefore("[[IMAGE:").trim()
                                        Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            if (path.isNotBlank()) {
                                                PickedPhoto(
                                                    path,
                                                    Modifier.widthIn(max = 260.dp).heightIn(max = 320.dp).bounceClick(pressedScale = 0.97f, onLongClick = openMenu) { toggleDetails() },
                                                    maxPx = 1000
                                                )
                                            } else {
                                                IMessageBubble("图片附件无法读取，请重新选择发送。", user, true, chatDark, if (user) sentTextColor else receivedTextColor, myBubbleHex, bubbleHex, "")
                                            }
                                            if (caption.isNotBlank()) {
                                                IMessageBubble(
                                                    caption, user, e.endRun, chatDark, if (user) sentTextColor else receivedTextColor, myBubbleHex, bubbleHex, bubbleBackgroundUri,
                                                    modifier = Modifier.bounceClick(pressedScale = 0.97f, onLongClick = openMenu) { toggleDetails() }
                                                )
                                            }
                                        }
                                    }
                                    GIF_RE.containsMatchIn(content) -> {
                                        val path = GIF_RE.find(content)?.groupValues?.getOrNull(1).orEmpty()
                                        AsyncImage(
                                            model = ImageRequest.Builder(context).data(Uri.parse(path)).crossfade(true).build(),
                                            imageLoader = gifLoader, contentDescription = "GIF 表情包",
                                            modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 240.dp).clip(RoundedCornerShape(20.dp))
                                                .bounceClick(pressedScale = 0.97f, onLongClick = openMenu) { toggleDetails() },
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                    isEmojiOnly(content) -> {
                                        Text(
                                            content.trim(), fontSize = 56.sp, lineHeight = 64.sp,
                                            modifier = Modifier.bounceClick(pressedScale = 0.9f, onLongClick = openMenu) { toggleDetails() }.padding(horizontal = 2.dp)
                                        )
                                    }
                                    else -> IMessageBubble(
                                        content, user, e.endRun, chatDark, if (user) sentTextColor else receivedTextColor, myBubbleHex, bubbleHex, bubbleBackgroundUri,
                                        modifier = Modifier.bounceClick(pressedScale = 0.97f, onLongClick = openMenu) { toggleDetails() }
                                    )
                                }
                                if (reaction.isNotBlank()) ReactionBadge(reaction, !user, chatDark, accent)
                            }
                        }
                        if (user && (e.key == lastUserKey || expanded)) {
                            val ts = m.optLong("time", 0L)
                            Text(
                                buildAnnotatedString {
                                    if (ts > 0L) append(clockText(ts))
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(if (m.optBoolean("read", false)) "已读" else "已送达") }
                                },
                                fontSize = 12.sp, color = Muted, modifier = Modifier.align(Alignment.End).padding(top = 4.dp, end = 4.dp)
                            )
                        }
                        if (!user && !typing && (e.endRun || expanded)) {
                            val model = m.optString("model").ifBlank { p.optString("model") }
                                .ifBlank { AiGateway.defaultModel(p.optString("provider", "DeepSeek")) }
                            val tokens = m.optInt("tokens", 0)
                            val ts = m.optLong("time", 0L)
                            val parts = ArrayList<String>()
                            parts.add(model)
                            if (tokens > 0) parts.add(String.format(Locale.US, "%,d tokens", tokens))
                            if (ts > 0L) parts.add(clockText(ts))
                            Text(parts.joinToString("  ✦  "), fontSize = 11.sp, color = Muted.copy(alpha = 0.8f), modifier = Modifier.padding(start = 6.dp, top = 4.dp))
                        }
                    }
                }
            }
        }

        if (error.isNotBlank()) {
            Text(
                error, fontSize = 12.sp, lineHeight = 16.sp,
                color = if (error.startsWith("请求失败") || error.startsWith("发送失败")) Color(0xFFFF6B6B) else Ink.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp).bounceClick(pressedScale = 0.98f) { onDismissError() }
                    .glassSurface(RoundedCornerShape(16.dp), 1.8f).padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }

        AnimatedVisibility(visible = panelOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .glassSurface(RoundedCornerShape(28.dp), 1.8f, 6.dp).padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PanelItem("图片", Icons.Default.Image, Color(0xFF3B9AF5), Ink, 0) { panelOpen = false; onPickImage() }
                PanelItem("GIF", Icons.Default.Gif, Color(0xFFAF52DE), Ink, 50) { panelOpen = false; onPickGif() }
                PanelItem("红包", Icons.Default.CardGiftcard, Color(0xFFE75B56), Ink, 100) {
                    panelOpen = false; paymentKind = "redpacket"; paymentAmount = "10.00"; paymentNote = "一点心意"; paymentDialogOpen = true
                }
                PanelItem("转账", Icons.Default.SwapHoriz, Color(0xFF17A979), Ink, 150) {
                    panelOpen = false; paymentKind = "transfer"; paymentAmount = "10.00"; paymentNote = "请收下"; paymentDialogOpen = true
                }
                if (assistantEnabled) {
                    PanelItem("助手", Icons.Default.SmartToy, Color(0xFF7B61FF), Ink, 200) { panelOpen = false; onAssistant() }
                }
            }
        }

        if (attach != null) {
            val attachPath = attach.groupValues[2]
            Box(Modifier.padding(start = 18.dp, bottom = 2.dp).bounceIn().size(76.dp)) {
                Box(Modifier.size(70.dp).glassSurface(RoundedCornerShape(18.dp), 1.6f, 4.dp).padding(3.dp)) {
                    PickedPhoto(attachPath, Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp)), rounded = false, maxPx = 300)
                }
                Box(
                    Modifier.align(Alignment.TopEnd).size(24.dp).bounceClick { onComposer(typedText) }
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Close, "移除附件", tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }

        // ── iMessage composer: round + button, rounded "iMessage信息" field with mic / send ──
        Row(
            Modifier.navigationBarsPadding().imePadding().fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val plusRotation by animateFloatAsState(
                targetValue = if (panelOpen) 45f else 0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "plus-rotation"
            )
            Box(
                Modifier.size(44.dp).bounceClick(pressedScale = 0.88f) { panelOpen = !panelOpen }.glassSurface(CircleShape, 1.8f, 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, "展开功能", tint = Ink, modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = plusRotation })
            }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.weight(1f).heightIn(min = 44.dp).glassSurface(RoundedCornerShape(22.dp), 1.8f, 6.dp).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (typedText.isEmpty()) Text(inputHint.ifBlank { "iMessage信息" }, color = Muted, fontSize = 17.sp, maxLines = 1)
                    BasicTextField(
                        value = typedText,
                        onValueChange = { onComposer(it + (attach?.value ?: "")) },
                        maxLines = 6,
                        textStyle = TextStyle(color = Ink, fontSize = 17.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(accent),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    )
                }
                AnimatedContent(
                    targetState = canSend,
                    transitionSpec = {
                        (scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.4f) + fadeIn()) togetherWith
                            (scaleOut(targetScale = 0.4f) + fadeOut())
                    },
                    label = "send-or-mic"
                ) { sendMode ->
                    if (sendMode) {
                        Box(
                            Modifier.size(32.dp).bounceClick(pressedScale = 0.82f) { onSend(composer) }.clip(CircleShape).background(Color(0xFF0A84FF)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.ArrowUpward, "发送消息", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    } else {
                        Box(
                            Modifier.size(32.dp).bounceClick(pressedScale = 0.85f) {
                                callReplyText = ""; recognizedText = ""; callRequestStarted = false; callDialog = true
                            },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Mic, "语音输入", tint = Muted, modifier = Modifier.size(22.dp)) }
                    }
                }
            }
        }
    }

    // ── long-press: tapback reactions + actions (iMessage style) ──
    if (menuKey.isNotBlank()) {
        val entry = entries.firstOrNull { it.key == menuKey }
        if (entry != null) {
            val em = entry.msg
            val emContent = em.optString("content")
            val emUser = em.optString("role") == "user"
            val emText = when {
                em.optString("type") == "payment" -> "[虚拟${if (em.optString("paymentType") == "redpacket") "红包" else "转账"}] ¥${em.optString("amount")}"
                emContent.contains("[[IMAGE:") -> "[图片] " + emContent.substringBefore("[[IMAGE:").trim()
                GIF_RE.containsMatchIn(emContent) -> "[动图]"
                else -> emContent
            }
            TapbackMenu(
                text = emText, canReact = !emUser, current = em.optString("reaction"), ink = Ink, accent = accent,
                onReact = { emoji ->
                    val idx = (0 until arr.length()).firstOrNull { arr.optJSONObject(it) === em }
                    if (idx != null) onReact(idx, emoji)
                    menuKey = ""
                },
                onCopy = { clipboard.setText(AnnotatedString(emText)); menuKey = "" },
                onQuote = { onQuote("「${emText.take(500)}」\n"); menuKey = "" },
                onDelete = {
                    val idx = (0 until arr.length()).firstOrNull { arr.optJSONObject(it) === em }
                    if (idx != null) onDeleteMessage(idx)
                    menuKey = ""
                },
                onDismiss = { menuKey = "" }
            )
        } else {
            LaunchedEffect(menuKey) { menuKey = "" }
        }
    }

    if (paymentDialogOpen) AlertDialog(
        onDismissRequest = { paymentDialogOpen = false },
        containerColor = Color.White.copy(alpha = 0.96f),
        shape = RoundedCornerShape(28.dp),
        title = { Text(if (paymentKind == "redpacket") "发送红包" else "发送转账") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("仅为 DeepLine 内的虚拟互动，不会产生真实支付。", fontSize = 12.sp, color = Color(0xFF6F7B88))
                Text("可用余额：无限", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF9B741D))
                GlassField(value = paymentAmount, onValueChange = { paymentAmount = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, label = { Text("金额（金币）") }, singleLine = true)
                GlassField(value = paymentNote, onValueChange = { paymentNote = it.take(60) }, label = { Text("备注") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = (paymentAmount.toDoubleOrNull() ?: 0.0) > 0.0, onClick = {
                val amount = paymentAmount.toBigDecimalOrNull()
                if (amount != null && amount > java.math.BigDecimal.ZERO && amount <= java.math.BigDecimal("999999.99")) {
                    val marker = "[[PAYMENT:$paymentKind:${amount.setScale(2, java.math.RoundingMode.HALF_UP)}:${paymentNote.replace("]]", "")}]]"
                    paymentDialogOpen = false
                    onSend(marker)
                }
            }) { Text("发送") }
        },
        dismissButton = { TextButton(onClick = { paymentDialogOpen = false }) { Text("取消") } }
    )

    LaunchedEffect(revision, busy, arr.length(), callDialog, callRequestStarted) {
        if (callDialog && callRequestStarted && !busy) {
            val latest = (arr.length() - 1 downTo 0).asSequence().mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("role") == "assistant" && it.optString("content") != "---" && it.optString("type") != "summary" }
            if (latest != null) callReplyText = latest.optString("content").take(1200)
        }
    }

    if (callDialog) Dialog(onDismissRequest = {
        callDialog = false; voiceActive = false
        try { recognizer?.cancel() } catch (_: Exception) { }
        voiceStatus = "语音聊天已结束"
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            GlassWallpaper(backgroundUri, backgroundAlpha, backgroundBlur)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(43.dp).bounceClick {
                            callDialog = false; voiceActive = false
                            try { recognizer?.cancel() } catch (_: Exception) { }
                        }.glassSurface(CircleShape, 1.8f, 4.dp),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Close, "关闭语音聊天", tint = Ink) }
                    Spacer(Modifier.weight(1f))
                    Text(if (voiceActive) "语音聊天中" else "语音聊天", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(43.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(p.optString("avatar"), p.optString("name"), 124)
                    Text(p.optString("name", "AI"), fontWeight = FontWeight.Bold, fontSize = 27.sp, color = Ink)
                    Text(p.optString("relationship", "AI 好友"), color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(voiceStatus, color = Ink.copy(alpha = 0.7f), textAlign = TextAlign.Center, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
                    if (recognizedText.isNotBlank()) Text("你说：$recognizedText", color = Ink.copy(alpha = 0.6f), textAlign = TextAlign.Center, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
                    Column(Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(24.dp), 1.6f, 4.dp).padding(15.dp)) {
                        Text(if (busy) "AI 正在回复…" else "AI 回复", color = Ink.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(callReplyText.ifBlank { "说完话后，AI 的文字回复会显示在这里。" }, color = Ink, fontSize = 15.sp, lineHeight = 22.sp)
                    }
                    Text("语音识别 → AI 文字回复（不会自动朗读）", color = Muted, textAlign = TextAlign.Center, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(70.dp).bounceClick(pressedScale = 0.88f) {
                                if (voiceActive) {
                                    voiceActive = false
                                    try { recognizer?.cancel() } catch (_: Exception) { }
                                    voiceStatus = "语音监听已暂停"
                                } else {
                                    voiceActive = true
                                    if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        Handler(Looper.getMainLooper()).postDelayed({ startListeningHolder.value?.invoke() }, 250L)
                                    } else permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            }.clip(CircleShape).background(if (voiceActive) Color(0xFF525E69) else Color(0xFF1E2935)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (voiceActive) Icons.Default.MicOff else Icons.Default.Mic, if (voiceActive) "暂停语音" else "开始语音聊天", tint = Color.White, modifier = Modifier.size(29.dp))
                        }
                        Spacer(Modifier.height(8.dp)); Text(if (voiceActive) "暂停监听" else "开始说话", color = Ink, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(42.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(70.dp).bounceClick(pressedScale = 0.88f) {
                                callDialog = false; voiceActive = false
                                try { recognizer?.cancel() } catch (_: Exception) { }
                                voiceStatus = "语音聊天已结束"
                            }.clip(CircleShape).background(Color(0xFFEE5B64)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.CallEnd, "结束语音聊天", tint = Color.White, modifier = Modifier.size(29.dp)) }
                        Spacer(Modifier.height(8.dp)); Text("结束", color = Ink, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    }
}

// ───────────────────────────── Payment card (red packet / transfer) ─────────────────────────────

@Composable
private fun PaymentMessageCard(
    modifier: Modifier, type: String, amount: String, note: String, status: String,
    outgoing: Boolean, userMayDecide: Boolean, onAccept: () -> Unit, onReject: () -> Unit, caption: String
) {
    val red = type == "redpacket"
    val base = when {
        status == "rejected" -> Color(0xFF9AA3AD)
        red -> Color(0xFFE8574D)
        else -> Color(0xFF17A97A)
    }
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.width(248.dp).clip(shape)
            .background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.82f))))
            .border(1.dp, Color.White.copy(alpha = 0.40f), shape)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(Color.White.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (red) Icons.Default.CardGiftcard else Icons.Default.SwapHoriz, null, tint = Color.White, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (red) "虚拟红包" else "虚拟转账", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                Text(note.ifBlank { if (red) "恭喜发财" else "请收下" }, color = Color.White.copy(alpha = 0.92f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("¥$amount", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
        Text(
            when (status) {
                "accepted" -> if (outgoing) "对方已收下" else "你已收下"
                "rejected" -> if (outgoing) "对方已拒绝" else "你已拒绝"
                else -> "等待${if (outgoing) "对方" else "你"}处理"
            },
            color = Color.White.copy(alpha = 0.94f), fontSize = 12.sp
        )
        if (caption.isNotBlank() && caption != note) Text(caption, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        if (userMayDecide) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).bounceClick(pressedScale = 0.92f) { onAccept() }.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) { Text(if (red) "收下红包" else "确认收下", fontSize = 13.sp, color = base, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1f).bounceClick(pressedScale = 0.92f) { onReject() }.clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(20.dp)).padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) { Text("拒绝", fontSize = 13.sp, color = Color.White) }
            }
        }
        Text("仅应用内 UI，不涉及真实资金", color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

// ───────────────────────────── Persona editor ─────────────────────────────

@Composable
fun PersonaEditor(
    editing: Boolean = false, provider: String, onProvider: (String) -> Unit, model: String, onModel: (String) -> Unit,
    name: String, onName: (String) -> Unit, bio: String, onBio: (String) -> Unit, persona: String, onPersona: (String) -> Unit,
    apiKey: String, onApiKey: (String) -> Unit, gender: String, onGender: (String) -> Unit, relation: String, onRelation: (String) -> Unit,
    likes: String, onLikes: (String) -> Unit, dislikes: String, onDislikes: (String) -> Unit, hobbies: String, onHobbies: (String) -> Unit,
    avatar: String, onPickAvatar: () -> Unit, error: String, onBack: () -> Unit, onCreate: () -> Unit,
    assistantSlot: (@Composable () -> Unit)? = null
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().glassBar(edgeTop = false).statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).bounceClick { onBack() }.glassSurface(CircleShape, 1.6f), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ArrowBack, "返回", tint = MaterialTheme.colorScheme.primary)
            }
            Text(if (editing) "编辑 AI 人设" else "创建 AI 人设", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink, modifier = Modifier.padding(start = 12.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(26.dp), 1.25f).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        Box(Modifier.bounceClick { onPickAvatar() }) { Avatar(avatar, name, 72) }
                        Spacer(Modifier.width(12.dp))
                        SettingButton("从相册选择头像", Icons.Default.Image, MaterialTheme.colorScheme.primary) { onPickAvatar() }
                    }
                    GlassField(name, onName, label = { Text("名字 *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    GlassField(bio, onBio, label = { Text("简介") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    GlassField(persona, onPersona, label = { Text("人设 *") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    Text("AI 服务商", fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.padding(top = 12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("DeepSeek", "Claude", "豆包", "ChatGPT / OpenAI").chunked(2).forEach { group ->
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                group.forEach { option -> FilterChip(selected = provider == option, onClick = { onProvider(option) }, label = { Text(option) }) }
                            }
                        }
                    }
                    GlassField(model, onModel, label = { Text("模型名称 / 豆包接入点 ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("默认模型：${AiGateway.defaultModel(provider)}。豆包需使用控制台中实际可用的模型或接入点 ID。", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
                    GlassField(
                        apiKey, onApiKey,
                        label = { Text(if (editing) "更新 $provider API Key（留空保持原密钥）" else "$provider API Key *") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation()
                    )
                    Text(
                        if (editing) "编辑资料不会删除现有聊天记录。API Key 留空会保留原密钥。" else "必须填写可用的 API Key 才能创建此 AI。费用和额度由 API 服务商决定。",
                        fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp)
                    )
                    Text("性别", fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.padding(top = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("男", "女", "其他").forEach { FilterChip(selected = gender == it, onClick = { onGender(it) }, label = { Text(it) }) } }
                    Text("与你的关系", fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("家人", "情侣", "哥哥", "弟弟", "朋友", "亲戚").forEach { FilterChip(selected = relation == it, onClick = { onRelation(it) }, label = { Text(it) }) } }
                    GlassField(relation, onRelation, label = { Text("自定义关系（可直接输入）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("选填：角色偏好", fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    GlassField(likes, onLikes, label = { Text("喜欢什么") }, modifier = Modifier.fillMaxWidth())
                    GlassField(dislikes, onDislikes, label = { Text("讨厌什么") }, modifier = Modifier.fillMaxWidth())
                    GlassField(hobbies, onHobbies, label = { Text("爱好") }, modifier = Modifier.fillMaxWidth())
                    if (error.isNotBlank()) Text(error, color = Color(0xFFD14343), modifier = Modifier.padding(top = 8.dp))
                }
                assistantSlot?.invoke()
            }
        }
        PrimaryButton(if (editing) "保存修改" else "创建 AI", Modifier.navigationBarsPadding().imePadding().fillMaxWidth().padding(16.dp)) { onCreate() }
    }
}

// ───────────────────────────── Colour picker ─────────────────────────────

@Composable
fun ColorSpectrumPicker(selected: String, onSelect: (String) -> Unit) {
    val initial = remember(selected) {
        try {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(selected), hsv)
            hsv
        } catch (_: Exception) { floatArrayOf(210f, .85f, .98f) }
    }
    var hue by remember(selected) { mutableFloatStateOf(initial[0]) }
    var saturation by remember(selected) { mutableFloatStateOf(initial[1]) }
    var value by remember(selected) { mutableFloatStateOf(initial[2]) }
    val colorInt = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
    val color = Color(colorInt)
    val hex = "#%06X".format(colorInt and 0xFFFFFF)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.55f)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(color).border(1.dp, Color.Black.copy(alpha = .12f), RoundedCornerShape(15.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text("实时颜色预览", fontWeight = FontWeight.SemiBold, color = Ink); Text(hex, color = Muted, fontSize = 13.sp) }
            Box(
                Modifier.bounceClick(pressedScale = 0.9f) { onSelect(hex) }.clip(RoundedCornerShape(22.dp)).background(color).padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) { Text("应用", color = if (color.luminance() < .45f) Color.White else Color.Black, fontWeight = FontWeight.SemiBold) }
        }
        Text("色相", fontSize = 12.sp, color = Muted)
        Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f, steps = 35, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
        Text("饱和度 ${(saturation * 100).toInt()}%", fontSize = 12.sp, color = Muted)
        Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
        Text("明度 ${(value * 100).toInt()}%", fontSize = 12.sp, color = Muted)
        Slider(value = value, onValueChange = { value = it }, valueRange = .05f..1f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
    }
}

// ───────────────────────────── Groups ─────────────────────────────

@Composable
fun GroupCreateScreen(aiList: List<JSONObject>, groupName: String, onGroupName: (String) -> Unit, onBack: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().glassBar(edgeTop = false).statusBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).bounceClick { onBack() }.glassSurface(CircleShape, 1.6f), contentAlignment = Alignment.Center) { Icon(Icons.Default.ArrowBack, "返回", tint = accent) }
            Text("创建群聊", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(start = 12.dp), color = Ink)
        }
        Column(Modifier.weight(1f).padding(16.dp)) {
            GlassField(groupName, onGroupName, label = { Text("群聊名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("选择你创建的 AI（每人每轮最多回复一次，最多 8 个）", fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(aiList, key = { it.optString("id") }) { ai ->
                    val id = ai.optString("id")
                    Row(
                        Modifier.fillMaxWidth().bounceClick(pressedScale = 0.97f) {
                            selected = if (selected.contains(id)) selected - id else if (selected.size < 8) selected + id else selected
                        }.glassSurface(RoundedCornerShape(20.dp), 1.2f).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(ai.optString("avatar"), ai.optString("name"), 46)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ai.optString("name"), fontWeight = FontWeight.SemiBold, color = Ink)
                            Text(ai.optString("persona"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Muted)
                        }
                        Checkbox(checked = selected.contains(id), onCheckedChange = { checked -> selected = if (checked && selected.size < 8) selected + id else selected - id })
                    }
                }
            }
        }
        PrimaryButton("创建群聊 · ${selected.size} 个 AI", Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp)) { onCreate(groupName, selected.toList()) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupChatScreen(
    group: JSONObject, revision: Int, myName: String, myAvatar: String, busy: Boolean, error: String, glassUi: Boolean,
    autoSpeakAi: Boolean = false, voiceGender: String = "female", voicePitch: Float = 1.0f, voiceRate: Float = 1.0f, voiceName: String = "",
    onBack: () -> Unit, findPersona: (String) -> JSONObject?, onSend: (String) -> Unit,
    chatDark: Boolean = false
) {
    val accent = MaterialTheme.colorScheme.primary
        val context = LocalContext.current
    val messages = group.optJSONArray("messages") ?: JSONArray()
    var composer by remember(group.optString("id")) { mutableStateOf("") }
    val listState = rememberLazyListState()
    var groupSpeechEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var groupSpeechReady by remember { mutableStateOf(false) }
    val spokenGroupMessageIds = remember(group.optString("id")) { mutableSetOf<String>() }
    LaunchedEffect(group.optString("id")) {
        // Existing messages are not read out automatically upon entering the group.
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val id = m.optString("id")
            if (id.isNotBlank() && m.optString("role") == "assistant" && m.optString("content") != "---") spokenGroupMessageIds.add(id)
        }
    }
    DisposableEffect(context, group.optString("id")) {
        val engine = TextToSpeech(context) { status ->
            Handler(Looper.getMainLooper()).post { groupSpeechReady = status == TextToSpeech.SUCCESS }
        }
        groupSpeechEngine = engine
        onDispose {
            try { engine.stop(); engine.shutdown() } catch (_: Exception) { }
            groupSpeechEngine = null
            groupSpeechReady = false
        }
    }
    LaunchedEffect(groupSpeechReady, voiceGender, voicePitch, voiceRate, voiceName) {
        val engine = groupSpeechEngine ?: return@LaunchedEffect
        if (!groupSpeechReady) return@LaunchedEffect
        try {
            engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            engine.setPitch(voicePitch)
            engine.setSpeechRate(voiceRate)
            val choices = engine.voices?.filter { it.locale.language == "zh" }.orEmpty()
            val selected = choices.firstOrNull { it.name == voiceName }
                ?: choices.firstOrNull { if (voiceGender == "male") it.name.contains("male", true) || it.name.contains("man", true) else it.name.contains("female", true) || it.name.contains("woman", true) }
                ?: choices.firstOrNull()
            if (selected != null) engine.voice = selected
        } catch (_: Exception) { }
    }
    LaunchedEffect(revision, busy, messages.length(), autoSpeakAi, groupSpeechReady) {
        if (autoSpeakAi && !busy && groupSpeechReady) {
            val pending = mutableListOf<Pair<String, String>>()
            for (i in 0 until messages.length()) {
                val m = messages.optJSONObject(i) ?: continue
                val id = m.optString("id")
                val content = m.optString("content").trim()
                if (m.optString("role") == "assistant" && content.isNotBlank() && content != "---" && !content.startsWith("本条回复失败") && id.isNotBlank() && !spokenGroupMessageIds.contains(id)) {
                    val speaker = m.optString("speakerName").trim()
                    pending.add(id to (if (speaker.isBlank()) content else "$speaker：$content").take(3000))
                    spokenGroupMessageIds.add(id)
                }
            }
            pending.forEach { (id, textToSpeak) -> groupSpeechEngine?.speak(textToSpeak, TextToSpeech.QUEUE_ADD, null, "group_$id") }
        }
    }
    LaunchedEffect(messages.length(), revision, busy) { if (messages.length()>0) listState.animateScrollToItem(messages.length()-1) }
    val ids = group.optJSONArray("memberIds") ?: JSONArray()
    val entryKeys = remember(revision, messages.length()) {
        val seen = HashSet<String>()
        (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }.map { m ->
            var key = m.optString("id").ifBlank { "g" + m.hashCode() }
            while (!seen.add(key)) key += "_"
            key to m
        }
    }
    val doneKeys = remember(group.optString("id")) { HashSet<String>().also { set -> entryKeys.forEach { set.add(it.first) } } }
    val fg = if (chatDark) Color.White else Color(0xFF111111)
    val Ink = fg
    val Muted = fg.copy(alpha = 0.55f)
    val glassTint = if (chatDark) Color(0xFF2B2B2E) else Color.White
    CompositionLocalProvider(LocalGlassTint provides glassTint) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp)) {
            Row(
                Modifier.align(Alignment.TopStart).height(44.dp).bounceClick(pressedScale = 0.92f) { onBack() }
                    .glassSurface(RoundedCornerShape(22.dp), 1.8f, 6.dp).padding(start = 6.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { Icon(Icons.Default.ChevronLeft, "返回", tint = Ink, modifier = Modifier.size(30.dp)) }
            Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(66.dp).glassSurface(CircleShape, 1.8f, 6.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Groups, null, tint = Ink, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.widthIn(max = 190.dp).glassSurface(RoundedCornerShape(16.dp), 1.8f, 4.dp).padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(group.optString("name", "群聊"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${ids.length()} 位 AI", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
            }
        }
        LazyColumn(
            state = listState, modifier = Modifier.weight(1f).fillMaxWidth().fadingEdges(16.dp, 12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(entryKeys, key = { it.first }) { (key, m) ->
                val index = entryKeys.indexOfFirst { it.first == key }
                val isUser = m.optString("role") == "user"
                val prev = entryKeys.getOrNull(index - 1)?.second
                val next = entryKeys.getOrNull(index + 1)?.second
                val speaker = m.optString("speakerId")
                val sameAsPrev = prev != null && prev.optString("role") == m.optString("role") && prev.optString("speakerId") == speaker
                val sameAsNext = next != null && next.optString("role") == m.optString("role") && next.optString("speakerId") == speaker
                val animate = remember(key) {
                    val fresh = key !in doneKeys
                    doneKeys.add(key)
                    fresh
                }
                val ai = if (isUser) null else findPersona(speaker)
                val typing = m.optString("content") == "---"
                Column(Modifier.fillMaxWidth().padding(top = if (sameAsPrev) 0.dp else 8.dp).bounceIn(enabled = animate, origin = TransformOrigin(if (isUser) 1f else 0f, 1f), rise = 20f)) {
                    if (!isUser && !sameAsPrev && m.optString("speakerName").isNotBlank()) {
                        Text(m.optString("speakerName"), fontSize = 11.sp, color = Muted, modifier = Modifier.padding(start = 44.dp, bottom = 2.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                        if (!isUser) {
                            Box(Modifier.width(36.dp)) {
                                if (!sameAsNext && ai != null) Avatar(ai.optString("avatar"), ai.optString("name"), 32)
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Box(Modifier.widthIn(max = 290.dp)) {
                            IMessageBubble(
                                m.optString("content"), isUser, !sameAsNext, chatDark, if (isUser) Color.White else fg,
                                "#0A84FF", "#FFFFFF", "", typing = typing
                            )
                        }
                    }
                    val t = m.optLong("time", 0L)
                    if (!sameAsNext && t > 0L && !typing) {
                        Text(clockText(t), fontSize = 10.sp, color = Muted, modifier = Modifier.align(if (isUser) Alignment.End else Alignment.Start).padding(start = 44.dp, end = 6.dp, top = 3.dp))
                    }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp))
        val canSendGroup = composer.isNotBlank() && !busy
        Row(
            Modifier.navigationBarsPadding().imePadding().fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = 44.dp).glassSurface(RoundedCornerShape(22.dp), 1.8f, 6.dp).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (composer.isEmpty()) Text("iMessage信息", color = Muted, fontSize = 17.sp)
                    BasicTextField(
                        value = composer, onValueChange = { composer = it }, maxLines = 6,
                        textStyle = TextStyle(color = Ink, fontSize = 17.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(accent), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    )
                }
                Box(
                    Modifier.size(32.dp).bounceClick(enabled = canSendGroup, pressedScale = 0.82f) {
                        val msg = composer
                        composer = ""
                        onSend(msg)
                    }.clip(CircleShape).background(Color(0xFF0A84FF).copy(alpha = if (canSendGroup) 1f else 0.35f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.ArrowUpward, "发送", tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
        }
    }
    }
}
