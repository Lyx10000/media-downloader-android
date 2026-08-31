package com.local.douyindownloader

import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ParserGateway {
    suspend fun parse(shareText: String, cookieHeader: String): ParseResult =
        withContext(Dispatchers.IO) {
            try {
                val module = Python.getInstance().getModule("android_bridge")
                val json = module.callAttr("parse_share", shareText, cookieHeader).toString()
                ParseResult.fromJson(json)
            } catch (error: Throwable) {
                ParseResult(
                    ok = false,
                    errorCode = "PYTHON_BRIDGE",
                    message = error.message ?: error.javaClass.simpleName,
                )
            }
        }
}

