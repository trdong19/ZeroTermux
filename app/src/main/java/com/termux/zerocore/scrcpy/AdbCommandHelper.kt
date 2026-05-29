package com.termux.zerocore.scrcpy

import android.content.Context
import android.util.Log
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import android.util.Base64
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * 通过 ADB 向远程设备发送单条 shell 命令。
 * 每次调用创建独立的 ADB 连接，用完即关，不影响 scrcpy 视频流连接。
 */
class AdbCommandHelper(private val context: Context) {

    companion object {
        private const val TAG = "AdbCommandHelper"
        private const val ADB_PORT = 5555
        private const val TIMEOUT_MS = 10_000L
    }

    /**
     * 关闭远程设备屏幕（不锁屏）。
     * 方案1: cmd display power-off 0 (Android 15+)
     * 方案2: 亮度降为0 + 关闭自动亮度 (Android 6+)
     */
    fun powerOffScreen(ip: String): Boolean {
        // 方案1: cmd display power-off (Android 15+)
        val r1 = executeCommand(ip, "cmd display power-off 0")
        if (r1.isSuccess && !r1.output.contains("Unknown command")) {
            Log.i(TAG, "Screen off via cmd display power-off 0")
            return true
        }
        Log.i(TAG, "cmd display power-off not available, trying brightness fallback")

        // 方案2: 亮度降为0（不锁屏，屏幕变暗近似关闭）
        executeCommand(ip, "settings put system screen_brightness_mode 0")
        val r2 = executeCommand(ip, "settings put system screen_brightness 0")
        if (r2.isSuccess) {
            Log.i(TAG, "Screen dimmed via brightness=0")
            return true
        }

        Log.w(TAG, "All screen off methods failed")
        return false
    }

    /**
     * 打开远程设备屏幕。
     */
    fun powerOnScreen(ip: String): Boolean {
        // 方案1: cmd display power-on (Android 15+)
        val r1 = executeCommand(ip, "cmd display power-on 0")
        if (r1.isSuccess && !r1.output.contains("Unknown command")) {
            Log.i(TAG, "Screen on via cmd display power-on 0")
            return true
        }

        // 方案2: 恢复亮度
        executeCommand(ip, "settings put system screen_brightness 255")
        executeCommand(ip, "settings put system screen_brightness_mode 1")
        Log.i(TAG, "Screen restored via brightness")
        return true
    }

    /**
     * 执行远程 ADB shell 命令并返回结果。
     */
    fun executeCommand(ip: String, command: String): CommandResult {
        var socket: Socket? = null
        var adb: AdbConnection? = null
        try {
            val crypto = loadOrCreateCrypto()
            socket = Socket(ip, ADB_PORT)
            socket.soTimeout = TIMEOUT_MS.toInt()

            adb = AdbConnection.create(socket, crypto)
            adb.connect()

            val stream = adb.open("shell:")
            readUntilPrompt(stream)
            stream.write("$command\n")
            val output = readUntilPrompt(stream)

            val hasError = output.contains("Unknown command") ||
                    output.contains("Error:") ||
                    output.contains("Permission denied")

            return CommandResult(!hasError, output)
        } catch (e: IOException) {
            Log.e(TAG, "ADB command failed: $command", e)
            return CommandResult(false, e.message ?: "IOException")
        } catch (e: InterruptedException) {
            Log.e(TAG, "ADB command interrupted: $command", e)
            return CommandResult(false, e.message ?: "InterruptedException")
        } finally {
            try { adb?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun readUntilPrompt(stream: com.tananaev.adblib.AdbStream): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                val bytes = stream.read()
                val response = String(bytes, StandardCharsets.US_ASCII)
                sb.append(response)
                if (response.endsWith("\$ ") || response.endsWith("# ")) {
                    return sb.toString()
                }
            } catch (e: Exception) {
                return sb.toString()
            }
        }
        return sb.toString()
    }

    private fun loadOrCreateCrypto(): AdbCrypto {
        val privKeyFile = context.getFileStreamPath("priv.key")
        val pubKeyFile = context.getFileStreamPath("pub.key")
        return try {
            AdbCrypto.loadAdbKeyPair(getBase64Impl(), privKeyFile, pubKeyFile)
        } catch (e: Exception) {
            val crypto = AdbCrypto.generateAdbKeyPair(getBase64Impl())
            crypto.saveAdbKeyPair(privKeyFile, pubKeyFile)
            crypto
        }
    }

    private fun getBase64Impl(): AdbBase64 {
        return AdbBase64 { arg0 -> Base64.encodeToString(arg0, Base64.NO_WRAP) }
    }

    data class CommandResult(val isSuccess: Boolean, val output: String)
}
