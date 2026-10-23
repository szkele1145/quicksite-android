package com.qk.app.core

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/**
 * 等长字节替换：在不改变二进制长度的前提下，把 termux 的硬编码前缀换成我们的前缀。
 *
 * termux bootstrap 的二进制在 ELF 字符串表与 RUNPATH 中写死了
 * `/data/data/com.termux/files/usr`。由于我们的包名 `com.qk.app` 与 `com.termux`
 * 长度相同，替换前后所有偏移量不变，因此无需重新链接 ELF。
 */
object PrefixPatcher {

    private const val TERMUX = "com.termux"
    private const val OURS = "com.qk.app"

    /** 超过该大小的文件走流式分块替换，避免整文件读入内存导致 OOM */
    private const val LARGE_FILE_THRESHOLD = 2L * 1024 * 1024   // 2MB

    private val TERMUX_BYTES = TERMUX.toByteArray(Charsets.ISO_8859_1)
    private val OUR_BYTES = OURS.toByteArray(Charsets.ISO_8859_1)

    init {
        require(TERMUX_BYTES.size == OUR_BYTES.size) {
            "替换串必须等长：${TERMUX_BYTES.size} != ${OUR_BYTES.size}"
        }
    }

    /**
     * 对单个文件做等长前缀替换（自动选择整读或流式）。
     * @return true 表示发生了修改
     */
    fun patchFile(file: File): Boolean {
        if (!file.isFile) return false
        return try {
            if (file.length() > LARGE_FILE_THRESHOLD) patchLarge(file) else patchSmall(file)
        } catch (e: Exception) {
            Paths.log("PrefixPatcher", "跳过 ${file.name}: ${e.message}")
            false
        }
    }

    /** 小文件：整读整写 */
    private fun patchSmall(file: File): Boolean {
        val bytes = file.readBytes()
        val patched = patchBytes(bytes)
        if (patched === bytes) return false
        file.writeBytes(patched)
        return true
    }

    /**
     * 大文件：按窗口分块扫描替换，内存占用恒定。
     *
     * 每次多读 `overlap` 字节作为前瞻，并把「主体 + 前瞻」整体写回，
     * 这样跨窗口边界的匹配也能被完整替换（只写主体会留下半截替换串）。
     */
    private fun patchLarge(file: File): Boolean {
        val needle = TERMUX_BYTES
        val repl = OUR_BYTES
        val window = 4 * 1024 * 1024
        val overlap = needle.size - 1
        var changed = false

        RandomAccessFile(file, "rw").use { raf ->
            val len = raf.length()
            var pos = 0L
            while (pos < len) {
                val mainLen = minOf(window.toLong(), len - pos).toInt()
                val lookahead = minOf(overlap.toLong(), len - (pos + mainLen)).toInt()
                val total = mainLen + lookahead
                val buf = ByteArray(total)
                raf.seek(pos)
                raf.readFully(buf, 0, total)

                // 替换所有完整包含在本缓冲内的匹配
                var idx = indexOf(buf, needle, 0)
                var localChanged = false
                while (idx >= 0) {
                    System.arraycopy(repl, 0, buf, idx, repl.size)
                    localChanged = true
                    idx = indexOf(buf, needle, idx + repl.size)
                }
                if (localChanged) {
                    raf.seek(pos)
                    raf.write(buf, 0, total)
                    changed = true
                }
                // 下一个窗口从主体之后开始；前瞻区已被写回，重复扫描是幂等的
                pos += mainLen.toLong()
            }
        }
        return changed
    }

    /**
     * 递归替换目录内所有文件中的前缀。
     * @return 被修改的文件数
     */
    fun patchTree(root: File): Int {
        var count = 0
        root.walkTopDown().forEach { f ->
            if (f.isFile && patchFile(f)) count++
        }
        return count
    }

    /**
     * 从 zip 解包到目录，边解边做前缀替换。
     * 直接解到目标目录，避免「先解临时目录再复制」造成的双倍磁盘占用与额外 IO。
     */
    fun unzipAndPatch(zip: File, dest: File, onProgress: ((String) -> Unit)? = null) {
        dest.mkdirs()
        val destPath = dest.canonicalPath
        var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                // 防目录穿越
                if (!out.canonicalPath.startsWith(destPath)) {
                    throw SecurityException("非法 zip 条目: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    writePatched(zis, out)
                    count++
                    if (count % 500 == 0) onProgress?.invoke("已解包 $count 个文件")
                }
                entry = zis.nextEntry
            }
        }
        onProgress?.invoke("解包完成，共 $count 个文件")
    }

    /**
     * 把当前 zip 条目的内容边读边替换边写到 out。
     * 供 EnvInstaller 直接从 assets 流式解包使用。
     */
    fun writeEntriesPatched(input: InputStream, out: File) = writePatched(input, out)

    /**
     * 边读边替换边写：滑动缓冲，保证跨块边界的匹配不丢失，内存占用恒定。
     *
     * 要点：尾部保留的 `keep` 字节必须取自**已替换后的 work**，不能取自原始 data。
     * 否则跨边界的匹配会被重复处理成残缺串（`com.termux` → `c` + `om.termux`）。
     */
    private fun writePatched(input: InputStream, out: File) {
        val needle = TERMUX_BYTES
        val repl = OUR_BYTES
        val chunk = 256 * 1024
        val keep = needle.size - 1

        out.outputStream().buffered(64 * 1024).use { os ->
            var carry = ByteArray(0)
            val buf = ByteArray(chunk)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val data = if (carry.isEmpty()) buf.copyOf(n) else carry + buf.copyOf(n)
                if (data.size <= keep) {
                    carry = data
                    continue
                }
                val processLen = data.size - keep
                val work = data.copyOf()
                var idx = indexOf(work, needle, 0)
                while (idx >= 0) {
                    System.arraycopy(repl, 0, work, idx, repl.size)
                    idx = indexOf(work, needle, idx + repl.size)
                }
                os.write(work, 0, processLen)
                // 关键：从 work 取尾巴（已替换），而不是 data
                carry = work.copyOfRange(processLen, work.size)
            }
            if (carry.isNotEmpty()) {
                var idx = indexOf(carry, needle, 0)
                while (idx >= 0) {
                    System.arraycopy(repl, 0, carry, idx, repl.size)
                    idx = indexOf(carry, needle, idx + repl.size)
                }
                os.write(carry)
            }
        }
    }

    /** 内存中等长替换 */
    fun patchBytes(src: ByteArray): ByteArray {
        var idx = indexOf(src, TERMUX_BYTES, 0)
        if (idx < 0) return src
        while (idx >= 0) {
            System.arraycopy(OUR_BYTES, 0, src, idx, OUR_BYTES.size)
            idx = indexOf(src, TERMUX_BYTES, idx + OUR_BYTES.size)
        }
        return src
    }

    /** Kotlin 标准库没有 ByteArray.indexOf(ByteArray)，这里手写一个。 */
    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        val last = haystack.size - needle.size
        if (from > last) return -1
        var i = if (from < 0) 0 else from
        while (i <= last) {
            if (haystack[i] == needle[0]) {
                var j = 1
                while (j < needle.size && haystack[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }
}
