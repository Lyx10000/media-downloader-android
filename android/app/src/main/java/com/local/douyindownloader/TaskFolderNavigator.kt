package com.local.douyindownloader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class FolderNavigationResult(
    val launched: Boolean,
    val message: String = "",
)

@Singleton
class TaskFolderNavigator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val logger: DiagnosticLogger,
) {
    internal suspend fun open(
        launchContext: Context,
        taskId: String,
        spec: TaskSpec,
    ): FolderNavigationResult {
        val targets = withContext(Dispatchers.IO) { navigationTargets(spec) }
        log(taskId, "FOLDER_OPEN_REQUESTED", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("sdk", Build.VERSION.SDK_INT)
            put("storage_mode", effectiveStorageMode(spec).wireValue)
            put("exact_available", targets.exact != null)
        })

        val attempts = buildList {
            targets.exact?.let { exact ->
                add(NavigationAttempt("direct_standard", directoryViewIntent(exact, DIRECTORY_MIME)))
                add(NavigationAttempt("direct_compat", directoryViewIntent(exact, COMPAT_DIRECTORY_MIME)))
                add(NavigationAttempt("documents_exact", documentTreeIntent(exact)))
            }
            if (targets.root != null && targets.root != targets.exact) {
                add(NavigationAttempt("documents_root", documentTreeIntent(targets.root)))
            }
            add(NavigationAttempt("documents_default", documentTreeIntent(null)))
        }

        for (attempt in attempts) {
            val outcome = launch(launchContext, taskId, attempt)
            if (outcome) return FolderNavigationResult(launched = true)
        }
        return FolderNavigationResult(
            launched = false,
            message = "无法打开文件夹，请从系统文件管理器进入 Download/DouyinDownloader",
        )
    }

    private fun navigationTargets(spec: TaskSpec): DirectoryTargets {
        return if (effectiveStorageMode(spec) == StorageMode.SAF) {
            val root = spec.storageRoot.takeIf(String::isNotBlank)?.let { value ->
                runCatching { Uri.parse(value) }.getOrNull()
            }
            val exact = root?.let { tree ->
                runCatching {
                    DocumentFile.fromTreeUri(appContext, tree)
                        ?.findRelativeDirectory(spec.taskFolder, create = false)
                        ?.takeIf(DocumentFile::exists)
                        ?.uri
                }.getOrNull()
            }
            DirectoryTargets(exact = exact, root = root)
        } else {
            DirectoryTargets(
                exact = defaultDirectoryDocumentUri(spec.taskFolder),
                root = defaultRootDocumentUri(),
            )
        }
    }

    private suspend fun launch(
        context: Context,
        taskId: String,
        attempt: NavigationAttempt,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        val packageManager = context.packageManager
        val handler = runCatching { attempt.intent.resolveActivity(packageManager) }.getOrNull()
        log(taskId, "FOLDER_OPEN_ATTEMPT", JSONObject().apply {
            put("strategy", attempt.strategy)
            put("action", attempt.intent.action.orEmpty())
            put("authority", attempt.intent.data?.authority.orEmpty())
            put("handler", handler?.packageName.orEmpty())
            put(
                "write_grant",
                attempt.intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
            )
        })
        if (handler == null) return@withContext false

        val intent = Intent(attempt.intent).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            log(taskId, "FOLDER_OPEN_LAUNCHED", JSONObject().apply {
                put("strategy", attempt.strategy)
                put("handler", handler.packageName)
            })
            true
        } catch (error: ActivityNotFoundException) {
            logFailure(taskId, attempt.strategy, error)
            false
        } catch (error: SecurityException) {
            logFailure(taskId, attempt.strategy, error)
            false
        } catch (error: IllegalArgumentException) {
            logFailure(taskId, attempt.strategy, error)
            false
        } catch (error: IllegalStateException) {
            logFailure(taskId, attempt.strategy, error)
            false
        } catch (error: RuntimeException) {
            logFailure(taskId, attempt.strategy, error)
            false
        }
    }

    private fun logFailure(taskId: String, strategy: String, error: Throwable) {
        log(taskId, "FOLDER_OPEN_FAILED", JSONObject().apply {
            put("strategy", strategy)
            put("error", error.javaClass.name)
            put("message", error.message.orEmpty())
        })
    }

    private fun log(taskId: String, name: String, details: JSONObject) {
        runCatching { logger.event(taskId, "FOLDER", name, details) }
    }

    private data class DirectoryTargets(val exact: Uri?, val root: Uri?)
    private data class NavigationAttempt(val strategy: String, val intent: Intent)

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val DIRECTORY_MIME = DocumentsContract.Document.MIME_TYPE_DIR
        const val COMPAT_DIRECTORY_MIME = "resource/folder"

        fun effectiveStorageMode(spec: TaskSpec): StorageMode = when {
            spec.storageMode != StorageMode.LEGACY -> spec.storageMode
            spec.storageRoot.isNotBlank() -> StorageMode.SAF
            else -> StorageMode.DEFAULT
        }

        fun defaultRootDocumentUri(): Uri = DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            defaultDirectoryDocumentId(null),
        )

        fun defaultDirectoryDocumentUri(taskFolder: String): Uri = DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            defaultDirectoryDocumentId(taskFolder),
        )

        fun directoryViewIntent(uri: Uri, mimeType: String): Intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                clipData = ClipData.newRawUri("下载任务文件夹", uri)
                addFlags(DIRECTORY_VIEW_GRANT_FLAGS)
            }

        fun documentTreeIntent(initialUri: Uri?): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
                if (initialUri != null) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                }
            }
    }
}

internal const val DIRECTORY_VIEW_GRANT_FLAGS: Int =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

internal fun defaultDirectoryDocumentId(taskFolder: String?): String = buildString {
    append("primary:Download/DouyinDownloader")
    if (!taskFolder.isNullOrBlank()) {
        append('/')
        append(taskFolder)
    }
}
