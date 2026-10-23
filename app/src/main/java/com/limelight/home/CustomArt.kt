package com.limelight.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import java.io.File
import java.security.MessageDigest

/**
 * Your own picture for a game tile (a PC game, a PS5, an Xbox or an Android app): picked from the device's photos,
 * kept as a resized copy in the app's own storage (never shared), keyed by the tile's game key.
 */
object CustomArt {
    private const val MAX = 900   // the longest side kept: sharp on the biggest tiles, small on disk
    // pictures by bytes (about 24 MB), not by count: 24 pictures of 900 px could take 50 MB
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // which keys have a picture, read from disk once: asking the disk for every tile on every redraw cost 100 file checks
    private var known: MutableSet<String>? = null
    private val colors = HashMap<String, Int>()

    private fun knownKeys(c: Context): MutableSet<String> = known ?: HashSet<String>().also { set ->
        File(c.filesDir, "tile-art").list()?.forEach { set += it.removeSuffix(".jpg") }
        known = set
    }

    private fun hash(key: String) = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun file(c: Context, key: String) = File(File(c.filesDir, "tile-art").apply { mkdirs() }, "${hash(key)}.jpg")

    @JvmStatic fun has(c: Context, key: String) = hash(key) in knownKeys(c)

    /** The picture for [key], or null when there's none. */
    @JvmStatic fun bitmap(c: Context, key: String): Bitmap? {
        cache.get(key)?.let { return it }
        if (!has(c, key)) return null
        val f = file(c, key)
        return BitmapFactory.decodeFile(f.path)?.also { cache.put(key, it) }
    }

    /** Copies the picked image in, resized; false when it can't be read. Call it off the main thread. */
    @JvmStatic fun set(c: Context, key: String, uri: Uri): Boolean = try {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
                val s = maxOf(info.size.width, info.size.height)
                if (s > MAX) d.setTargetSize(info.size.width * MAX / s, info.size.height * MAX / s)
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= MAX) sample *= 2
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw IllegalStateException("unreadable")
        }
        file(c, key).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        cache.put(key, bmp); knownKeys(c) += hash(key); colors.remove(key)
        true
    } catch (e: Exception) {
        android.util.Log.w("MoonCast", "custom picture: $e"); false
    }

    @JvmStatic fun clear(c: Context, key: String) { file(c, key).delete(); cache.remove(key); knownKeys(c) -= hash(key); colors.remove(key) }

    /** The picture's average colour, for the background wash (worked out once per picture). */
    @JvmStatic fun color(key: String, b: Bitmap): Int = colors.getOrPut(key) { Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0) }
}
