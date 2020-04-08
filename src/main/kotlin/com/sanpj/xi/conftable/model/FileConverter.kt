package com.sanpj.xi.conftable.model

import com.google.common.base.Throwables
import com.google.common.io.Files
import com.sanpj.xi.conftable.utils.LuaFinder
import com.sanpj.xi.conftable.utils.SystemProperties
import javafx.application.Platform
import javafx.beans.property.*
import javafx.concurrent.Task
import javafx.concurrent.WorkerStateEvent
import javafx.scene.paint.Color
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.lib.jse.JsePlatform
import tornadofx.*
import java.io.File
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.logging.Logger


class FileConverter(val file: File, val outDirectory: File) {
    val log by lazy { Logger.getLogger(this.javaClass.name) }
    val messages by lazy { FX.messages }

    val selectedProperty = SimpleBooleanProperty(true)
    var selected by selectedProperty
    
    val lastModifiedProperty = SimpleLongProperty(file.lastModified())
    var lastModified by lastModifiedProperty
    
    val lastConvertedProperty = SimpleLongProperty(0)
    var lastConverted by lastConvertedProperty
    
    val errorMessagesProperty = SimpleStringProperty("")
    var errorMessages by errorMessagesProperty
    
    val progressProperty = SimpleDoubleProperty(0.0)
    var progress by progressProperty
    
    enum class Status(val fillColor: Color, val backgroundColor: Color?) {
        PENDING(c("#e67700"), null),
        RUNNING(Color.WHITE, c("#51cf66")),
        FAILED(Color.WHITE, c("#c92a2a")),
        SUCCEEDED(Color.WHITE, c("#2b8a3e")),
    }
    
    val statusProperty = SimpleObjectProperty(Status.PENDING)
    var status by statusProperty

    fun onModified(modifiedEpochMilli: Long) {
        lastModified = modifiedEpochMilli
        if (status == Status.SUCCEEDED) {
            status = Status.PENDING
        }
    }

    fun <T> executeTask(executor: ExecutorService, func: FXTask<*>.() -> T): Task<T> = FXTask(null, func = func).apply {
        executor.execute(this)
    }

    @Synchronized fun run(executor: ExecutorService): Task<Unit> {
        log.info("${file.name}: start convert")
        progress = 0.0
        status = Status.RUNNING

        val t = executeTask(executor) { runWorker(this) }
        progressProperty.bind(t.progressProperty())

        Platform.runLater {
            t.addEventHandler(WorkerStateEvent.WORKER_STATE_SUCCEEDED) {
                progressProperty.unbind()
                progress = 1.0
                status = Status.SUCCEEDED
                lastConverted = Instant.now().toEpochMilli()
                errorMessages = ""
                log.info("${file.name}: completed convert")
            }
            t.addEventHandler(WorkerStateEvent.WORKER_STATE_FAILED) {
                progressProperty.unbind()
                status = Status.FAILED
                when (t.exception) {
                    is FileConverterError -> errorMessages = t.exception.message
                    else -> {
                        val message = t.exception?.message ?: t.exception?.toString() ?: messages["convert.failed"]
                        errorMessages = "${file.name}: ${message}"
                    }
                }
                if (t.exception != null) {
                    log.severe(Throwables.getStackTraceAsString(t.exception))
                }
            }
            t.addEventHandler(WorkerStateEvent.WORKER_STATE_CANCELLED) {
                progressProperty.unbind()
                status = Status.FAILED
                errorMessages = "${file.name}: ${messages["convert.cancelled"]}"
                log.severe(errorMessages)
            }
        }

        return t
    }

    @Synchronized private fun runWorker(t: FXTask<*>) {
        if (file.name == DirectoryConverter.ENUMS_LUA) {
            enumWorker()
        } else {
            excelWorker(t)
        }
    }

    private fun createLuaVm(): Globals {
        val globals = JsePlatform.debugGlobals()
        globals.finder = LuaFinder(listOf(outDirectory, file.parentFile))
        return globals
    }

    private fun loadEnums(L: Globals) {
        if (File(file.parentFile, DirectoryConverter.ENUMS_LUA).exists()) {
            val enums = L.loadfile(DirectoryConverter.ENUMS_LUA).call()
            L.set("__ENUMS", enums)
        }

    }

    private fun enumWorker() {
        val L = createLuaVm()
        try {
            loadEnums(L)
            val inspect = L.loadfile("inspect.lua").call()
            val exportedEnums = L.loadfile("export_enums.lua").call()
            val outLua = String.format("-- %s\nreturn %s\n", DirectoryConverter.ENUMS_LUA, inspect.call(exportedEnums).toString())
            val outputFile = File(file.parentFile, "enums.lua")
            Files.write(outLua, outputFile, Charsets.UTF_8)
        } catch (e: LuaError) {
            throw OneFileConverterError(file, "${e.message}${SystemProperties.LINE_SEPARATOR}${L.debuglib.traceback(1)}")
        } finally {
        }
    }

    private fun excelWorker(t: FXTask<*>) {
        val L = createLuaVm()
        try {
            loadEnums(L)
            val utilLua = File(file.parentFile, "__util.lua")
            if (utilLua.isFile()) {
                val util = L.load(Files.toString(utilLua, Charsets.UTF_8), "__util.lua").call()
                L.set("util", util)
            }

            ExcelWorker(file, outDirectory, L).run(t)
        } catch (e: LuaError) {
            throw OneFileConverterError(file, "${e.message}${SystemProperties.LINE_SEPARATOR}${L.debuglib.traceback(1)}", cause = e)
        }
    }
}

