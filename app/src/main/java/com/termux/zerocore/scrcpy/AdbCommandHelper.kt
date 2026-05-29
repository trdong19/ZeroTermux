package com.termux.zerocore.scrcpy

import android.content.Context
import android.util.Log
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import org.apache.commons.codec.binary.Base64
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
     * 关闭远程设备屏幕（不锁屏，仅关闭显示背光）。
     * 使用 cmd display power-off 0（Android 12+），
     * 远程设备屏幕变黑但画面仍可被截取镜像，控制端正常操作。
     * @return 是否成功
     */
    fun powerOffScreen(ip: String): Boolean {
        val result = executeCommand(ip, "cmd display power-off 0")
        if (result.isSuccess) {
            Log.i(TAG, "Screen off via cmd display power-off 0")
            return true
        }
        Log.w(TAG, "cmd display power-off failed: ${result.output}")
        return false
    }

    /**
     * 打开远程设备屏幕。
     */
    fun powerOnScreen(ip: String): Boolean {
        val result = executeCommand(ip, "cmd display power-on 0")
        if (result.isSuccess) {
            Log.i(TAG, "Screen on via cmd display power-on 0")
            return true
        }
        Log.w(TAG, "cmd display power-on failed: ${result.output}")
        return false
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
            // 等待 shell 提示符
            readUntilPrompt(stream)
            // 发送命令
            stream.write("$command\n")
            // 读取命令输出
            val output = readUntilPrompt(stream)

            // 判断命令是否成功：检查输出中是否包含错误关键字
            val hasError = output.contains("Error") || output.contains("error") ||
                    output.contains("not found") || output.contains("Unknown command")

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
        return AdbBase64 { arg0 -> Base64.encodeBase64String(arg0) }
    }

    data class CommandResult(val isSuccess: Boolean, val output: String)
}
