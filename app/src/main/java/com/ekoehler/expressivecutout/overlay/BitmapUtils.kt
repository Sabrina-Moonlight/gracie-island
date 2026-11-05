package com.ekoehler.expressivecutout.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap

/** Log tag for the bitmap decoding below, which fails softly rather than throwing. */
private const val TAG = "BitmapUtils"

/**
 * Longest edge a badge icon is scaled down to. Icons arrive at whatever size their app chose, and
 * the island draws them a few dp across.
 */
private const val MAX_ICON_PX = 128

/**
 * Longest edge album art is scaled down to. Larger than an icon because the art fills the expanded
 * island, but still far below the full-size bitmap a player hands over.
 */
private const val MAX_ART_PX = 256

/** Rasterises any [Drawable] (e.g. an app launcher icon) into an [ImageBitmap]. */
fun Drawable.toImageBitmap(): ImageBitmap {
    if (this is BitmapDrawable) {
        bitmap?.let { return it.asImageBitmap() }
    }
    val width = intrinsicWidth.takeIf { it > 0 } ?: MAX_ICON_PX
    val height = intrinsicHeight.takeIf { it > 0 } ?: MAX_ICON_PX
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}

/**
 * Rasterises a notification's own [Icon] (its large icon or its small status-bar glyph), or null if
 * it cannot be loaded — a resource icon has to be read out of the posting app's package, which can
 * fail if that app is not visible to us or has since been uninstalled. Callers fall back.
 */
fun Icon.loadImageBitmapOrNull(context: Context): ImageBitmap? = runCatching {
    loadDrawable(context)?.toImageBitmap()
}.onFailure { Log.w(TAG, "Unable to load notification icon $this", it) }.getOrNull()

/**
 * Loads a user-picked image URI into a down-sampled [ImageBitmap], or null if it can no
 * longer be read (revoked permission, deleted file). Callers fall back to the default.
 */
fun Uri.loadImageBitmapOrNull(context: Context): ImageBitmap? = runCatching {
    context.contentResolver.openInputStream(this)?.use { stream ->
        android.graphics.BitmapFactory.decodeStream(stream)
            ?.scaledToIcon()
            ?.asImageBitmap()
    }
}.onFailure { Log.w(TAG, "Unable to load custom icon $this", it) }.getOrNull()

private fun Bitmap.scaledToIcon(): Bitmap = scaledToMax(MAX_ICON_PX)

/** Down-samples a media album-art [Bitmap] to a display-friendly [ImageBitmap]. */
fun Bitmap.toArtImageBitmap(): ImageBitmap =
    scaledToMax(MAX_ART_PX).asImageBitmap().withoutBlackBars()

/** Darkest-channel level at or below which a pixel counts as part of a black bar. */
private const val BLACK_BAR_MAX_CHANNEL = 18

/** Share of a row or column that must be black for it to count as a bar (video art is not perfectly clean). */
private const val BLACK_BAR_SHARE = 0.97f

/** A bar thinner than this many pixels is left alone; it is more likely part of the picture. */
private const val BLACK_BAR_MIN_PX = 3

/** Never trim away more than this share of the picture, in case the whole image is simply dark. */
private const val BLACK_BAR_MAX_TRIM = 0.6f

/**
 * Cuts the black bars off the edges of an album cover. Videos (YouTube and the like) publish their
 * 16:9 thumbnail with black bands above and below the picture, and the island crops covers to fill
 * a square, so those bands showed up as dark stripes. Only edges that are black across their whole
 * length are removed; a cover that is merely dark, or has no bars, comes back unchanged.
 */
fun ImageBitmap.withoutBlackBars(): ImageBitmap = runCatching {
    val source = asAndroidBitmap()
    // A hardware bitmap cannot be read pixel by pixel, so read from a software copy.
    val readable: Bitmap = (
        if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }
        ) ?: return@runCatching this
    val w = readable.width
    val h = readable.height
    if (w < 16 || h < 16) return@runCatching this

    val row = IntArray(w)
    val col = IntArray(h)
    fun isBlack(pixel: Int): Boolean {
        val alpha = pixel ushr 24
        if (alpha < 40) return true
        return maxOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF) <= BLACK_BAR_MAX_CHANNEL
    }
    fun rowIsBar(y: Int): Boolean {
        readable.getPixels(row, 0, w, 0, y, w, 1)
        return row.count { isBlack(it) } >= w * BLACK_BAR_SHARE
    }
    fun colIsBar(x: Int): Boolean {
        readable.getPixels(col, 0, 1, x, 0, 1, h)
        return col.count { isBlack(it) } >= h * BLACK_BAR_SHARE
    }

    val maxRows = (h * BLACK_BAR_MAX_TRIM / 2).toInt()
    val maxCols = (w * BLACK_BAR_MAX_TRIM / 2).toInt()
    var top = 0
    while (top < maxRows && rowIsBar(top)) top++
    var bottom = 0
    while (bottom < maxRows && rowIsBar(h - 1 - bottom)) bottom++
    var left = 0
    while (left < maxCols && colIsBar(left)) left++
    var right = 0
    while (right < maxCols && colIsBar(w - 1 - right)) right++

    if (top < BLACK_BAR_MIN_PX) top = 0
    if (bottom < BLACK_BAR_MIN_PX) bottom = 0
    if (left < BLACK_BAR_MIN_PX) left = 0
    if (right < BLACK_BAR_MIN_PX) right = 0
    if (top == 0 && bottom == 0 && left == 0 && right == 0) return@runCatching this

    Bitmap.createBitmap(readable, left, top, w - left - right, h - top - bottom).asImageBitmap()
}.getOrDefault(this)

/**
 * Scales a bitmap down so its longest edge is at most [maxPx], returning it untouched when it is
 * already small enough. Icons and album art arrive at whatever size their app chose, and the island
 * draws them a few dp across.
 */
private fun Bitmap.scaledToMax(maxPx: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxPx) return this
    val scale = maxPx.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
}
