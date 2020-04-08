package com.sanpj.xi.conftable.model

import com.sanpj.xi.conftable.utils.SystemProperties
import tornadofx.*
import java.io.File
import java.text.MessageFormat


private fun buildFileConverterErrorMessage(file: File, message: String, sheet: String?, row: Int?, col: String?): String {
    val messages = FX.messages
    val builder = StringBuilder("[")
    builder.append(file.name)
    if (sheet != null) {
        builder.append(" #")
        builder.append(sheet)
    }
    if (row != null) {
        builder.append(" ")
        builder.append(MessageFormat.format(messages["exception.row"], row))
    }
    if (col != null) {
        builder.append(" ")
        builder.append(MessageFormat.format(messages["exception.col"], col))
    }
    builder.append("]: ")
    builder.append(message.prependIndent("   ").trim())
    return builder.toString()
}

open class FileConverterError(message: String = "", cause: Throwable? = null): RuntimeException(message, cause) {}
class OneFileConverterError(val file: File, message: String, val sheet: String? = null, val row: Int? = null, val col: String? = null, cause: Throwable? = null) : FileConverterError(buildFileConverterErrorMessage(file, message, sheet, row, col), cause) {
}
class MultiFileConverterError() : FileConverterError() {
    val errors = ArrayList<FileConverterError>()

    fun add(err: FileConverterError) = errors.add(err)

    fun assertErrors() {
        if (errors.isNotEmpty()) {
            throw this
        }
    }

    fun hasErrors() = errors.isNotEmpty()

    override val message: String?
        get() = errors.map { it.message }.joinToString(SystemProperties.LINE_SEPARATOR)
}
