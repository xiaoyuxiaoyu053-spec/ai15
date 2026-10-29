package com.deepline.privateapp

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal val Green = Color(0xFF0A84FF)
internal val Ink = Color(0xFF17202B)
internal val Muted = Color(0xFF6F7B88)
internal val Page = Color(0xFFF3F5F9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge on every Android version, with dark status/navigation icons over the light glass wallpaper.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        Sfx.init(this)
        setContent {
            val context = LocalContext.current
            var accent by remember {
                mutableStateOf(try {
                    val saved = LocalStore.load(context).optString("theme", "#0A84FF")
                    Color(android.graphics.Color.parseColor(if (saved.equals("#06C755", true)) "#0A84FF" else saved))
                } catch (_: Exception) { Green })
            }
            MaterialTheme(colorScheme = lightColorScheme(primary = accent, background = Page)) {
                DeepLineApp(onThemeChange = { accent = it })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) Sfx.release()
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
    var aiAutoSpeakEnabled by remember { mutableStateOf(false) }
    fun persist() { LocalStore.save(context, data); revision++ }

    // Wallet is stored locally; coins accrue by elapsed wall-clock seconds, including time while the app is closed.
    // The balance is updated in memory every second but written to disk only every 30 s: serialising the whole
    // chat history every second made scrolling stutter as chats grew.
    LaunchedEffect(Unit) {
        val bootNow = System.currentTimeMillis()
        if (!data.has("coins")) data.put("coins", 100.0)
        if (!data.has("coinLastAccrualAt")) data.put("coinLastAccrualAt", bootNow)
        coinBalance = data.optDouble("coins", 100.0).coerceAtLeast(0.0)
        var sinceSave = 0
        while (true) {
            kotlinx.coroutines.delay(1000L)
            val now = System.currentTimeMillis()
            var last = data.optLong("coinLastAccrualAt", now)
            if (last > now) { last = now; data.put("coinLastAccrualAt", last) }
            val elapsedSeconds = ((now - last) / 1000L).coerceAtLeast(0L)
            if (elapsedSeconds > 0L) {
                val nextBalance = (data.optDouble("coins", coinBalance) + elapsedSeconds.toDouble()).coerceAtMost(999999999.0)
                coinBalance = nextBalance
                data.put("coins", nextBalance)
                data.put("coinLastAccrualAt", last + elapsedSeconds * 1000L)
                sinceSave++
                if (sinceSave >= 30) { sinceSave = 0; LocalStore.save(context, data) }
            }
        }
    }
    var tab by remember { mutableStateOf("聊天") }
    var chatSearch by remember { mutableStateOf("") }
    var chatEditMode by remember { mutableStateOf(false) }
    var chatListMode by remember { mutableStateOf("私聊") }
    var advancedSettingsOpen by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf("home") }
    var deleteTargetId by remember { mutableStateOf("") }
    var chatTheme by remember { mutableStateOf(data.optString("chatTheme", "自动")) }
    var assistantConsentTarget by remember { mutableStateOf("") }
    var assistantNeedsPermission by remember { mutableStateOf(false) }
    var assistantTaskTarget by remember { mutableStateOf("") }
    var assistantTaskError by remember { mutableStateOf("") }
    var aiPostBusy by remember { mutableStateOf(false) }
    var aiReactEnabled by remember { mutableStateOf(data.optBoolean("aiMomentsEnabled", true)) }
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
    var themeHex by remember { mutableStateOf(data.optString("theme","#0A84FF").let { if (it.equals("#06C755", true)) "#0A84FF" else it }) }
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
    var myBubbleHex by remember { mutableStateOf(data.optString("myBubbleColor", "#0A84FF").let { if (it.equals("#DCFCE7", true) || it.equals("#FFFFFF", true)) "#0A84FF" else it }) }
    var bubbleShape by remember { mutableStateOf(data.optString("bubbleShape", "柔和圆角")) }
    var bubbleBackgroundUri by remember { mutableStateOf(data.optString("bubbleBackground", "")) }
    var backgroundAlpha by remember { mutableFloatStateOf(data.optDouble("backgroundAlpha", 0.22).toFloat()) }
    var glassOpacity by remember { mutableFloatStateOf(data.optDouble("glassOpacity", 0.34).toFloat().coerceIn(0.08f, 0.85f)) }
    var deepThinkingEnabled by remember { mutableStateOf(data.optBoolean("deepThinkingEnabled", false)) }
    var conversationSummaryEnabled by remember { mutableStateOf(data.optBoolean("conversationSummaryEnabled", false)) }
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
    var appTitle by remember { mutableStateOf(data.optString("appTitle", "信息").let { if (it == "DeepLine" || it == "消息") "信息" else it }) }
    var inputHint by remember { mutableStateOf(data.optString("inputHint", "iMessage信息").let { if (it == "发送消息…" || it == "说点什么") "iMessage信息" else it }) }
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
                        composer = composer.replace(Regex("\\[\\[(IMAGE|GIF):(.*?)]]", RegexOption.DOT_MATCHES_ALL), "") + "[[IMAGE:$storedImage]]"
                        error = "图片已添加，点发送即可（需要支持识图的 AI 模型）。"
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
                composer = composer.replace(Regex("\\[\\[(IMAGE|GIF):(.*?)]]", RegexOption.DOT_MATCHES_ALL), "") + "[[GIF:$stored]]"
                error = "GIF 已添加，点发送即可。能否看懂每一帧取决于当前 AI 接口。"
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
                    aiAutoSpeakEnabled = false
                    LocalStore.save(context, data)
                    myName = data.optString("myName", "我")
                    myBio = data.optString("myBio", "")
                    themeHex = data.optString("theme", "#0A84FF").let { if (it.equals("#06C755", true)) "#0A84FF" else it }
                    try { onThemeChange(Color(android.graphics.Color.parseColor(themeHex))) } catch (_: Exception) { }
                    glassUi = data.optBoolean("glassUi", true)
                    soundEnabled = data.optBoolean("soundEnabled", true)
                    bubbleHex = data.optString("bubbleColor", "#FFFFFF")
                    myBubbleHex = data.optString("myBubbleColor", "#0A84FF").let { if (it.equals("#DCFCE7", true) || it.equals("#FFFFFF", true)) "#0A84FF" else it }
                    bubbleShape = data.optString("bubbleShape", "柔和圆角")
                    bubbleBackgroundUri = data.optString("bubbleBackground", "")
                    backgroundAlpha = data.optDouble("backgroundAlpha", .22).toFloat()
                    glassOpacity = data.optDouble("glassOpacity", .34).toFloat().coerceIn(.08f, .85f)
                    deepThinkingEnabled = data.optBoolean("deepThinkingEnabled", false)
                    conversationSummaryEnabled = data.optBoolean("conversationSummaryEnabled", false)
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
                    aiReactEnabled = data.optBoolean("aiMomentsEnabled", true)
                    appTitle = data.optString("appTitle", "信息").let { if (it == "DeepLine" || it == "消息") "信息" else it }
                    inputHint = data.optString("inputHint", "iMessage信息").let { if (it == "发送消息…" || it == "说点什么") "iMessage信息" else it }
                    yandereEnabled = data.optBoolean("yandereEnabled", false)
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
                    if (liked && !already) { likedBy.put(ai.optString("id")); post.put("likes",post.optInt("likes")+1); if (soundEnabled) Sfx.play(Sfx.POP) }
                    val comment = result.optString("comment").trim().take(240)
                    if (comment.isNotBlank() && !comment.equals("null",true)) {
                        val comments = post.optJSONArray("comments") ?: JSONArray().also { post.put("comments",it) }
                        comments.put(JSONObject().put("id",UUID.randomUUID().toString()).put("author",ai.optString("name")).put("avatar",ai.optString("avatar")).put("content",comment).put("reply","")); if (soundEnabled) Sfx.play(Sfx.RECEIVE)
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
        if (soundEnabled) Sfx.play(Sfx.SEND)
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
                            messages.put(messages.length() - 1, response); if (soundEnabled) Sfx.play(Sfx.RECEIVE)
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
        val trimmed = inputValue.trim()
        val msg = if (Regex("\\[\\[GIF:").containsMatchIn(trimmed) && trimmed.substringBefore("[[GIF:").isBlank()) "【GIF表情包】请结合这张动图的表情和当前聊天自然回应。\n" + trimmed else trimmed
        if (msg.isBlank() || busy) return
        lastInteractionAt = System.currentTimeMillis()
        val arr = p.optJSONArray("messages") ?: JSONArray().also { p.put("messages", it) }
        val pay = Regex("^\\[\\[PAYMENT:(redpacket|transfer):([0-9]+(?:\\.[0-9]{1,2})?):(.*?)]]$", RegexOption.DOT_MATCHES_ALL).find(msg)
        if (pay != null) {
            val amountToSend = pay.groupValues[2].toDoubleOrNull()
            if (amountToSend == null || amountToSend <= 0.0) { error = "金额无效，请重新输入。"; return }
            coinBalance = 999999999.0
            data.put("coins", coinBalance)
        }
        val isImage = msg.contains("[[IMAGE:")
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
        if (soundEnabled) Sfx.play(Sfx.SEND)
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
                val system = "你正在扮演 AI 角色。名字：${p.optString("name")}。简介：${p.optString("bio")}。人设：${p.optString("persona")}。性别：${p.optString("gender")}。与用户关系：${p.optString("relationship")}。喜欢：${p.optString("likes")}。讨厌：${p.optString("dislikes")}。爱好：${p.optString("hobbies")}。请遵循人设并用用户使用的语言自然回复。你是 AI，不要声称现实中不存在的经历。若消息包含 [[IMAGE:...]]，那是真实图片附件，请根据图片可见内容回答；如果模型不支持图片，接口失败时应由应用显示错误，不要假装看过。用户发来虚拟红包/转账时，请以 [收下] 或 [拒绝] 开头，并给出自然简短理由。若你想向用户发送应用内虚拟红包或转账，可在回复末尾使用精确格式 [红包|金额|备注] 或 [转账|金额|备注]，金额仅为本地 UI 示例，不能涉及真实支付；不要为了格式而强行发送。你也可以像 iMessage 的点按回应一样，偶尔在回复最开头加一个标记 [[REACT:❤️]]（表情只能是 ❤️ 👍 👎 😂 ‼️ ❓ 之一）来回应用户最后一条消息；若只想回应而不说话，可只输出该标记。不要频繁使用。记忆参考最近 $memoryLimit 条消息。"
                val targetReplyCount = if (multiReplyEnabled) (1..multiReplyCount).random() else 1
                val result = AiGateway.replyDetailed(context, p.optString("provider", "DeepSeek"), p.optString("apiKey"), p.optString("model", AiGateway.defaultModel(p.optString("provider", "DeepSeek"))), system, history, deepThinkingEnabled)
                val reply = result.text
                val aiPayment = Regex("\\[(红包|转账)\\|([0-9]+(?:\\.[0-9]{1,2})?)\\|([^]]*)]", RegexOption.DOT_MATCHES_ALL).find(reply)
                val reactMatch = Regex("\\[\\[REACT:([^]]{1,8})]]").find(reply)
                val decisionCleanReply = reply.replace(Regex("\\[\\[REACT:[^]]{1,8}]]"), "").trim().replace(Regex("^\\s*\\[(?:收下|拒绝)]\\s*"), "").trim()
                val cleanReply = if (aiPayment != null) decisionCleanReply.replace(aiPayment.value, "").trim() else decisionCleanReply
                for (i in 0 until arr.length()) { val item = arr.optJSONObject(i) ?: continue; if (item.optString("role") == "user") item.put("read", true) }
                if (reactMatch != null) {
                    val emoji = reactMatch.groupValues[1].trim()
                    if (emoji in listOf("❤️", "👍", "👎", "😂", "‼️", "❓")) {
                        for (i in arr.length() - 1 downTo 0) {
                            val item = arr.optJSONObject(i) ?: continue
                            if (item.optString("role") == "user" && item.optString("type") != "payment") { item.put("reaction", emoji); break }
                        }
                    }
                }
                val reactionOnly = cleanReply.isBlank() && reactMatch != null && aiPayment == null
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
                if (reactionOnly) { arr.remove(arr.length() - 1); persist(); if (soundEnabled) Sfx.play(Sfx.POP) } else {
                val first = JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("read", false).put("model", result.model).put("tokens", result.totalTokens)
                if (aiPayment != null) first.put("type", "payment").put("paymentType", if (aiPayment.groupValues[1] == "红包") "redpacket" else "transfer").put("amount", aiPayment.groupValues[2]).put("note", aiPayment.groupValues[3]).put("status", "pending").put("content", cleanReply)
                else first.put("content", cleanReply)
                if (result.reasoning.isNotBlank()) first.put("reasoning", result.reasoning)
                arr.put(arr.length() - 1, first); persist()
                if (soundEnabled) Sfx.play(Sfx.RECEIVE)
                }
                for (replyIndex in 1 until (if (reactionOnly) 1 else targetReplyCount)) {
                    val followHistory = JSONArray()
                    val followStart = maxOf(0, arr.length() - (memoryLimit + 1))
                    for (j in followStart until arr.length()) {
                        val item = arr.optJSONObject(j) ?: continue
                        if (item.optString("content") != "---") followHistory.put(JSONObject().put("role", item.optString("role")).put("content", item.optString("content")))
                    }
                    followHistory.put(JSONObject().put("role", "user").put("content", "如果还有自然想补充的话，再发一条简短消息；不要重复刚才内容。"))
                    arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("content", "---")); persist()
                    val extra = AiGateway.reply(context, p.optString("provider", "DeepSeek"), p.optString("apiKey"), p.optString("model", AiGateway.defaultModel(p.optString("provider", "DeepSeek"))), system, followHistory, deepThinkingEnabled)
                    arr.put(arr.length() - 1, JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("content", extra)); persist()
                }
                if (conversationSummaryEnabled) {
                    val realCount = (0 until arr.length()).count { index ->
                        val item = arr.optJSONObject(index) ?: return@count false
                        item.optString("role") in listOf("user", "assistant") && item.optString("content") != "---" && item.optString("type") != "summary"
                    }
                    val summaryThrough = p.optInt("summaryThrough", 0)
                    val targetThrough = (realCount / 50) * 50
                    if (targetThrough >= 50 && targetThrough > summaryThrough) {
                        val summaryHistory = JSONArray()
                        val recent = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                            .filter { it.optString("role") in listOf("user", "assistant") && it.optString("content") != "---" && it.optString("type") != "summary" }
                            .takeLast(50)
                        recent.forEach { item ->
                            val content = if (item.optString("type") == "payment") "[虚拟${if (item.optString("paymentType") == "redpacket") "红包" else "转账"}：${item.optString("amount")} 金币，状态 ${item.optString("status")}]" else item.optString("content").take(1200)
                            summaryHistory.put(JSONObject().put("role", if (item.optString("role") == "assistant") "assistant" else "user").put("content", content))
                        }
                        val summarySystem = "你是对话记录整理助手。请用中文写一段简洁、准确的阶段总结，概括最近对话的主题、已确定的信息、用户偏好、待继续的话题。不要虚构内容，不要输出标题以外的多余格式，最多 180 字。只总结提供的聊天记录。"
                        val summaryText = try {
                            AiGateway.reply(context, p.optString("provider", "DeepSeek"), p.optString("apiKey"), p.optString("model", AiGateway.defaultModel(p.optString("provider", "DeepSeek"))), summarySystem, summaryHistory, deepThinkingEnabled).take(400)
                        } catch (summaryError: Exception) {
                            "本阶段共整理至第 $targetThrough 条消息。自动总结暂时失败：${summaryError.message ?: "请检查 API 设置"}"
                        }
                        arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("type", "summary").put("summaryThrough", targetThrough).put("content", summaryText))
                        p.put("summaryThrough", targetThrough)
                        persist()
                    }
                }
            } catch (e: Exception) {
                val last = arr.optJSONObject(arr.length() - 1)
                if (last != null && last.optString("content") == "---") arr.put(arr.length() - 1, JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("content", "请求失败：${e.message ?: "请检查 API 配置"}"))
                persist(); error = e.message ?: "请求失败"
            } finally { busy = false }
        }
    }


    fun startEditPersona(p: JSONObject) {
        editingPersonaId = p.optString("id"); personaName = p.optString("name"); personaBio = p.optString("bio"); persona = p.optString("persona")
        gender = p.optString("gender", "其他"); relation = p.optString("relationship", "朋友"); likes = p.optString("likes"); dislikes = p.optString("dislikes")
        hobbies = p.optString("hobbies"); editAvatar = p.optString("avatar"); personaApiKey = ""
        personaProvider = p.optString("provider", "DeepSeek")
        personaModel = p.optString("model", AiGateway.defaultModel(p.optString("provider", "DeepSeek")))
        error = ""; page = "create"
    }
    fun startNewPersona() {
        editingPersonaId = ""; personaName = ""; personaBio = ""; persona = ""; personaApiKey = ""; personaProvider = "DeepSeek"
        personaModel = AiGateway.defaultModel("DeepSeek"); likes = ""; dislikes = ""; hobbies = ""; editAvatar = ""; error = ""; page = "create"
    }
    // NOTE: JSONArray.put(0, x) REPLACES element 0 (it does not insert), which silently overwrote the newest
    // dynamic every time a new one was posted. New posts are now truly prepended.
    fun prependPost(post: JSONObject) {
        val old = data.optJSONArray("posts") ?: JSONArray()
        val fresh = JSONArray().put(post)
        for (i in 0 until old.length()) { val x = old.opt(i); if (x != null) fresh.put(x) }
        data.put("posts", fresh)
    }
    fun deletePost(post: JSONObject) {
        val old = data.optJSONArray("posts") ?: return
        val fresh = JSONArray()
        for (i in 0 until old.length()) { val x = old.optJSONObject(i) ?: continue; if (x !== post) fresh.put(x) }
        data.put("posts", fresh); persist()
    }
    fun toggleLike(post: JSONObject) {
        val likedBy = post.optJSONArray("likedBy") ?: JSONArray().also { post.put("likedBy", it) }
        var at = -1
        for (i in 0 until likedBy.length()) if (likedBy.optString(i) == "me") at = i
        if (at >= 0) { likedBy.remove(at); post.put("likes", (post.optInt("likes") - 1).coerceAtLeast(0)) }
        else { likedBy.put("me"); post.put("likes", post.optInt("likes") + 1); if (soundEnabled) Sfx.play(Sfx.POP) }
        persist()
    }
    fun findPost(id: String): JSONObject? {
        val a = data.optJSONArray("posts") ?: return null
        for (i in 0 until a.length()) { val x = a.optJSONObject(i) ?: continue; if (x.optString("id") == id) return x }
        return null
    }

    /** Asks one AI to write a dynamic in its own voice. Returns false (and posts nothing) if the API call fails. */
    suspend fun generateAiPost(ai: JSONObject): Boolean {
        return try {
            val history = JSONArray()
            val recent = ai.optJSONArray("messages") ?: JSONArray()
            for (j in maxOf(0, recent.length() - 20) until recent.length()) {
                val item = recent.optJSONObject(j) ?: continue
                if (item.optString("content") != "---") history.put(JSONObject().put("role", if (item.optString("role") == "assistant") "assistant" else "user").put("content", item.optString("content")))
            }
            history.put(JSONObject().put("role", "user").put("content", "请基于你和用户最近的对话、你的人设，写一条像你本人会发的自然朋友圈动态。只返回动态正文，不要编造现实中实际发生过但对话里没有的事情。"))
            val sys = "你正在扮演 ${ai.optString("name")}。人设：${ai.optString("persona")}。喜欢：${ai.optString("likes")}。爱好：${ai.optString("hobbies")}。动态内容应该结合你最近与用户的对话，像真实社交动态一样自然；不得声称 AI 实际去了某处或做过未经对话支持的现实活动。"
            val postText = AiGateway.reply(context, ai.optString("provider", "DeepSeek"), ai.optString("apiKey"), ai.optString("model", AiGateway.defaultModel(ai.optString("provider", "DeepSeek"))), sys, history)
            prependPost(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("author", ai.optString("name")).put("avatar", ai.optString("avatar")).put("content", postText).put("likes", 0).put("likedBy", JSONArray()).put("comments", JSONArray()).put("image", "").put("ai", true))
            val msgs = ai.optJSONArray("messages") ?: JSONArray().also { ai.put("messages", it) }
            msgs.put(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("read", false).put("content", "我刚刚发了一条动态：$postText"))
            persist()
            if (soundEnabled) Sfx.play(Sfx.RECEIVE)
            true
        } catch (_: Exception) { false }
    }
    fun aiPostNow() {
        if (aiPostBusy) return
        val all = personas()
        if (all.length() == 0) { error = "还没有 AI，先到「增加AI」创建一个。"; return }
        scope.launch {
            aiPostBusy = true
            var ok = 0; var fail = 0
            for (i in 0 until all.length()) { val ai = all.optJSONObject(i) ?: continue; if (generateAiPost(ai)) ok++ else fail++ }
            aiPostBusy = false
            error = if (fail == 0) "AI 已发布 $ok 条动态" else "成功 $ok 条，失败 $fail 条，请检查对应 AI 的 API Key"
        }
    }
    fun publishPost() {
        val text = feedText.trim()
        if (text.isBlank() && postImage.isBlank()) return
        val published = JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("author", myName).put("avatar", data.optString("myAvatar")).put("content", text).put("image", postImage).put("likes", 0).put("likedBy", JSONArray()).put("comments", JSONArray()).put("ai", false)
        prependPost(published)
        feedText = ""; postImage = ""
        persist()
        if (soundEnabled) Sfx.play(Sfx.SUCCESS)
        askAisToReactToPost(published)
    }

    LaunchedEffect(autoAiEnabled) {
        if (!autoAiEnabled) return@LaunchedEffect
        while (true) {
            // Random 30–60 min cadence; each AI posts with 60% probability per round so it feels natural.
            kotlinx.coroutines.delay((30L..60L).random() * 60_000L)
            val all = personas()
            for (i in 0 until all.length()) {
                val ai = all.optJSONObject(i) ?: continue
                if ((0..99).random() < 60) generateAiPost(ai)
            }
        }
    }


    val accent = MaterialTheme.colorScheme.primary
    val baseDensity = LocalDensity.current
    val hostView = LocalView.current
    val unreadTotal = totalUnread(data)
    val bgUri = data.optString("background")
    val photoLuminance by rememberPhotoLuminance(bgUri)
    val effectiveLuminance = if (bgUri.isBlank()) 0.85f else photoLuminance * (1f - backgroundAlpha) + 0.85f * backgroundAlpha
    val wallpaperDark = yandereEnabled || effectiveLuminance < 0.5f
    val chatDark = when (chatTheme) { "浅色" -> false; "深色" -> true; else -> wallpaperDark }
    val appFont = remember(fontChoice, data.optString("customFontPath")) {
        when (fontChoice) {
            "圆润" -> FontFamily.SansSerif
            "衬线" -> FontFamily.Serif
            "等宽" -> FontFamily.Monospace
            "自定义" -> try { FontFamily(android.graphics.Typeface.createFromFile(data.optString("customFontPath"))) } catch (_: Exception) { FontFamily.Default }
            else -> FontFamily.Default
        }
    }
    SideEffect {
        val w = (context as? android.app.Activity)?.window
        if (w != null) {
            val controller = androidx.core.view.WindowInsetsControllerCompat(w, hostView)
            val barsDark = if (page == "chat" || page == "groupChat") chatDark else wallpaperDark
            controller.isAppearanceLightStatusBars = !barsDark
            controller.isAppearanceLightNavigationBars = !barsDark
        }
    }
    // System back: leave chat / editors first, then fall back to the messages tab, then exit.
    BackHandler(enabled = page != "home" || tab != "聊天") {
        when {
            page == "chat" || page == "groupChat" -> { error = ""; page = "home" }
            page != "home" -> page = "home"
            else -> tab = "聊天"
        }
    }

    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = appFont),
        // Cap system font scaling so layouts look the same on every device.
        LocalDensity provides Density(baseDensity.density, baseDensity.fontScale.coerceIn(0.85f, 1.15f)),
        LocalGlass provides GlassStyle(glassUi, glassOpacity),
        LocalWallpaperDark provides wallpaperDark
    ) {
    Box(Modifier.fillMaxSize()) {
        GlassWallpaper(data.optString("background"), backgroundAlpha, backgroundBlur, wallpaperDark)
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter
        ) {
        Box(Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth()) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow), initialScale = 0.96f)) togetherWith fadeOut(tween(140))
                },
                label = "pages"
            ) { screen ->
                when (screen) {
                    "groupCreate" -> GroupCreateScreen(
                        aiList = (0 until personas().length()).mapNotNull { personas().optJSONObject(it) },
                        groupName = groupName, onGroupName = { groupName = it },
                        onBack = { page = "home" },
                        onCreate = { name, ids ->
                            if (name.isBlank() || ids.isEmpty()) { error = "请填写群聊名称，并至少选择一个 AI" }
                            else {
                                groups().put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", name.trim()).put("memberIds", JSONArray(ids)).put("messages", JSONArray()))
                                groupName = ""; persist(); tab = "聊天"; chatListMode = "群聊"; page = "home"
                            }
                        }
                    )
                    "groupChat" -> {
                        val group = findGroup(currentGroupId)
                        if (group == null) LaunchedEffect(Unit) { page = "home" }
                        else GroupChatScreen(
                            group = group, revision = revisionSnapshot, myName = myName, myAvatar = data.optString("myAvatar"), busy = busy, error = error,
                            glassUi = glassUi, autoSpeakAi = aiAutoSpeakEnabled, voiceGender = voiceGender, voicePitch = voicePitch, voiceRate = voiceRate, voiceName = voiceName,
                            onBack = { error = ""; page = "home" }, findPersona = { id -> findPersona(id) }, onSend = { text -> sendGroupMessage(group, text) },
                            chatDark = chatDark
                        )
                    }
                    "create" -> PersonaEditor(
                        editing = editingPersonaId.isNotBlank(),
                        provider = personaProvider,
                        onProvider = { newProvider -> if (newProvider != personaProvider) personaApiKey = ""; personaProvider = newProvider; personaModel = AiGateway.defaultModel(newProvider) },
                        model = personaModel, onModel = { personaModel = it },
                        name = personaName, onName = { personaName = it }, bio = personaBio, onBio = { personaBio = it },
                        persona = persona, onPersona = { persona = it }, apiKey = personaApiKey, onApiKey = { personaApiKey = it },
                        gender = gender, onGender = { gender = it }, relation = relation, onRelation = { relation = it },
                        likes = likes, onLikes = { likes = it }, dislikes = dislikes, onDislikes = { dislikes = it },
                        hobbies = hobbies, onHobbies = { hobbies = it }, avatar = editAvatar,
                        onPickAvatar = { selectedImageTarget = "personaAvatar"; imagePicker.launch("image/*") },
                        error = error, onBack = { page = "home" }, onCreate = { createPersona() },
                        assistantSlot = {
                            val target = if (editingPersonaId.isNotBlank()) findPersona(editingPersonaId) else null
                            if (target != null) {
                                AssistantSettingsCard(
                                    personaName = target.optString("name"), providerName = target.optString("provider", "DeepSeek"),
                                    enabled = target.optBoolean("assistantEnabled", false), accent = accent,
                                    onEnable = { assistantConsentTarget = target.optString("id") },
                                    onDisable = {
                                        target.put("assistantEnabled", false)
                                        if (AssistantController.isActive && AssistantController.personaName == target.optString("name")) AssistantController.stop("已关闭这个 AI 的智能助手，任务已停止。")
                                        persist()
                                    },
                                    onRevoke = {
                                        AssistantController.stop("已撤销无障碍权限，任务已停止。")
                                        val svc = DeepLineAccessibilityService.instance
                                        if (svc != null) svc.disableSelf() else DeepLineAccessibilityService.openAccessibilitySettings(context)
                                    }
                                )
                            } else {
                                Text("创建并保存 AI 后，可在它的编辑页底部启用「AI 智能助手」。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 14.dp, start = 6.dp))
                            }
                        }
                    )
                    "chat" -> {
                        val p = findPersona(currentId)
                        if (p == null) LaunchedEffect(Unit) { page = "home" }
                        else ChatScreen(
                            p = p, revision = revisionSnapshot, myName = myName, myAvatar = data.optString("myAvatar"),
                            myTextColor = myTextColor, aiTextColor = aiTextColor,
                            composer = composer, onComposer = { composer = it; lastInteractionAt = System.currentTimeMillis() },
                            busy = busy, error = error, onDismissError = { error = "" },
                            backgroundUri = data.optString("background"), backgroundAlpha = backgroundAlpha, backgroundBlur = backgroundBlur,
                            bubbleBackgroundUri = bubbleBackgroundUri, conversationSummaryEnabled = conversationSummaryEnabled,
                            bubbleHex = bubbleHex, myBubbleHex = myBubbleHex, bubbleShape = bubbleShape, inputHint = inputHint,
                            onBack = { error = ""; page = "home" },
                            onQuote = { quote -> composer = quote },
                            onDeleteMessage = { index -> val ms = p.optJSONArray("messages") ?: JSONArray(); ms.remove(index); persist() },
                            onPickImage = { selectedImageTarget = "chatImage"; imagePicker.launch("image/*") },
                            onPickGif = { gifPicker.launch("image/gif") },
                            onPaymentDecision = { messageId, accepted ->
                                val ms = p.optJSONArray("messages") ?: JSONArray()
                                for (i in 0 until ms.length()) {
                                    val item = ms.optJSONObject(i) ?: continue
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
                                if (accepted && soundEnabled) Sfx.play(Sfx.SUCCESS)
                                val decisionText = if (accepted) "我收下了你发来的应用内虚拟礼物，谢谢你。" else "我暂时不收这份应用内虚拟礼物，但谢谢你的心意。"
                                sendDirectChatMessage(p, decisionText)
                            },
                            onSend = { msgInput -> sendDirectChatMessage(p, msgInput) },
                            onEditPersona = { startEditPersona(p) },
                            chatDark = chatDark,
                            assistantEnabled = p.optBoolean("assistantEnabled", false),
                            onAssistant = { assistantTaskError = ""; assistantTaskTarget = p.optString("id") },
                            unreadElsewhere = (unreadTotal - unreadCount(p.optJSONArray("messages"))).coerceAtLeast(0),
                            onReact = { index, emoji ->
                                val target = p.optJSONArray("messages")?.optJSONObject(index)
                                if (target != null) {
                                    if (target.optString("reaction") == emoji) target.remove("reaction") else target.put("reaction", emoji)
                                    persist()
                                    if (soundEnabled) Sfx.play(Sfx.POP)
                                }
                            }
                        )
                    }
                    else -> Column(Modifier.fillMaxSize().imePadding()) {
                        LargeTitleHeader(
                            title = when (tab) { "聊天" -> appTitle.ifBlank { "信息" }; "动态" -> "动态"; "增加AI" -> "增加 AI"; else -> "我的" },
                            leading = {
                                if (tab == "聊天") {
                                    Text(
                                        if (chatEditMode) "完成" else "编辑", fontSize = 17.sp, color = accent,
                                        modifier = Modifier.bounceClick(pressedScale = 0.9f) { chatEditMode = !chatEditMode }.padding(vertical = 8.dp)
                                    )
                                } else {
                                    Box(Modifier.size(38.dp).bounceClick { tab = "我" }) { Avatar(data.optString("myAvatar"), myName, 36) }
                                }
                            },
                            trailing = {
                                if (tab == "聊天") {
                                    Box(
                                        Modifier.size(40.dp).bounceClick(pressedScale = 0.85f) { tab = "增加AI" }.glassSurface(CircleShape, 1.6f, 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Default.Edit, "新建信息", tint = accent, modifier = Modifier.size(21.dp)) }
                                }
                            }
                        )
                        // ── tab content ──
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AnimatedContent(
                                targetState = tab,
                                transitionSpec = { (fadeIn(tween(200)) + scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), initialScale = 0.97f)) togetherWith fadeOut(tween(120)) },
                                label = "tabs"
                            ) { t ->
                                when (t) {
                                    "聊天" -> MessagesTab(
                                        data = data, revision = revisionSnapshot, accent = accent,
                                        search = chatSearch, onSearch = { chatSearch = it },
                                        mode = chatListMode, onMode = { chatListMode = it }, editMode = chatEditMode,
                                        onOpenChat = { id -> currentId = id; page = "chat" },
                                        onOpenGroup = { id -> currentGroupId = id; page = "groupChat" },
                                        onEditPersona = { startEditPersona(it) },
                                        onExport = { exportBackupLauncher.launch("DeepLine-backup.zip") },
                                        onTogglePin = { it.put("pinned", !it.optBoolean("pinned", false)); persist() },
                                        onDelete = { deleteTargetId = it.optString("id") }
                                    )
                                    "动态" -> DynamicTab(
                                        data = data, revision = revisionSnapshot, accent = accent,
                                        myName = myName, myBio = myBio, myAvatar = data.optString("myAvatar"), cover = data.optString("momentsCover"),
                                        feedText = feedText, onFeedText = { feedText = it },
                                        postImage = postImage, onPickPostImage = { selectedImageTarget = "postImage"; imagePicker.launch("image/*") },
                                        onClearPostImage = { postImage = "" }, onPublish = { publishPost() },
                                        autoAi = autoAiEnabled, onAutoAi = { autoAiEnabled = it; data.put("autoAiEnabled", it); persist() },
                                        aiReact = aiReactEnabled, onAiReact = { aiReactEnabled = it; data.put("aiMomentsEnabled", it); persist() },
                                        aiPostBusy = aiPostBusy, onAiPostNow = { aiPostNow() },
                                        onPickCover = { selectedImageTarget = "momentsCover"; imagePicker.launch("image/*") },
                                        onLike = { toggleLike(it) }, onOpenComments = { commentPostId = it.optString("id") }, onDelete = { deletePost(it) }
                                    )
                                    "增加AI" -> AddAiTab(
                                        data = data, revision = revisionSnapshot, accent = accent,
                                        onImport = { importBackupMode = "personas"; importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                                        onCreate = { startNewPersona() }, onGroup = { page = "groupCreate" },
                                        onOpenChat = { id -> currentId = id; page = "chat" }
                                    )
                                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                                        SettingsGroup("个人资料") {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Box(Modifier.bounceClick { selectedImageTarget = "myAvatar"; imagePicker.launch("image/*") }) { Avatar(data.optString("myAvatar"), myName, 68) }
                                                Spacer(Modifier.width(12.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(myName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Ink, maxLines = 1)
                                                    Text("点头像从相册更换", fontSize = 12.sp, color = Muted)
                                                }
                                            }
                                            GlassField(myName, { myName = it }, label = { Text("名字") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                            GlassField(myBio, { myBio = it }, label = { Text("简介") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                                            SettingButton("保存资料", Icons.Default.Check, accent, filled = true) { saveData(); error = "资料已保存" }
                                        }
                                        SettingsGroup("外观") {
                                            Text("主题颜色", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                                            Text("拖动滑块预览，点「应用」生效", fontSize = 12.sp, color = Muted)
                                            ColorSpectrumPicker(selected = themeHex, onSelect = { value -> themeHex = value; data.put("theme", value); persist(); try { onThemeChange(Color(android.graphics.Color.parseColor(value))) } catch (_: Exception) { } })
                                            SettingSwitch("玻璃拟态 UI", "半透明卡片与柔和层次", glassUi) { glassUi = it; data.put("glassUi", it); persist() }
                                            SettingSlider("玻璃透明度 ${(glassOpacity * 100).toInt()}%", glassOpacity, 0.08f..0.85f, onFinished = { persist() }) { glassOpacity = it.coerceIn(0.08f, 0.85f); data.put("glassOpacity", glassOpacity.toDouble()) }
                                            SettingSwitch("发送 / 接收音效", "轻柔提示音，可随时关闭", soundEnabled) { soundEnabled = it; data.put("soundEnabled", it); persist(); if (it) Sfx.play(Sfx.RECEIVE) }
                                            SettingChips("全局字体", listOf("系统", "圆润", "衬线", "等宽"), fontChoice) { fontChoice = it; data.put("fontChoice", it); persist() }
                                            SettingButton("导入自定义 TTF 字体", Icons.Default.FolderOpen, accent) { fontPicker.launch(arrayOf("font/ttf", "application/x-font-ttf", "application/octet-stream")) }
                                        }
                                        SettingsGroup("聊天背景与气泡") {
                                            SettingButton("选择全局背景图", Icons.Default.Wallpaper, accent) { selectedImageTarget = "background"; imagePicker.launch("image/*") }
                                            if (data.optString("background").isNotBlank()) {
                                                SettingButton("恢复默认玻璃背景", Icons.Default.Close, accent) { data.put("background", ""); persist() }
                                            }
                                            SettingSlider("背景图变淡 ${(backgroundAlpha * 100).toInt()}%", backgroundAlpha, 0f..1f, onFinished = { persist() }) { backgroundAlpha = it; data.put("backgroundAlpha", it.toDouble()) }
                                            SettingSlider("背景模糊 ${backgroundBlur.toInt()}", backgroundBlur, 0f..28f, steps = 13, onFinished = { persist() }) { backgroundBlur = it; data.put("backgroundBlur", it.toDouble()) }
                                            GlassField(appTitle, { appTitle = it; data.put("appTitle", it); persist() }, label = { Text("消息页标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                            GlassField(inputHint, { inputHint = it; data.put("inputHint", it); persist() }, label = { Text("聊天输入框提示文字") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                            SettingChips("聊天外观（iMessage）", listOf("自动", "浅色", "深色"), chatTheme) { chatTheme = it; data.put("chatTheme", it); persist() }
                                            Text("自动：背景图较暗时使用深色 iMessage 外观", fontSize = 12.sp, color = Muted)
                                            Text("AI 气泡颜色（#FFFFFF = iMessage 自动灰）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.padding(top = 6.dp))
                                            ColorSpectrumPicker(selected = bubbleHex, onSelect = { bubbleHex = it; data.put("bubbleColor", it); persist() })
                                            Text("我的气泡颜色（默认 iMessage 蓝）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.padding(top = 6.dp))
                                            ColorSpectrumPicker(selected = myBubbleHex, onSelect = { myBubbleHex = it; data.put("myBubbleColor", it); persist() })
                                            Text("AI 消息文字颜色（默认自动）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.padding(top = 6.dp))
                                            ColorSpectrumPicker(selected = aiTextColor, onSelect = { aiTextColor = it; data.put("aiTextColor", it); persist() })
                                            Text("我的消息文字颜色（默认白色）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.padding(top = 6.dp))
                                            ColorSpectrumPicker(selected = myTextColor, onSelect = { myTextColor = it; data.put("myTextColor", it); persist() })
                                            SettingButton(if (bubbleBackgroundUri.isBlank()) "设置气泡背景图" else "更换气泡背景图", Icons.Default.Image, accent) { selectedImageTarget = "bubbleBackground"; imagePicker.launch("image/*") }
                                            if (bubbleBackgroundUri.isNotBlank()) {
                                                SettingButton("移除气泡背景图", Icons.Default.Close, accent) { bubbleBackgroundUri = ""; data.put("bubbleBackground", ""); persist() }
                                            }
                                        }
                                        SettingsGroup("AI 行为") {
                                            SettingSwitch("深度思考", "让 AI 更谨慎地分析；DeepSeek 开启后可显示「思考摘要」", deepThinkingEnabled) { deepThinkingEnabled = it; data.put("deepThinkingEnabled", it); persist() }
                                            SettingSwitch("每 50 条消息生成总结", "聊天页顶部显示「记忆自动总结已开启」，会额外调用一次 API", conversationSummaryEnabled) { conversationSummaryEnabled = it; data.put("conversationSummaryEnabled", it); persist() }
                                            SettingSwitch("允许 AI 自动发动态", "应用运行期间，AI 不定时发布动态并通知你；会消耗 API 额度", autoAiEnabled) { autoAiEnabled = it; data.put("autoAiEnabled", it); persist() }
                                            SettingSwitch("AI 自动点赞 / 评论", "你发布动态后，各 AI 按人设决定是否点赞或评论", aiReactEnabled) { aiReactEnabled = it; data.put("aiMomentsEnabled", it); persist() }
                                            SettingSwitch("AI 随机多条回复", "开启后每次随机发送 1 至 $multiReplyCount 条", multiReplyEnabled) { multiReplyEnabled = it; data.put("multiReplyEnabled", it); persist() }
                                            SettingSlider("单次最多回复数：$multiReplyCount", multiReplyCount.toFloat(), 1f..4f, steps = 2, onFinished = { persist() }) { multiReplyCount = it.toInt().coerceIn(1, 4); data.put("multiReplyCount", multiReplyCount) }
                                            SettingSlider("记忆参考消息数：$memoryLimit", memoryLimit.toFloat(), 5f..200f, steps = 38, onFinished = { persist() }) { memoryLimit = it.toInt().coerceIn(5, 200); data.put("memoryLimit", memoryLimit) }
                                        }
                                        SettingsGroup("数据与备份") {
                                            Text("创建 AI 时单独填写 API Key。密钥只保存在本机，不会上传到任何服务器。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                                            SettingButton("导出完整数据 ZIP", Icons.Default.FolderOpen, accent) { exportBackupLauncher.launch("DeepLine-backup.zip") }
                                            SettingButton("从 ZIP 恢复完整备份", Icons.Default.FolderOpen, accent) { importBackupMode = "full"; importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
                                            Text("ZIP 备份包含 API Key 和聊天记录，请勿公开分享。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
                                        }
                                        SettingsGroup("开发者模式 · Yandere") {
                                            Text("输入兑换码开启病娇人格。效果仅在 DeepLine 内运行，不会监控、阻止或强制切换其他 App。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                                            GlassField(yandereCode, { yandereCode = it }, label = { Text("兑换码") }, placeholder = { Text("Yandere") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                    Text(if (yandereEnabled) "病娇模式已开启" else "病娇模式已关闭", fontWeight = FontWeight.SemiBold, color = Ink)
                                                    Text("黑红主题、主动消息、5 秒全屏提示；可手动关闭或摇动手机三次关闭。", fontSize = 12.sp, color = Muted)
                                                }
                                                if (yandereEnabled) OutlinedButton(onClick = { yandereEnabled = false; data.put("yandereEnabled", false); persist(); yandereFlash = false }) { Text("关闭") }
                                                else Button(onClick = { if (yandereCode.trim().equals("Yandere", true)) showYandereWarning = true else error = "兑换码不正确" }) { Text("兑换") }
                                            }
                                        }
                                    }
                                }
                            }
                            if (page == "home" && error.isNotBlank()) {
                                StatusToast(error, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) { error = "" }
                            }
                        }
                        AppTabBar(tab, accent, unreadTotal) { picked -> tab = picked; if (picked != "聊天") chatEditMode = false }
                    }
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

        AssistantRunningBar(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 4.dp))

        if (assistantConsentTarget.isNotBlank()) {
            val consentPersona = findPersona(assistantConsentTarget)
            if (consentPersona != null) {
                AssistantConsentFlow(
                    personaName = consentPersona.optString("name"), providerName = consentPersona.optString("provider", "DeepSeek"),
                    onConfirmed = {
                        // Only reached after all three confirmations were accepted.
                        consentPersona.put("assistantEnabled", true)
                        consentPersona.put("assistantEnabledAt", System.currentTimeMillis())
                        persist()
                        assistantConsentTarget = ""
                        if (!DeepLineAccessibilityService.isEnabledInSettings(context)) assistantNeedsPermission = true
                    },
                    onCancelled = { assistantConsentTarget = "" }
                )
            } else {
                LaunchedEffect(Unit) { assistantConsentTarget = "" }
            }
        }
        if (assistantNeedsPermission) {
            AssistantNeedsPermissionDialog(
                onOpen = { assistantNeedsPermission = false; DeepLineAccessibilityService.openAccessibilitySettings(context) },
                onLater = { assistantNeedsPermission = false }
            )
        }
        if (assistantTaskTarget.isNotBlank()) {
            val taskPersona = findPersona(assistantTaskTarget)
            if (taskPersona != null) {
                AssistantTaskDialog(
                    personaName = taskPersona.optString("name"), accent = accent, errorText = assistantTaskError,
                    onStart = { taskText ->
                        val startError = AssistantController.start(context, taskPersona, taskText) { summary, ok ->
                            val ms = taskPersona.optJSONArray("messages") ?: JSONArray().also { taskPersona.put("messages", it) }
                            ms.put(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "assistant").put("read", false).put("content", (if (ok) "✅ " else "⚠️ ") + summary))
                            persist()
                            if (soundEnabled) Sfx.play(Sfx.RECEIVE)
                        }
                        if (startError != null) {
                            assistantTaskError = startError
                        } else {
                            val ms = taskPersona.optJSONArray("messages") ?: JSONArray().also { taskPersona.put("messages", it) }
                            ms.put(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("role", "user").put("read", true).put("content", "【智能助手任务】$taskText"))
                            persist()
                            assistantTaskTarget = ""
                        }
                    },
                    onDismiss = { assistantTaskTarget = "" }
                )
            } else {
                LaunchedEffect(Unit) { assistantTaskTarget = "" }
            }
        }

        if (deleteTargetId.isNotBlank()) {
            val target = findPersona(deleteTargetId)
            if (target != null) AlertDialog(
                onDismissRequest = { deleteTargetId = "" },
                containerColor = Color.White.copy(alpha = 0.96f), shape = RoundedCornerShape(28.dp),
                title = { Text("删除「${target.optString("name")}」？") },
                text = { Text("与这个 AI 的聊天记录会一并删除，无法恢复。建议先导出备份。") },
                confirmButton = {
                    TextButton(onClick = {
                        val keep = JSONArray()
                        for (i in 0 until personas().length()) { val item = personas().optJSONObject(i); if (item != null && item.optString("id") != target.optString("id")) keep.put(item) }
                        data.put("personas", keep); deleteTargetId = ""; persist()
                    }) { Text("删除", color = Color(0xFFD64255)) }
                },
                dismissButton = { TextButton(onClick = { deleteTargetId = "" }) { Text("取消") } }
            )
        }

        if (commentPostId.isNotBlank()) {
            val post = findPost(commentPostId)
            if (post != null) {
                val comments = post.optJSONArray("comments") ?: JSONArray().also { post.put("comments", it) }
                AlertDialog(
                    onDismissRequest = { commentPostId = "" },
                    containerColor = Color.White.copy(alpha = 0.96f), shape = RoundedCornerShape(28.dp),
                    title = { Text("评论与回复") },
                    text = {
                        Column {
                            if (comments.length() == 0) Text("还没有评论，来说点什么吧。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                                items((0 until comments.length()).mapNotNull { comments.optJSONObject(it) }) { c ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Avatar(c.optString("avatar"), c.optString("author"), 34)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Page).padding(10.dp)) {
                                            Text(c.optString("author"), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = accent)
                                            Text(c.optString("content"), fontSize = 14.sp, color = Ink)
                                            if (c.optString("reply").isNotBlank()) Text("回复：${c.optString("reply")}", fontSize = 12.sp, color = Muted)
                                            Text("回复", fontSize = 12.sp, color = accent, modifier = Modifier.padding(top = 2.dp).bounceClick { commentText = "回复 ${c.optString("author")}: " })
                                        }
                                    }
                                }
                            }
                            GlassField(commentText, { commentText = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("写下评论或回复…") }, maxLines = 3)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val postedComment = commentText.trim()
                            if (postedComment.isNotBlank()) {
                                comments.put(JSONObject().put("id", UUID.randomUUID().toString()).put("author", myName).put("avatar", data.optString("myAvatar")).put("content", postedComment).put("reply", ""))
                                persist(); commentText = ""; commentPostId = ""
                                scope.launch {
                                    val all = personas()
                                    val everyone = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
                                    // On an AI's own dynamic, that AI answers; on yours, up to two random AIs may reply.
                                    val responders = if (post.optBoolean("ai")) everyone.filter { it.optString("name") == post.optString("author") } else everyone.shuffled().take(2)
                                    for (ai in responders) {
                                        try {
                                            val history = JSONArray().put(JSONObject().put("role", "user").put("content", "动态内容：${post.optString("content")}。用户刚刚评论：$postedComment。请根据你的角色决定是否回复这条评论；不想回复可只输出 SKIP，只回复一条自然简短的文字。${if (post.optString("image").isNotBlank()) "该动态还附带图片：[[IMAGE:${post.optString("image")}]]，请查看图片。" else ""}"))
                                            val sys = "你是 ${ai.optString("name")}。人设：${ai.optString("persona")}。你可以根据自己的性格决定是否回应，但不要每次都刻意讨好。"
                                            val answer = AiGateway.reply(context, ai.optString("provider", "DeepSeek"), ai.optString("apiKey"), ai.optString("model", AiGateway.defaultModel(ai.optString("provider", "DeepSeek"))), sys, history).trim()
                                            if (answer.isNotBlank() && !answer.equals("SKIP", true)) {
                                                val updated = post.optJSONArray("comments") ?: JSONArray().also { post.put("comments", it) }
                                                updated.put(JSONObject().put("id", UUID.randomUUID().toString()).put("author", ai.optString("name")).put("avatar", ai.optString("avatar")).put("content", answer.take(300)).put("reply", ""))
                                                persist()
                                                if (soundEnabled) Sfx.play(Sfx.RECEIVE)
                                            }
                                        } catch (_: Exception) { }
                                    }
                                }
                            }
                        }) { Text("发送") }
                    },
                    dismissButton = { TextButton(onClick = { commentPostId = "" }) { Text("关闭") } }
                )
            }
        }
    }
    }
}


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
