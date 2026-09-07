package com.local.multiplatformdownloader.core.compat


/** Stable names used by current builds. */
object ProductIdentity {
    const val DEFAULT_DOWNLOAD_DIRECTORY = "MultiPlatformDownloader"
}

/**
 * Published identifiers retained solely for upgrades and existing user data.
 *
 * Do not remove these values as part of a product rename. Android uses the legacy application ID
 * to identify the installed app, while older releases wrote public files under the legacy download
 * directory. Removing either compatibility contract would strand app-private state or make old
 * task folders appear missing.
 */
object LegacyCompatibility {
    const val PUBLISHED_APPLICATION_ID = "com.local.douyindownloader"
    const val DOWNLOAD_DIRECTORY = "DouyinDownloader"

    val readableDownloadDirectories: List<String> = listOf(
        ProductIdentity.DEFAULT_DOWNLOAD_DIRECTORY,
        DOWNLOAD_DIRECTORY,
    )

    private val historicalWorkerPackages = listOf(
        // Worker names persisted by the public 1.7.0 release and all earlier Android builds.
        "com.local.douyindownloader",
        // Short-lived development builds used this namespace before feature packages were split.
        "com.local.multiplatformdownloader",
    )

    fun workerClassNames(simpleName: String): Set<String> =
        historicalWorkerPackages.mapTo(linkedSetOf()) { packageName -> "$packageName.$simpleName" }
}

fun matchesPersistedWorkerClass(
    persistedClassName: String,
    currentClassName: String,
    simpleName: String,
): Boolean = persistedClassName == currentClassName ||
    persistedClassName in LegacyCompatibility.workerClassNames(simpleName)
