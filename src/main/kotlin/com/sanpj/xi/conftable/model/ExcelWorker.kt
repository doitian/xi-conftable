package com.sanpj.xi.conftable.model

import com.google.common.base.Strings
import com.google.common.io.Files
import com.sanpj.xi.conftable.utils.LuaUtils
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.*
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaInteger
import org.luaj.vm2.LuaValue
import tornadofx.*
import java.io.File
import java.nio.file.Paths
import java.util.*
import java.util.logging.Logger


class ExcelWorker(val file: File, val outDir: File, val L: Globals) {
    val dataFormatter = DataFormatter()
    val log by lazy { Logger.getLogger(this.javaClass.name) }
    val messages by lazy { FX.messages }

    internal data class Config(
        val firstRowNum: Int,
        val titleRowNum: Int,
        val key: String,
        val name: String,
        val format: String = "lua",
        val overallValidate: LuaValue? = null
    )

    internal data class ColumnConfig(
        val cellNum: Int,
        val name: String,
        val parseType: LuaValue,
        val postProcess: LuaValue? = null,
        val validate: LuaValue? = null,
        val title: String? = null
    )

    fun run(t: FXTask<*>) {
        ZipSecureFile.setMinInflateRatio(0.0)
        val workbook = WorkbookFactory.create(file, null, true)
        val sheets = (0 until workbook.numberOfSheets).map {
            workbook.getSheetAt(it)
        }.filter { sheet ->
            val firstRow = sheet.getRow(sheet.firstRowNum)
            if (firstRow == null) {
                false
            } else {
                val firstCol = getCellAsString(firstRow, firstRow.firstCellNum.toInt())
                firstCol == "配置"
            }
        }

        if (sheets.isEmpty()) {
            throw OneFileConverterError(file, messages["convert.empty_sheets"])
        }
        for (sheet in sheets) {
            convertSheet(t, workbook, sheet, sheets.size > 1)
        }
    }

    fun convertSheet(t: FXTask<*>, workbook: Workbook, sheet: Sheet, includeSheetName: Boolean) {
        val config: Config
        val columnConfigs: List<ColumnConfig>
        var step = messages["convert.parse_config_failed"] + " "
        try {
            config = parseConfig(sheet)
            columnConfigs = parseColumnConfigs(sheet, config.titleRowNum)
        } catch (ex: RuntimeException) {
            throw OneFileConverterError(file, step + LuaUtils.extraceMessageWithDebugTraceback(L, ex), sheet.sheetName, cause = ex)
        }

        val rows = LuaValue.tableOf()

        val totalRows = (sheet.lastRowNum - config.firstRowNum + 2).toDouble()
        var progress = 0.0
        t.updateProgress(progress, totalRows)
        for (r in config.firstRowNum - 1..sheet.lastRowNum) {
            progress += 1.0
            val row = sheet.getRow(r)
            if (row == null || isEmptyRow(row)) {
                t.updateProgress(progress, totalRows)
                continue
            }
            val rawRow = LuaValue.tableOf()
            val convertedRow = LuaValue.tableOf()
            step = messages["convert.parse_type_failed"] + " "
            for (columnConfig in columnConfigs) {
                try {
                    rawRow.set(columnConfig.name, columnConfig.parseType.call(getCellAsString(row, columnConfig.cellNum)))
                } catch (ex: Exception) {
                    throw OneFileConverterError(file, step + LuaUtils.extraceMessageWithDebugTraceback(L, ex), sheet.sheetName,r + 1, columnConfig.name, cause = ex)
                }

            }
            step = messages["convert.validate_failed"] + " "
            for (columnConfig in columnConfigs) {
                if (columnConfig.validate != null) {
                    val validationResult: LuaValue
                    try {
                        validationResult = columnConfig.validate.call(rawRow.get(columnConfig.name), rawRow)
                    } catch (ex: Exception) {
                        throw OneFileConverterError(file, step + LuaUtils.extraceMessageWithDebugTraceback(L, ex), sheet.sheetName, r + 1, columnConfig.name, cause = ex)
                    }

                    if (validationResult.isstring()) {
                        throw OneFileConverterError(file, step + validationResult.toString(), sheet.sheetName, r + 1, columnConfig.name)
                    }
                    if (!validationResult.toboolean()) {
                        throw OneFileConverterError(file, step, sheet.sheetName, r + 1, columnConfig.name)
                    }
                }
            }
            step = messages["convert.post_process_failed"] + " "
            for (columnConfig in columnConfigs) {
                if (!columnConfig.name.startsWith("_")) {
                    val rawVal = rawRow.get(columnConfig.name)
                    if (columnConfig.postProcess != null) {
                        try {
                            val value = columnConfig.postProcess.call(rawVal, rawRow)
                            convertedRow.set(columnConfig.name, value)
                        } catch (ex: Exception) {
                            throw OneFileConverterError(file, step + LuaUtils.extraceMessageWithDebugTraceback(L, ex), sheet.sheetName, r + 1, columnConfig.name, cause = ex)
                        }

                    } else {
                        convertedRow.set(columnConfig.name, rawVal)
                    }
                }
            }

            val nextRowKey = if (Strings.isNullOrEmpty(config.key))
                LuaValue.valueOf(rows.length() + 1)
            else
                convertedRow.get(config.key)
            if (nextRowKey.isnil()) {
                throw OneFileConverterError(file, messages["convert.require_id"], sheet.sheetName, r + 1)
            }

            if (!rows.get(nextRowKey).isnil()) {
                throw OneFileConverterError(file, messages["convert.duplicated_id"], sheet.sheetName, r + 1)
            }
            rows.set(nextRowKey, convertedRow)
            t.updateProgress(progress, totalRows)
        }
        workbook.close()

        step = messages["convert.overall_validate_failed"] + " "
        if (config.overallValidate != null) {
            val validationResult: LuaValue
            try {
                validationResult = config.overallValidate.call(rows)
            } catch (ex: Exception) {
                throw OneFileConverterError(file, step + LuaUtils.extraceMessageWithDebugTraceback(L, ex), sheet.sheetName, cause = ex)
            }

            if (validationResult.isstring()) {
                throw OneFileConverterError(file, step + validationResult.toString(), sheet.sheetName)
            }
            if (!validationResult.toboolean()) {
                throw OneFileConverterError(file, step, sheet.sheetName)
            }
        }

        val inspect = L.loadfile("inspect.lua").call()
        val columnTitles = columnConfigs.filter { !it.title.isNullOrBlank() }.map {
            String.format("-- %s: %s", it.name, it.title)
        }.joinToString("\n")
        val outLua = String.format(
                "-- %s%s%s%s\nreturn %s\n",
                file.name,
                if (includeSheetName) " " + sheet.sheetName else "",
                if (columnTitles.isEmpty()) "" else "\n",
                columnTitles,
                inspect.call(rows).toString()
        )
        val outputFile = makeOutputFile(file, config)
        Files.write(outLua, outputFile, Charsets.UTF_8)
    }

    private fun makeOutputFile(input: File, config: Config): File {
        var name = config.name
        if (Strings.isNullOrEmpty(name)) {
            name = input.nameWithoutExtension
        }

        return Paths.get(outDir.path, name + ".lua").toFile()
    }


    private fun parseColumnConfigs(sheet: Sheet, titleRowNum: Int): List<ColumnConfig> {
        val firstRowNum = sheet.getFirstRowNum()
        val nameRowNum = (firstRowNum + 2 .. sheet.lastRowNum).find { rowNum ->
            sheet.getRow(rowNum).let { nameRow ->
                nameRow != null && getCellAsString(nameRow, nameRow.firstCellNum.toInt()) == messages["table.row.column_key"]
            }
        }
        if (nameRowNum == null) {
            throw OneFileConverterError(file, messages["convert.find_column_config_failed"], sheet.sheetName)
        }

        val nameRow = sheet.getRow(nameRowNum)
        val typeRow = sheet.getRow(nameRowNum + 1)
        val validationRow = sheet.getRow(nameRowNum + 2)
        val postProcessRow = sheet.getRow(nameRowNum + 3)
        val titleRow = if (titleRowNum > 0) sheet.getRow(titleRowNum - 1) else null

        val configs = ArrayList<ColumnConfig>()

        for (i in nameRow.getFirstCellNum() + 1..nameRow.getLastCellNum()) {
            val name = getCellAsString(nameRow, i)
            if (!Strings.isNullOrEmpty(name)) {
                val typeLuaRaw = getCellAsString(typeRow, i)
                val typeLua = if (typeLuaRaw.isNullOrEmpty()) "string" else typeLuaRaw
                val validationLua = getCellAsString(validationRow, i)
                val postProcessLua = getCellAsString(postProcessRow, i)
                val title = if (titleRow != null) getCellAsString(titleRow, i) else null
                val postProcess = if (!Strings.isNullOrEmpty(postProcessLua))
                    L.load("return function(val, row)\n${postProcessLua}\nend").call()
                else
                    null
                val validate = if (!Strings.isNullOrEmpty(validationLua))
                    L.load("return function(val, row)\n${validationLua}\nend").call()
                else
                    null

                val config = ColumnConfig(
                        cellNum = i,
                        name = name,
                        parseType = L.load("local _ENV = require('types');return " + typeLua).call(),
                        postProcess = postProcess,
                        validate = validate,
                        title = title
                )

                configs.add(config)
            }
        }

        return configs
    }

    private fun parseConfig(sheet: Sheet): Config {
        val firstRowNum = sheet.getFirstRowNum()
        val keyRow = sheet.getRow(firstRowNum)
        val valueRow = sheet.getRow(firstRowNum + 1)

        val map = HashMap<String, String>()
        for (i in keyRow.getFirstCellNum() + 1..keyRow.getLastCellNum()) {
            val key = getCellAsString(keyRow, i)
            val value = getCellAsString(valueRow, i)

            map.put(key, value)
        }

        val keyFirstRowNum = messages["table.config.firstRowNum"]
        val keyTitleRowNum = messages["table.config.titleRowNum"]
        val keyName = messages["table.config.name"]
        val keyKey = messages["table.config.key"]
        val keyFormat = messages["table.config.format"]
        val overallValidationLua = map.getOrDefault(messages["table.config.overallValidationLua"], "")
        val overallValidate = if (overallValidationLua.isNotBlank())
            L.load("return function(rows)\n${overallValidationLua}\nend").call()
        else
            null

        return Config(
                firstRowNum = map.getOrDefault(keyFirstRowNum, "8").toInt(),
                titleRowNum = map.getOrDefault(keyTitleRowNum, "-1").toInt(),
                name = map.getOrDefault(keyName, file.nameWithoutExtension),
                key = map.getOrDefault(keyKey, ""),
                format = map.getOrDefault(keyFormat, "lua"),
                overallValidate = overallValidate
        )
    }

    private fun getCellAsString(row: Row, i: Int): String {
        if (i < 0) {
            return ""
        }

        val cell = row.getCell(i)
        if (cell != null) {
            if (cell.cellTypeEnum == CellType.FORMULA) {
                cell.setCellType(CellType.STRING)
                return cell.richStringCellValue.toString().trim()
            }
            return dataFormatter.formatCellValue(cell).trim()
        }

        return ""
    }

    private fun isEmptyRow(row: Row): Boolean {
        return (row.firstCellNum..row.lastCellNum).all {
            getCellAsString(row, it).isBlank()
        }
    }
}

