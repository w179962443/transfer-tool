package com.w179962443.transfertool

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.w179962443.transfertool.databinding.ActivityMainBinding
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var sourceUri: Uri? = null
    private var targetUri: Uri? = null
    private var isRunning = false

    private val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    private val sourcePicker = registerForActivityResult(OpenDocumentTree()) { uri ->
        uri?.let { selectedUri ->
            persistPermissions(selectedUri)
            sourceUri = selectedUri
            binding.sourcePathValue.text = formatTreePath(selectedUri)
            updateStartButton()
        }
    }

    private val targetPicker = registerForActivityResult(OpenDocumentTree()) { uri ->
        uri?.let { selectedUri ->
            persistPermissions(selectedUri)
            targetUri = selectedUri
            binding.targetPathValue.text = formatTreePath(selectedUri)
            updateStartButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pickSourceButton.setOnClickListener {
            sourcePicker.launch(sourceUri)
        }
        binding.pickTargetButton.setOnClickListener {
            targetPicker.launch(targetUri)
        }
        binding.startButton.setOnClickListener {
            startTransfer()
        }

        updateStartButton()
    }

    private fun startTransfer() {
        val currentSourceUri = sourceUri
        val currentTargetUri = targetUri
        if (isRunning || currentSourceUri == null || currentTargetUri == null) {
            return
        }

        if (isTargetInsideSource(currentSourceUri, currentTargetUri)) {
            showStatus(getString(R.string.error_target_inside_source))
            return
        }

        val sourceRoot = DocumentFile.fromTreeUri(this, currentSourceUri)
        val targetRoot = DocumentFile.fromTreeUri(this, currentTargetUri)
        if (sourceRoot == null || !sourceRoot.isDirectory || targetRoot == null || !targetRoot.isDirectory) {
            showStatus(getString(R.string.error_invalid_directory))
            return
        }

        isRunning = true
        binding.progressValue.text = getString(R.string.progress_count, 0)
        showStatus(getString(R.string.status_scanning))
        updateStartButton()

        lifecycleScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    moveFiles(sourceRoot, targetRoot) { movedCount, totalCount, fileName ->
                        withContext(Dispatchers.Main) {
                            binding.progressValue.text =
                                getString(R.string.progress_count, movedCount)
                            binding.statusValue.text =
                                getString(
                                    R.string.status_moving,
                                    fileName,
                                    movedCount,
                                    totalCount,
                                )
                        }
                    }
                }
                showStatus(
                    getString(
                        R.string.status_completed,
                        summary.movedCount,
                        summary.failedCount,
                    ),
                )
            } catch (cancelled: CancellationException) {
                showStatus(getString(R.string.status_cancelled))
            } catch (exception: Exception) {
                showStatus(
                    getString(
                        R.string.status_failed,
                        exception.message ?: getString(R.string.error_unknown),
                    ),
                )
            } finally {
                isRunning = false
                updateStartButton()
            }
        }
    }

    private suspend fun moveFiles(
        sourceRoot: DocumentFile,
        targetRoot: DocumentFile,
        onProgress: suspend (movedCount: Int, totalCount: Int, fileName: String) -> Unit,
    ): TransferSummary {
        val files = mutableListOf<DocumentFile>()
        collectFiles(sourceRoot, files)

        var movedCount = 0
        var failedCount = 0
        val totalCount = files.size

        files.forEach { sourceFile ->
            val sourceName = sourceFile.name
            if (sourceName.isNullOrBlank()) {
                failedCount += 1
                return@forEach
            }

            runCatching {
                val categoryName = resolveCategory(sourceName)
                val monthName = resolveMonthFolder(sourceFile)
                val extensionDir = requireNotNull(findOrCreateDirectory(targetRoot, categoryName))
                val monthDir = requireNotNull(findOrCreateDirectory(extensionDir, monthName))
                val targetName = buildUniqueName(monthDir, sourceName)
                val createdFile = createTargetFile(monthDir, targetName, sourceFile)
                copyContents(sourceFile, createdFile)
                if (!sourceFile.delete()) {
                    createdFile.delete()
                    throw IllegalStateException(getString(R.string.error_delete_source, sourceName))
                }
            }.onSuccess {
                movedCount += 1
                onProgress(movedCount, totalCount, sourceName)
            }.onFailure {
                failedCount += 1
            }
        }

        return TransferSummary(movedCount = movedCount, failedCount = failedCount)
    }

    private fun collectFiles(directory: DocumentFile, output: MutableList<DocumentFile>) {
        directory.listFiles().forEach { child ->
            when {
                child.isDirectory -> collectFiles(child, output)
                child.isFile -> output += child
            }
        }
    }

    private fun resolveCategory(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex > 0 && dotIndex < fileName.lastIndex) {
            fileName.substring(dotIndex + 1).lowercase(Locale.ROOT)
        } else {
            "others"
        }
    }

    private fun resolveMonthFolder(documentFile: DocumentFile): String {
        val millis = readCreationTime(documentFile)
            ?: documentFile.lastModified().takeIf { it > 0L }
            ?: System.currentTimeMillis()
        return monthFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }

    private fun readCreationTime(documentFile: DocumentFile): Long? {
        val absolutePath = resolveAbsolutePath(documentFile.uri) ?: return null
        return runCatching {
            Files.readAttributes(Paths.get(absolutePath), BasicFileAttributes::class.java)
                .creationTime()
                .toMillis()
        }.getOrNull()
    }

    private fun resolveAbsolutePath(uri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }
            .getOrElse { runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() }
            ?: return null
        val split = documentId.split(':', limit = 2)
        if (split.size != 2 || split[0] != "primary") {
            return null
        }
        return if (split[1].isBlank()) {
            "/storage/emulated/0"
        } else {
            "/storage/emulated/0/${split[1]}"
        }
    }

    private fun findOrCreateDirectory(parent: DocumentFile, childName: String): DocumentFile? {
        parent.findFile(childName)?.let { existing ->
            if (existing.isDirectory) {
                return existing
            }
        }
        return parent.createDirectory(childName)
    }

    private fun buildUniqueName(parent: DocumentFile, preferredName: String): String {
        if (parent.findFile(preferredName) == null) {
            return preferredName
        }

        val dotIndex = preferredName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) preferredName.substring(0, dotIndex) else preferredName
        val suffix = if (dotIndex > 0) preferredName.substring(dotIndex) else ""

        var index = 1
        while (true) {
            val candidate = "$baseName ($index)$suffix"
            if (parent.findFile(candidate) == null) {
                return candidate
            }
            index += 1
        }
    }

    private fun createTargetFile(
        parent: DocumentFile,
        fileName: String,
        sourceFile: DocumentFile,
    ): DocumentFile {
        val mimeType = sourceFile.type ?: guessMimeType(fileName)
        return requireNotNull(parent.createFile(mimeType, fileName)) {
            getString(R.string.error_create_target, fileName)
        }
    }

    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension.isBlank()) {
            "application/octet-stream"
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }
    }

    private fun copyContents(sourceFile: DocumentFile, targetFile: DocumentFile) {
        val inputStream = contentResolver.openInputStream(sourceFile.uri)
            ?: throw FileNotFoundException(sourceFile.uri.toString())
        val outputStream = contentResolver.openOutputStream(targetFile.uri, "w")
            ?: throw FileNotFoundException(targetFile.uri.toString())

        inputStream.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun persistPermissions(uri: Uri) {
        val flags = IntentFlags.readWriteFlags
        runCatching {
            contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    private fun formatTreePath(uri: Uri): String {
        return resolveAbsolutePath(uri) ?: uri.toString()
    }

    private fun isTargetInsideSource(source: Uri, target: Uri): Boolean {
        if (source == target) {
            return true
        }
        val sourcePath = resolveAbsolutePath(source) ?: return false
        val targetPath = resolveAbsolutePath(target) ?: return false
        return targetPath == sourcePath || targetPath.startsWith("$sourcePath/")
    }

    private fun showStatus(message: String) {
        binding.statusValue.text = message
    }

    private fun updateStartButton() {
        binding.startButton.isEnabled = !isRunning && sourceUri != null && targetUri != null
        binding.pickSourceButton.isEnabled = !isRunning
        binding.pickTargetButton.isEnabled = !isRunning
    }

    private data class TransferSummary(
        val movedCount: Int,
        val failedCount: Int,
    )

    private object IntentFlags {
        const val readWriteFlags: Int =
            ContentResolver.SCHEME_CONTENT.length and 0 or
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
