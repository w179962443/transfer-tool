package com.w179962443.transfertool

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var sourceUri: Uri? = null
    private var targetUri: Uri? = null
    private var transferSession: TransferSession? = null
    private var transferJob: Job? = null
    private var isRunning = false

    private val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    private val sourcePicker = registerForActivityResult(OpenDocumentTree()) { uri ->
        uri?.let { selectedUri ->
            persistPermissions(selectedUri)
            sourceUri = selectedUri
            transferSession = null
            binding.sourcePathValue.text = formatTreePath(selectedUri)
            renderTransferState()
        }
    }

    private val targetPicker = registerForActivityResult(OpenDocumentTree()) { uri ->
        uri?.let { selectedUri ->
            persistPermissions(selectedUri)
            targetUri = selectedUri
            transferSession = null
            binding.targetPathValue.text = formatTreePath(selectedUri)
            renderTransferState()
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
            startOrContinueTransfer()
        }
        binding.pauseAfterBatchButton.setOnClickListener {
            requestPauseAfterCurrentBatch()
        }
        binding.autoRunSwitch.setOnCheckedChangeListener { _, isChecked ->
            transferSession?.let { session ->
                session.autoRun = isChecked
                session.pauseAfterBatch = !isChecked && isRunning
            }
            renderTransferState()
        }
        binding.batchSizeInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    transferSession?.batchLimit = readBatchLimit()
                    renderTransferState()
                }

                override fun afterTextChanged(s: Editable?) = Unit
            },
        )

        renderTransferState()
    }

    override fun onDestroy() {
        transferJob?.cancel()
        super.onDestroy()
    }

    private fun startOrContinueTransfer() {
        if (isRunning) {
            return
        }

        val session = prepareSession() ?: return
        session.batchLimit = readBatchLimit()
        session.autoRun = binding.autoRunSwitch.isChecked
        session.pauseAfterBatch = false

        isRunning = true
        showStatus(getString(R.string.status_scanning_batch, session.batches.size + 1))
        renderTransferState()

        transferJob = lifecycleScope.launch {
            try {
                runTransferLoop(session)
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
                renderTransferState()
            }
        }
    }

    private fun requestPauseAfterCurrentBatch() {
        val session = transferSession ?: return
        session.autoRun = false
        session.pauseAfterBatch = true
        if (binding.autoRunSwitch.isChecked) {
            binding.autoRunSwitch.isChecked = false
        }
        showStatus(getString(R.string.status_pause_requested))
        renderTransferState()
    }

    private fun prepareSession(): TransferSession? {
        val currentSourceUri = sourceUri
        val currentTargetUri = targetUri
        if (currentSourceUri == null || currentTargetUri == null) {
            showStatus(getString(R.string.error_invalid_directory))
            return null
        }

        if (isTargetInsideSource(currentSourceUri, currentTargetUri)) {
            showStatus(getString(R.string.error_target_inside_source))
            return null
        }

        transferSession?.takeUnless { it.isComplete }?.let {
            return it
        }

        val sourceRoot = DocumentFile.fromTreeUri(this, currentSourceUri)
        val targetRoot = DocumentFile.fromTreeUri(this, currentTargetUri)
        if (sourceRoot == null || !sourceRoot.isDirectory || targetRoot == null || !targetRoot.isDirectory) {
            showStatus(getString(R.string.error_invalid_directory))
            return null
        }

        return TransferSession(
            sourceRoot = sourceRoot,
            targetRoot = targetRoot,
            batchLimit = readBatchLimit(),
            autoRun = binding.autoRunSwitch.isChecked,
            directoryStack = ArrayDeque<DirectoryCursor>().apply {
                add(DirectoryCursor(sourceRoot, ""))
            },
        ).also {
            transferSession = it
        }
    }

    private suspend fun runTransferLoop(session: TransferSession) {
        while (true) {
            val batch = withContext(Dispatchers.IO) {
                discoverNextBatch(session)
            }

            if (batch == null) {
                session.isComplete = true
                showCompletedStatus(session)
                return
            }

            showStatus(getString(R.string.status_batch_started, batch.number, batch.tasks.size))
            renderTransferState()
            transferBatch(session, batch)

            if (session.scanComplete) {
                session.isComplete = true
                showCompletedStatus(session)
                return
            }

            if (!session.autoRun || session.pauseAfterBatch) {
                showStatus(getString(R.string.status_paused_after_batch, batch.number))
                return
            }

            showStatus(getString(R.string.status_scanning_batch, session.batches.size + 1))
            renderTransferState()
        }
    }

    private fun discoverNextBatch(session: TransferSession): TransferBatch? {
        val tasks = mutableListOf<FileTask>()
        val batchNumber = session.batches.size + 1

        while (tasks.size < session.batchLimit && session.directoryStack.isNotEmpty()) {
            val cursor = session.directoryStack.last()
            val children = cursor.children ?: cursor.directory.listFiles().also {
                cursor.children = it
            }

            if (cursor.nextIndex >= children.size) {
                session.directoryStack.removeLast()
                continue
            }

            val child = children[cursor.nextIndex]
            cursor.nextIndex += 1

            val childName = child.name ?: getString(R.string.unnamed_file)
            val displayPath = if (cursor.displayPath.isBlank()) {
                childName
            } else {
                "${cursor.displayPath}/$childName"
            }

            when {
                child.isDirectory -> session.directoryStack.add(DirectoryCursor(child, displayPath))
                child.isFile -> {
                    session.nextTaskId += 1
                    tasks += FileTask(
                        id = session.nextTaskId,
                        batchNumber = batchNumber,
                        displayPath = displayPath,
                        sourceFile = child,
                    )
                }
            }
        }

        if (session.directoryStack.isEmpty()) {
            session.scanComplete = true
        }

        if (tasks.isEmpty()) {
            return null
        }

        return TransferBatch(
            number = batchNumber,
            tasks = tasks,
        ).also { batch ->
            session.batches += batch
            session.tasks += tasks
        }
    }

    private suspend fun transferBatch(session: TransferSession, batch: TransferBatch) {
        batch.status = BatchStatus.Transferring
        renderTransferState()

        batch.tasks.forEach { task ->
            transferFileTask(session, task)
        }

        batch.status = BatchStatus.Completed
        renderTransferState()
    }

    private suspend fun transferFileTask(session: TransferSession, task: FileTask) {
        task.status = FileTaskStatus.Transferring
        task.errorMessage = null
        showStatus(getString(R.string.status_moving, task.displayPath, task.batchNumber))
        renderTransferState()

        val result = withContext(Dispatchers.IO) {
            runCatching {
                moveSingleFile(task.sourceFile, session.targetRoot)
            }
        }

        result.onSuccess {
            task.status = FileTaskStatus.Completed
        }.onFailure { exception ->
            task.status = FileTaskStatus.Failed
            task.errorMessage = exception.message ?: getString(R.string.error_unknown)
        }

        renderTransferState()
    }

    private fun retryTask(taskId: Int) {
        if (isRunning) {
            return
        }
        val session = transferSession ?: return
        val task = session.tasks.firstOrNull { it.id == taskId && it.status == FileTaskStatus.Failed }
            ?: return

        isRunning = true
        showStatus(getString(R.string.status_retrying, task.displayPath))
        renderTransferState()

        transferJob = lifecycleScope.launch {
            try {
                transferFileTask(session, task)
                showStatus(getString(R.string.status_retry_finished, task.displayPath))
            } catch (cancelled: CancellationException) {
                showStatus(getString(R.string.status_cancelled))
            } finally {
                isRunning = false
                renderTransferState()
            }
        }
    }

    private fun retryAllFailedTasks() {
        if (isRunning) {
            return
        }
        val session = transferSession ?: return
        val failedTaskIds = session.tasks
            .filter { it.status == FileTaskStatus.Failed }
            .map { it.id }
        if (failedTaskIds.isEmpty()) {
            return
        }

        isRunning = true
        showStatus(getString(R.string.status_retrying_all, failedTaskIds.size))
        renderTransferState()

        transferJob = lifecycleScope.launch {
            try {
                failedTaskIds.forEach { taskId ->
                    val task = session.tasks.firstOrNull {
                        it.id == taskId && it.status == FileTaskStatus.Failed
                    } ?: return@forEach
                    transferFileTask(session, task)
                }
                showStatus(getString(R.string.status_retry_all_finished))
            } catch (cancelled: CancellationException) {
                showStatus(getString(R.string.status_cancelled))
            } finally {
                isRunning = false
                renderTransferState()
            }
        }
    }

    private fun moveSingleFile(sourceFile: DocumentFile, targetRoot: DocumentFile) {
        val sourceName = sourceFile.name
        if (sourceName.isNullOrBlank()) {
            throw IllegalArgumentException(getString(R.string.error_missing_source_name))
        }

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
        try {
            val inputStream = contentResolver.openInputStream(sourceFile.uri)
                ?: throw FileNotFoundException(sourceFile.uri.toString())
            val outputStream = contentResolver.openOutputStream(targetFile.uri, "w")
                ?: throw FileNotFoundException(targetFile.uri.toString())

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
        } catch (exception: Exception) {
            targetFile.delete()
            throw exception
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

    private fun readBatchLimit(): Int {
        val parsed = binding.batchSizeInput.text?.toString()?.toIntOrNull()
        return parsed?.coerceAtLeast(1) ?: DEFAULT_BATCH_LIMIT
    }

    private fun showCompletedStatus(session: TransferSession) {
        showStatus(
            getString(
                R.string.status_completed,
                session.completedCount,
                session.failedCount,
                session.batches.size,
            ),
        )
    }

    private fun showStatus(message: String) {
        binding.statusValue.text = message
    }

    private fun renderTransferState() {
        val session = transferSession

        binding.startButton.isEnabled = !isRunning && sourceUri != null && targetUri != null
        binding.pickSourceButton.isEnabled = !isRunning
        binding.pickTargetButton.isEnabled = !isRunning
        binding.batchSizeInput.isEnabled = !isRunning
        binding.pauseAfterBatchButton.isEnabled = isRunning && session?.autoRun == true
        binding.autoRunSwitch.isEnabled = !isRunning || session?.autoRun == true

        binding.startButton.text = when {
            isRunning -> getString(R.string.running_button)
            session?.isComplete == true -> getString(R.string.start_new_task_button)
            session != null -> getString(R.string.continue_button)
            else -> getString(R.string.start_button)
        }

        binding.progressValue.text = if (session == null) {
            getString(R.string.progress_count_initial)
        } else {
            getString(
                R.string.progress_count,
                session.completedCount,
                session.failedCount,
                session.discoveredCount,
            )
        }

        binding.taskOverviewValue.text = if (session == null) {
            getString(R.string.task_overview_empty)
        } else {
            getString(
                R.string.task_overview_value,
                session.batches.size,
                session.batchLimit,
                session.discoveredCount,
                if (session.scanComplete) {
                    getString(R.string.scan_state_finished)
                } else {
                    getString(R.string.scan_state_incremental)
                },
            )
        }

        renderBatchDetails(session)
        renderFailedTasks(session)
    }

    private fun renderBatchDetails(session: TransferSession?) {
        binding.batchStatusContainer.removeAllViews()
        if (session == null || session.batches.isEmpty()) {
            binding.batchStatusContainer.addView(createBodyText(getString(R.string.batch_status_empty)))
            return
        }

        session.batches.forEach { batch ->
            val statusText = when (batch.status) {
                BatchStatus.Pending -> getString(R.string.batch_status_pending)
                BatchStatus.Transferring -> getString(R.string.batch_status_transferring)
                BatchStatus.Completed -> getString(R.string.batch_status_completed)
            }
            binding.batchStatusContainer.addView(
                createBodyText(
                    getString(
                        R.string.batch_status_value,
                        batch.number,
                        statusText,
                        batch.completedCount,
                        batch.failedCount,
                        batch.tasks.size,
                    ),
                ),
            )
        }
    }

    private fun renderFailedTasks(session: TransferSession?) {
        binding.failedTasksContainer.removeAllViews()
        val failedTasks = session?.tasks.orEmpty().filter { it.status == FileTaskStatus.Failed }
        if (failedTasks.isEmpty()) {
            binding.failedTasksContainer.addView(createBodyText(getString(R.string.failed_tasks_empty)))
            return
        }

        val retryAllButton = MaterialButton(this).apply {
            text = getString(R.string.retry_all_button, failedTasks.size)
            isEnabled = !isRunning
            setOnClickListener { retryAllFailedTasks() }
        }
        binding.failedTasksContainer.addView(retryAllButton)

        failedTasks.forEach { task ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, resources.getDimensionPixelSize(R.dimen.task_row_vertical_padding), 0, 0)
            }

            val detailText = createBodyText(
                getString(
                    R.string.failed_task_value,
                    task.batchNumber,
                    task.displayPath,
                    task.errorMessage ?: getString(R.string.error_unknown),
                ),
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            }

            val retryButton = MaterialButton(this).apply {
                text = getString(R.string.retry_button)
                isEnabled = !isRunning
                setOnClickListener { retryTask(task.id) }
            }

            row.addView(detailText)
            row.addView(retryButton)
            binding.failedTasksContainer.addView(row)
        }
    }

    private fun createBodyText(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = BODY_TEXT_SIZE_SP
            visibility = View.VISIBLE
        }
    }

    private data class TransferSession(
        val sourceRoot: DocumentFile,
        val targetRoot: DocumentFile,
        var batchLimit: Int,
        var autoRun: Boolean,
        val directoryStack: ArrayDeque<DirectoryCursor>,
        val batches: MutableList<TransferBatch> = mutableListOf(),
        val tasks: MutableList<FileTask> = mutableListOf(),
        var nextTaskId: Int = 0,
        var scanComplete: Boolean = false,
        var isComplete: Boolean = false,
        var pauseAfterBatch: Boolean = false,
    ) {
        val discoveredCount: Int
            get() = tasks.size

        val completedCount: Int
            get() = tasks.count { it.status == FileTaskStatus.Completed }

        val failedCount: Int
            get() = tasks.count { it.status == FileTaskStatus.Failed }
    }

    private data class DirectoryCursor(
        val directory: DocumentFile,
        val displayPath: String,
        var children: Array<DocumentFile>? = null,
        var nextIndex: Int = 0,
    )

    private data class TransferBatch(
        val number: Int,
        val tasks: List<FileTask>,
        var status: BatchStatus = BatchStatus.Pending,
    ) {
        val completedCount: Int
            get() = tasks.count { it.status == FileTaskStatus.Completed }

        val failedCount: Int
            get() = tasks.count { it.status == FileTaskStatus.Failed }
    }

    private data class FileTask(
        val id: Int,
        val batchNumber: Int,
        val displayPath: String,
        val sourceFile: DocumentFile,
        var status: FileTaskStatus = FileTaskStatus.Pending,
        var errorMessage: String? = null,
    )

    private enum class BatchStatus {
        Pending,
        Transferring,
        Completed,
    }

    private enum class FileTaskStatus {
        Pending,
        Transferring,
        Completed,
        Failed,
    }

    private object IntentFlags {
        const val readWriteFlags: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    private companion object {
        const val DEFAULT_BATCH_LIMIT = 20
        const val BODY_TEXT_SIZE_SP = 14f
    }
}
