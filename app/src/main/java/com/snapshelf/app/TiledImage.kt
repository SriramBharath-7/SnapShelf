package com.snapshelf.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TileSize = 1024

private data class ImageInfo(val width: Int, val height: Int)
private data class TileKey(val path: String, val sample: Int, val left: Int, val top: Int)

private object TileCache {
    private val cache = object : LinkedHashMap<TileKey, Bitmap>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, Bitmap>?) = size > 20
    }

    @Synchronized fun get(key: TileKey): Bitmap? = cache[key]

    @Synchronized fun put(key: TileKey, bitmap: Bitmap) {
        cache[key]?.recycle()
        cache[key] = bitmap
    }
}

@Composable
fun TiledImage(
    path: String,
    initialReadingPosition: Float,
    onReadingPositionChanged: (Float) -> Unit,
    onHorizontalSwipe: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var info by remember(path) { mutableStateOf<ImageInfo?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offsetX by remember(path) { mutableFloatStateOf(0f) }
    var offsetY by remember(path) { mutableFloatStateOf(0f) }
    var positionInitialized by remember(path) { mutableStateOf(false) }
    val loadedTiles = remember(path) { mutableStateMapOf<TileKey, Bitmap>() }

    LaunchedEffect(path) {
        info = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) ImageInfo(bounds.outWidth, bounds.outHeight) else null
        }
    }

    BoxWithConstraints(
        modifier.onSizeChanged { viewport = it }
    ) {
        val imageInfo = info
        if (imageInfo == null || viewport.width == 0 || viewport.height == 0) return@BoxWithConstraints
        val baseScale = min(
            viewport.width.toFloat() / imageInfo.width,
            viewport.height.toFloat() / imageInfo.height
        )
        val effectiveScale = baseScale * scale
        val contentWidth = imageInfo.width * effectiveScale
        val contentHeight = imageInfo.height * effectiveScale
        val centeredX = (viewport.width - contentWidth) / 2f
        val maxPanX = max(0f, (contentWidth - viewport.width) / 2f)
        val maxPanY = max(0f, (contentHeight - viewport.height) / 2f)
        if (!positionInitialized) {
            LaunchedEffect(imageInfo, viewport) {
                offsetY = -maxPanY * initialReadingPosition.coerceIn(0f, 1f)
                positionInitialized = true
            }
        }
        val x = centeredX + offsetX.coerceIn(-maxPanX, maxPanX)
        val y = (viewport.height - contentHeight) / 2f + offsetY.coerceIn(-maxPanY, maxPanY)
        val sample = remember(effectiveScale) {
            var value = 1
            while (effectiveScale * value * 2f <= 1f) value *= 2
            value
        }
        val sourceLeft = ((-x) / effectiveScale).coerceIn(0f, imageInfo.width.toFloat())
        val sourceTop = ((-y) / effectiveScale).coerceIn(0f, imageInfo.height.toFloat())
        val sourceRight = ((viewport.width - x) / effectiveScale).coerceIn(0f, imageInfo.width.toFloat())
        val sourceBottom = ((viewport.height - y) / effectiveScale).coerceIn(0f, imageInfo.height.toFloat())
        val leftTile = (sourceLeft.toInt() / TileSize) * TileSize
        val topTile = (sourceTop.toInt() / TileSize) * TileSize
        val rightTile = (((sourceRight.toInt() - 1).coerceAtLeast(0) / TileSize) * TileSize)
            .coerceAtMost(imageInfo.width - 1)
        val bottomTile = (((sourceBottom.toInt() - 1).coerceAtLeast(0) / TileSize) * TileSize)
            .coerceAtMost(imageInfo.height - 1)
        val tileKeys = buildList {
            for (top in topTile..bottomTile step TileSize) {
                for (left in leftTile..rightTile step TileSize) {
                    add(TileKey(path, sample, left, top))
                }
            }
        }

        LaunchedEffect(tileKeys) {
            val missing = tileKeys.filter { it !in loadedTiles && TileCache.get(it) == null }
            val decoded = withContext(Dispatchers.IO) {
                missing.map { key -> async { key to decodeTile(key, imageInfo) } }.awaitAll()
            }
            decoded.forEach { (key, bitmap) ->
                if (bitmap != null) {
                    TileCache.put(key, bitmap)
                    loadedTiles[key] = bitmap
                }
            }
            tileKeys.forEach { key -> TileCache.get(key)?.let { loadedTiles[key] = it } }
        }

        Canvas(
            Modifier
                .pointerInput(path) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (scale <= 1.01f && zoom == 1f && abs(pan.x) > abs(pan.y) && abs(pan.x) > 40f) {
                            onHorizontalSwipe(pan.x < 0)
                        } else {
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offsetX = (offsetX + pan.x).coerceIn(-maxPanX, maxPanX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxPanY, maxPanY)
                            val position = if (maxPanY == 0f) 0f else (-offsetY + maxPanY) / (2f * maxPanY)
                            onReadingPositionChanged(position.coerceIn(0f, 1f))
                        }
                    }
                }
        ) {
            drawRect(Color.Black)
            tileKeys.forEach { key ->
                val bitmap = loadedTiles[key] ?: return@forEach
                val destinationLeft = x + key.left * effectiveScale
                val destinationTop = y + key.top * effectiveScale
                val destinationRight = destinationLeft + bitmap.width * sample * effectiveScale
                val destinationBottom = destinationTop + bitmap.height * sample * effectiveScale
                drawImage(
                    bitmap.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset(destinationLeft.toInt(), destinationTop.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(
                        (destinationRight - destinationLeft).toInt(),
                        (destinationBottom - destinationTop).toInt()
                    )
                )
            }
        }
    }
}

private fun decodeTile(key: TileKey, info: ImageInfo): Bitmap? {
    val decoder = BitmapRegionDecoder.newInstance(key.path, false)
    return try {
        val right = min(info.width, key.left + TileSize)
        val bottom = min(info.height, key.top + TileSize)
        val options = BitmapFactory.Options().apply {
            inSampleSize = key.sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        decoder.decodeRegion(Rect(key.left, key.top, right, bottom), options)
    } finally {
        decoder.recycle()
    }
}
