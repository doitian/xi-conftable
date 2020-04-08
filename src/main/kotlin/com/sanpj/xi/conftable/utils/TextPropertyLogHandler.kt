package com.sanpj.xi.conftable.utils

import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import tornadofx.*
import java.util.*
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.SimpleFormatter

class TextPropertyLogHandler(val maxRecords: Int = 5000): Handler() {
    val textProperty = SimpleStringProperty("")
    val text by textProperty

    private val logsList = LinkedList<String>()


    init {
        formatter = SimpleFormatter()
    }

    override fun publish(record: LogRecord?) {
        if (!isLoggable(record)) {
            return
        }
        synchronized(logsList) {
            logsList.add(formatter.format(record))
            while (logsList.size > maxRecords) {
                logsList.removeAt(0)
            }
        }
        flush()
    }

    override fun flush() {
        if (Platform.isFxApplicationThread()) {
            flushInFxApplicationThread()
        } else {
            Platform.runLater { flushInFxApplicationThread() }
        }
    }

    fun flushInFxApplicationThread() {
        synchronized(logsList) {
            textProperty.set(logsList.joinToString(""))
        }
    }

    override fun close() {
        flush()
        synchronized(logsList) {
            logsList.clear()
        }
    }

    fun clear() {
        synchronized(logsList) {
            logsList.clear()
        }
        flush()
    }
}