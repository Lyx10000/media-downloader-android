package com.local.multiplatformdownloader.core.download

typealias DownloadProgress = suspend (stage: String, progress: Int, persist: Boolean) -> Unit
