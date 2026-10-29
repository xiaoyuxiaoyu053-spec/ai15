package com.deepline.privateapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ───────────────────────────── Glass style ─────────────────────────────

/** Global glass settings; provided once at the app root so every surface stays consistent. */
internal data class GlassStyle(val enabled: Boolean, val opacity: Float)

internal val LocalGlass = compositionLocalOf { GlassStyle(true, 0.34f) }

/** Tint used by glassSurface when none is given (white by default, charcoal on dark iMessage chats). */
internal val LocalGlassTint = compositionLocalOf { Color.White }

/** True when the wallpaper is dark: text drawn straight on the wallpaper switches to white. */
internal val LocalWallpaperDark = compositionLocalOf { false }

@Composable
fun wallInk(): Color = if (LocalWallpaperDark.current) Color.White else Ink

@Composable
fun wallMuted(): Color = if (LocalWallpaperDark.current) Color.White.copy(alpha = 0.62f) else Muted

/** Frosted, iOS-style translucent surface: soft gradient fill + bright specular rim. */
fun Modifier.glass(shape: Shape, opacity: Float, tint: Color = Color.White, shadow: Dp = 0.dp): Modifier {
    val a = opacity.coerceIn(0.04f, 0.96f)
    val fill = Brush.verticalGradient(
        listOf(
            tint.copy(alpha = (a * 1.35f).coerceAtMost(0.97f)),
            tint.copy(alpha = (a * 0.85f).coerceAtMost(0.97f))
        )
    )
    val rim = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.95f),
            Color.White.copy(alpha = 0.22f),
            Color.White.copy(alpha = 0.70f)
        )
    )
    val base = if (shadow > 0.dp) {
        this.shadow(shadow, shape, clip = false, ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
    } else this
    return base.clip(shape).background(fill).border(1.dp, rim, shape)
}

/** Glass surface that follows the user's glass settings (opacity slider / glass switch). */
fun Modifier.glassSurface(shape: Shape, strength: Float = 1f, shadow: Dp = 0.dp, tint: Color = Color.Unspecified): Modifier = composed {
    val g = LocalGlass.current
    val resolvedTint = if (tint == Color.Unspecified) LocalGlassTint.current else tint
    val o = if (g.enabled) (g.opacity * strength).coerceIn(0.05f, 0.92f) else 0.95f
    Modifier.glass(shape, o, resolvedTint, shadow)
}

/** Full-width bar (top header / bottom tab bar) with a single hairline edge. */
fun Modifier.glassBar(edgeTop: Boolean): Modifier = composed {
    val g = LocalGlass.current
    val o = if (g.enabled) (g.opacity * 1.8f).coerceIn(0.18f, 0.9f) else 0.96f
    Modifier
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = o), Color.White.copy(alpha = o * 0.86f))))
        .drawBehind {
            val y = if (edgeTop) 0.5f else size.height - 0.5f
            drawLine(Color.Black.copy(alpha = 0.08f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
}

/** Soft fade at the top/bottom of a scrolling list so content melts under glass bars. */
fun Modifier.fadingEdges(top: Dp, bottom: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val t = top.toPx()
        val b = bottom.toPx()
        if (t > 0f) {
            drawRect(
                brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = t),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, t),
                blendMode = BlendMode.DstIn
            )
        }
        if (b > 0f) {
            drawRect(
                brush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = size.height - b, endY = size.height),
                topLeft = Offset(0f, size.height - b),
                size = Size(size.width, b),
                blendMode = BlendMode.DstIn
            )
        }
    }

// ───────────────────────────── Bounce animation ─────────────────────────────

/** Press-down squish that springs back with a bounce. Replaces clickable for every tappable element. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bounceClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.9f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bounce-click"
    )
    Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick
        )
}

/** Entrance: scale + rise + fade with an overshoot spring. */
fun Modifier.bounceIn(
    enabled: Boolean = true,
    delayMillis: Int = 0,
    initialScale: Float = 0.82f,
    rise: Float = 18f,
    origin: TransformOrigin = TransformOrigin.Center
): Modifier = if (!enabled) this else composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMillis > 0) delay(delayMillis.toLong())
        progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
    }
    Modifier.graphicsLayer {
        val v = progress.value
        val s = initialScale + (1f - initialScale) * v
        scaleX = s
        scaleY = s
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * rise * density
        transformOrigin = origin
    }
}

// ───────────────────────────── Wallpaper ─────────────────────────────

/**
 * App-wide wallpaper. A user photo (Settings → background) wins; otherwise a silvery
 * "liquid glass" water surface is painted procedurally and drifts very slowly.
 */
@Composable
fun GlassWallpaper(uri: String, dim: Float, blur: Float, dark: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        val base = if (dark) {
            listOf(Color(0xFF0A0A0C), Color(0xFF1A0B10), Color(0xFF0A0A0C))
        } else {
            listOf(Color(0xFFF3F5F8), Color(0xFFD3D9E0), Color(0xFFEDEFF3), Color(0xFFC5CDD5))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(base)))
        if (uri.isNotBlank()) {
            PickedPhoto(
                uri,
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = (1f - dim).coerceIn(0.15f, 1f)
                    if (Build.VERSION.SDK_INT >= 31 && blur > 0f) {
                        renderEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP).asComposeRenderEffect()
                    }
                },
                rounded = false,
                maxPx = 1600
            )
        } else {
            WaterCaustics(dark)
        }
    }
}

@Composable
private fun WaterCaustics(dark: Boolean) {
    val transition = rememberInfiniteTransition(label = "water")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Restart),
        label = "water-phase"
    )
    Canvas(Modifier.fillMaxSize()) {
        val p = phase.value * 2f * Math.PI.toFloat()
        val w = size.width
        val h = size.height
        val glow = if (dark) Color(0xFFFF4060) else Color.White
        val glowAlpha = if (dark) 0.13f else 0.62f
        for (k in 0 until 9) {
            val fx = 0.10f + 0.80f * (((k * 37) % 100) / 100f)
            val fy = 0.05f + 0.90f * (((k * 61) % 100) / 100f)
            val cx = w * fx + w * 0.05f * kotlin.math.sin(p + k * 1.7f)
            val cy = h * fy + h * 0.025f * kotlin.math.cos(p + k * 2.3f)
            val r = w * (0.26f + 0.16f * (((k * 13) % 10) / 10f))
            drawCircle(
                brush = Brush.radialGradient(listOf(glow.copy(alpha = glowAlpha), Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r,
                center = Offset(cx, cy)
            )
        }
        val trough = if (dark) Color(0xFF000000) else Color(0xFF8995A2)
        val troughAlpha = if (dark) 0.35f else 0.17f
        for (k in 0 until 5) {
            val cx = w * (0.15f + 0.70f * (((k * 29 + 11) % 100) / 100f)) + w * 0.04f * kotlin.math.cos(p + k * 1.3f)
            val cy = h * (0.10f + 0.80f * (((k * 53 + 7) % 100) / 100f)) + h * 0.02f * kotlin.math.sin(p + k * 0.9f)
            val r = w * (0.22f + 0.10f * ((k % 3) / 3f))
            drawCircle(
                brush = Brush.radialGradient(listOf(trough.copy(alpha = troughAlpha), Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r,
                center = Offset(cx, cy)
            )
        }
    }
}

// ───────────────────────────── Images ─────────────────────────────

/** Average brightness (0..1) of the wallpaper photo, used to pick light/dark iMessage styling. */
@Composable
fun rememberPhotoLuminance(uri: String): State<Float> {
    val context = LocalContext.current
    return produceState(initialValue = 0.85f, uri) {
        value = if (uri.isBlank()) 0.85f else withContext(Dispatchers.IO) {
            val bmp = BitmapLoader.load(context, uri, 24)
            if (bmp == null) {
                0.85f
            } else {
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                var sum = 0.0
                for (c in px) {
                    val r = (c shr 16) and 255
                    val g = (c shr 8) and 255
                    val b = c and 255
                    sum += 0.299 * r + 0.587 * g + 0.114 * b
                }
                (sum / (px.size * 255.0)).toFloat()
            }
        }
    }
}

internal object BitmapLoader {
    private val cache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L).coerceAtMost(96L * 1024L * 1024L).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun open(context: Context, value: String): java.io.InputStream? {
        return try {
            if (value.startsWith("/")) {
                java.io.FileInputStream(value)
            } else {
                val uri = Uri.parse(value)
                val path = uri.path
                if (uri.scheme == "file" && path != null) java.io.FileInputStream(path) else context.contentResolver.openInputStream(uri)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Decodes a down-sampled bitmap (long edge between maxPx and 2*maxPx) with EXIF rotation applied. */
    fun load(context: Context, value: String, maxPx: Int): Bitmap? {
        if (value.isBlank()) return null
        val key = "$value@$maxPx"
        cache.get(key)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(context, value)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            if (longEdge <= 0) return null
            var sample = 1
            while (longEdge / (sample * 2) >= maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = open(context, value)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            val orientation = try {
                open(context, value)?.use {
                    android.media.ExifInterface(it).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: android.media.ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) {
                android.media.ExifInterface.ORIENTATION_NORMAL
            }
            val degrees = when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }
            cache.put(key, bmp)
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}

/** Decodes off the main thread so scrolling never stalls on big photos. */
@Composable
fun rememberLocalBitmap(uri: String, maxPx: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, uri, maxPx) {
        value = if (uri.isBlank()) null else withContext(Dispatchers.IO) { BitmapLoader.load(context, uri, maxPx)?.asImageBitmap() }
    }
}

@Composable
fun PickedPhoto(uri: String, modifier: Modifier = Modifier, rounded: Boolean = true, maxPx: Int = 1280) {
    val bitmap by rememberLocalBitmap(uri, maxPx)
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bmp,
            contentDescription = "图片",
            modifier = if (rounded) modifier.clip(RoundedCornerShape(16.dp)) else modifier,
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun Avatar(uri: String, name: String, size: Int, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val bitmap by rememberLocalBitmap(uri, (size * density * 1.5f).toInt().coerceIn(96, 720))
    val bmp = bitmap
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.85f))
            .border(1.5.dp, Color.White, CircleShape)
            .padding(1.5.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFEAF0FA), Color(0xFFC9D5EA)))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name.take(1).ifBlank { "?" },
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size * 0.42f).sp
                )
            }
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 56.dp, start = 24.dp, end = 24.dp).bounceIn(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(76.dp).glassSurface(CircleShape, 1.3f, 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Forum, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = wallInk())
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = wallMuted(), fontSize = 13.sp)
    }
}

/** Three bouncing dots for "AI is typing". */
@Composable
fun FloatingTypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    Row(
        modifier = Modifier.padding(horizontal = 3.dp, vertical = 5.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val y by transition.animateFloat(
                initialValue = 2f,
                targetValue = -5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 420, delayMillis = index * 120, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "typing-dot-$index"
            )
            Box(Modifier.offset(y = y.dp).size(7.dp).background(Color(0xFF7D8A94), CircleShape))
        }
    }
}
