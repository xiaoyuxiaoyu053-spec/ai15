package com.deepline.privateapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.background

// ───────────────────────────── 3-step activation consent ─────────────────────────────

/**
 * Three confirmations, shown strictly in order. Declining (or dismissing) any of them cancels activation and
 * nothing is enabled; the caller only enables the feature inside onConfirmed.
 */
@Composable
fun AssistantConsentFlow(personaName: String, providerName: String, onConfirmed: () -> Unit, onCancelled: () -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    var understood by remember { mutableStateOf(false) }
    val heading: String
    val body: String
    when (step) {
        1 -> {
            heading = "① 权限与数据去向"
            body = "要使用智能助手，需要你在系统「设置 → 无障碍」中为 DeepLine 打开「DeepLine 智能助手」服务（Android 官方无障碍接口，DeepLine 无法自行开启）。\n\n" +
                "授权后，助手可以读取屏幕上可见的文字、按钮和界面元素；在 Android 11 及以上还可以截屏，并用手机本地的文字识别（OCR）读取图片里的文字。\n\n" +
                "数据去向：为了理解界面并规划步骤，屏幕上的文字（以及 OCR 识别结果）会连同你的任务，发送给「$providerName」的 AI 接口（使用你为「$personaName」填写的 API Key），由该服务商按其政策处理。\n\n" +
                "屏幕上可能出现聊天记录、验证码、账号、金额等隐私信息。请不要在这类界面使用助手。"
        }
        2 -> {
            heading = "② 助手会做什么、不会做什么"
            body = "启用后，当你给「$personaName」下达任务时，助手可以：\n" +
                "• 点击、长按、滑动、滚动；\n• 在输入框输入文字、搜索词或表单内容；\n• 打开应用和网页，搜索并总结页面内容；\n• 分多步完成任务，每一步之后检查屏幕是否符合预期。\n\n" +
                "安全边界：\n• 发送消息、删除内容、发布动态、付款/购买/转账、安装/卸载等敏感操作，每一次都会弹窗等你点「允许这一次」，60 秒无响应视为拒绝；\n" +
                "• 不会输入密码，不会操作系统设置、权限弹窗和安装界面；\n• 不会绕过锁屏或其他应用的安全保护，设备锁屏时立即停止；\n" +
                "• 任务运行时屏幕上方有悬浮面板，可随时暂停、继续或停止。"
        }
        else -> {
            heading = "③ 风险与你的责任"
            body = "请理解并接受以下后果：\n" +
                "• AI 可能误解指令、点错位置或读错内容，造成消息误发、内容被删除、设置被改动等；虽然有确认弹窗，仍可能出现你没有预料到的结果。\n" +
                "• 网页或其他应用里的文字可能含有「诱导 AI」的指令。请只在可信场景使用，并在任务运行时留意屏幕。\n" +
                "• 每一步都会调用 AI 接口，消耗你的 API 额度，费用由服务商计费。\n\n" +
                "你可以随时在此页面关闭智能助手，或在系统无障碍设置里关闭服务以撤销权限；关闭后助手立即停止，已有聊天记录和数据不受影响。\n\n" +
                "点击「确认启用」，即表示你已完成三项确认并自愿为「$personaName」启用智能助手。"
        }
    }
    AlertDialog(
        onDismissRequest = onCancelled,
        containerColor = Color.White.copy(alpha = 0.97f),
        shape = RoundedCornerShape(28.dp),
        title = { Text("启用智能助手 · 第 $step / 3 步", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column {
                Text(heading, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Ink)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
                    Text(body, fontSize = 14.sp, lineHeight = 21.sp, color = Color(0xFF2B3440))
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("我已阅读并理解以上内容", fontSize = 14.sp, color = Ink)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = understood, onClick = {
                if (step < 3) {
                    step += 1
                    understood = false
                } else {
                    onConfirmed()
                }
            }) { Text(if (step < 3) "同意并继续" else "确认启用", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onCancelled) { Text("拒绝并取消", color = Color(0xFFD64255)) } }
    )
}

@Composable
fun AssistantNeedsPermissionDialog(onOpen: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = Color.White.copy(alpha = 0.97f),
        shape = RoundedCornerShape(28.dp),
        title = { Text("还需要系统授权") },
        text = {
            Text(
                "智能助手已为这个 AI 启用，但 Android 还没有授权无障碍服务。点「去系统设置」后，在「无障碍 / 已下载的应用」里找到「DeepLine 智能助手」并打开；系统会再向你确认一次。你也可以以后再开启，未授权时助手不会做任何事。",
                fontSize = 14.sp, lineHeight = 21.sp
            )
        },
        confirmButton = { TextButton(onClick = onOpen) { Text("去系统设置", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onLater) { Text("以后再说") } }
    )
}

// ───────────────────────────── Settings card (bottom of the persona page) ─────────────────────────────

@Composable
fun AssistantSettingsCard(
    personaName: String, providerName: String, enabled: Boolean, accent: Color,
    onEnable: () -> Unit, onDisable: () -> Unit, onRevoke: () -> Unit
) {
    val context = LocalContext.current
    var serviceOn by remember { mutableStateOf(DeepLineAccessibilityService.isEnabledInSettings(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            serviceOn = DeepLineAccessibilityService.isEnabledInSettings(context)
            delay(1000L)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).glassSurface(RoundedCornerShape(24.dp), 1.25f).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SmartToy, null, tint = accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("AI 智能助手", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Ink)
        }
        Text(
            "让「$personaName」在你授权后帮你操作手机：识别屏幕、点击、输入、滚动、打开应用和网页并总结。发送、删除、发布、付款等敏感操作每次都会先问你。",
            fontSize = 12.sp, lineHeight = 17.sp, color = Muted, modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            "助手：" + (if (enabled) "已启用" else "未启用") + "　·　系统无障碍权限：" + (if (serviceOn) "已开启" else "未开启"),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) accent else Muted, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
        )
        if (!enabled) {
            Text("启用前需要依次完成 3 项确认，任何一项拒绝都不会启用。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 4.dp))
            SettingButton("启用 AI 智能助手", Icons.Default.SmartToy, accent, filled = true) { onEnable() }
        } else {
            SettingButton("关闭 AI 智能助手", Icons.Default.Close, Color(0xFFD64255)) { onDisable() }
            if (serviceOn) {
                SettingButton("撤销系统无障碍权限", Icons.Default.Close, accent) { onRevoke() }
            } else {
                SettingButton("去系统开启无障碍权限", Icons.Default.SmartToy, accent) { DeepLineAccessibilityService.openAccessibilitySettings(context) }
            }
            Text("启用后，在与「$personaName」的聊天里点 + → 助手，即可下达任务。服务商：$providerName", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ───────────────────────────── Task dialog + running bar ─────────────────────────────

@Composable
fun AssistantTaskDialog(personaName: String, accent: Color, errorText: String, onStart: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var task by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(DeepLineAccessibilityService.isConnected()) }
    LaunchedEffect(Unit) {
        while (true) {
            connected = DeepLineAccessibilityService.isConnected()
            delay(800L)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White.copy(alpha = 0.97f),
        shape = RoundedCornerShape(28.dp),
        title = { Text("让「$personaName」帮你操作手机") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!connected) {
                    Text("系统无障碍权限尚未开启，助手暂时无法工作。", fontSize = 13.sp, color = Color(0xFFD64255))
                    SettingButton("去系统开启无障碍权限", Icons.Default.SmartToy, accent) { DeepLineAccessibilityService.openAccessibilitySettings(context) }
                }
                GlassField(task, { task = it }, Modifier.fillMaxWidth(), placeholder = { Text("例如：打开浏览器搜索今天的天气，并总结给我") }, minLines = 3)
                listOf("打开浏览器搜索「今天的天气」，把结果总结给我", "打开计算器，算一下 128 × 37 等于多少").forEach { example ->
                    Text(
                        example, fontSize = 12.sp, color = accent, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp).bounceClick(pressedScale = 0.96f) { task = example }
                            .clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Text(
                    "开始后助手会先回到主屏幕，并在屏幕上方显示悬浮面板（可暂停 / 停止）。敏感操作会先弹窗问你。助手会把屏幕文字发送给此 AI 的接口。",
                    fontSize = 11.sp, lineHeight = 16.sp, color = Muted, modifier = Modifier.padding(top = 10.dp)
                )
                if (errorText.isNotBlank()) Text(errorText, fontSize = 12.sp, color = Color(0xFFD64255), modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(enabled = connected && task.isNotBlank(), onClick = { onStart(task.trim()) }) { Text("开始", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** Shown on top of DeepLine while a task runs (the same controls also float over other apps). */
@Composable
fun AssistantRunningBar(modifier: Modifier = Modifier) {
    if (!AssistantController.isActive) return
    Row(
        modifier.padding(horizontal = 12.dp).fillMaxWidth().glassSurface(RoundedCornerShape(22.dp), 2f, 8.dp).padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("智能助手 · ${AssistantController.personaName}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink)
            Text(
                "步骤 ${AssistantController.step}/${AssistantController.MAX_STEPS} · ${AssistantController.statusText}",
                fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            if (AssistantController.status == AssistantController.Status.PAUSED) "继续" else "暂停",
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.bounceClick(pressedScale = 0.9f) { AssistantController.togglePause() }
                .clip(RoundedCornerShape(16.dp)).background(Color(0xFF636366)).padding(horizontal = 14.dp, vertical = 8.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "停止", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.bounceClick(pressedScale = 0.9f) { AssistantController.stop("你已停止任务。") }
                .clip(RoundedCornerShape(16.dp)).background(Color(0xFFFF453A)).padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
