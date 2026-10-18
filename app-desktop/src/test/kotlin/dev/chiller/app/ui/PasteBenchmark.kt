package dev.chiller.app.ui

import dev.chiller.app.di.AttachmentRepository
import dev.chiller.contracts.ArtifactRef
import dev.chiller.contracts.ArtifactStore
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.atomic.AtomicLong

/**
 * Paste preparation benchmark (night audit acceptance): image encoding and attachment storage off the UI
 * thread, repeated, with p50/p95 and the peak heap growth while a case runs. Writes
 * build/reports/paste-benchmark.md. Headless: it does not include reading the native clipboard or first paint.
 */
fun main(args: Array<String>) {
    val repeats = args.firstOrNull()?.toIntOrNull() ?: 30
    val rows = mutableListOf<String>()
    val random = Random(7)
    fun image(kind: String, w: Int, h: Int): BufferedImage = when (kind) {
        "photo" -> BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { img ->
            // Smooth gradients with mild grain: compresses like a screenshot of a photo.
            img.createGraphics().apply { paint = GradientPaint(0f, 0f, Color(30, 90, 160), w.toFloat(), h.toFloat(), Color(240, 200, 120)); fillRect(0, 0, w, h); dispose() }
            for (y in 0 until h step 3) for (x in 0 until w step 3) { val p = img.getRGB(x, y); img.setRGB(x, y, p xor (random.nextInt(8) * 0x010101)) }
        }
        "noise" -> BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { img -> for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, random.nextInt()) }
        else -> BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { img ->
            for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, ((x * 255 / w) shl 24) or 0x3366AA)
        }
    }
    val sizes = listOf("1080p" to (1920 to 1080), "4K" to (3840 to 2160), "8K" to (7680 to 4320))
    for ((label, size) in sizes.takeIf { args.getOrNull(1) != "text" }.orEmpty()) for (kind in listOf("photo", "noise", "alpha")) {
        val source = image(kind, size.first, size.second)
        var last: EncodedPaste? = null
        val (times, peak) = measure(repeats) { last = encodePastedImage(source) }
        val r = last
        rows += "| $label $kind | ${stats(times)} | ${peak / (1024 * 1024)} MiB | ${r?.let { "${it.png.size / 1024} KiB, ${it.width}×${it.height}${if (it.scaled) ", уменьшено" else ""}" } ?: "не уложилось"} |"
        println(rows.last())
    }
    val store = object : ArtifactStore {
        override suspend fun put(sessionId: String, content: String) = ArtifactRef(java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }, sessionId, content.length.toLong())
        override suspend fun read(sessionId: String, artifactId: String, offset: Int, limit: Int) = ""
    }
    val dir = Files.createTempDirectory("chiller-paste-bench")
    val textRows = mutableListOf<String>()
    try {
        val repo = AttachmentRepository(dir, store)
        for ((label, bytes) in listOf("5 KiB" to 5 * 1024, "1 MiB" to 1024 * 1024, "8 MiB" to 8 * 1024 * 1024 - 4096)) {
            val text = "строка текста для вставки, ".repeat(bytes / 50 + 1).take(bytes / 2)
            val (times, peak) = measure(repeats) { runBlocking { repo.importText("bench", text) } }
            textRows += "| текст $label (${text.toByteArray().size / 1024} KiB UTF-8) | ${stats(times)} | ${peak / (1024 * 1024)} MiB |"
            println(textRows.last())
        }
        // Vision payload: the first round prepares the image, later rounds must reuse it.
        val png = encodePastedImage(image("photo", 3840, 2160))!!.png
        val item = runBlocking { repo.importImage("bench", png) }
        val first = timed { repo.modelImage("bench", item.artifactId) }
        val later = (1..5).map { timed { repo.modelImage("bench", item.artifactId) } }
        textRows += "| vision 4K: первый раунд / следующие 5 | ${"%.1f".format(first)} мс / до ${"%.2f".format(later.max())} мс | — |"
    } finally { dir.toFile().deleteRecursively() }

    val os = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, JDK ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} CPU"
    val report = buildString {
        appendLine("# Paste benchmark")
        appendLine()
        appendLine("$os · $repeats повторов на случай · кодирование вне UI-потока (как в приложении) · «пик heap» — наибольший рост занятой памяти за случай, включая ещё не собранный мусор: верхняя граница, а не живые данные.")
        appendLine("Не измеряется: чтение системного буфера и первая отрисовка карточки (нужен нативный clipboard).")
        appendLine()
        appendLine("| Изображение | p50 / p95 / max | Пик heap | Результат |")
        appendLine("|---|---|---|---|")
        rows.forEach(::appendLine)
        appendLine()
        appendLine("| Текст и vision | p50 / p95 / max | Пик heap |")
        appendLine("|---|---|---|")
        textRows.forEach(::appendLine)
    }
    val out = Path.of("build/reports/paste-benchmark.md"); Files.createDirectories(out.parent); Files.writeString(out, report)
    println("Paste benchmark written to ${out.toAbsolutePath()}")
}

private fun timed(block: () -> Unit): Double { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6 }

/** Runs [block] [repeats] times; returns the durations in ms and the peak heap growth seen by a 2 ms sampler. */
private fun measure(repeats: Int, block: () -> Unit): Pair<List<Double>, Long> {
    block() // warm-up, not counted
    System.gc(); Thread.sleep(50)
    val memory = ManagementFactory.getMemoryMXBean()
    val base = memory.heapMemoryUsage.used
    val peak = AtomicLong(base)
    val running = java.util.concurrent.atomic.AtomicBoolean(true)
    val sampler = Thread { while (running.get()) { peak.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf); Thread.sleep(2) } }.apply { isDaemon = true; start() }
    val times = (1..repeats).map { timed(block) }
    running.set(false); sampler.join()
    return times to (peak.get() - base).coerceAtLeast(0)
}

private fun stats(times: List<Double>): String {
    val sorted = times.sorted()
    fun p(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
    return "%.0f / %.0f / %.0f мс".format(p(0.5), p(0.95), sorted.last())
}
