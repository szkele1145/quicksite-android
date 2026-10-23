package com.qk.app.core

import java.io.File
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

    init {
        require(TERMUX.length == OURS.length)
    }

    /**
     * 对单个文件做等长前缀替换。
     * @return true 表示发生了修改
     */
    fun patchFile(file: File): Boolean {
        if (!file.isFile) return false
        val bytes = file.readBytes()
        val patched = patchBytes(bytes)
        if (patched === bytes) return false
        file.writeBytes(patched)
        return true
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
     * 从 zip 流解包到目录，同时对每个解出的文件做前缀替换。
     * 这样我们无需在构建期预先 patch 压缩包，APK 内保持 termux 原样，
     * 运行时解包即完成替换 —— 更省磁盘也更快。
     */
    fun unzipAndPatch(zip: File, dest: File, onProgress: ((String) -> Unit)? = null) {
        dest.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                val out = File(dest, entry.name)
                // 防目录穿越
                if (!out.canonicalPath.startsWith(dest.canonicalPath)) {
                    throw SecurityException("非法 zip 条目: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    val raw = zis.readBytes()
                    val patched = patchBytes(raw)
                    out.writeBytes(patched)
                    count++
                    if (count % 200 == 0) onProgress?.invoke("已解包 $count 个文件")
                }
                entry = zis.nextEntry
            }
            onProgress?.invoke("解包完成，共 $count 个文件")
        }
    }

    /** 内存中等长替换 */
    fun patchBytes(src: ByteArray): ByteArray {
        val termuxBytes = TERMUX.toByteArray(Charsets.ISO_8859_1)
        val ourBytes = OURS.toByteArray(Charsets.ISO_8859_1)
        var idx = indexOf(src, termuxBytes, 0)
        if (idx < 0) return src
        while (idx >= 0) {
            System.arraycopy(ourBytes, 0, src, idx, ourBytes.size)
            idx = indexOf(src, termuxBytes, idx + ourBytes.size)
        }
        return src
    }

    /** Kotlin 标准库没有 ByteArray.indexOf(ByteArray)，这里手写一个。 */
    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
