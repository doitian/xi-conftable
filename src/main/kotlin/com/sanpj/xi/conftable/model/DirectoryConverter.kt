package com.sanpj.xi.conftable.model

import com.google.common.base.Throwables
import com.sanpj.xi.conftable.utils.LuaFinder
import com.sanpj.xi.conftable.utils.LuaUtils
import com.sanpj.xi.conftable.utils.SystemProperties
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.concurrent.Task
import javafx.concurrent.WorkerStateEvent
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.lib.jse.JsePlatform
import tornadofx.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.text.MessageFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.logging.Logger
import kotlin.concurrent.thread


class DirectoryConverter(directory: File, outDirectory: File) {
    companion object {
        val ENUMS_LUA = "__enums.lua"
    }

    val log by lazy { Logger.getLogger(this.javaClass.name) }
    val messages by lazy { FX.messages }
    val executor = Executors.newWorkStealingPool()

    val watcher = FileSystems.getDefault().newWatchService()

    val directoryProperty = SimpleObjectProperty(directory)
    var directory by directoryProperty

    val outDirectoryProperty = SimpleObjectProperty(outDirectory)
    var outDirectory by outDirectoryProperty

    val autoValidateAllProperty = SimpleBooleanProperty(true)
    var autoValidateAll by autoValidateAllProperty
    
    val autoConvertProperty = SimpleBooleanProperty(false)
    var autoConvert by autoConvertProperty

    val onlyConvertUpdatedFilesProperty = SimpleBooleanProperty(false)
    var onlyConvertUpdatedFiles by onlyConvertUpdatedFilesProperty

    var onlyConvertFailedFilesProperty = SimpleBooleanProperty(false)
    var onlyConvertFailedFiles by onlyConvertFailedFilesProperty
    
    val shouldValidateAllProperty = SimpleBooleanProperty(false)
    var shouldValidateAll by shouldValidateAllProperty
    
    val validateAllMessagesProperty = SimpleStringProperty("")
    var validateAllMessages by validateAllMessagesProperty
    
    val files = FXCollections.observableList<FileConverter>(
            ArrayList<FileConverter>(),
            { arrayOf(it.errorMessagesProperty, it.statusProperty) }
    )

    val filterProperty = SimpleStringProperty("")
    var filter by filterProperty
    val filteredFiles = SortedFilteredList(FXCollections.observableList<FileConverter>(
            ArrayList<FileConverter>(),
            { arrayOf(it.errorMessagesProperty, it.statusProperty) }
    ))

    val filterPredicate: (FileConverter) -> Boolean = { fileConverter ->
        filter.isNullOrBlank() || fileConverter.file.name.contains(filter)
    }

    init {
        autoConvertProperty.addListener({ _, oldValue, newValue ->
            if (!oldValue && newValue) {
                convertModifiedFiles()
            }
        })
        files.addListener(ListChangeListener { c ->
            while (c.next()) {
                if (!c.wasUpdated() && !c.wasPermutated()) {
                    filteredFiles.removeAll(c.removed)
                    filteredFiles.addAll(c.addedSubList.filter(filterPredicate))
                }
            }
        })
        filterProperty.onChange {
            filteredFiles.setAll(*files.filter(filterPredicate).toTypedArray())
        }
    }

    fun isConvertible(filename: String): Boolean {
        if (filename == ENUMS_LUA) {
            return true
        }

        val lc = filename.toLowerCase()
        return !lc.startsWith("~") && (lc.endsWith(".xls") || lc.endsWith(".xlsx"))
    }


    fun <T> executeTask(func: FXTask<*>.() -> T): Task<T> = FXTask(null, func = func).apply {
        executor.execute(this)
    }

    fun open(directory: File, outDirectory: File) {
        executeTask {
            directory.listFiles({ _, name ->
                isConvertible(name)
            }).map {
                FileConverter(it, outDirectory)
            }
        }.apply {
            setOnSucceeded {
                stopWatcher()
                this@DirectoryConverter.directory = directory
                this@DirectoryConverter.outDirectory = outDirectory

                files.setAll(value)

                shouldValidateAll = false
                validateAllMessages = ""
                startWatcher()
            }
        }
    }

    fun rescan() {
        val keepFiles = files.filter { it.file.exists() }.map { it.file.absolutePath to it }.toMap()
        files.clear()

        executeTask {
            directory.listFiles({ _, name ->
                isConvertible(name)
            }).map {
                keepFiles.getOrDefault(it.absolutePath, FileConverter(it, outDirectory))
            }
        }.apply {
            setOnSucceeded {
                files.setAll(value)
                if (autoConvert) {
                    convertModifiedFiles()
                }
            }
        }
    }

    fun selectAll() {
        files.forEach { it.selected = true }
    }
    fun deselectAll() {
        files.forEach { it.selected = false }
    }
    fun toggleSelection() {
        files.forEach { it.selected = !it.selected }
    }

    fun shutdown() {
        stopWatcher()
        try {
            executor.shutdown()
        } finally {
            executor.shutdownNow()
        }
    }

    private var watchKey: WatchKey? = null
    private var watchThread: Thread? = null

    private fun startWatcher() {
        if (watchKey != null && watchThread != null) {
            return
        }
        stopWatcher()

        watchKey = directory.toPath().register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        watchThread = thread {
            log.info("file wacher started")
            while (true) {
                // wait for key to be signaled
                val key: WatchKey
                try {
                    key = watcher.take()
                } catch (_: InterruptedException) {
                    log.info("file wacher interrupted")
                    return@thread
                }

                for (event in key.pollEvents()) {
                    val kind = event.kind()
                    if (kind == OVERFLOW) {
                        continue
                    }
                    @Suppress("UNCHECKED_CAST")
                    val watchEvent = event as WatchEvent<Path>
                    val filename = watchEvent.context().fileName.toString()
                    if (!isConvertible(filename)) {
                        continue
                    }

                    when (kind) {
                        ENTRY_CREATE -> Platform.runLater {
                            log.info("file created: ${filename}")
                            val fileConverter = FileConverter(File(directory, filename), outDirectory)
                            files.add(fileConverter)
                            if (autoConvert && fileConverter.selected) {
                                convert(fileConverter)
                            }
                        }
                        ENTRY_MODIFY -> Platform.runLater {
                            log.info("file modified: ${filename}")
                            val fileConverter = files.find { it.file.name == filename }
                            if (fileConverter != null) {
                                fileConverter.onModified(File(directory, filename).lastModified())
                                if (autoConvert && fileConverter.selected) {
                                    convert(fileConverter)
                                }
                            }
                        }
                        ENTRY_DELETE -> Platform.runLater {
                            log.info("file deleted: ${filename}")
                            files.removeIf { it.file.name == filename }
                        }
                    }
                }

                val valid = key.reset()
                if (!valid) {
                    break
                }
            }
        }
    }

    private fun stopWatcher() {
        if (watchThread != null) {
            watchThread!!.interrupt()
            watchThread = null
        }
        if (watchKey != null) {
            watchKey!!.cancel()
            watchKey = null
        }
    }

    fun convert(vararg fileConverters: FileConverter) : Task<Unit> {
        val tasks = ArrayList<Task<Unit>>(fileConverters.size)
        val enumsLua  = fileConverters.find { it.file.name == DirectoryConverter.ENUMS_LUA }
        if (enumsLua != null) {
            tasks.add(enumsLua.run(executor))
        }

        tasks.addAll(fileConverters.filter { it !== enumsLua }.map {
            it.run(executor)
        })
        val rememberAutoValidateAll = autoValidateAll
        val rememberShouldValidateAll = shouldValidateAll
        validateAllMessages = "P ..."
        return executeTask {
            var succeeded = 0
            for (task in tasks) {
                try {
                    task.get()
                    succeeded += 1
                } catch (_: InterruptedException) {
                    log.info("convert task interrupted")
                    return@executeTask
                } catch (ex: ExecutionException) {
                    // pass
                }
            }

            if (rememberShouldValidateAll || succeeded > 0) {
                if (rememberAutoValidateAll) {
                    validateAll().get()
                } else {
                    Platform.runLater { this@DirectoryConverter.shouldValidateAll = true }
                }
            }
        }
    }

    private fun shouldConvert(fileConverter: FileConverter): Boolean {
        if (!fileConverter.selected) {
            return false
        }

        if (!filterPredicate(fileConverter)) {
            return false
        }

        if (onlyConvertUpdatedFiles) {
            if (fileConverter.lastConverted > fileConverter.lastModified) {
                return false
            }
        }
        if (onlyConvertFailedFiles) {
            if (fileConverter.errorMessages.isNullOrBlank()) {
                return false
            }
        }

        return true
    }

    fun convertSelectedFiles() : Task<Unit> {
        if (onlyConvertUpdatedFiles) {
            files.forEach {
                it.lastModified = it.file.lastModified()
            }
        }

        return convert(*files.filter { shouldConvert(it) }.toTypedArray())
    }

    fun convertModifiedFiles() : Task<Unit> {
        files.forEach {
            it.lastModified = it.file.lastModified()
        }
        return convert(*files.filter { shouldConvert(it) && it.lastConverted <= it.lastModified }.toTypedArray())
    }

    private fun createLuaVm(): Globals {
        val globals = JsePlatform.debugGlobals()
        globals.finder = LuaFinder(listOf(outDirectory, directory))
        return globals
    }

    private fun logByteArrayOutputStream(baos: ByteArrayOutputStream) =
        String(baos.toByteArray(), StandardCharsets.UTF_8)


    fun validateAll() : Task<Unit> {
        log.info("start validateAll")
        val t = executeTask {
            val file = File(directory, "__validate_all.lua")
            if (!file.exists()) {
                log.info("__validate_all.lua does not exist")
                return@executeTask
            }
            val L = createLuaVm()
            val baos = ByteArrayOutputStream()
            val ps = PrintStream(baos, true, "utf-8")
            L.STDOUT = ps
            L.STDERR = ps

            try {
                val result = LuaUtils.loadFile(L, file)
                if (result.isstring()) {
                    log.severe(logByteArrayOutputStream(baos))
                    throw RuntimeException(result.toString())
                }
                if (!result.toboolean()) {
                    log.severe(logByteArrayOutputStream(baos))
                    throw RuntimeException(messages["convert.validate_all_failed"])
                }
            } catch (e: LuaError) {
                log.severe(logByteArrayOutputStream(baos))
                throw RuntimeException("${e.message}${SystemProperties.LINE_SEPARATOR}${L.debuglib.traceback(1)}", e)
            }
        }

        Platform.runLater {
            t.addEventHandler(WorkerStateEvent.WORKER_STATE_SUCCEEDED) {
                log.info("validateAll succeeded")
                validateAllMessages = "S " + MessageFormat.format(messages["convert.validate_all_succeeded"], LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                shouldValidateAll = false
            }
            t.addEventHandler(WorkerStateEvent.WORKER_STATE_FAILED) {
                validateAllMessages = "E " + (t.exception?.message ?: (t.exception?.toString() ?: messages["convert.validate_all_failed"]))
                if (t.exception != null) {
                    log.severe(Throwables.getStackTraceAsString(t.exception))
                }
            }
        }

        return t
    }
}
