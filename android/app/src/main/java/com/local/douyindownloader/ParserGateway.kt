package com.local.douyindownloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParserGateway @Inject constructor(
    private val router: KotlinParserRouter,
) {
    suspend fun parse(
        shareText: String,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot? = null,
    ): ParseResult =
        withContext(Dispatchers.IO) {
            try {
                router.parse(shareText, cookieHeader, pageSnapshot)
            } catch (error: Throwable) {
                ParseResult(
                    ok = false,
                    errorCode = "KOTLIN_PARSER",
                    message = error.message ?: error.javaClass.simpleName,
                )
            }
        }
}
