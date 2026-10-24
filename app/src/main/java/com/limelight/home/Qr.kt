package com.limelight.home

/**
 * A small QR code encoder (byte mode, error correction M, versions 1 to 10: up to 213 bytes), for the TV's Xbox
 * sign-in card: a phone camera opens microsoft.com/link with the code filled in. Made on the device, no QR service.
 * A compact port of Project Nayuki's QR Code generator (MIT licence, nayuki.io/page/qr-code-generator-library).
 */
object Qr {
    // error correction M, versions 1..10
    private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val NUM_BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
    private const val ECC_FORMAT_BITS = 0   // M

    /** The modules, [y][x], true = dark. Null if [text] is too long. */
    @JvmStatic
    fun encode(text: String): Array<BooleanArray>? {
        val data = text.toByteArray(Charsets.UTF_8)
        var ver = 1
        while (ver <= 10) {
            val bits = 4 + (if (ver < 10) 8 else 16) + data.size * 8
            if (bits <= dataCodewords(ver) * 8) break
            ver++
        }
        if (ver > 10) return null
        // data bits: byte mode, length, bytes, terminator, padding
        val bb = ArrayList<Int>()
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bb.add((v ushr i) and 1) }
        put(4, 4); put(data.size, if (ver < 10) 8 else 16)
        data.forEach { put(it.toInt() and 0xFF, 8) }
        val cap = dataCodewords(ver) * 8
        put(0, minOf(4, cap - bb.size))
        put(0, (8 - bb.size % 8) % 8)
        var pad = 0xEC
        while (bb.size < cap) { put(pad, 8); pad = pad xor (0xEC xor 0x11) }
        val codewords = ByteArray(bb.size / 8)
        for (i in bb.indices) codewords[i ushr 3] = (codewords[i ushr 3].toInt() or (bb[i] shl (7 - (i and 7)))).toByte()

        val q = Grid(ver)
        q.functionPatterns()
        q.drawCodewords(addEcc(codewords, ver))
        var best = 0; var bestPenalty = Int.MAX_VALUE
        for (m in 0 until 8) {
            q.applyMask(m); q.drawFormat(m)
            val p = q.penalty()
            if (p < bestPenalty) { best = m; bestPenalty = p }
            q.applyMask(m)   // XOR again undoes it
        }
        q.applyMask(best); q.drawFormat(best)
        return q.modules
    }

    private fun rawModules(ver: Int): Int {
        var r = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val n = ver / 7 + 2
            r -= (25 * n - 10) * n - 55
            if (ver >= 7) r -= 36
        }
        return r
    }

    private fun dataCodewords(ver: Int) = rawModules(ver) / 8 - ECC_PER_BLOCK[ver - 1] * NUM_BLOCKS[ver - 1]

    private fun addEcc(data: ByteArray, ver: Int): ByteArray {
        val numBlocks = NUM_BLOCKS[ver - 1]; val eccLen = ECC_PER_BLOCK[ver - 1]
        val raw = rawModules(ver) / 8
        val numShort = numBlocks - raw % numBlocks
        val shortLen = raw / numBlocks
        val div = rsDivisor(eccLen)
        val blocks = ArrayList<ByteArray>()
        var k = 0
        for (i in 0 until numBlocks) {
            val datLen = shortLen - eccLen + (if (i < numShort) 0 else 1)
            val dat = data.copyOfRange(k, k + datLen); k += datLen
            val ecc = rsRemainder(dat, div)
            val block = ByteArray(shortLen + 1)
            // short blocks get a gap at datLen so all blocks line up
            System.arraycopy(dat, 0, block, 0, dat.size)
            System.arraycopy(ecc, 0, block, shortLen + 1 - eccLen, eccLen)
            blocks.add(block)
        }
        val out = ByteArray(raw); var n = 0
        for (i in 0..shortLen) for (j in blocks.indices) {
            if (i != shortLen - eccLen || j >= numShort) out[n++] = blocks[j][i]
        }
        return out
    }

    private fun mul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11D); z = z xor (((y ushr i) and 1) * x) }
        return z
    }

    private fun rsDivisor(degree: Int): IntArray {
        val r = IntArray(degree); r[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in r.indices) { r[j] = mul(r[j], root); if (j + 1 < r.size) r[j] = r[j] xor r[j + 1] }
            root = mul(root, 0x02)
        }
        return r
    }

    private fun rsRemainder(data: ByteArray, div: IntArray): ByteArray {
        val r = IntArray(div.size)
        for (b in data) {
            val f = (b.toInt() and 0xFF) xor r[0]
            System.arraycopy(r, 1, r, 0, r.size - 1); r[r.size - 1] = 0
            for (i in r.indices) r[i] = r[i] xor mul(div[i], f)
        }
        return ByteArray(r.size) { r[it].toByte() }
    }

    private class Grid(val ver: Int) {
        val size = ver * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; isFunction[y][x] = true }

        fun functionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val pos = alignmentPositions()
            for (i in pos.indices) for (j in pos.indices) {
                if (i == 0 && j == 0 || i == 0 && j == pos.size - 1 || i == pos.size - 1 && j == 0) continue
                for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            drawFormat(0)
            if (ver >= 7) {
                var rem = ver
                for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
                val bits = (ver shl 12) or rem
                for (i in 0 until 18) {
                    val bit = ((bits ushr i) and 1) != 0
                    val a = size - 11 + i % 3; val b = i / 3
                    set(a, b, bit); set(b, a, bit)
                }
            }
        }

        private fun finder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(Math.abs(dx), Math.abs(dy)); val xx = x + dx; val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) set(xx, yy, d != 2 && d != 4)
            }
        }

        private fun alignmentPositions(): IntArray {
            if (ver == 1) return IntArray(0)
            val num = ver / 7 + 2
            val step = (ver * 8 + num * 3 + 5) / (num * 4 - 4) * 2
            val r = IntArray(num); r[0] = 6
            var pos = size - 7
            for (i in num - 1 downTo 1) { r[i] = pos; pos -= step }
            return r
        }

        fun drawFormat(mask: Int) {
            val data = (ECC_FORMAT_BITS shl 3) or mask
            var rem = data
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((data shl 10) or rem) xor 0x5412
            fun b(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) set(8, i, b(i))
            set(8, 7, b(6)); set(8, 8, b(7)); set(7, 8, b(8))
            for (i in 9 until 15) set(14 - i, 8, b(i))
            for (i in 0 until 8) set(size - 1 - i, 8, b(i))
            for (i in 8 until 15) set(8, size - 15 + i, b(i))
            set(8, size - 8, true)
        }

        fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) {
                        modules[y][x] = (((data[i ushr 3].toInt() and 0xFF) ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        fun applyMask(m: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val inv = when (m) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (inv && !isFunction[y][x]) modules[y][x] = !modules[y][x]
            }
        }

        /** A simplified penalty (runs, 2x2 blocks, dark balance): enough to pick a mask that scans well. */
        fun penalty(): Int {
            var p = 0
            for (y in 0 until size) {
                var run = 1
                for (x in 1 until size) { if (modules[y][x] == modules[y][x - 1]) { run++; if (run == 5) p += 3 else if (run > 5) p++ } else run = 1 }
            }
            for (x in 0 until size) {
                var run = 1
                for (y in 1 until size) { if (modules[y][x] == modules[y - 1][x]) { run++; if (run == 5) p += 3 else if (run > 5) p++ } else run = 1 }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3
            }
            var dark = 0
            for (row in modules) for (c in row) if (c) dark++
            val total = size * size
            p += (Math.abs(dark * 20 - total * 10) + total - 1) / total * 10
            return p
        }
    }

    /** A bitmap of [modules] with a 4-module quiet zone, [px] pixels per module, black on white. */
    @JvmStatic
    fun bitmap(modules: Array<BooleanArray>, px: Int): android.graphics.Bitmap {
        val n = modules.size + 8
        val bmp = android.graphics.Bitmap.createBitmap(n * px, n * px, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFFFFFFFF.toInt())
        for (y in modules.indices) for (x in modules.indices) if (modules[y][x])
            for (dy in 0 until px) for (dx in 0 until px) bmp.setPixel((x + 4) * px + dx, (y + 4) * px + dy, 0xFF000000.toInt())
        return bmp
    }
}
