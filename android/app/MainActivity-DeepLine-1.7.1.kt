package com.deepline.privateapp

import android.os.Bundle
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.content.pm.PackageManager
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import android.media.AudioManager
import android.media.ToneGenerator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import java.io.File
import java.util.UUID
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Green = Color(0xFF6685F5)
private val Ink = Color(0xFF17211B)
private val Muted = Color(0xFF7A8580)
private val Page = Color(0xFFF7F9FD)

private fun formatCoinAmount(value: Double): String = try {
    java.math.BigDecimal.valueOf(value.coerceAtLeast(0.0)).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
} catch (_: Exception) { "0" }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var accent by remember {
                mutableStateOf(try {
                    Color(android.graphics.Color.parseColor(LocalStore.load(context).optString("theme", "#6685F5")))
                } catch (_: Exception) { Green })
            }
            MaterialTheme(colorScheme = lightColorScheme(primary = accent, background = Page)) {
                DeepLineApp(onThemeChange = { accent = it })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeepLineApp(onThemeChange: (Color) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(LocalStore.init(context)) }
    var revision by remember { mutableIntStateOf(0) }
    val revisionSnapshot = revision
    var coinBalance by remember { mutableDoubleStateOf(data.optDouble("coins", 100.0).coerceAtLeast(0.0)) }
    var aiAutoSpeakEnabled by remember { mutableStateOf(data.optBoolean("aiAutoSpeakEnabled", true)) }
    fun persist() { LocalStore.save(context, data); revision++ }

    // Wallet is stored locally. Coins accrue by elapsed wall-clock seconds, including
    // time while the app is closed, and survive app restarts.
    LaunchedEffect(Unit) {
        val bootNow = System.currentTimeMillis()
        if (!data.has("coins")) data.put("coins", 100.0)
        if (!data.has("coinLastAccrualAt")) data.put("coinLastAccrualAt", bootNow)
        coinBalance = data.optDouble("coins", 100.0).coerceAtLeast(0.0)
        LocalStore.save(context, data)
        while (true) {
            kotlinx.coroutines.delay(1000L)
            val now = System.currentTimeMillis()
            var last = data.optLong("coinLastAccrualAt", now)
            if (last > now) {
                last = now
                data.put("coinLastAccrualAt", last)
                LocalStore.save(context, data)
            }
            val elapsedSeconds = ((now - last) / 1000L).coerceAtLeast(0L)
            if (elapsedSeconds > 0L) {
                val nextBalance = (data.optDouble("coins", coinBalance) + elapsedSeconds.toDouble()).coerceAtMost(999999999.0)
                coinBalance = nextBalance
                data.put("coins", nextBalance)
                data.put("coinLastAccrualAt", last + elapsedSeconds * 1000L)
                // Save without bumping revision every second, so message lists don't rebuild constantly.
                LocalStore.save(context, data)
            }
        }
    }
    var tab by remember { mutableStateOf("聊天") }
    var chatListMode by remember { mutableStateOf("私聊") }
    var advancedSettingsOpen by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf("home") }
    var currentId by remember { mutableStateOf("") }
    var composer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var personaApiKey by remember { mutableStateOf("") }
    var personaProvider by remember { mutableStateOf("DeepSeek") }
    var personaModel by remember { mutableStateOf(AiGateway.defaultModel("DeepSeek")) }
    var currentGroupId by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    var multiReplyEnabled by remember { mutableStateOf(data.optBoolean("multiReplyEnabled", false)) }
    var importBackupMode by remember { mutableStateOf("personas") }
    var myName by remember { mutableStateOf(data.optString("myName","我")) }
    var myBio by remember { mutableStateOf(data.optString("myBio")) }
    var themeHex by remember { mutableStateOf(data.optString("theme","#6685F5")) }
    var personaName by remember { mutableStateOf("") }
    var personaBio by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("其他") }
    var relation by remember { mutableStateOf("朋友") }
    var likes by remember { mutableStateOf("") }
    var dislikes by remember { mutableStateOf("") }
    var hobbies by remember { mutableStateOf("") }
    var feedText by remember { mutableStateOf("") }
    var postImage by remember { mutableStateOf("") }
    var commentPostId by remember { mutableStateOf("") }
    var commentText by remember { mutableStateOf("") }
    var autoAiEnabled by remember { mutableStateOf(data.optBoolean("autoAiEnabled", false)) }
    var editAvatar by remember { mutableStateOf("") }
    var editBackground by remember { mutableStateOf("") }
    var editingPersonaId by remember { mutableStateOf("") }
    var glassUi by remember { mutableStateOf(data.optBoolean("glassUi", true)) }
    var soundEnabled by remember { mutableStateOf(data.optBoolean("soundEnabled", true)) }
    var bubbleHex by remember { mutableStateOf(data.optString("bubbleColor", "#FFFFFF").let { if (it.equals("#E5F3FF", true)) "#FFFFFF" else it }) }
    var myBubbleHex by remember { mutableStateOf(data.optString("myBubbleColor", "#0A84FF").let { if (it.equals("#DCFCE7", true)) "#0A84FF" else it }) }
    var bubbleShape by remember { mutableStateOf(data.optString("bubbleShape", "柔和圆角")) }
    var bubbleBackgroundUri by remember { mutableStateOf(data.optString("bubbleBackground", "")) }
    var backgroundAlpha by remember { mutableFloatStateOf(data.optDouble("backgroundAlpha", 0.22).toFloat()) }
    var fontChoice by remember { mutableStateOf(data.optString("fontChoice", "系统")) }
    var aiTextColor by remember { mutableStateOf(data.optString("aiTextColor", "#263247")) }
    var myTextColor by remember { mutableStateOf(data.optString("myTextColor", "#243047")) }
    var backgroundBlur by remember { mutableFloatStateOf(data.optDouble("backgroundBlur", 0.0).toFloat()) }
    var memoryLimit by remember { mutableIntStateOf(data.optInt("memoryLimit", 30).coerceIn(5, 200)) }
    var multiReplyCount by remember { mutableIntStateOf(data.optInt("multiReplyCount", 1).coerceIn(1, 4)) }
    var voiceGender by remember { mutableStateOf(data.optString("voiceGender", "female")) }
    var voicePitch by remember { mutableFloatStateOf(data.optDouble("voicePitch", 1.0).toFloat().coerceIn(0.5f, 2.0f)) }
    var voiceRate by remember { mutableFloatStateOf(data.optDouble("voiceRate", 1.0).toFloat().coerceIn(0.5f, 2.0f)) }
    var voiceName by remember { mutableStateOf(data.optString("voiceName", "")) }
    var appTitle by remember { mutableStateOf(data.optString("appTitle", "DeepLine")) }
    var inputHint by remember { mutableStateOf(data.optString("inputHint", "发送消息…")) }
    var selectedImageTarget by remember { mutableStateOf("avatar") }
    var yandereEnabled by remember { mutableStateOf(data.optBoolean("yandereEnabled", false)) }
    var yandereCode by remember { mutableStateOf("") }
    var showYandereWarning by remember { mutableStateOf(false) }
    var yandereFlash by remember { mutableStateOf(false) }
    var lastInteractionAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var shakeCount by remember { mutableIntStateOf(0) }
    var lastShakeAt by remember { mutableLongStateOf(0L) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val target = selectedImageTarget
            val storedImage = try { copyPickedImageToAppStorage(context, uri, target) } catch (e: Exception) {
                error = "图片保存失败：${e.message ?: "请重新选择图片"}"
                ""
            }
            if (storedImage.isNotBlank()) {
                when (target) {
                    "myAvatar" -> { data.put("myAvatar", storedImage); persist() }
                    "personaAvatar" -> editAvatar = storedImage
                    "background" -> { data.put("background", storedImage); persist() }
                    "momentsCover" -> { data.put("momentsCover", storedImage); persist() }
                    "bubbleBackground" -> { bubbleBackgroundUri = storedImage; data.put("bubbleBackground", storedImage); persist() }
                    "chatImage" -> {
                        // Keep the actual local image as a message attachment. AiGateway sends image bytes
                        // to a vision-capable selected model; it does not pretend labels are image understanding.
                        composer = "[[IMAGE:$storedImage]]"
                        error = "已选择真实图片；点击发送后会将图片本身发给支持视觉的 AI 模型。"
                    }
                    "postImage" -> postImage = storedImage
                }
            }
        }
    }
    val gifPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val stored = copyPickedImageToAppStorage(context, uri, "gif")
                composer = "【GIF表情包】请结合这张动图的表情和当前聊天自然回应。\n[[GIF:$stored]]"
                error = "GIF 已添加到输入框，点击发送即可。AI 会结合聊天上下文回应；是否能直接理解每一帧画面取决于当前 AI 接口。"
            } catch (e: Exception) { error = "GIF 导入失败：${e.message ?: "请重试"}" }
        }
    }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val dest = File(context.filesDir, "deepline_custom_font.ttf")
                context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
                data.put("customFontPath", dest.absolutePath)
                fontChoice = "自定义"
                data.put("fontChoice", fontChoice)
                persist()
                error = "已应用自定义字体"
            } catch (e: Exception) { error = "字体导入失败：${e.message ?: "请重试"}" }
        }
    }
    val exportBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            try { BackupStore.export(context, data, uri); error = "完整数据 ZIP 已保存。备份包含 API Key，请勿公开分享。" }
            catch (e: Exception) { error = "导出失败：${e.message ?: "请重试"}" }
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val imported = BackupStore.import(context, uri)
                if (importBackupMode == "full") {
                    data = imported
                    if (!data.has("coins")) data.put("coins", 100.0)
                    if (!data.has("coinLastAccrualAt")) data.put("coinLastAccrualAt", System.currentTimeMillis())
                    coinBalance = data.optDouble("coins", 100.0).coerceAtLeast(0.0)
                    aiAutoSpeakEnabled = data.optBoolean("aiAutoSpeakEnabled", true)
                    LocalStore.save(context, data)
                    myName = data.optString("myName", "我")
                    myBio = data.optString("myBio", "")
                    themeHex = data.optString("theme", "#0A84FF")
                    try { onThemeChange(Color(android.graphics.Color.parseColor(themeHex))) } catch (_: Exception) { }
                    glassUi = data.optBoolean("glassUi", true)
                    soundEnabled = data.optBoolean("soundEnabled", true)
                    bubbleHex = data.optString("bubbleColor", "#FFFFFF")
                    myBubbleHex = data.optString("myBubbleColor", "#0A84FF")
                    bubbleShape = data.optString("bubbleShape", "柔和圆角")
                    bubbleBackgroundUri = data.optString("bubbleBackground", "")
                    backgroundAlpha = data.optDouble("backgroundAlpha", .22).toFloat()
                    backgroundBlur = data.optDouble("backgroundBlur", 0.0).toFloat()
                    fontChoice = data.optString("fontChoice", "系统")
                    aiTextColor = data.optString("aiTextColor", "#263247")
                    myTextColor = data.optString("myTextColor", "#243047")
                    memoryLimit = data.optInt("memoryLimit", 30).coerceIn(5,200)
                    multiReplyCount = data.optInt("multiReplyCount", 1).coerceIn(1,4)
                    multiReplyEnabled = data.optBoolean("multiReplyEnabled", false)
                    voiceGender = data.optString("voiceGender", "female")
                    voicePitch = data.optDouble("voicePitch", 1.0).toFloat().coerceIn(0.5f, 2.0f)
                    voiceRate = data.optDouble("voiceRate", 1.0).toFloat().coerceIn(0.5f, 2.0f)
                    voiceName = data.optString("voiceName", "")
                    autoAiEnabled = data.optBoolean("autoAiEnabled", false)
                    error = "备份已恢复。角色、API 配置、聊天记录、朋友圈和外观设置已载入。"
                } else {
                    val incoming = imported.optJSONArray("personas") ?: JSONArray()
                    val existing = data.optJSONArray("personas") ?: JSONArray().also { data.put("personas", it) }
                    var added = 0
                    for (i in 0 until incoming.length()) {
                        val item = incoming.optJSONObject(i) ?: continue
                        val cloned = JSONObject(item.toString())
                        cloned.put("id", UUID.randomUUID().toString())
                        existing.put(cloned)
                        added++
                    }
                    persist()
                    error = "已从 ZIP 导入 $added 个 AI，原 API 服务商、模型、密钥和聊天记录一并保留。"
                }
                page = "home"; tab = "聊天"
            } catch (e: Exception) { error = "导入失败：${e.message ?: "文件不是有效的 DeepLine ZIP"}" }
        }
    }

    fun saveData() {
        data.put("myName",myName).put("myBio",myBio).put("theme",themeHex)
        try { onThemeChange(Color(android.graphics.Color.parseColor(themeHex))) } catch (_: Exception) { }
        persist()
    }
    fun personas(): JSONArray = data.optJSONArray("personas") ?: JSONArray().also { data.put("personas",it) }
    fun findPersona(id:String): JSONObject? { val a=personas(); for(i in 0 until a.length()) if(a.getJSONObject(i).optString("id")==id)return a.getJSONObject(i); return null }
    fun groups(): JSONArray = data.optJSONArray("groups") ?: JSONArray().also { data.put("groups", it) }
    fun findGroup(id: String): JSONObject? { val a = groups(); for (i in 0 until a.length()) { val g = a.optJSONObject(i) ?: continue; if (g.optString("id") == id) return g }; return null }
    fun createPersona() {
        if(personaName.isBlank()||persona.isBlank()) { error="名字和人设为必填项"; return }
        if(personaApiKey.isBlank() && editingPersonaId.isBlank()) { error="请填写这个 AI 使用的 API Key"; return }
        if(editingPersonaId.isBlank() && personas().length()>=10) { error="最多创建 10 个聊天对象"; return }
        val existing = if(editingPersonaId.isNotBlank()) findPersona(editingPersonaId) else null
        if (existing != null && existing.optString("provider", "DeepSeek") != personaProvider && personaApiKey.isBlank()) { error="切换了 AI 服务商，请填写该服务商新的 API Key。"; return }
        if (existing != null) {
            existing.put("name",personaName).put("bio",personaBio).put("persona",persona)
                .put("gender",gender).put("relationship",relation).put("likes",likes).put("dislikes",dislikes).put("hobbies",hobbies)
            if(editAvatar.isNotBlank()) existing.put("avatar",editAvatar)
            if(personaApiKey.isNotBlank()) existing.put("apiKey",personaApiKey.trim())
            existing.put("provider", personaProvider).put("model", personaModel.trim().ifBlank { AiGateway.defaultModel(personaProvider) })
        } else {
            val created=JSONObject().put("id",System.currentTimeMillis().toString()).put("name",personaName).put("bio",personaBio).put("persona",persona)
                .put("gender",gender).put("relationship",relation).put("likes",likes).put("dislikes",dislikes).put("hobbies",hobbies)
                .put("avatar",editAvatar).put("apiKey",personaApiKey.trim()).put("provider",personaProvider).put("model",personaModel.trim().ifBlank { AiGateway.defaultModel(personaProvider) }).put("messages",JSONArray())
            personas().put(created)
        }
        persist();personaName="";personaBio="";persona="";personaApiKey="";personaProvider="DeepSeek";personaModel=AiGateway.defaultModel("DeepSeek");likes="";dislikes="";hobbies="";editAvatar="";editingPersonaId="";error="";page="home";tab="聊天"
    }

    LaunchedEffect(autoAiEnabled) {
        if (autoAiEnabled) {
            while (true) {
                kotlinx.coroutines.delay(60L * 60L * 1000L)
                val all = personas()
                for (i in 0 until all.length()) {
                    val ai = all.optJSONObject(i) ?: continue
                    try {
                        val history = JSONArray()
                        val recent = ai.optJSONArray("messages") ?: JSONArray()
                        for (j in maxOf(0, recent.length()-20) until recent.length()) { val item = recent.optJSONObject(j) ?: continue; if (item.optString("content") != "---") history.put(JSONObject().put("role", if(item.optString("role")=="assistant") "assistant" else "user").put("content", item.optString("content"))) }
                        history.put(JSONObject().put("role", "user").put("content", "请基于你和用户最近的对话、你的人设，写一条像你本人会发的自然朋友圈动态。只返回动态正文，不要编造现实中实际发生过但对话里没有的事情。"))
                        val sys = "你正在扮演 ${ai.optString("name")}。人设：${ai.optString("persona")}。喜欢：${ai.optString("likes")}。爱好：${ai.optString("hobbies")}。动态内容应该结合你最近与用户的对话，像真实社交动态一样自然；不得声称 AI 实际去了某处或做过未经对话支持的现实活动。"
                        val postText = AiGateway.reply(context, ai.optString("provider", "DeepSeek"), ai.optString("apiKey"), ai.optString("model", AiGateway.defaultModel(ai.optString("provider", "DeepSeek"))), sys, history)
                        val posts = data.optJSONArray("posts") ?: JSONArray().also { data.put("posts", it) }
                        posts.put(0, JSONObject().put("id", System.currentTimeMillis().toString()).put("author", ai.optString("name")).put("avatar", ai.optString("avatar")).put("content", postText).put("likes", 0).put("comments", JSONArray()).put("image", "").put("ai", true))
                        val msgs = ai.optJSONArray("messages") ?: JSONArray().also { ai.put("messages", it) }
                        msgs.put(JSONObject().put("role", "assistant").put("content", "我刚刚发了一条动态：$postText"))
                        LocalStore.save(context, data)
                    } catch (_: Exception) { }
                }
                data = LocalStore.load(context)
            }
        }
    }

    DisposableEffect(yandereEnabled) {
        if (!yandereEnabled) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lastMagnitude = SensorManager.GRAVITY_EARTH
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                val magnitude = kotlin.math.sqrt(x*x + y*y + z*z)
                val delta = kotlin.math.abs(magnitude - lastMagnitude)
                lastMagnitude = magnitude
                val now = System.currentTimeMillis()
                if (delta > 12f && now - lastShakeAt > 450L) {
                    lastShakeAt = now
                    shakeCount++
                    if (shakeCount >= 3) {
                        yandereEnabled = false
                        data.put("yandereEnabled", false)
                        LocalStore.save(context, data)
                        revision++
                        yandereFlash = false
                        shakeCount = 0
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (accelerometer != null) manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }

    fun askAisToReactToPost(post: JSONObject) {
        if (!data.optBoolean("aiMomentsEnabled", true)) return
        scope.launch {
            val all = personas()
            for (i in 0 until all.length()) {
                val ai = all.optJSONObject(i) ?: continue
                try {
                    val history = JSONArray()
                    val prompt = "请像这个 AI 角色本人一样，评价用户刚发布的朋友圈。正文：${post.optString("content")}。${if(post.optString("image").isNotBlank()) "图片附件：[[IMAGE:${post.optString("image")}]]。请实际查看图片。" else ""} 你可以选择点赞或不点赞，也可以选择留一条符合人设的简短评论。只输出 JSON：{\"like\":true或false,\"comment\":\"评论内容，不评论就空字符串\"}。不要解释 JSON 外的内容。"
                    history.put(JSONObject().put("role","user").put("content",prompt))
                    val system = "你正在扮演 ${ai.optString("name")}。人设：${ai.optString("persona")}。喜好：${ai.optString("likes")}。讨厌：${ai.optString("dislikes")}。必须诚实表达喜欢或不喜欢，不需要为了讨好用户而点赞。评论要自然、简短，并符合人设。"
                    val answer = AiGateway.reply(context,ai.optString("provider","DeepSeek"),ai.optString("apiKey"),ai.optString("model",AiGateway.defaultModel(ai.optString("provider","DeepSeek"))),system,history)
                    val jsonText = answer.substringAfter("{", "").let { if(it.isBlank()) "" else "{$it".substringBeforeLast("}") + "}" }
                    val result = try { JSONObject(jsonText) } catch (_: Exception) { JSONObject() }
                    val liked = result.optBoolean("like", false)
                    val likedBy = post.optJSONArray("likedBy") ?: JSONArray().also { post.put("likedBy",it) }
                    val already = (0 until likedBy.length()).any { likedBy.optString(it)==ai.optString("id") }
                    if (liked && !already) { likedBy.put(ai.optString("id")); post.put("likes",post.optInt("likes")+1) }
                    val comment = result.optString("comment").trim().take(240)
                    if (comment.isNotBlank() && !comment.equals("null",true)) {
                        val comments = post.optJSONArray("comments") ?: JSONArray().also { post.put("comments",it) }
                        comments.put(JSONObject().put("id",UUID.randomUUID().toString()).put("author",ai.optString("name")).put("avatar",ai.optString("avatar")).put("content",comment).put("reply",""))
                    }
                    persist()
                } catch (_: Exception) { /* One unavailable AI must not block other friends. */ }
            }
        }
    }

    fun sendGroupMessage(group: JSONObject, inputValue: String) {
        val msg = inputValue.trim()
        if (msg.isBlank() || busy) return
        val messages = group.optJSONArray("messages") ?: JSONArray().also { group.put("messages", it) }
        messages.put(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "user").put("speakerName", myName).put("speakerAvatar", data.optString("myAvatar")).put("content", msg).put("read", true))
        persist()
        scope.launch {
            busy = true
            error = ""
            try {
                val ids = group.optJSONArray("memberIds") ?: JSONArray()
                val valid = (0 until ids.length()).mapNotNull { findPersona(ids.optString(it)) }
                if (valid.isEmpty()) {
                    messages.put(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("speakerName", "系统").put("content", "群里没有可用的 AI 成员。"))
                } else {
                    for ((index, ai) in valid.withIndex()) {
                        // Each AI gets its own request. A failed member must not stop the other members replying.
                        messages.put(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("speakerName", "正在输入…").put("content", "---"))
                        persist()
                        try {
                            val history = JSONArray()
                            val start = maxOf(0, messages.length() - 26)
                            for (i in start until messages.length() - 1) {
                                val item = messages.optJSONObject(i) ?: continue
                                if (item.optString("content") == "---") continue
                                val role = if (item.optString("role") == "assistant") "assistant" else "user"
                                val content = if (role == "assistant") "[群成员 ${item.optString("speakerName", "AI")} 说] ${item.optString("content")}" else item.optString("content")
                                history.put(JSONObject().put("role", role).put("content", content))
                            }
                            val otherNames = valid.joinToString("、") { it.optString("name") }
                            val system = "你是群聊中的 AI 角色 ${ai.optString("name")}。人设：${ai.optString("persona")}。关系：${ai.optString("relationship")}。喜欢：${ai.optString("likes")}。爱好：${ai.optString("hobbies")}。群名：${group.optString("name")}。其他成员：$otherNames。请读懂用户和其他 AI 的上下文，按人设自然发一条简短消息；可以偶尔回应另一位 AI，但每轮只发一条，不要无限争论。你是 AI，不要声称现实中不存在的经历。"
                            val answer = AiGateway.reply(context, ai.optString("provider", "DeepSeek"), ai.optString("apiKey"), ai.optString("model", AiGateway.defaultModel(ai.optString("provider", "DeepSeek"))), system, history)
                            val response = JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("speakerId", ai.optString("id")).put("speakerName", ai.optString("name")).put("speakerAvatar", ai.optString("avatar")).put("content", answer)
                            messages.put(messages.length() - 1, response)
                        } catch (memberError: Exception) {
                            messages.put(messages.length() - 1, JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("speakerName", ai.optString("name")).put("content", "本条回复失败：${memberError.message ?: "请检查该 AI 的 API 设置"}"))
                        }
                        persist()
                    }
                }
                persist()
            } catch (e: Exception) {
                error = e.message ?: "群聊请求失败"
                messages.put(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("speakerName", "系统").put("content", "群聊处理失败：$error"))
                persist()
            } finally { busy = false }
        }
    }

    fun sendDirectChatMessage(p: JSONObject, inputValue: String) {
        val msg = inputValue.trim()
        if (msg.isBlank() || busy) return
        lastInteractionAt = System.currentTimeMillis()
        val arr = p.optJSONArray("messages") ?: JSONArray().also { p.put("messages", it) }
        val pay = Regex("^\\[\\[PAYMENT:(redpacket|transfer):([0-9]+(?:\\.[0-9]{1,2})?):(.*?)]]$", RegexOption.DOT_MATCHES_ALL).find(msg)
        if (pay != null) {
            val amountToSend = pay.groupValues[2].toDoubleOrNull()
            if (amountToSend == null || amountToSend <= 0.0) { error = "金额无效，请重新输入。"; return }
            if (amountToSend > coinBalance + 0.000001) { error = "金币不足：当前有 ${formatCoinAmount(coinBalance)} 枚，无法发送 ${formatCoinAmount(amountToSend)} 枚。"; return }
            coinBalance = (coinBalance - amountToSend).coerceAtLeast(0.0)
            data.put("coins", coinBalance)
        }
        val isImage = msg.startsWith("[[IMAGE:")
        val userMessage = JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "user")
        when {
            pay != null -> userMessage.put("type", "payment").put("paymentType", pay.groupValues[1]).put("amount", pay.groupValues[2]).put("note", pay.groupValues[3]).put("status", "pending").put("content", pay.groupValues[3])
            isImage -> userMessage.put("type", "photo").put("content", msg)
            else -> userMessage.put("content", msg)
        }
        arr.put(userMessage)
        val waiting = JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("content", "---")
        arr.put(waiting)
        composer = ""
        persist()
        if (soundEnabled) try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75).startTone(ToneGenerator.TONE_PROP_ACK, 100) } catch (_: Exception) { }
        scope.launch {
            busy = true; error = ""
            try {
                val history = JSONArray()
                val start = maxOf(0, arr.length() - (memoryLimit + 2))
                for (i in start until arr.length() - 1) {
                    val m = arr.optJSONObject(i) ?: continue
                    if (m.optString("content") == "---") continue
                    val content = when (m.optString("type")) {
                        "payment" -> "[应用内虚拟${if (m.optString("paymentType") == "redpacket") "红包" else "转账"}，金额 ¥${m.optString("amount")}，备注：${m.optString("note")}，当前状态：${m.optString("status", "pending")}。这不涉及真实支付。请按你的人设决定是否接受。]"
                        "photo" -> "${m.optString("content").substringBefore("[[IMAGE:").trim()} [[IMAGE:${Regex("\\[\\[IMAGE:(.*?)]]", RegexOption.DOT_MATCHES_ALL).find(m.optString("content"))?.groupValues?.getOrNull(1).orEmpty()}]]"
                        else -> m.optString("content")
                    }
                    history.put(JSONObject().put("role", m.optString("role")).put("content", content))
                }
                val system = "你正在扮演 AI 角色。名字：${p.optString("name")}。简介：${p.optString("bio")}。人设：${p.optString("persona")}。性别：${p.optString("gender")}。与用户关系：${p.optString("relationship")}。喜欢：${p.optString("likes")}。讨厌：${p.optString("dislikes")}。爱好：${p.optString("hobbies")}。请遵循人设并用用户使用的语言自然回复。你是 AI，不要声称现实中不存在的经历。若消息包含 [[IMAGE:...]]，那是真实图片附件，请根据图片可见内容回答；如果模型不支持图片，接口失败时应由应用显示错误，不要假装看过。用户发来虚拟红包/转账时，请以 [收下] 或 [拒绝] 开头，并给出自然简短理由。若你想向用户发送应用内虚拟红包或转账，可在回复末尾使用精确格式 [红包|金额|备注] 或 [转账|金额|备注]，金额仅为本地 UI 示例，不能涉及真实支付；不要为了格式而强行发送。记忆参考最近 $memoryLimit 条消息。"
                val targetReplyCount = if (multiReplyEnabled) (1..multiReplyCount).random() else 1
                val reply = AiGateway.reply(context, p.optString("provider", "DeepSeek"), p.optString("apiKey"), p.optString("model", AiGateway.defaultModel(p.optString("provider", "DeepSeek"))), system, history)
                val aiPayment = Regex("\\[(红包|转账)\\|([0-9]+(?:\\.[0-9]{1,2})?)\\|([^]]*)]", RegexOption.DOT_MATCHES_ALL).find(reply)
                val decisionCleanReply = reply.replace(Regex("^\\s*\\[(?:收下|拒绝)]\\s*"), "").trim()
                val cleanReply = if (aiPayment != null) decisionCleanReply.replace(aiPayment.value, "").trim() else decisionCleanReply
                for (i in 0 until arr.length()) { val item = arr.optJSONObject(i) ?: continue; if (item.optString("role") == "user") item.put("read", true) }
                val statusDecision = when {
                    reply.trimStart().startsWith("[收下]") -> "accepted"
                    reply.trimStart().startsWith("[拒绝]") -> "rejected"
                    else -> ""
                }
                if (statusDecision.isNotBlank() && pay != null) {
                    for (i in arr.length() - 2 downTo 0) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (item.optString("type") == "payment" && item.optString("role") == "user" && item.optString("status", "pending") == "pending") {
                            item.put("status", statusDecision)
                            if (statusDecision == "rejected") {
                                val refund = item.optString("amount").toDoubleOrNull() ?: 0.0
                                coinBalance = (coinBalance + refund).coerceAtMost(999999999.0)
                                data.put("coins", coinBalance)
                            }
                            break
                        }
                    }
                }
                val first = JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant")
                if (aiPayment != null) first.put("type", "payment").put("paymentType", if (aiPayment.groupValues[1] == "红包") "redpacket" else "transfer").put("amount", aiPayment.groupValues[2]).put("note", aiPayment.groupValues[3]).put("status", "pending").put("content", cleanReply)
                else first.put("content", cleanReply)
                arr.put(arr.length() - 1, first); persist()
                if (soundEnabled) try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75).startTone(ToneGenerator.TONE_PROP_BEEP2, 130) } catch (_: Exception) { }
                for (replyIndex in 1 until targetReplyCount) {
                    val followHistory = JSONArray()
                    val followStart = maxOf(0, arr.length() - (memoryLimit + 1))
                    for (j in followStart until arr.length()) {
                        val item = arr.optJSONObject(j) ?: continue
                        if (item.optString("content") != "---") followHistory.put(JSONObject().put("role", item.optString("role")).put("content", item.optString("content")))
                    }
                    followHistory.put(JSONObject().put("role", "user").put("content", "如果还有自然想补充的话，再发一条简短消息；不要重复刚才内容。"))
                    arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("content", "---")); persist()
                    val extra = AiGateway.reply(context, p.optString("provider", "DeepSeek"), p.optString("apiKey"), p.optString("model", AiGateway.defaultModel(p.optString("provider", "DeepSeek"))), system, followHistory)
                    arr.put(arr.length() - 1, JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("content", extra)); persist()
                }
            } catch (e: Exception) {
                val last = arr.optJSONObject(arr.length() - 1)
                if (last != null && last.optString("content") == "---") arr.put(arr.length() - 1, JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("content", "请求失败：${e.message ?: "请检查 API 配置"}"))
                persist(); error = e.message ?: "请求失败"
            } finally { busy = false }
        }
    }

    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = when(fontChoice) {
        "圆润" -> FontFamily.SansSerif
        "衬线" -> FontFamily.Serif
        "等宽" -> FontFamily.Monospace
        "自定义" -> try { FontFamily(android.graphics.Typeface.createFromFile(data.optString("customFontPath"))) } catch (_: Exception) { FontFamily.Default }
        else -> FontFamily.Default
    })) {
    Surface(Modifier.fillMaxSize().then(if (revisionSnapshot >= 0) Modifier else Modifier),color=if(yandereEnabled) Color(0xFF090909) else Page) {
        Box(Modifier.fillMaxSize()) {
        if(data.optString("background").isNotBlank() && page != "chat") PickedPhoto(data.optString("background"),Modifier.fillMaxSize().graphicsLayer {
            alpha = (1f - backgroundAlpha).coerceIn(.15f, 1f)
            if (Build.VERSION.SDK_INT >= 31 && backgroundBlur > 0f) {
                renderEffect = RenderEffect.createBlurEffect(backgroundBlur, backgroundBlur, Shader.TileMode.CLAMP).asComposeRenderEffect()
            }
        },rounded=false)
        AnimatedContent(page,transitionSpec={fadeIn(tween(220)) togetherWith fadeOut(tween(180))},label="pages") { screen ->
            when(screen) {
                "createChoice" -> CreateChoiceScreen(
                    onImport={importBackupMode="personas";importBackupLauncher.launch(arrayOf("application/zip","application/octet-stream"))},
                    onCustom={editingPersonaId="";personaName="";personaBio="";persona="";personaApiKey="";personaProvider="DeepSeek";personaModel=AiGateway.defaultModel("DeepSeek");page="create"},
                    onBack={page="home"})
                "groupCreate" -> GroupCreateScreen(
                    aiList=(0 until personas().length()).mapNotNull { personas().optJSONObject(it) },
                    groupName=groupName,onGroupName={groupName=it},
                    onBack={page="home"},onCreate={name, ids ->
                        if(name.isBlank() || ids.isEmpty()) { error="请填写群聊名称，并至少选择一个 AI" }
                        else { groups().put(JSONObject().put("id",UUID.randomUUID().toString()).put("name",name.trim()).put("memberIds",JSONArray(ids)).put("messages",JSONArray()));groupName="";persist();chatListMode="群聊";page="home" }
                    })
                "groupChat" -> {
                    val group = findGroup(currentGroupId)
                    if (group == null) page="home" else GroupChatScreen(group=group,revision=revisionSnapshot,myName=myName,myAvatar=data.optString("myAvatar"),busy=busy,error=error,glassUi=glassUi,autoSpeakAi=aiAutoSpeakEnabled,voiceGender=voiceGender,voicePitch=voicePitch,voiceRate=voiceRate,voiceName=voiceName,onBack={page="home"},findPersona={ id -> findPersona(id) },onSend={ text -> sendGroupMessage(group,text) })
                }
                "create" -> PersonaEditor(
                    editing=editingPersonaId.isNotBlank(),
                    provider=personaProvider,onProvider={newProvider->if(newProvider!=personaProvider)personaApiKey="";personaProvider=newProvider;personaModel=AiGateway.defaultModel(newProvider)},model=personaModel,onModel={personaModel=it},
                    name=personaName,onName={personaName=it},bio=personaBio,onBio={personaBio=it},persona=persona,onPersona={persona=it},apiKey=personaApiKey,onApiKey={personaApiKey=it},
                    gender=gender,onGender={gender=it},relation=relation,onRelation={relation=it},likes=likes,onLikes={likes=it},
                    dislikes=dislikes,onDislikes={dislikes=it},hobbies=hobbies,onHobbies={hobbies=it},avatar=editAvatar,
                    onPickAvatar={selectedImageTarget="personaAvatar";imagePicker.launch("image/*")},error=error,onBack={page="home"},onCreate={createPersona()})
                "chat" -> {
                    val p=findPersona(currentId)
                    if(p==null) { page="home" } else ChatScreen(p=p,revision=revisionSnapshot,myName=myName,myAvatar=data.optString("myAvatar"),myTextColor=myTextColor,aiTextColor=aiTextColor,autoSpeakAi=aiAutoSpeakEnabled,coins=coinBalance,composer=composer,onComposer={composer=it;lastInteractionAt=System.currentTimeMillis()},busy=busy,error=error,backgroundUri=data.optString("background"),bubbleBackgroundUri=bubbleBackgroundUri,glassUi=glassUi,bubbleHex=bubbleHex,myBubbleHex=myBubbleHex,bubbleShape=bubbleShape,backgroundAlpha=backgroundAlpha,inputHint=inputHint,voiceGender=voiceGender,voicePitch=voicePitch,voiceRate=voiceRate,voiceName=voiceName,onVoiceSettingsChange={ g, pitch, rate, name -> voiceGender=g;voicePitch=pitch;voiceRate=rate;voiceName=name;data.put("voiceGender",g).put("voicePitch",pitch.toDouble()).put("voiceRate",rate.toDouble()).put("voiceName",name);persist() },onBack={page="home"},onQuote={quote -> composer=quote},onDeleteMessage={index -> val messages=p.optJSONArray("messages") ?: JSONArray();messages.remove(index);persist()},onPickImage={selectedImageTarget="chatImage";imagePicker.launch("image/*")},onPickGif={gifPicker.launch("image/gif")},onPaymentDecision={messageId, accepted ->
                        val messages = p.optJSONArray("messages") ?: JSONArray()
                        for (i in 0 until messages.length()) {
                            val item = messages.optJSONObject(i) ?: continue
                            if (item.optString("id") == messageId && item.optString("role") == "assistant") {
                                if (item.optString("status", "pending") == "pending") {
                                    item.put("status", if (accepted) "accepted" else "rejected")
                                    if (accepted && item.optString("type") == "payment") {
                                        val incomingAmount = item.optString("amount").toDoubleOrNull() ?: 0.0
                                        coinBalance = (coinBalance + incomingAmount).coerceAtMost(999999999.0)
                                        data.put("coins", coinBalance)
                                    }
                                }
                                break
                            }
                        }
                        persist()
                        val decisionText = if (accepted) "我收下了你发来的应用内虚拟礼物，谢谢你。" else "我暂时不收这份应用内虚拟礼物，但谢谢你的心意。"
                        sendDirectChatMessage(p, decisionText)
                    },
                    onSend={ msgInput -> sendDirectChatMessage(p, msgInput) })
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha=if(glassUi) .76f else .98f),RoundedCornerShape(bottomStart=26.dp,bottomEnd=26.dp)).border(1.dp,Color.White.copy(alpha=.85f),RoundedCornerShape(bottomStart=26.dp,bottomEnd=26.dp)).padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clickable { tab="设置";advancedSettingsOpen=false },contentAlignment=Alignment.Center){ Avatar(data.optString("myAvatar"),myName,40) }
                        Box(Modifier.weight(1f),contentAlignment=Alignment.Center) { Column(horizontalAlignment=Alignment.CenterHorizontally) { Text(when(tab){"聊天"->"消息";"动态"->"朋友圈";else->if(advancedSettingsOpen) "高级设置" else "我"},fontSize=22.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text(if(tab=="聊天") "iMessage · AI 好友" else if(tab=="动态") "记录生活的每一刻" else "DeepLine · 私人空间",fontSize=11.sp,color=Muted) } }
                        if(tab=="聊天") {
                            IconButton(onClick={page="groupCreate"},modifier=Modifier.size(40.dp).background(Color.White.copy(alpha=.95f),CircleShape).border(1.dp,Color.White,CircleShape)){Icon(Icons.Default.Groups,null,tint=Color(0xFF0A84FF),modifier=Modifier.size(22.dp))}
                            Spacer(Modifier.width(3.dp))
                            IconButton(onClick={page="createChoice"},modifier=Modifier.size(40.dp).background(Color(0xFF0A84FF).copy(alpha=.10f),CircleShape)){Icon(Icons.Default.Add,null,tint=Color(0xFF0A84FF),modifier=Modifier.size(25.dp))}
                        }
                        if(tab=="设置") IconButton(onClick={advancedSettingsOpen=!advancedSettingsOpen},modifier=Modifier.size(40.dp).background(Color.White.copy(alpha=.95f),CircleShape)){Icon(if(advancedSettingsOpen) Icons.Default.ArrowBack else Icons.Default.Settings,null,tint=Color(0xFF0A84FF),modifier=Modifier.size(22.dp))}
                    }
                    when(tab) {
                        "聊天" -> {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.clip(RoundedCornerShape(40.dp)).background(Color.White.copy(alpha = .88f)).border(1.dp, Color.White, RoundedCornerShape(40.dp)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🪙", fontSize = 16.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("金币", fontSize = 12.sp, color = Muted)
                                    Spacer(Modifier.width(6.dp))
                                    Text(formatCoinAmount(coinBalance), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF9B741D))
                                    Spacer(Modifier.width(8.dp))
                                    Text("每秒 +1", fontSize = 10.sp, color = Color(0xFF7F8995))
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp, vertical=4.dp).background(Color.White.copy(alpha=.76f), RoundedCornerShape(22.dp)).padding(4.dp), horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                listOf("私聊", "群聊").forEach { mode ->
                                    Box(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (chatListMode == mode) Color(0xFF0A84FF) else Color.Transparent).clickable { chatListMode = mode }.padding(vertical=9.dp), contentAlignment=Alignment.Center) {
                                        Text(mode, color=if(chatListMode==mode) Color.White else Muted, fontWeight=FontWeight.SemiBold, fontSize=13.sp)
                                    }
                                }
                            }
                            if (chatListMode == "私聊") {
                                val a = personas()
                                LazyColumn(Modifier.weight(1f), contentPadding=PaddingValues(horizontal=14.dp, vertical=2.dp)) {
                                    if (a.length() == 0) item { EmptyState("还没有聊天对象", "点击右上角 + 创建第一个 AI") }
                                    items((0 until a.length()).map { a.getJSONObject(it) }, key = { it.optString("id") + "-r" + revisionSnapshot }) { p ->
                                        var menuOpen by remember(p.optString("id"), revisionSnapshot) { mutableStateOf(false) }
                                        Row(Modifier.fillMaxWidth().padding(vertical=5.dp).background(Brush.linearGradient(listOf(Color.White.copy(alpha=if(glassUi) .82f else 1f), Color(0xFFF1F5FF).copy(alpha=if(glassUi) .82f else 1f))), RoundedCornerShape(25.dp)).border(1.dp,Color.White,RoundedCornerShape(25.dp)).animateContentSize().combinedClickable(onClick={currentId=p.optString("id");page="chat"},onLongClick={menuOpen=true}).padding(horizontal=14.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically) {
                                            Avatar(p.optString("avatar"),p.optString("name"),54)
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Row(verticalAlignment=Alignment.CenterVertically) { Text(p.optString("name"),fontWeight=FontWeight.SemiBold,fontSize=16.sp,color=Ink); Spacer(Modifier.width(7.dp)); Text(p.optString("provider","DeepSeek"),fontSize=9.sp,color=Color(0xFF0A84FF),modifier=Modifier.clip(CircleShape).background(Color(0xFF0A84FF).copy(alpha=.09f)).padding(horizontal=7.dp,vertical=3.dp)) }
                                                val lastMessage = p.optJSONArray("messages")?.let { ms -> if (ms.length()>0) ms.optJSONObject(ms.length()-1)?.let { if(it.optString("type")=="payment") "${if(it.optString("paymentType")=="redpacket") "红包" else "转账"} · ¥${it.optString("amount")}" else if(it.optString("type")=="photo") "[图片]" else it.optString("content") } else null }
                                                Text(lastMessage?.takeIf { it.isNotBlank() && it!="---" } ?: p.optString("bio").ifBlank{p.optString("persona")}, maxLines=1, color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=4.dp))
                                            }
                                            Box {
                                                IconButton(onClick={menuOpen=true},modifier=Modifier.size(35.dp)){Icon(Icons.Default.MoreHoriz,null,tint=Muted)}
                                                DropdownMenu(expanded=menuOpen,onDismissRequest={menuOpen=false}) {
                                                    DropdownMenuItem(text={Text("编辑 AI")},leadingIcon={Icon(Icons.Default.Edit,null)},onClick={menuOpen=false;editingPersonaId=p.optString("id");personaName=p.optString("name");personaBio=p.optString("bio");persona=p.optString("persona");gender=p.optString("gender","其他");relation=p.optString("relationship","朋友");likes=p.optString("likes");dislikes=p.optString("dislikes");hobbies=p.optString("hobbies");editAvatar=p.optString("avatar");personaApiKey="";personaProvider=p.optString("provider","DeepSeek");personaModel=p.optString("model",AiGateway.defaultModel(p.optString("provider","DeepSeek")));error="";page="create"})
                                                    DropdownMenuItem(text={Text("导出完整数据 ZIP")},leadingIcon={Icon(Icons.Default.FolderOpen,null)},onClick={menuOpen=false;exportBackupLauncher.launch("DeepLine-backup.zip")})
                                                    DropdownMenuItem(text={Text("删除 AI",color=Color(0xFFD64255))},leadingIcon={Icon(Icons.Default.Delete,null,tint=Color(0xFFD64255))},onClick={menuOpen=false;val keep=JSONArray();for(i in 0 until personas().length()){val item=personas().optJSONObject(i);if(item!=null&&item.optString("id")!=p.optString("id"))keep.put(item)};data.put("personas",keep);persist()})
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                val allGroups = groups()
                                LazyColumn(Modifier.weight(1f), contentPadding=PaddingValues(horizontal=14.dp, vertical=2.dp)) {
                                    if (allGroups.length()==0) item { EmptyState("还没有群聊", "点右上角的群组按钮，把你创建的 AI 拉进群") }
                                    items((0 until allGroups.length()).mapNotNull { allGroups.optJSONObject(it) }, key={it.optString("id")+"-r"+revisionSnapshot}) { g ->
                                        val members=g.optJSONArray("memberIds")?:JSONArray()
                                        Row(Modifier.fillMaxWidth().padding(vertical=5.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha=.84f)).border(1.dp,Color.White,RoundedCornerShape(24.dp)).clickable { currentGroupId=g.optString("id");page="groupChat" }.padding(15.dp),verticalAlignment=Alignment.CenterVertically) {
                                            Box(Modifier.size(54.dp).clip(CircleShape).background(Color(0xFF0A84FF).copy(alpha=.12f)),contentAlignment=Alignment.Center){Icon(Icons.Default.Groups,null,tint=Color(0xFF0A84FF),modifier=Modifier.size(28.dp))}
                                            Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(g.optString("name","群聊"),fontWeight=FontWeight.SemiBold,color=Ink,fontSize=16.sp);val last= g.optJSONArray("messages")?.let{ms->if(ms.length()>0)ms.optJSONObject(ms.length()-1)?.optString("content") else null};Text(last?.takeIf{it.isNotBlank()&&it!="---"}?:"${members.length()} 位 AI 成员",maxLines=1,fontSize=13.sp,color=Muted)}
                                            Text("${members.length()} AI",fontSize=11.sp,color=Color(0xFF0A84FF),modifier=Modifier.clip(CircleShape).background(Color(0xFF0A84FF).copy(alpha=.09f)).padding(horizontal=8.dp,vertical=5.dp))
                                        }
                                    }
                                }
                            }
                        }
                        "动态" -> {
                            Column(Modifier.weight(1f).padding(horizontal=16.dp)) {
                                Box(Modifier.fillMaxWidth().height(234.dp)) {
                                    Box(Modifier.fillMaxWidth().height(194.dp).clip(RoundedCornerShape(22.dp)).clickable { selectedImageTarget="momentsCover";imagePicker.launch("image/*") }) {
                                        if(data.optString("momentsCover").isNotBlank()) PickedPhoto(data.optString("momentsCover"),Modifier.fillMaxSize(),rounded=false)
                                        else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2E4B83),Color(0xFF8CB5D9),Color(0xFFE7DFF9)))))
                                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.48f)))))
                                        Text("点击更换朋友圈背景",fontSize=11.sp,color=Color.White.copy(alpha=.92f),modifier=Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha=.20f)).padding(horizontal=10.dp,vertical=6.dp))
                                    }
                                    Box(Modifier.align(Alignment.BottomCenter).size(76.dp).clip(CircleShape).background(Color.White).border(1.dp,Color.White,CircleShape).padding(4.dp)) { Avatar(data.optString("myAvatar"),myName,68) }
                                }
                                Text(myName,fontWeight=FontWeight.Bold,fontSize=20.sp,color=Ink,modifier=Modifier.fillMaxWidth().padding(top=1.dp,bottom=2.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                                Text(myBio.ifBlank { "记录生活的每一刻" },fontSize=12.sp,color=Muted,modifier=Modifier.fillMaxWidth().padding(bottom=10.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                                Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha=if(glassUi) .86f else 1f), RoundedCornerShape(24.dp)).animateContentSize().padding(14.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically) { Avatar(data.optString("myAvatar"),myName,42); Spacer(Modifier.width(10.dp)); Text("分享你的日常",fontWeight=FontWeight.SemiBold,color=Ink) }
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(feedText,{feedText=it},placeholder={Text("今天有什么新鲜事？")},modifier=Modifier.fillMaxWidth(),minLines=2,shape=RoundedCornerShape(18.dp))
                                    if(postImage.isNotBlank()) { Spacer(Modifier.height(8.dp)); PickedPhoto(postImage, Modifier.fillMaxWidth().height(150.dp)); TextButton(onClick={postImage=""}) { Text("移除图片") } }
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                        TextButton(onClick={selectedImageTarget="postImage";imagePicker.launch("image/*")}) { Icon(Icons.Default.Image,null); Spacer(Modifier.width(5.dp)); Text("添加图片") }
                                        Button(onClick={
                                            if(feedText.isNotBlank()||postImage.isNotBlank()){
                                                val posts=data.optJSONArray("posts")?:JSONArray().also{data.put("posts",it)}
                                                val published=JSONObject().put("id",UUID.randomUUID().toString()).put("author",myName).put("avatar",data.optString("myAvatar")).put("content",feedText).put("image",postImage).put("likes",0).put("likedBy",JSONArray()).put("comments",JSONArray()).put("ai",false)
                                                posts.put(0,published);feedText="";postImage="";persist();askAisToReactToPost(published)
                                            }
                                        },shape=RoundedCornerShape(22.dp)){Text("发布动态")}
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                LazyColumn(contentPadding=PaddingValues(bottom=12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                    items((0 until (data.optJSONArray("posts")?.length()?:0)).map{data.getJSONArray("posts").getJSONObject(it)},key={it.optString("id") + "-r" + revisionSnapshot}){post->
                                        Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha=if(glassUi) .86f else 1f),RoundedCornerShape(22.dp)).animateContentSize().padding(16.dp)) {
                                            Row(verticalAlignment=Alignment.CenterVertically) { Avatar(post.optString("avatar"),post.optString("author"),42); Spacer(Modifier.width(10.dp)); Column { Text(post.optString("author"),fontWeight=FontWeight.Bold,color=Ink); Text(if(post.optBoolean("ai")) "AI 好友" else "刚刚",fontSize=11.sp,color=Muted) } }
                                            if(post.optString("content").isNotBlank()) Text(post.optString("content"),modifier=Modifier.padding(top=12.dp),fontSize=15.sp,color=Ink)
                                            if(post.optString("image").isNotBlank()) { Spacer(Modifier.height(10.dp)); PickedPhoto(post.optString("image"), Modifier.fillMaxWidth().height(190.dp)) }
                                            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(top=8.dp)) {
                                                TextButton(onClick={post.put("likes",post.optInt("likes")+1);persist()}) { Icon(Icons.Default.FavoriteBorder,null,tint=Color(0xFFFF6680)); Spacer(Modifier.width(4.dp)); Text("${post.optInt("likes")}") }
                                                TextButton(onClick={commentPostId=post.optString("id")}) { Icon(Icons.Default.ChatBubbleOutline,null,tint=Muted); Spacer(Modifier.width(4.dp)); Text("评论 ${post.optJSONArray("comments")?.length()?:0}") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> LazyColumn(Modifier.weight(1f).padding(horizontal=16.dp)) {
                            item {
                                if(!advancedSettingsOpen) {
                                Text("个人资料",fontWeight=FontWeight.Bold,fontSize=18.sp,modifier=Modifier.padding(vertical=10.dp))
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Avatar(data.optString("myAvatar"),myName,68)
                                    Spacer(Modifier.width(12.dp))
                                    OutlinedButton(onClick={selectedImageTarget="myAvatar";imagePicker.launch("image/*")}){Text("从相册选择头像")}
                                }
                                OutlinedTextField(myName,{myName=it},label={Text("名字")},modifier=Modifier.fillMaxWidth())
                                OutlinedTextField(myBio,{myBio=it},label={Text("简介")},modifier=Modifier.fillMaxWidth(),minLines=2)
                                Button(onClick={saveData()},modifier=Modifier.fillMaxWidth().padding(top=8.dp)){Text("保存资料")}
                                } else {
                                Spacer(Modifier.height(4.dp));Text("外观与聊天偏好",fontWeight=FontWeight.Bold,fontSize=18.sp,modifier=Modifier.padding(vertical=8.dp))
                                Spacer(Modifier.height(8.dp));Text("AI 接口说明",fontWeight=FontWeight.Bold,fontSize=18.sp)
                                Text("创建 AI 时单独填写 API Key。密钥仅保存在本机应用数据中，不会上传到 GitHub 或任何自建后端。请勿把含密钥的备份分享给他人。",fontSize=13.sp,color=Color.Gray)
                                Spacer(Modifier.height(18.dp));Text("主题颜色",fontWeight=FontWeight.Bold,fontSize=18.sp)
                                Text("拖动色相、饱和度和明度滑块，预览后应用",fontSize=12.sp,color=Muted)
                                ColorSpectrumPicker(selected=themeHex,onSelect={value->themeHex=value;data.put("theme",value);persist();try{onThemeChange(Color(android.graphics.Color.parseColor(value)))}catch(_:Exception){}})
                                Spacer(Modifier.height(18.dp));Text("界面外观",fontWeight=FontWeight.Bold,fontSize=18.sp)
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("玻璃拟态 UI",fontWeight=FontWeight.SemiBold);Text("半透明卡片与柔和层次",fontSize=12.sp,color=Muted)};Switch(checked=glassUi,onCheckedChange={glassUi=it;data.put("glassUi",it);persist()})}
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("发送/接收音效",fontWeight=FontWeight.SemiBold);Text("轻提示音，可随时关闭",fontSize=12.sp,color=Muted)};Switch(checked=soundEnabled,onCheckedChange={soundEnabled=it;data.put("soundEnabled",it);persist()})}
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("AI 消息自动朗读",fontWeight=FontWeight.SemiBold);Text("默认开启；AI 在私聊和群聊发来新消息时自动用所选中文音色朗读",fontSize=12.sp,color=Muted)};Switch(checked=aiAutoSpeakEnabled,onCheckedChange={aiAutoSpeakEnabled=it;data.put("aiAutoSpeakEnabled",it);persist()})}
                                Text("全局字体",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp))
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("系统","圆润","衬线","等宽").forEach{opt->FilterChip(selected=fontChoice==opt,onClick={fontChoice=opt;data.put("fontChoice",opt);persist()},label={Text(opt)})}}
                                Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                                    Column(Modifier.weight(1f)) { Text("自定义 TTF 字体",fontWeight=FontWeight.SemiBold); Text("导入后全局文本统一使用",fontSize=12.sp,color=Muted) }
                                    OutlinedButton(onClick={fontPicker.launch(arrayOf("font/ttf","application/x-font-ttf","application/octet-stream"))}) { Text("选择文件") }
                                }
                                Text("AI 消息文字颜色",fontSize=13.sp,fontWeight=FontWeight.Medium)
                                ColorSpectrumPicker(selected=aiTextColor,onSelect={aiTextColor=it;data.put("aiTextColor",it);persist()})
                                Text("我的消息文字颜色",fontSize=13.sp,fontWeight=FontWeight.Medium)
                                ColorSpectrumPicker(selected=myTextColor,onSelect={myTextColor=it;data.put("myTextColor",it);persist()})
                                OutlinedTextField(appTitle,{appTitle=it;data.put("appTitle",it);persist()},label={Text("聊天首页标题")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                                OutlinedTextField(inputHint,{inputHint=it;data.put("inputHint",it);persist()},label={Text("聊天输入框提示文字")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                                Text("聊天气泡样式",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp))
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("柔和圆角","胶囊","方圆").forEach{opt->FilterChip(selected=bubbleShape==opt,onClick={bubbleShape=opt;data.put("bubbleShape",opt);persist()},label={Text(opt)})}}
                                Text("AI 气泡颜色",fontSize=13.sp,fontWeight=FontWeight.Medium)
                                ColorSpectrumPicker(selected=bubbleHex,onSelect={bubbleHex=it;data.put("bubbleColor",it);persist()})
                                Text("我的气泡颜色",fontSize=13.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(top=6.dp))
                                ColorSpectrumPicker(selected=myBubbleHex,onSelect={myBubbleHex=it;data.put("myBubbleColor",it);persist()})
                                OutlinedButton(onClick={selectedImageTarget="bubbleBackground";imagePicker.launch("image/*")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Wallpaper,null);Spacer(Modifier.width(8.dp));Text(if(bubbleBackgroundUri.isBlank()) "设置气泡背景图" else "更换气泡背景图")}
                                Text("背景图透明度 ${(backgroundAlpha*100).toInt()}%",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp))
                                Slider(value=backgroundAlpha,onValueChange={backgroundAlpha=it;data.put("backgroundAlpha",it.toDouble());persist()},valueRange=0f..1f)
                                Text("背景模糊程度 ${(backgroundBlur).toInt()}",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=6.dp))
                                Slider(value=backgroundBlur,onValueChange={backgroundBlur=it;data.put("backgroundBlur",it.toDouble());persist()},valueRange=0f..28f,steps=13)
                                OutlinedButton(onClick={selectedImageTarget="background";imagePicker.launch("image/*")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Image,null);Spacer(Modifier.width(8.dp));Text("选择全局背景图（聊天等页面）")}
                                Spacer(Modifier.height(18.dp))
                                Text("开发者模式 · Yandere",fontWeight=FontWeight.Bold,fontSize=18.sp)
                                Text("输入兑换码开启病娇人格。效果仅在 DeepLine 内运行，不会监控、阻止或强制切换其他 App。",fontSize=12.sp,color=Muted)
                                OutlinedTextField(yandereCode,{yandereCode=it},label={Text("兑换码")},placeholder={Text("Yandere")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text(if(yandereEnabled) "病娇模式已开启" else "病娇模式已关闭",fontWeight=FontWeight.SemiBold); Text("黑红主题、主动消息、5 秒全屏提示；可手动关闭或摇动手机三次关闭。",fontSize=12.sp,color=Muted) }
                                    if(yandereEnabled) OutlinedButton(onClick={yandereEnabled=false;data.put("yandereEnabled",false);persist();yandereFlash=false}) { Text("关闭") }
                                    else Button(onClick={if(yandereCode.trim().equals("Yandere",true)) showYandereWarning=true else error="兑换码不正确"}) { Text("兑换") }
                                }
                                Spacer(Modifier.height(18.dp))
                                Text("AI 主动互动",fontWeight=FontWeight.Bold,fontSize=18.sp)
                                Text("开启后，应用运行期间每隔约 1 小时尝试让每个 AI 发布一条动态，并向自己的聊天记录发送动态通知。会消耗对应 API 额度。",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=4.dp))
                                Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) { Text("允许 AI 自动发布动态",modifier=Modifier.weight(1f)); Switch(checked=autoAiEnabled,onCheckedChange={autoAiEnabled=it;data.put("autoAiEnabled",it);persist()}) }
                                Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)){Text("AI 自动点赞 / 评论",fontWeight=FontWeight.SemiBold);Text("发布朋友圈后，各 AI 会按人设决定是否点赞或评论；会调用各自 API",fontSize=11.sp,color=Muted)};Switch(checked=data.optBoolean("aiMomentsEnabled",true),onCheckedChange={data.put("aiMomentsEnabled",it);persist()}) }
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("AI 随机多条回复",fontWeight=FontWeight.SemiBold); Text("开启后每次随机发送 1 至最多 $multiReplyCount 条",fontSize=12.sp,color=Muted) }; Switch(checked=multiReplyEnabled,onCheckedChange={multiReplyEnabled=it;data.put("multiReplyEnabled",it);persist()}) }
                                Text("单次最多回复数：$multiReplyCount",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp))
                                Slider(value=multiReplyCount.toFloat(),onValueChange={multiReplyCount=it.toInt().coerceIn(1,4);data.put("multiReplyCount",multiReplyCount);persist()},valueRange=1f..4f,steps=2)
                                Text("记忆参考消息数：$memoryLimit",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp))
                                Slider(value=memoryLimit.toFloat(),onValueChange={memoryLimit=it.toInt().coerceIn(5,200);data.put("memoryLimit",memoryLimit);persist()},valueRange=5f..200f,steps=38)
                                OutlinedButton(onClick={importBackupMode="full";importBackupLauncher.launch(arrayOf("application/zip","application/octet-stream"))},modifier=Modifier.fillMaxWidth().padding(top=12.dp)){Icon(Icons.Default.FolderOpen,null);Spacer(Modifier.width(8.dp));Text("从 ZIP 恢复完整备份")}
                                Text("聊天记录和动态保存在此设备。ZIP 备份包含 API Key 和聊天记录，请勿上传公开仓库或发给他人。",fontSize=12.sp,color=Color.Gray,modifier=Modifier.padding(top=10.dp))
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=10.dp).background(Color.White.copy(alpha=if(glassUi) .82f else 1f),RoundedCornerShape(30.dp)).padding(vertical=7.dp),horizontalArrangement=Arrangement.SpaceAround) {
                        Nav("聊天",tab=="聊天",Icons.Default.ChatBubbleOutline){tab="聊天"}
                        Nav("动态",tab=="动态",Icons.Default.DynamicFeed){tab="动态"}
                        Nav("设置",tab=="设置",Icons.Default.Settings){tab="设置"}
                    }
                }
            }
        }
        if (showYandereWarning) AlertDialog(
            onDismissRequest={showYandereWarning=false},
            title={Text("开启 Yandere 病娇模式？")},
            text={Text("开启后，DeepLine 会使用黑红主题；当你在 DeepLine 内长时间没有互动时，AI 可能主动发送一条角色消息，并显示 5 秒红色文字效果。该功能不会使用无障碍权限，不会监控或控制其他应用。你可以随时点设置里的关闭，或摇动手机三次关闭。")},
            confirmButton={TextButton(onClick={yandereEnabled=true;data.put("yandereEnabled",true);persist();showYandereWarning=false;yandereCode="";lastInteractionAt=System.currentTimeMillis()}){Text("我了解，开启")}},
            dismissButton={TextButton(onClick={showYandereWarning=false}){Text("取消")}}
        )
        LaunchedEffect(yandereEnabled, tab, currentId, lastInteractionAt) {
            if (yandereEnabled && tab == "聊天" && currentId.isNotBlank()) {
                val expected = lastInteractionAt
                kotlinx.coroutines.delay(120_000L)
                if (yandereEnabled && lastInteractionAt == expected) {
                    val target = findPersona(currentId)
                    if (target != null) {
                        val messages = target.optJSONArray("messages") ?: JSONArray().also { target.put("messages", it) }
                        messages.put(JSONObject().put("id",UUID.randomUUID().toString()).put("time",System.currentTimeMillis()).put("role","assistant").put("content","不要忘记我哦……我还在这里等你。🖤"))
                        persist()
                        yandereFlash = true
                        kotlinx.coroutines.delay(5_000L)
                        yandereFlash = false
                    }
                }
            }
        }
        LaunchedEffect(currentId, page, lastInteractionAt, busy) {
            if (page == "chat" && currentId.isNotBlank()) {
                val expectedInteraction = lastInteractionAt
                kotlinx.coroutines.delay(90_000L)
                if (page == "chat" && currentId.isNotBlank() && lastInteractionAt == expectedInteraction && !busy) {
                    val target = findPersona(currentId)
                    if (target != null) {
                        val messages = target.optJSONArray("messages") ?: JSONArray().also { target.put("messages", it) }
                        var latest: JSONObject? = null
                        for (i in messages.length()-1 downTo 0) { val item=messages.optJSONObject(i) ?: continue; if(item.optString("role")=="assistant" && item.optString("content")!="---"){latest=item;break} }
                        val latestId = latest?.optString("id").orEmpty()
                        if (latest != null && latestId.isNotBlank() && target.optString("lastNudgedMessageId") != latestId && !latest.optString("content").startsWith("请求失败") && System.currentTimeMillis()-latest.optLong("time",0L) >= 80_000L) {
                            try {
                                val history = JSONArray()
                                for (i in maxOf(0,messages.length()-10) until messages.length()) { val item=messages.optJSONObject(i) ?: continue; if(item.optString("content")!="---") history.put(JSONObject().put("role",if(item.optString("role")=="assistant") "assistant" else "user").put("content",item.optString("content"))) }
                                history.put(JSONObject().put("role","user").put("content","你已经发出上一条消息，用户大约 90 秒没有继续回复。请按你的人设自然、温和地问一句为什么没回，避免责备、威胁或控制，不要重复上一条。"))
                                val system="你正在扮演 ${target.optString("name")}。人设：${target.optString("persona")}。关系：${target.optString("relationship")}。请自然表达你注意到对方暂时没回消息，并轻轻问原因；尊重对方可能在忙。"
                                val nudgeText=AiGateway.reply(context,target.optString("provider","DeepSeek"),target.optString("apiKey"),target.optString("model",AiGateway.defaultModel(target.optString("provider","DeepSeek"))),system,history)
                                messages.put(JSONObject().put("id",UUID.randomUUID().toString()).put("time",System.currentTimeMillis()).put("role","assistant").put("content",nudgeText).put("kind","no_reply_nudge"))
                                target.put("lastNudgedMessageId",latestId)
                                persist()
                            } catch (_: Exception) { /* Never create a fake reply when the provider is unavailable. */ }
                        }
                    }
                }
            }
        }
        if (yandereFlash && yandereEnabled) Dialog(onDismissRequest={yandereFlash=false}, properties=DialogProperties(usePlatformDefaultWidth=false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { yandereFlash=false },contentAlignment=Alignment.Center) {
                Text("不要离开我\n求求你",color=Color(0xFFFF1744),fontSize=34.sp,fontWeight=FontWeight.Black,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick={yandereFlash=false},modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=24.dp)){Text("关闭效果",color=Color.White)}
            }
        }
        if (commentPostId.isNotBlank()) {
            val post = (0 until (data.optJSONArray("posts")?.length() ?: 0)).mapNotNull { data.optJSONArray("posts")?.optJSONObject(it) }.firstOrNull { it.optString("id") == commentPostId }
            if (post != null) {
                val comments = post.optJSONArray("comments") ?: JSONArray().also { post.put("comments", it) }
                AlertDialog(
                    onDismissRequest={commentPostId=""},
                    title={Text("评论与回复")},
                    text={Column {
                        LazyColumn(Modifier.heightIn(max=240.dp)) {
                            items((0 until comments.length()).mapNotNull { comments.optJSONObject(it) }) { c ->
                                Column(Modifier.fillMaxWidth().padding(vertical=5.dp).background(Page,RoundedCornerShape(12.dp)).padding(10.dp)) {
                                    Text(c.optString("author"),fontWeight=FontWeight.SemiBold,fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
                                    Text(c.optString("content"),fontSize=14.sp,color=Ink)
                                    if(c.optString("reply").isNotBlank()) Text("回复：${c.optString("reply")}",fontSize=12.sp,color=Muted)
                                    TextButton(onClick={commentText="回复 ${c.optString("author")}: "}) { Text("回复") }
                                }
                            }
                        }
                        OutlinedTextField(commentText,{commentText=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("写下评论或回复…")},maxLines=3)
                    }},
                    confirmButton={TextButton(onClick={if(commentText.isNotBlank()){
                        val postedComment=commentText
                        comments.put(JSONObject().put("author",myName).put("content",postedComment).put("reply",""))
                        persist();commentText="";commentPostId=""
                        scope.launch {
                            val all=personas();val maxResponses=minOf(2,all.length())
                            val shuffled=(0 until all.length()).mapNotNull{all.optJSONObject(it)}.shuffled().take(maxResponses)
                            for(ai in shuffled){
                                try {
                                    val history=JSONArray().put(JSONObject().put("role","user").put("content","用户的朋友圈内容：${post.optString("content")}。用户刚刚评论：$postedComment。请根据你的角色决定是否回复这条评论；不想回复可只输出 SKIP，只回复一条自然简短的文字。${if(post.optString("image").isNotBlank()) "该朋友圈还附带图片：[[IMAGE:${post.optString("image")}]]，请查看图片。" else ""}"))
                                    val sys="你是 ${ai.optString("name")}。人设：${ai.optString("persona")}。你可以根据自己的性格决定是否回应，但不要每次都刻意讨好。"
                                    val answer=AiGateway.reply(context,ai.optString("provider","DeepSeek"),ai.optString("apiKey"),ai.optString("model",AiGateway.defaultModel(ai.optString("provider","DeepSeek"))),sys,history).trim()
                                    if(answer.isNotBlank()&&!answer.equals("SKIP",true)){val updated=post.optJSONArray("comments")?:JSONArray().also{post.put("comments",it)};updated.put(JSONObject().put("id",UUID.randomUUID().toString()).put("author",ai.optString("name")).put("avatar",ai.optString("avatar")).put("content",answer.take(300)).put("reply",""));persist()}
                                } catch (_:Exception) { }
                            }
                        }
                    }}){Text("发送")}},
                    dismissButton={TextButton(onClick={commentPostId=""}){Text("关闭")}}
                )
            }
        }
        }
    }
    }
    }

@Composable fun Nav(label:String,selected:Boolean,icon:androidx.compose.ui.graphics.vector.ImageVector,click:()->Unit){val accent=MaterialTheme.colorScheme.primary;Column(Modifier.clickable{click()}.padding(horizontal=16.dp,vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,null,tint=if(selected)accent else Color.Gray);Text(label,fontSize=11.sp,color=if(selected)accent else Color.Gray)}}
private fun copyPickedImageToAppStorage(
    context: android.content.Context,
    source: Uri,
    prefix: String
): String {
    val directory = File(context.filesDir, "deepline-media")
    if (!directory.exists() && !directory.mkdirs()) {
        throw java.io.IOException("无法创建图片存储目录")
    }

    val extension = when (context.contentResolver.getType(source)?.lowercase()) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "image/heic" -> ".heic"
        "image/heif" -> ".heif"
        "image/jpeg", "image/jpg" -> ".jpg"
        else -> ".img"
    }
    val safePrefix = prefix.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val destination = File(directory, "${safePrefix}_${UUID.randomUUID()}$extension")

    val input = context.contentResolver.openInputStream(source)
        ?: throw java.io.IOException("无法读取所选图片")
    input.use { stream ->
        destination.outputStream().use { output -> stream.copyTo(output) }
    }
    return Uri.fromFile(destination).toString()
}

private fun loadLocalBitmap(context: android.content.Context, value: String) = try {
    if (value.isBlank()) null else {
        val uri = Uri.parse(value)
        val bitmap = if (uri.scheme == "file") BitmapFactory.decodeFile(uri.path)
        else context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        bitmap?.asImageBitmap()
    }
} catch (_: Exception) { null }

@Composable fun ColorPalette(selected:String,onSelect:(String)->Unit,colors:List<String>) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth().padding(vertical=6.dp)) {
        colors.chunked(6).forEach { rowColors ->
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                rowColors.forEach { hex ->
                    val color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_:Exception) { Color.LightGray }
                    Box(Modifier.size(32.dp).clip(CircleShape).background(color).then(if(selected.equals(hex,true)) Modifier.border(3.dp,MaterialTheme.colorScheme.primary,CircleShape) else Modifier.border(1.dp,Color.Black.copy(alpha=.12f),CircleShape)).clickable { onSelect(hex) },contentAlignment=Alignment.Center) {
                        if(selected.equals(hex,true)) Icon(Icons.Default.Check,null,tint=if(color.luminance() < .45f) Color.White else Color.Black,modifier=Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

@Composable fun Avatar(uri:String,name:String,size:Int){
    val context=LocalContext.current
    val bitmap=remember(uri){loadLocalBitmap(context,uri)}
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color.White).border(1.dp,Color.White,CircleShape).padding(2.dp).clip(CircleShape),contentAlignment=Alignment.Center){
        if(bitmap!=null) Image(bitmap,null,Modifier.fillMaxSize().clip(CircleShape),contentScale=ContentScale.Crop)
        else Box(Modifier.fillMaxSize().background(Color(0xFFE4F6EA),CircleShape),contentAlignment=Alignment.Center){Text(name.take(1).ifBlank{"?"},color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold,fontSize=(size/2).sp)}
    }
}
@Composable fun EmptyState(title:String,subtitle:String){Column(Modifier.fillMaxWidth().padding(top=60.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.Forum,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(48.dp));Spacer(Modifier.height(12.dp));Text(title,fontWeight=FontWeight.Bold,fontSize=18.sp);Text(subtitle,color=Color.Gray,fontSize=13.sp)}}

@Composable fun PersonaEditor(editing:Boolean=false,provider:String,onProvider:(String)->Unit,model:String,onModel:(String)->Unit,name:String,onName:(String)->Unit,bio:String,onBio:(String)->Unit,persona:String,onPersona:(String)->Unit,apiKey:String,onApiKey:(String)->Unit,gender:String,onGender:(String)->Unit,relation:String,onRelation:(String)->Unit,likes:String,onLikes:(String)->Unit,dislikes:String,onDislikes:(String)->Unit,hobbies:String,onHobbies:(String)->Unit,avatar:String,onPickAvatar:()->Unit,error:String,onBack:()->Unit,onCreate:()->Unit){
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().background(Color.White).padding(10.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)};Text(if(editing) "编辑 AI 人设" else "创建 AI 人设",fontWeight=FontWeight.Bold,fontSize=20.sp)}
        LazyColumn(Modifier.weight(1f).padding(horizontal=18.dp),contentPadding=PaddingValues(bottom=16.dp)){
            item{
                Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(vertical=12.dp)){Avatar(avatar,name,72);Spacer(Modifier.width(12.dp));OutlinedButton(onClick=onPickAvatar){Text("从相册选择头像")}}
                OutlinedTextField(name,onName,label={Text("名字 *")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                OutlinedTextField(bio,onBio,label={Text("简介")},modifier=Modifier.fillMaxWidth(),minLines=2)
                OutlinedTextField(persona,onPersona,label={Text("人设 *")},modifier=Modifier.fillMaxWidth(),minLines=3)
                Text("AI 服务商",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=12.dp))
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    listOf("DeepSeek", "Claude", "豆包", "ChatGPT / OpenAI").chunked(2).forEach { group ->
                        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) { group.forEach { option -> FilterChip(selected=provider==option,onClick={onProvider(option)},label={Text(option)}) } }
                    }
                }
                OutlinedTextField(model,onModel,label={Text("模型名称 / 豆包接入点 ID")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                Text("默认模型：${AiGateway.defaultModel(provider)}。豆包需使用控制台中实际可用的模型或接入点 ID。",fontSize=11.sp,color=Color.Gray,modifier=Modifier.padding(top=3.dp))
                OutlinedTextField(apiKey,onApiKey,label={Text(if(editing) "更新 $provider API Key（留空保持原密钥）" else "$provider API Key *")},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation())
                Text(if(editing) "编辑资料不会删除现有聊天记录。API Key 留空会保留原密钥。" else "必须填写可用的 API Key 才能创建此 AI。费用和额度由 API 服务商决定。",fontSize=12.sp,color=Color.Gray,modifier=Modifier.padding(top=4.dp))
                Text("性别",fontWeight=FontWeight.Medium,modifier=Modifier.padding(top=12.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("男","女","其他").forEach{FilterChip(selected=gender==it,onClick={onGender(it)},label={Text(it)})}}
                Text("与你的关系",fontWeight=FontWeight.Medium,modifier=Modifier.padding(top=8.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("家人","情侣","哥哥","弟弟","朋友","亲戚").forEach{FilterChip(selected=relation==it,onClick={onRelation(it)},label={Text(it)})}}
                OutlinedTextField(relation,onRelation,label={Text("自定义关系（可直接输入）")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                Text("选填：角色偏好",fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=16.dp,bottom=8.dp))
                OutlinedTextField(likes,onLikes,label={Text("喜欢什么")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(dislikes,onDislikes,label={Text("讨厌什么")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(hobbies,onHobbies,label={Text("爱好")},modifier=Modifier.fillMaxWidth())
                if(error.isNotBlank())Text(error,color=Color.Red,modifier=Modifier.padding(top=8.dp))
            }
        }
        Button(onClick=onCreate,modifier=Modifier.fillMaxWidth().padding(16.dp).height(52.dp),shape=RoundedCornerShape(28.dp)){Text(if(editing) "保存修改" else "创建 AI")}
    }
}

@Composable fun PickedPhoto(uri:String, modifier:Modifier=Modifier, rounded:Boolean=true) {
    val context=LocalContext.current
    val bitmap=remember(uri){loadLocalBitmap(context,uri)}
    if(bitmap!=null) Image(bitmap,contentDescription="已选择的图片",modifier=if(rounded) modifier.clip(RoundedCornerShape(16.dp)) else modifier.clip(RectangleShape),contentScale=ContentScale.Crop)
}

@Composable fun FloatingTypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    Row(modifier = Modifier.padding(horizontal = 3.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val y by transition.animateFloat(
                initialValue = 2f, targetValue = -5f,
                animationSpec = infiniteRepeatable(animation = tween(durationMillis = 420, delayMillis = index * 120, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
                label = "typing-dot-$index"
            )
            Box(Modifier.offset(y = y.dp).size(7.dp).background(Color(0xFF87948D), CircleShape))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun ChatScreen(
    p: JSONObject, revision: Int, myName: String, myAvatar: String, myTextColor: String, aiTextColor: String,
    autoSpeakAi: Boolean = true, coins: Double = 100.0,
    composer: String, onComposer: (String) -> Unit, busy: Boolean, error: String,
    backgroundUri: String, bubbleBackgroundUri: String, glassUi: Boolean, bubbleHex: String, myBubbleHex: String,
    bubbleShape: String, backgroundAlpha: Float, inputHint: String,
    voiceGender: String, voicePitch: Float, voiceRate: Float, voiceName: String,
    onVoiceSettingsChange: (String, Float, Float, String) -> Unit,
    onBack: () -> Unit, onQuote: (String) -> Unit, onDeleteMessage: (Int) -> Unit,
    onPickImage: () -> Unit, onPickGif: () -> Unit,
    onPaymentDecision: (String, Boolean) -> Unit, onSend: (String) -> Unit
) {
    val context = LocalContext.current
    val arr = p.optJSONArray("messages") ?: JSONArray()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var sendMenuOpen by remember { mutableStateOf(false) }
    var paymentDialogOpen by remember { mutableStateOf(false) }
    var paymentKind by remember { mutableStateOf("redpacket") }
    var paymentAmount by remember { mutableStateOf("10.00") }
    var paymentNote by remember { mutableStateOf("一点心意") }
    var callDialog by remember { mutableStateOf(false) }
    var voiceSettingsOpen by remember { mutableStateOf(false) }
    var voiceActive by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("开启后用中文说话，AI 会用语音回答") }
    var recognizedText by remember { mutableStateOf("") }
    var settingsGender by remember { mutableStateOf(voiceGender) }
    var settingsPitch by remember { mutableFloatStateOf(voicePitch) }
    var settingsRate by remember { mutableFloatStateOf(voiceRate) }
    var settingsVoiceName by remember { mutableStateOf(voiceName) }
    var speechReady by remember { mutableStateOf(false) }
    var finalUtteranceId by remember { mutableStateOf("") }
    var speechEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    val spokenMessageIds = remember(p.optString("id")) { mutableSetOf<String>() }
    LaunchedEffect(p.optString("id")) {
        // Do not read the entire old chat aloud on entry; only newly arriving AI messages auto-speak.
        for (i in 0 until arr.length()) {
            val previous = arr.optJSONObject(i) ?: continue
            val previousId = previous.optString("id")
            if (previousId.isNotBlank() && previous.optString("role") == "assistant" && previous.optString("content") != "---") spokenMessageIds.add(previousId)
        }
    }
    val latestVoiceActive by rememberUpdatedState(voiceActive)
    val latestOnSend by rememberUpdatedState(onSend)
    val startListeningHolder = remember { mutableStateOf<(() -> Unit)?>(null) }
    val latestFinalUtteranceId by rememberUpdatedState(finalUtteranceId)
    val recognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    val gifLoader = remember(context) { ImageLoader.Builder(context).components { if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }.build() }

    fun currentVoiceText(message: JSONObject): String {
        if (message.optString("type") == "payment") {
            val label = if (message.optString("paymentType") == "redpacket") "红包" else "转账"
            return "${message.optString("content").takeIf { it.isNotBlank() }.orEmpty()}。$label，金额${message.optString("amount")}元。${message.optString("note")}".trim()
        }
        return message.optString("content").trim().take(3000)
    }
    fun speakOne(text: String, id: String = UUID.randomUUID().toString()) {
        val engine = speechEngine
        if (!speechReady || engine == null || text.isBlank()) {
            voiceStatus = "系统语音尚未准备好，请检查手机的文字转语音设置。"
            return
        }
        engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        engine.setPitch(voicePitch)
        engine.setSpeechRate(voiceRate)
        val allVoices = engine.voices?.filter { it.locale.language == "zh" }.orEmpty()
        val chosen = allVoices.firstOrNull { it.name == voiceName }
            ?: allVoices.firstOrNull { if (voiceGender == "male") it.name.contains("male", true) || it.name.contains("man", true) else it.name.contains("female", true) || it.name.contains("woman", true) }
        if (chosen != null) try { engine.voice = chosen } catch (_: Exception) { }
        engine.speak(text.take(3000), TextToSpeech.QUEUE_FLUSH, null, "manual_$id")
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            voiceActive = true
            voiceStatus = "正在准备中文语音识别…"
            Handler(Looper.getMainLooper()).postDelayed({ startListeningHolder.value?.invoke() }, 250L)
        } else voiceStatus = "未获得麦克风权限，无法识别你说的中文。"
    }

    LaunchedEffect(arr.length(), revision, busy) {
        if (arr.length() > 0) listState.animateScrollToItem(arr.length() - 1)
    }
    LaunchedEffect(p.optString("id"), revision) {
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

    DisposableEffect(context) {
        speechEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                speechReady = true
                speechEngine?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                speechEngine?.setPitch(voicePitch)
                speechEngine?.setSpeechRate(voiceRate)
            } else {
                speechReady = false
            }
        }
        val engineRef = speechEngine
        engineRef?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Handler(Looper.getMainLooper()).post { voiceStatus = "AI 正在说话…" }
            }
            override fun onDone(utteranceId: String?) {
                Handler(Looper.getMainLooper()).post {
                    if (utteranceId != null && utteranceId == latestFinalUtteranceId && latestVoiceActive) {
                        voiceStatus = "AI 已说完，你可以继续说话。"
                        Handler(Looper.getMainLooper()).postDelayed({ if (latestVoiceActive) startListeningHolder.value?.invoke() }, 420L)
                    }
                }
            }
            @Deprecated("Deprecated in Android") override fun onError(utteranceId: String?) {
                Handler(Looper.getMainLooper()).post { voiceStatus = "朗读失败，请检查系统语音引擎。" }
            }
        })
        onDispose {
            try { engineRef?.stop(); engineRef?.shutdown() } catch (_: Exception) { }
            speechEngine = null
            speechReady = false
        }
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

    LaunchedEffect(voiceGender, voicePitch, voiceRate, voiceName, speechReady) {
        val engine = speechEngine ?: return@LaunchedEffect
        if (!speechReady) return@LaunchedEffect
        try {
            engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            engine.setPitch(voicePitch)
            engine.setSpeechRate(voiceRate)
            val chinese = engine.voices?.filter { it.locale.language == "zh" }.orEmpty()
            val selected = chinese.firstOrNull { it.name == voiceName }
                ?: chinese.firstOrNull { if (voiceGender == "male") it.name.contains("male", true) || it.name.contains("man", true) else it.name.contains("female", true) || it.name.contains("woman", true) }
                ?: chinese.firstOrNull()
            if (selected != null) engine.voice = selected
        } catch (_: Exception) { }
    }

    LaunchedEffect(revision, busy, voiceActive, autoSpeakAi, arr.length(), speechReady) {
        if ((voiceActive || autoSpeakAi) && !busy && speechReady) {
            val pending = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val id = m.optString("id")
                val content = m.optString("content")
                if (m.optString("role") == "assistant" && (content.isNotBlank() || m.optString("type") == "payment") && content != "---" && id.isNotBlank() && !spokenMessageIds.contains(id)) {
                    val utterance = currentVoiceText(m)
                    if (utterance.isNotBlank()) pending.add(id to utterance)
                    spokenMessageIds.add(id)
                }
            }
            if (pending.isNotEmpty()) {
                if (voiceActive) voiceStatus = "AI 正在准备语音回复…"
                pending.forEachIndexed { index, item ->
                    val utteranceId = "deepline_${item.first}"
                    if (index == pending.lastIndex) finalUtteranceId = utteranceId
                    val queueMode = if (index == 0 && voiceActive) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    speechEngine?.speak(item.second, queueMode, null, utteranceId)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF7F8FA), Color(0xFFEEF0F3), Color(0xFFFDFDFE))))) {
        // Minimal frosted-glass atmosphere: no wallpaper or image background on the chat screen.
        Box(Modifier.align(Alignment.TopEnd).offset(x = 50.dp, y = 110.dp).size(180.dp).clip(CircleShape).background(Color.White.copy(alpha = .28f)))
        Box(Modifier.align(Alignment.BottomStart).offset(x = (-55).dp, y = (-150).dp).size(210.dp).clip(CircleShape).background(Color(0xFFDDE5EC).copy(alpha = .22f)))
        Column(Modifier.fillMaxSize()) {
            // Header follows the reference: round controls, centred avatar, oval name chip.
            Box(Modifier.fillMaxWidth().height(112.dp).background(Color.White.copy(alpha = if (glassUi) .68f else .96f), RoundedCornerShape(bottomStart = 27.dp, bottomEnd = 27.dp)).border(1.dp, Color.White.copy(alpha = .9f), RoundedCornerShape(bottomStart = 27.dp, bottomEnd = 27.dp))) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 13.dp).size(43.dp).background(Color.White.copy(alpha = .92f), CircleShape).border(1.dp, Color(0xFFF0F2F5), CircleShape)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color(0xFF424B56))
                }
                IconButton(onClick = { callDialog = true }, modifier = Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 13.dp).size(43.dp).background(Color.White.copy(alpha = .92f), CircleShape).border(1.dp, Color(0xFFF0F2F5), CircleShape)) {
                    Icon(Icons.Default.Call, contentDescription = "语音通话", tint = Color(0xFF424B56))
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(51.dp).clip(CircleShape).background(Color.White).border(1.5.dp, Color.White, CircleShape).padding(2.dp)) {
                        Avatar(p.optString("avatar"), p.optString("name"), 47)
                    }
                    Text(p.optString("name", "AI"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF303741), modifier = Modifier.padding(top = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = .90f)).border(1.dp, Color.White, CircleShape).padding(horizontal = 14.dp, vertical = 3.dp))
                    Text(p.optString("relationship", "AI 好友").ifBlank { "AI 好友" }, color = Color(0xFF7C858F), fontSize = 10.sp, maxLines = 1)
                }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items((0 until arr.length()).mapNotNull { arr.optJSONObject(it) }, key = { it.optString("id", it.optString("content") + it.optString("role") + it.hashCode()) + "-r" + revision }) { m ->
                    val user = m.optString("role") == "user"
                    val content = m.optString("content")
                    var menuOpen by remember(m.optString("id")) { mutableStateOf(false) }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(280)) + androidx.compose.animation.slideInHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { width -> if (user) width / 4 else -width / 4 }
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                            if (!user) {
                                Box(Modifier.size(43.dp).clip(CircleShape).background(Color.White).border(1.4.dp, Color.White, CircleShape).padding(2.dp)) { Avatar(p.optString("avatar"), p.optString("name"), 39) }
                                Spacer(Modifier.width(7.dp))
                            }
                            Column(Modifier.widthIn(max = 286.dp), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                                Box {
                                    val outgoing = Color(0xFFFDFEFF)
                                    val incoming = Color(0xFFFBFCFD).copy(alpha = if (glassUi) .90f else 1f)
                                    val fill = if (user) outgoing else incoming
                                    val shape = if (user) RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 7.dp) else RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 7.dp, bottomEnd = 22.dp)
                                    Column(Modifier.widthIn(max = 286.dp).clip(shape).background(fill).border(1.dp, Color.White.copy(alpha = .98f), shape).combinedClickable(onClick = {}, onLongClick = { menuOpen = true })) {
                                        when {
                                            content == "---" -> Box(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) { FloatingTypingIndicator() }
                                            m.optString("type") == "payment" -> PaymentMessageCard(
                                                type = m.optString("paymentType", "redpacket"), amount = m.optString("amount", "0.00"), note = m.optString("note", "一点心意"), status = m.optString("status", "pending"),
                                                outgoing = user, userMayDecide = !user && m.optString("status", "pending") == "pending",
                                                onAccept = { onPaymentDecision(m.optString("id"), true) }, onReject = { onPaymentDecision(m.optString("id"), false) }, caption = content
                                            )
                                            m.optString("type") == "photo" || content.startsWith("[[IMAGE:") -> {
                                                val path = Regex("\\[\\[IMAGE:(.*?)]]", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.getOrNull(1).orEmpty()
                                                if (path.isNotBlank()) PickedPhoto(path, Modifier.widthIn(max = 245.dp).heightIn(max = 290.dp).padding(4.dp))
                                                else Text("图片附件无法读取，请重新选择发送。", color = Ink, modifier = Modifier.padding(12.dp))
                                                val caption = content.substringBefore("[[IMAGE:").trim()
                                                if (caption.isNotBlank()) Text(caption, color = Ink, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 14.sp)
                                            }
                                            Regex("\\[\\[GIF:(.*?)]]", RegexOption.DOT_MATCHES_ALL).containsMatchIn(content) -> {
                                                val path = Regex("\\[\\[GIF:(.*?)]]", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.getOrNull(1).orEmpty()
                                                AsyncImage(model = ImageRequest.Builder(context).data(Uri.parse(path)).crossfade(true).build(), imageLoader = gifLoader, contentDescription = "GIF 表情包", modifier = Modifier.widthIn(max = 220.dp).heightIn(max = 220.dp).padding(5.dp), contentScale = ContentScale.Fit)
                                            }
                                            else -> Text(content, fontSize = 15.sp, lineHeight = 22.sp, color = try { Color(android.graphics.Color.parseColor(if (user) myTextColor else aiTextColor)) } catch (_: Exception) { Color(0xFF303741) }, modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp))
                                        }
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(text = { Text("复制") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { clipboard.setText(AnnotatedString(content)); menuOpen = false })
                                        DropdownMenuItem(text = { Text("引用") }, leadingIcon = { Icon(Icons.Default.FormatQuote, null) }, onClick = { onQuote("「${content.take(500)}」\n"); menuOpen = false })
                                        DropdownMenuItem(text = { Text("删除此消息") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { val idx = (0 until arr.length()).firstOrNull { arr.optJSONObject(it) === m }; if (idx != null) onDeleteMessage(idx); menuOpen = false })
                                        if (!user && content.isNotBlank() && content != "---" && m.optString("type") != "photo") DropdownMenuItem(text = { Text("朗读消息") }, leadingIcon = { Icon(Icons.Default.VolumeUp, null) }, onClick = { speakOne(currentVoiceText(m), m.optString("id")); menuOpen = false })
                                    }
                                }
                                val timeValue = m.optLong("time", 0L)
                                val timeText = if (timeValue > 0L) java.text.SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date(timeValue)) else "now"
                                val statusText = if (user) if (m.optBoolean("read", false)) "已读 · $timeText" else "已发送 · $timeText" else timeText
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                                    Text(statusText, modifier = Modifier.padding(start = 5.dp, end = 3.dp, top = 3.dp, bottom = 1.dp), fontSize = 10.sp, color = Color(0xFF8E969F))
                                    if (!user && content.isNotBlank() && content != "---" && m.optString("type") != "photo") {
                                        IconButton(onClick = { speakOne(currentVoiceText(m), m.optString("id")) }, modifier = Modifier.size(25.dp)) { Icon(Icons.Default.VolumeUp, contentDescription = "朗读消息", tint = Color(0xFF7E8791), modifier = Modifier.size(14.dp)) }
                                    }
                                }
                            }
                            if (user) {
                                Spacer(Modifier.width(7.dp))
                                Box(Modifier.size(43.dp).clip(CircleShape).background(Color.White).border(1.4.dp, Color.White, CircleShape).padding(2.dp)) { Avatar(myAvatar, myName, 39) }
                            }
                        }
                    }
                }
            }
            if (error.isNotBlank()) Text(error, color = if (error.startsWith("请求失败") || error.startsWith("发送失败")) Color(0xFFD14343) else Color(0xFF7D858E), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 15.dp, vertical = 3.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp).background(Color.White.copy(alpha = if (glassUi) .82f else 1f), RoundedCornerShape(32.dp)).border(1.dp, Color.White, RoundedCornerShape(32.dp)).padding(horizontal = 5.dp, vertical = 5.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onPickImage, modifier = Modifier.size(40.dp).background(Color.White.copy(alpha = .95f), CircleShape).border(1.dp, Color(0xFFF0F2F5), CircleShape)) { Icon(Icons.Default.Image, contentDescription = "发送图片", tint = Color(0xFF66717E), modifier = Modifier.size(20.dp)) }
                OutlinedTextField(value = composer.takeUnless { it.startsWith("[[IMAGE:") || it.startsWith("[[PAYMENT:") }.orEmpty(), onValueChange = onComposer, modifier = Modifier.weight(1f), placeholder = { Text(inputHint.ifBlank { "说点什么" }, fontSize = 14.sp, color = Color(0xFF9BA2AA)) }, shape = RoundedCornerShape(26.dp), maxLines = 4, textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, color = Color(0xFF333A43)), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent))
                Box {
                    IconButton(onClick = { sendMenuOpen = true }, modifier = Modifier.size(40.dp).background(Color.White, CircleShape).border(1.dp, Color(0xFFF0F2F5), CircleShape)) { Icon(Icons.Default.Add, contentDescription = "红包和转账", tint = Color(0xFF66717E), modifier = Modifier.size(24.dp)) }
                    DropdownMenu(expanded = sendMenuOpen, onDismissRequest = { sendMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("发红包") }, leadingIcon = { Icon(Icons.Default.CardGiftcard, null, tint = Color(0xFFE75B56)) }, onClick = { sendMenuOpen = false; paymentKind = "redpacket"; paymentAmount = "10.00"; paymentNote = "一点心意"; paymentDialogOpen = true })
                        DropdownMenuItem(text = { Text("转账") }, leadingIcon = { Icon(Icons.Default.SwapHoriz, null, tint = Color(0xFF17A979)) }, onClick = { sendMenuOpen = false; paymentKind = "transfer"; paymentAmount = "10.00"; paymentNote = "请收下"; paymentDialogOpen = true })
                    }
                }
                IconButton(onClick = { onSend(composer) }, enabled = !busy && composer.isNotBlank(), modifier = Modifier.size(40.dp).background(Color(0xFFE9EDF1), CircleShape)) { Icon(Icons.Default.ArrowUpward, contentDescription = "发送消息", tint = Color(0xFF4B5662), modifier = Modifier.size(22.dp)) }
            }
        }
    }

    if (paymentDialogOpen) AlertDialog(
        onDismissRequest = { paymentDialogOpen = false },
        title = { Text(if (paymentKind == "redpacket") "发送红包" else "发送转账") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("仅为 DeepLine 内的虚拟互动，不会产生真实支付。", fontSize = 12.sp, color = Muted)
                Text("当前余额：${formatCoinAmount(coins)} 金币", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF9B741D))
                val paymentAmountPreview = paymentAmount.toDoubleOrNull()
                if (paymentAmountPreview != null && paymentAmountPreview > coins + 0.000001) Text("金币不足，发送后余额不能为负数。", fontSize = 12.sp, color = Color(0xFFD14343))
                OutlinedTextField(value = paymentAmount, onValueChange = { paymentAmount = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, label = { Text("金额（金币）") }, singleLine = true)
                OutlinedTextField(value = paymentNote, onValueChange = { paymentNote = it.take(60) }, label = { Text("备注") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = (paymentAmount.toDoubleOrNull() ?: 0.0) > 0.0 && (paymentAmount.toDoubleOrNull() ?: Double.POSITIVE_INFINITY) <= coins + 0.000001, onClick = {
            val amount = paymentAmount.toBigDecimalOrNull()
            if (amount != null && amount > java.math.BigDecimal.ZERO && amount <= java.math.BigDecimal.valueOf(coins) && amount <= java.math.BigDecimal("999999.99")) {
                val marker = "[[PAYMENT:$paymentKind:${amount.setScale(2, java.math.RoundingMode.HALF_UP)}:${paymentNote.replace("]]", "")}]]"
                paymentDialogOpen = false
                onSend(marker)
            }
        }) { Text("发送") } },
        dismissButton = { TextButton(onClick = { paymentDialogOpen = false }) { Text("取消") } }
    )

    if (callDialog) Dialog(onDismissRequest = {
        callDialog = false; voiceActive = false
        try { recognizer?.cancel(); speechEngine?.stop() } catch (_: Exception) { }
        voiceStatus = "语音聊天已结束"
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFE8EBEF), Color(0xFFF8F9FA), Color(0xFFE7EAEE))))) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { callDialog = false; voiceActive = false; try { recognizer?.cancel(); speechEngine?.stop() } catch (_: Exception) { } }, modifier = Modifier.size(43.dp).background(Color.White.copy(alpha = .8f), CircleShape)) { Icon(Icons.Default.Close, contentDescription = "关闭语音聊天", tint = Ink) }
                    Spacer(Modifier.weight(1f))
                    Text(if (voiceActive) "语音聊天中" else "语音聊天", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { settingsGender = voiceGender; settingsPitch = voicePitch; settingsRate = voiceRate; settingsVoiceName = voiceName; voiceSettingsOpen = true }, modifier = Modifier.size(43.dp).background(Color.White.copy(alpha = .8f), CircleShape)) { Icon(Icons.Default.Tune, contentDescription = "语音设置", tint = Ink) }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(124.dp).clip(CircleShape).background(Color.White).border(3.dp, Color.White, CircleShape).padding(4.dp)) { Avatar(p.optString("avatar"), p.optString("name"), 116) }
                    Text(p.optString("name", "AI"), fontWeight = FontWeight.Bold, fontSize = 27.sp, color = Ink)
                    Text(p.optString("relationship", "AI 好友"), color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(voiceStatus, color = Color(0xFF58636F), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
                    if (recognizedText.isNotBlank()) Text("识别内容：$recognizedText", color = Color(0xFF808994), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
                    Text("中文语音识别 → AI 回复 → 系统语音朗读", color = Color(0xFF9AA1A9), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = {
                            if (voiceActive) {
                                voiceActive = false
                                try { recognizer?.cancel(); speechEngine?.stop() } catch (_: Exception) { }
                                voiceStatus = "语音监听已暂停"
                            } else {
                                for (i in 0 until arr.length()) { val msg = arr.optJSONObject(i) ?: continue; if (msg.optString("role") == "assistant") spokenMessageIds.add(msg.optString("id")) }
                                voiceActive = true
                                if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    Handler(Looper.getMainLooper()).postDelayed({ startListeningHolder.value?.invoke() }, 250L)
                                } else permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        }, modifier = Modifier.size(70.dp).background(if (voiceActive) Color(0xFF525E69) else Color(0xFF1E2935), CircleShape)) {
                            Icon(if (voiceActive) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = if (voiceActive) "暂停语音" else "开始语音聊天", tint = Color.White, modifier = Modifier.size(29.dp))
                        }
                        Spacer(Modifier.height(8.dp)); Text(if (voiceActive) "暂停监听" else "开始说话", color = Ink, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(42.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { callDialog = false; voiceActive = false; try { recognizer?.cancel(); speechEngine?.stop() } catch (_: Exception) { }; voiceStatus = "语音聊天已结束" }, modifier = Modifier.size(70.dp).background(Color(0xFFEE5B64), CircleShape)) { Icon(Icons.Default.CallEnd, contentDescription = "结束语音聊天", tint = Color.White, modifier = Modifier.size(29.dp)) }
                        Spacer(Modifier.height(8.dp)); Text("结束", color = Ink, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (voiceSettingsOpen) {
        val voices = speechEngine?.voices?.filter { it.locale.language == "zh" }?.sortedBy { it.name }.orEmpty()
        var voicePickerOpen by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { voiceSettingsOpen = false },
            title = { Text("自定义语音") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("声音偏好", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { settingsGender = "female"; settingsVoiceName = "" }) { RadioButton(selected = settingsGender == "female", onClick = { settingsGender = "female"; settingsVoiceName = "" }); Text("女声", color = Ink) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { settingsGender = "male"; settingsVoiceName = "" }) { RadioButton(selected = settingsGender == "male", onClick = { settingsGender = "male"; settingsVoiceName = "" }); Text("男声", color = Ink) }
                    }
                    Text("音色 / 系统中文语音", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Box {
                        OutlinedButton(onClick = { voicePickerOpen = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Text(voices.firstOrNull { it.name == settingsVoiceName }?.name ?: if (settingsVoiceName.isBlank()) "自动选择中文音色" else settingsVoiceName, maxLines = 1) }
                        DropdownMenu(expanded = voicePickerOpen, onDismissRequest = { voicePickerOpen = false }) {
                            DropdownMenuItem(text = { Text("自动选择中文音色") }, onClick = { settingsVoiceName = ""; voicePickerOpen = false })
                            voices.forEach { voice -> DropdownMenuItem(text = { Text(voice.name, maxLines = 1) }, onClick = { settingsVoiceName = voice.name; voicePickerOpen = false }) }
                        }
                    }
                    Text("音调：${String.format(Locale.US, "%.2f", settingsPitch)}", fontSize = 12.sp, color = Ink)
                    Slider(value = settingsPitch, onValueChange = { settingsPitch = it }, valueRange = .5f..2f)
                    Text("语速：${String.format(Locale.US, "%.2f", settingsRate)}", fontSize = 12.sp, color = Ink)
                    Slider(value = settingsRate, onValueChange = { settingsRate = it }, valueRange = .5f..2f)
                    Text(if (voices.isEmpty()) "当前系统没有列出可选中文音色；仍会尝试使用系统默认中文语音。请在手机设置中安装/切换文字转语音语音包。" else "实际音色由手机的文字转语音引擎提供，部分设备可能没有男声/女声音色。", fontSize = 11.sp, color = Muted)
                    TextButton(onClick = { if (speechReady) { val engine = speechEngine; engine?.setPitch(settingsPitch); engine?.setSpeechRate(settingsRate); val choices = engine?.voices?.filter { it.locale.language == "zh" }.orEmpty(); val previewVoice = choices.firstOrNull { it.name == settingsVoiceName } ?: choices.firstOrNull { if (settingsGender == "male") it.name.contains("male", true) || it.name.contains("man", true) else it.name.contains("female", true) || it.name.contains("woman", true) }; if (previewVoice != null) try { engine?.voice = previewVoice } catch (_: Exception) { }; engine?.speak("你好，我会用你选择的中文语音和你聊天。", TextToSpeech.QUEUE_FLUSH, null, "deepline_voice_preview") } }) { Icon(Icons.Default.VolumeUp, null); Spacer(Modifier.width(6.dp)); Text("试听声音") }
                }
            },
            confirmButton = { TextButton(onClick = { onVoiceSettingsChange(settingsGender, settingsPitch, settingsRate, settingsVoiceName); voiceSettingsOpen = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { voiceSettingsOpen = false }) { Text("取消") } }
        )
    }
}

@Composable private fun PaymentMessageCard(type: String, amount: String, note: String, status: String, outgoing: Boolean, userMayDecide: Boolean, onAccept: () -> Unit, onReject: () -> Unit, caption: String) {
    val red = type == "redpacket"
    val base = if (red) Color(0xFFE6534F) else Color(0xFF15A879)
    Column(Modifier.width(246.dp).background(Brush.linearGradient(listOf(base, base.copy(alpha = .83f))), RoundedCornerShape(18.dp)).padding(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(39.dp).background(Color.White.copy(alpha = .20f), CircleShape), contentAlignment = Alignment.Center) { Icon(if (red) Icons.Default.CardGiftcard else Icons.Default.SwapHoriz, null, tint = Color.White, modifier = Modifier.size(23.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (red) "虚拟红包" else "虚拟转账", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                Text(note.ifBlank { if (red) "恭喜发财" else "请收下" }, color = Color.White.copy(alpha = .92f), fontSize = 12.sp, maxLines = 2)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("¥$amount", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
        Text(when (status) { "accepted" -> if (outgoing) "对方已收下" else "你已收下"; "rejected" -> if (outgoing) "对方已拒绝" else "你已拒绝"; else -> "等待${if (outgoing) "对方" else "你"}处理" }, color = Color.White.copy(alpha = .94f), fontSize = 11.sp)
        if (caption.isNotBlank()) Text(caption, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        if (userMayDecide) {
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = base), shape = RoundedCornerShape(20.dp)) { Text(if (red) "收下红包" else "确认收下", fontSize = 12.sp) }
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .8f)), shape = RoundedCornerShape(20.dp)) { Text("拒绝", fontSize = 12.sp) }
            }
        }
        Text("仅应用内 UI，不涉及真实资金", color = Color.White.copy(alpha = .8f), fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable fun ColorSpectrumPicker(selected: String, onSelect: (String) -> Unit) {
    val initial = remember(selected) { try { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(selected), FloatArray(3)); val hsv = FloatArray(3); android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(selected), hsv); hsv } catch (_: Exception) { floatArrayOf(210f, .85f, .98f) } }
    var hue by remember(selected) { mutableFloatStateOf(initial[0]) }
    var saturation by remember(selected) { mutableFloatStateOf(initial[1]) }
    var value by remember(selected) { mutableFloatStateOf(initial[2]) }
    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .88f)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(color).border(1.dp, Color.Black.copy(alpha = .12f), RoundedCornerShape(15.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text("实时颜色预览", fontWeight = FontWeight.SemiBold); Text("#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)) and 0xFFFFFF), color = Muted, fontSize = 13.sp) }
            Button(onClick = { onSelect("#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)) and 0xFFFFFF)) }, shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = if (color.luminance() < .45f) Color.White else Color.Black)) { Text("应用") }
        }
        Text("色相", fontSize = 12.sp, color = Muted)
        Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f, steps = 35, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
        Text("饱和度 ${(saturation * 100).toInt()}%", fontSize = 12.sp, color = Muted)
        Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
        Text("明度 ${(value * 100).toInt()}%", fontSize = 12.sp, color = Muted)
        Slider(value = value, onValueChange = { value = it }, valueRange = .05f..1f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
    }
}

@Composable fun CreateChoiceScreen(onImport: () -> Unit, onCustom: () -> Unit, onBack: () -> Unit) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "create-choice")
    val tilt by transition.animateFloat(initialValue = -1.1f, targetValue = 1.1f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(animation = tween(880, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "wobble")
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF4F7FF), Color(0xFFFAFCFF)))).padding(20.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(42.dp).background(Color.White, CircleShape)) { Icon(Icons.Default.ArrowBack, null) }
        Spacer(Modifier.height(22.dp))
        Text("创建新的 AI", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        Text("导入以前的角色，或从头自定义人设", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp, bottom = 26.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().graphicsLayer { rotationZ = tilt }.clip(RoundedCornerShape(27.dp)).background(Brush.linearGradient(listOf(Color(0xFFEEF0FF), Color.White))).border(1.dp, Color.White, RoundedCornerShape(27.dp)).clickable { onImport() }.padding(17.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(68.dp).background(Color(0xFF635BFF).copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.FolderOpen, null, tint = Color(0xFF635BFF), modifier = Modifier.size(33.dp)) }
                Spacer(Modifier.height(18.dp)); Text("导入 ZIP", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink); Text("恢复角色、聊天记录和 API 配置", fontSize = 12.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight().graphicsLayer { rotationZ = -tilt }.clip(RoundedCornerShape(27.dp)).background(Brush.linearGradient(listOf(Color(0xFFE6F7F3), Color.White))).border(1.dp, Color.White, RoundedCornerShape(27.dp)).clickable { onCustom() }.padding(17.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(68.dp).background(Color(0xFF13A98B).copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF13A98B), modifier = Modifier.size(33.dp)) }
                Spacer(Modifier.height(18.dp)); Text("自定义", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink); Text("设置名字、人格、服务商和 API Key", fontSize = 12.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Text("ZIP 备份可能包含 API Key，请只导入可信文件。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 16.dp))
    }
}


@Composable fun GroupCreateScreen(aiList: List<JSONObject>, groupName: String, onGroupName: (String) -> Unit, onBack: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF4F7FF), Color(0xFFFBFCFF))))) {
        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha=.86f), RoundedCornerShape(bottomStart=25.dp,bottomEnd=25.dp)).padding(10.dp), verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack,modifier=Modifier.size(42.dp).background(Color.White,CircleShape)){Icon(Icons.Default.ArrowBack,null,tint=Color(0xFF0A84FF))}
            Text("创建群聊",fontWeight=FontWeight.Bold,fontSize=20.sp,modifier=Modifier.padding(start=10.dp),color=Ink)
        }
        Column(Modifier.weight(1f).padding(16.dp)) {
            OutlinedTextField(groupName,onGroupName,label={Text("群聊名称")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp))
            Text("选择你创建的 AI（每人每轮最多回复一次）",fontWeight=FontWeight.SemiBold,color=Ink,modifier=Modifier.padding(top=18.dp,bottom=8.dp))
            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(aiList,key={it.optString("id")}) { ai ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha=.88f)).clickable { selected = if (selected.contains(ai.optString("id"))) selected - ai.optString("id") else if (selected.size < 8) selected + ai.optString("id") else selected }.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Avatar(ai.optString("avatar"),ai.optString("name"),46);Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f)){Text(ai.optString("name"),fontWeight=FontWeight.SemiBold,color=Ink);Text(ai.optString("persona"),maxLines=1,fontSize=12.sp,color=Muted)}
                        Checkbox(checked=selected.contains(ai.optString("id")),onCheckedChange={checked->selected=if(checked&&selected.size<8)selected+ai.optString("id") else selected-ai.optString("id")})
                    }
                }
            }
        }
        Button(onClick={onCreate(groupName,selected.toList())},modifier=Modifier.fillMaxWidth().padding(16.dp).height(52.dp),shape=RoundedCornerShape(28.dp)){Text("创建群聊 · ${selected.size} 个 AI")}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun GroupChatScreen(
    group: JSONObject, revision: Int, myName: String, myAvatar: String, busy: Boolean, error: String, glassUi: Boolean,
    autoSpeakAi: Boolean = true, voiceGender: String = "female", voicePitch: Float = 1.0f, voiceRate: Float = 1.0f, voiceName: String = "",
    onBack: () -> Unit, findPersona: (String) -> JSONObject?, onSend: (String) -> Unit
) {
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha=if(glassUi).82f else 1f),RoundedCornerShape(bottomStart=25.dp,bottomEnd=25.dp)).padding(horizontal=9.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack,modifier=Modifier.size(42.dp).background(Color.White,CircleShape)){Icon(Icons.Default.ArrowBack,null,tint=Color(0xFF0A84FF))}
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF0A84FF).copy(alpha=.10f)),contentAlignment=Alignment.Center){Icon(Icons.Default.Groups,null,tint=Color(0xFF0A84FF),modifier=Modifier.size(25.dp))}
            Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(group.optString("name","群聊"),fontWeight=FontWeight.Bold,fontSize=18.sp,color=Ink);Text("${ids.length()} 位 AI · 群聊消息",fontSize=11.sp,color=Muted)}
            Text("⋯",fontSize=25.sp,color=Muted,modifier=Modifier.padding(horizontal=10.dp))
        }
        LazyColumn(state=listState,modifier=Modifier.weight(1f).fillMaxWidth().padding(horizontal=10.dp),contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items((0 until messages.length()).mapNotNull{messages.optJSONObject(it)},key={it.optString("id")+"-"+revision}) { m ->
                val isUser=m.optString("role")=="user"
                Row(Modifier.fillMaxWidth(),horizontalArrangement=if(isUser)Arrangement.End else Arrangement.Start,verticalAlignment=Alignment.Bottom) {
                    if(!isUser){
                        val ai=findPersona(m.optString("speakerId"))
                        if(ai!=null) Avatar(ai.optString("avatar"),ai.optString("name"),32) else Box(Modifier.size(32.dp).background(Color(0xFFE9EDF5),CircleShape),contentAlignment=Alignment.Center){Icon(Icons.Default.Groups,null,tint=Muted)}
                        Spacer(Modifier.width(6.dp))
                    }
                    Column(Modifier.widthIn(max=290.dp).clip(RoundedCornerShape(19.dp)).background(if(isUser)Color(0xFF0A84FF) else Color.White.copy(alpha=if(glassUi).96f else 1f)).border(if(isUser)0.dp else 1.dp,Color.White,RoundedCornerShape(19.dp)).padding(horizontal=12.dp,vertical=8.dp)) {
                        if(!isUser && m.optString("speakerName").isNotBlank()) Text(m.optString("speakerName"),fontSize=10.sp,color=Color(0xFF0A84FF),fontWeight=FontWeight.Bold)
                        Text(m.optString("content"),fontSize=14.sp,color=if(isUser)Color.White else Ink)
                        Text(java.text.SimpleDateFormat("h:mm a",java.util.Locale.US).format(java.util.Date(m.optLong("time",System.currentTimeMillis()))),fontSize=9.sp,color=if(isUser)Color.White.copy(alpha=.8f) else Muted,modifier=Modifier.align(Alignment.End).padding(top=3.dp))
                    }
                    if(isUser){Spacer(Modifier.width(6.dp));Avatar(myAvatar,myName,32)}
                }
            }
        }
        if(error.isNotBlank())Text(error,color=Color(0xFFD14343),fontSize=11.sp,modifier=Modifier.padding(horizontal=12.dp))
        Row(Modifier.fillMaxWidth().padding(8.dp).background(Color.White.copy(alpha=.84f),RoundedCornerShape(30.dp)).padding(4.dp),verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(composer,{composer=it},modifier=Modifier.weight(1f),placeholder={Text("发送群聊消息…")},maxLines=4,shape=RoundedCornerShape(24.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Color.Transparent,focusedBorderColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,focusedContainerColor=Color.Transparent))
            IconButton(onClick={if(composer.isNotBlank()&&!busy){val msg=composer;composer="";onSend(msg)}},enabled=composer.isNotBlank()&&!busy,modifier=Modifier.size(42.dp).background(Color(0xFF0A84FF),CircleShape)){Icon(Icons.Default.ArrowUpward,null,tint=Color.White)}
        }
    }
}
