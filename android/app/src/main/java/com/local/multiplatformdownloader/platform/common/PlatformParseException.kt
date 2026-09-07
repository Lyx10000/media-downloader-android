package com.local.multiplatformdownloader.platform.common

/** Normalized failure raised by a platform adapter before it is mapped to a ParseResult. */
internal class PlatformParseException(
    val code: String,
    message: String,
    val statusCode: Int = 0,
) : Exception(message)
