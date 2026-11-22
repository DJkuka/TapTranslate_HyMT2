package com.tap_to_translate.snap_translate.hymt2.ocr.ppocrv6

import android.graphics.RectF
import com.paddle.ocr.model.OCRBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OCRTextGroup(val id: Int, val lineIndices: List<Int>, val bounds: RectF)

/** Groups adjacent OCR line boxes into approximate paragraph/text zones. */
object OCRTextGrouper {
    fun bounds(box: OCRBox): RectF {
        val p = box.points
        return RectF(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })
    }

    fun group(boxes: List<OCRBox>): List<OCRTextGroup> {
        if (boxes.isEmpty()) return emptyList()
        val rects = boxes.map(::bounds)
        val parent = IntArray(boxes.size) { it }
        fun root(v: Int): Int {
            var x = v
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun union(a: Int, b: Int) {
            val x = root(a); val y = root(b)
            if (x != y) parent[y] = x
        }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            if (sameBlock(rects[i], rects[j])) union(i, j)
        }
        val grouped = rects.indices.groupBy(::root).values.map { ids ->
            val l = ids.minOf { rects[it].left }; val t = ids.minOf { rects[it].top }
            val r = ids.maxOf { rects[it].right }; val b = ids.maxOf { rects[it].bottom }
            val w = r - l; val h = b - t
            ids.sorted() to RectF(l - max(3f, w * .025f), t - max(3f, h * .04f),
                r + max(3f, w * .025f), b + max(3f, h * .04f))
        }.sortedWith(compareBy<Pair<List<Int>, RectF>> { it.second.top }.thenBy { it.second.left })
        return grouped.mapIndexed { i, v -> OCRTextGroup(i + 1, v.first, v.second) }
    }

    private fun sameBlock(a: RectF, b: RectF): Boolean {
        val minH = min(a.height(), b.height()).coerceAtLeast(1f)
        val maxH = max(a.height(), b.height()).coerceAtLeast(1f)
        val overlapY = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top)) / minH
        if (overlapY > .35f) return false
        val gapY = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
        if (gapY > max(14f, maxH * 1.65f)) return false
        val overlapX = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val overlapRatio = overlapX / min(a.width(), b.width()).coerceAtLeast(1f)
        val dx = abs(a.centerX() - b.centerX())
        val maxW = max(a.width(), b.width())
        return (overlapRatio >= .16f || dx <= max(maxW * .32f, maxH * .55f)) &&
            dx <= max(maxW * .85f, maxH * 2.2f)
    }
}
