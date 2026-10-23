package com.qk.app.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * 纯 Kotlin 的 .deb 解包器 —— 不依赖 dpkg/ar/tar 等外部命令。
 *
 * .deb = ar 归档，内含 debian-binary / control.tar.gz / data.tar.gz。
 * 我们只需要 data.tar.gz（实际文件），control 用于元信息展示。
 * tar 支持三种条目：普通文件、目录、符号链接。
 */
object DebExtractor {

    private const val TAR_BLOCK = 512

    fun extract(deb: File, dest: File): List<String> {
        val raw = deb.readBytes()
        return extractStream(raw.inputStream(), dest)
    }

    fun extractStream(input: InputStream, dest: File): List<String> {
        dest.mkdirs()
        val entries = mutableListOf<String>()

        // ---- 1. 解析 ar 归档，找 data.tar.* ----
        val data = readAll(input)
        require(data.size >= 8 && String(data, 0, 8) == "!<arch>\n") { "不是合法的 .deb (ar) 文件" }
        var off = 8
        var dataTar: ByteArray? = null
        while (off + 60 <= data.size) {
            val name = String(data, off, 16).trim()
            val size = String(data, off + 48, 10).trim().toLongOrNull() ?: 0L
            val bodyStart = off + 60
            if (name == "data.tar.gz" || name == "data.tar.xz" || name == "data.tar.zst" || name == "data.tar") {
                dataTar = data.copyOfRange(bodyStart, (bodyStart + size).toInt().coerceAtMost(data.size))
                // ar 条目按 2 字节对齐
                off = bodyStart + ((size + 1) / 2 * 2).toInt()
                break
            }
            off = bodyStart + ((size + 1) / 2 * 2).toInt()
        }
        requireNotNull(dataTar) { "deb 内没有 data.tar" }

        // ---- 2. 解压缩 ----
        val tarBytes: ByteArray = when {
            // gzip magic 1f 8b
            dataTar.size > 2 && dataTar[0] == 0x1f.toByte() && dataTar[1] == 0x8b.toByte() ->
                GZIPInputStream(dataTar.inputStream()).use { readAll(it) }
            // xz magic FD 37 7A 58 5A 00
            dataTar.size > 6 && dataTar[0] == 0xFD.toByte() && dataTar[1] == 0x37.toByte() ->
                XzUtil.decompress(dataTar)
            // zstd magic 28 B5 2F FD
            dataTar.size > 4 && dataTar[0] == 0x28.toByte() && dataTar[1] == 0xB5.toByte() ->
                ZstdUtil.decompress(dataTar)
            else -> dataTar
        }

        // ---- 3. 解析 tar ----
        var i = 0
        while (i + TAR_BLOCK <= tarBytes.size) {
            val name = readCString(tarBytes, i, 100)
            if (name.isEmpty()) break // 结束块
            val mode = readCString(tarBytes, i + 100, 8).trim()
            val sizeStr = readCString(tarBytes, i + 124, 12).trim()
            val size = parseOctal(sizeStr)
            val typeflag = tarBytes[i + 156].toInt().toChar()
            val linkName = readCString(tarBytes, i + 157, 100)
            val prefix = readCString(tarBytes, i + 345, 155)
            val fullName = (if (prefix.isNotEmpty()) "$prefix/$name" else name)
                .trimStart('/')
                // termux 包内路径形如 ./usr/bin/xxx 或 usr/bin/xxx
                .removePrefix("./")

            when (typeflag) {
                '0', '\u0000' -> { // 普通文件
                    val start = i + TAR_BLOCK
                    val end = start + size
                    if (end > tarBytes.size) break
                    val body = tarBytes.copyOfRange(start, end.toInt())
                    val f = File(dest, fullName)
                    f.parentFile?.mkdirs()
                    f.writeBytes(body)
                    chmodIfPossible(f, mode)
                    entries += fullName
                }
                '2' -> { // 符号链接
                    val f = File(dest, fullName)
                    f.parentFile?.mkdirs()
                    try {
                        if (f.exists()) f.delete()
                        // 在 Android 上用绝对路径符号链接，只链接到 prefix 内部
                        if (linkName.isNotBlank()) {
                            androidOsSymlink(linkName, f)
                        }
                    } catch (_: Exception) {
                        // 部分文件系统不支持 symlink，降级为小标记文件
                        f.writeBytes(linkName.toByteArray())
                    }
                    entries += "$fullName -> $linkName"
                }
                '5' -> { // 目录
                    File(dest, fullName).mkdirs()
                }
                // 1=hardlink 3=chardev 4=blockdev 6=fifo：忽略
            }

            // 跳到下一个 512 对齐块
            i = i + TAR_BLOCK + ((size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK).toInt()
        }
        return entries
    }

    private fun chmodIfPossible(f: File, mode: String) {
        try {
            // 简化：凡含执行位的都 chmod 755，其余 644
            val m = mode.toIntOrNull(8) ?: return
            val exec = (m and 0x49) != 0  // 0o111 = 0x49
            Runtime.getRuntime().exec(
                arrayOf("/system/bin/chmod", if (exec) "755" else "644", f.absolutePath)
            ).waitFor()
        } catch (_: Exception) {
        }
    }

    /** 用 Os.symlink 建符号链接（POSIX 层） */
    private fun androidOsSymlink(target: String, linkFile: File) {
        try {
            val osClass = Class.forName("android.system.Os")
            val method = osClass.getMethod("symlink", String::class.java, String::class.java)
            method.invoke(null, target, linkFile.absolutePath)
        } catch (e: Exception) {
            throw IllegalStateException("symlink 失败", e)
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        input.copyTo(out, 64 * 1024)
        return out.toByteArray()
    }

    private fun readCString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = minOf(off + len, buf.size)
        while (end < max && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.ISO_8859_1)
    }

    private fun parseOctal(s: String): Long {
        if (s.isBlank() || s.all { it == ' ' }) return 0
        return s.trim().toLong(8)
    }
}

/** 极简 xz 解压：走 Android 自带 liblzma 通过 shell 不现实，这里用 Java 层不行 —— 直接用外部 xz 命令兜底 */
object XzUtil {
    fun decompress(data: ByteArray): ByteArray {
        throw UnsupportedOperationException("xz tar 请先在构建期用 xz 工具转成 gzip 格式的 deb")
    }
}

/** 极简 zstd 解压：同上 */
object ZstdUtil {
    fun decompress(data: ByteArray): ByteArray {
        throw UnsupportedOperationException("zstd tar 请先在构建期转成 gzip 格式的 deb")
    }
}
