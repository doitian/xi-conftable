package com.sanpj.xi.conftable.view

import com.google.common.io.Files
import com.sanpj.xi.conftable.app.Styles
import com.sanpj.xi.conftable.model.DirectoryConverter
import com.sanpj.xi.conftable.model.FileConverter
import com.sanpj.xi.conftable.model.HistoryList
import com.sanpj.xi.conftable.model.setOnCompleted
import com.sanpj.xi.conftable.utils.SystemProperties
import com.sanpj.xi.conftable.utils.TextPropertyLogHandler
import javafx.application.Platform
import javafx.beans.Observable
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Pos
import javafx.scene.input.KeyCode
import javafx.scene.layout.Priority
import javafx.scene.paint.Color
import javafx.stage.FileChooser
import tornadofx.*
import java.awt.Desktop
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.prefs.Preferences

class MainView : View() {
    val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val PREF_RECENT_HISTORY = "RECENT_HISTORY"
    private val PREF_OUT_DIR_PREFIX = "OUTDIR:"

    var prefs = Preferences.userNodeForPackage(MainView::class.java)
    val recentHistory = HistoryList()
    val directoryConverter = DirectoryConverter(File(""), File(""))
    val stringPropertyLogHandler = TextPropertyLogHandler()
    val logsFragment = find<LogsFragment>(mapOf(LogsFragment::textPropertyLogHandler to stringPropertyLogHandler))
    val filesErrorsFragment = find<FilesErrorsFragment>()

    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null

    override val root = borderpane {
        val sizeProperty = SimpleIntegerProperty(0)
        val pendingSizeProperty = SimpleIntegerProperty(0)
        val failedSizeProperty = SimpleIntegerProperty(0)
        val runningSizeProperty = SimpleIntegerProperty(0)
        val files = directoryConverter.files
        files.onChange {
            sizeProperty.set(files.size)
            pendingSizeProperty.set(files.count { it.status == FileConverter.Status.PENDING })
            failedSizeProperty.set(files.count { it.status == FileConverter.Status.FAILED })
            runningSizeProperty.set(files.count { it.status == FileConverter.Status.RUNNING })
        }
        val validateAllFailedProperty = directoryConverter.validateAllMessagesProperty.booleanBinding {
            it != null && it.startsWith("E ")
        }

        top = menubar {
            menu(messages["menu.file"]) {
                item(messages["menu.open"]).action {
                    open(chooseDirectory())
                }
                item(messages["menu.rescan"]).action {
                    directoryConverter.rescan()
                }
                menu(messages["menu.recent"]) {
                    recentHistory.list.addListener({ _: Observable ->
                        items.clear()
                        for (dir in recentHistory.list) {
                            item(dir).action {
                                open(File(dir))
                            }
                        }
                        separator()
                        item(messages["menu.recent.clear"]).action {
                            recentHistory.list.removeAll()
                        }
                    })
                }
                separator()
                item(messages["menu.quit"]).action {
                    Platform.exit()
                }
            }
            menu(messages["menu.windows"]) {
                item(messages["menu.logs"]).action {
                    logsFragment.openWindow()
                }
                item(messages["menu.errors"]).action {
                    filesErrorsFragment.openWindow()
                }
            }
        }
        
        center = vbox(10) {
            paddingAll = 10
            form {
                fieldset {
                    field(messages["label.directory"]) {
                        hbox {
                            textfield {
                                isEditable = false
                                hgrow = Priority.ALWAYS
                                bind(directoryConverter.directoryProperty.stringBinding {
                                    if (it != null && it.exists()) it.getAbsolutePath() else ""
                                })
                            }
                            button("...") {
                                action {
                                    open(chooseDirectory())
                                }
                            }
                        }
                    }
                }
                fieldset {
                    field(messages["label.out_directory"]) {
                        hbox {
                            textfield {
                                isEditable = false
                                hgrow = Priority.ALWAYS
                                bind(directoryConverter.outDirectoryProperty.stringBinding {
                                    if (it != null && it.exists()) it.getAbsolutePath() else ""
                                })
                            }
                            button("...") {
                                action {
                                    saveTo(chooseDirectory())
                                }
                            }
                        }
                    }
                }
                fieldset {
                    hbox(10) {
                        checkbox(messages["label.auto_validate_all"]).bind(directoryConverter.autoValidateAllProperty)
                        checkbox(messages["label.only_updated_files"]).bind(directoryConverter.onlyConvertUpdatedFilesProperty)
                        checkbox(messages["label.only_failed_files"]).bind(directoryConverter.onlyConvertFailedFilesProperty)
                    }
                }

                hbox(10) {
                    alignment = Pos.BASELINE_RIGHT
                    checkbox(messages["label.auto_convert"]).bind(directoryConverter.autoConvertProperty)
                    button(messages["label.start_convert"]) {
                        disableProperty().bind(runningSizeProperty.booleanBinding { it != null && it as Int > 0 })
                        failedSizeProperty.onChange {
                            toggleClass(Styles.primaryButton, it == 0)
                            toggleClass(Styles.dangerButton, it > 0)
                        }

                        addClass(Styles.primaryButton)
                        action {
                            directoryConverter.convertSelectedFiles()
                        }
                    }
                }
            }

            label(messages["label.not_selected_files_are_not_converted"])

            val filesButtons = hbox(10) {
                button(messages["label.select_all"]) {
                    action { directoryConverter.selectAll() }
                }
                button(messages["label.deselect_all"]) {
                    action { directoryConverter.deselectAll() }
                }
                button(messages["label.toggle_selection"]) {
                    action { directoryConverter.toggleSelection() }
                }
                button(messages["label.rescan"]) {
                    action { directoryConverter.rescan() }
                }
            }

            hbox(10) {
                val filterTextField = textfield {
                    hgrow = Priority.ALWAYS
                    setOnKeyPressed { e ->
                        if (e.code == KeyCode.ENTER) {
                            directoryConverter.filter = text
                        }
                    }
                }
                button(messages["label.set_filter"]) {
                    action {
                        directoryConverter.filter = filterTextField.text
                    }
                }
                button(messages["label.clear_filter"]) {
                    disableProperty().bind(directoryConverter.filterProperty.isEmpty)
                    action {
                        directoryConverter.filter = ""
                    }
                }
            }

            val filesTableView = tableview(directoryConverter.filteredFiles) {
                vgrow = Priority.ALWAYS
                column("", FileConverter::selectedProperty).useCheckbox(true)
                readonlyColumn(messages["model.file.name"], FileConverter::file).cellFormat {
                    text = it.name
                }
                column(messages["model.file.lastModified"], FileConverter::lastModifiedProperty).cellFormat {
                    text = Instant.ofEpochMilli(it.toLong()).atZone(ZoneId.systemDefault()).format(DATE_TIME_FORMAT)
                }
                column(messages["model.file.lastConverted"], FileConverter::lastConvertedProperty).cellFormat {
                    val milli = it.toLong()
                    if (milli <= 0L) {
                        text = messages["label.never"]
                    } else {
                        text = Instant.ofEpochMilli(it.toLong()).atZone(ZoneId.systemDefault()).format(DATE_TIME_FORMAT)
                    }
                }
                column(messages["model.file.status"], FileConverter::statusProperty).cellFormat {
                    text = messages["status." + it.name]
                    style {
                        textFill = it.fillColor
                        if (it.backgroundColor != null) {
                            backgroundColor += it.backgroundColor
                        }
                    }
                }
                column(messages["model.file.progress"], FileConverter::progressProperty).useProgressBar()
                columnResizePolicy = SmartResize.POLICY
            }
            filesErrorsFragment.selectedFileTextProperty.bind(
                    filesTableView.selectionModel.selectedItemProperty().stringBinding {
                        if (it != null) it.file.name else ""
                    }
            )

            if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) {
                filesButtons += button(messages["label.open_file"]) {
                    disableProperty().bind(filesTableView.selectionModel.selectedItemProperty().booleanBinding {
                        it == null || !it.file.exists()
                    })
                    action {
                        val selectedItem = filesTableView.selectionModel.selectedItem
                        if (selectedItem != null) {
                            desktop.open(selectedItem.file)
                        }
                    }
                }
                filesButtons += button(messages["label.open_directory"]) {
                    disableProperty().bind(directoryConverter.directoryProperty.booleanBinding {
                        it == null || !it.exists()
                    })
                    action {
                        desktop.open(directoryConverter.directory)
                    }
                }
            }

            hbox(10) {
                alignment = Pos.BASELINE_LEFT
                label(messages["label.select_file_to_see_errors"])
                button(messages["label.show_all_errors"]) {
                    disableWhen(filesTableView.selectionModel.selectedItemProperty().isNull)
                    action {
                        filesTableView.selectionModel.clearSelection()
                    }
                }
                button(messages["label.open_in_window"]) {
                    action {
                        filesErrorsFragment.openWindow()
                    }
                }
                button(messages["label.save_errors"]) {
                    action {
                        val choosedFiles = chooseFile(
                                messages["label.save_errors"],
                                arrayOf(FileChooser.ExtensionFilter("Text Files", "*.txt")),
                                FileChooserMode.Save
                        ) {
                            initialFileName = "conftable-errors-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("YYYYMMdd-HHmm"))
                        }
                        if (!choosedFiles.isEmpty()) {
                            val contentBuilder = StringBuilder()
                            contentBuilder.appendln("====== files errors ======")
                            directoryConverter.files.forEach {
                                if (it.errorMessages.isNotBlank()) {
                                    contentBuilder.appendln(it.errorMessages)
                                }
                            }
                            contentBuilder.appendln()

                            contentBuilder.appendln("====== validate_all ======")
                            contentBuilder.appendln(directoryConverter.validateAllMessages)
                            contentBuilder.appendln()

                            contentBuilder.appendln("====== logs ======")
                            contentBuilder.appendln(stringPropertyLogHandler.text)

                            Files.write(contentBuilder.toString(), choosedFiles[0], Charsets.UTF_8)
                        }
                    }
                }
            }
            textarea {
                vgrow = Priority.ALWAYS
                prefRowCount = 3
                isEditable = false

                disableProperty().bind(textProperty().booleanBinding { it.isNullOrBlank() })

                val allErrorsProperty = SimpleStringProperty("")
                files.onChange {
                    allErrorsProperty.set(files.map { it.errorMessages }.filter { it.isNotBlank() }.joinToString(SystemProperties.LINE_SEPARATOR))
                }

                var prevSelectedModel: FileConverter? = null
                bind(allErrorsProperty)

                filesTableView.selectionModel.selectedItemProperty().onChange { model ->
                    if (prevSelectedModel != null) {
                        prevSelectedModel!!.errorMessagesProperty.unbindBidirectional(this.textProperty())
                    } else {
                        allErrorsProperty.unbindBidirectional(this.textProperty())
                    }

                    prevSelectedModel = model
                    if (model != null) {
                        bind(model.errorMessagesProperty)
                    } else {
                        bind(allErrorsProperty)
                    }
                }

                filesErrorsFragment.errorsTextProperty.bind(textProperty())
            }

            separator()

            hbox(10) {
                alignment = Pos.TOP_RIGHT
                textarea {
                    prefRowCount = 3
                    isEditable = false
                    hgrow = Priority.ALWAYS
                    disableProperty().bind(textProperty().booleanBinding { it.isNullOrBlank() })
                    bind(directoryConverter.validateAllMessagesProperty)
                    styleProperty().bind(validateAllFailedProperty.objectBinding {
                        if (it ?: false) "-fx-text-fill: #c92a2a" else ""
                    })
                }
                button(messages["label.validate_all"]) {
                    validateAllFailedProperty.onChange {
                        toggleClass(Styles.primaryButton, !it)
                        toggleClass(Styles.dangerButton, it)
                    }

                    addClass(Styles.primaryButton)
                    action {
                        isDisable = true
                        directoryConverter.validateAll().setOnCompleted {
                            isDisable = false
                        }
                    }
                }
            }
        }

        bottom = textflow {
            text {
                textProperty().bind(sizeProperty.stringBinding {
                    " %s: %d".format(messages["status.total_files"], it)
                })
            }
            text {
                textProperty().bind(runningSizeProperty.stringBinding {
                    " %s: %d".format(messages["status.running_files"], it)
                })
                fillProperty().bind(runningSizeProperty.objectBinding {
                    if (it == 0) {
                        Color.GRAY
                    } else {
                        FileConverter.Status.RUNNING.backgroundColor
                    }
                })
            }
            text {
                textProperty().bind(pendingSizeProperty.stringBinding {
                    " %s: %d".format(messages["status.pending_files"], it)
                })
                fillProperty().bind(pendingSizeProperty.objectBinding {
                    if (it == 0) {
                        Color.GRAY
                    } else {
                        FileConverter.Status.PENDING.fillColor
                    }
                })
            }
            text {
                textProperty().bind(failedSizeProperty.stringBinding {
                    " %s: %d".format(messages["status.failed_files"], it)
                })
                fillProperty().bind(failedSizeProperty.objectBinding {
                    if (it == 0) {
                        Color.GRAY
                    } else {
                        FileConverter.Status.FAILED.backgroundColor
                    }
                })
            }
        }
    }

    init {
        log.parent.addHandler(stringPropertyLogHandler)

        title = messages["app.title"]
        val restoredRecentHistory = prefs.get(PREF_RECENT_HISTORY, "")
        recentHistory.list.setAll(restoredRecentHistory.split(File.pathSeparator).filter { File(it).isDirectory })
        
        recentHistory.list.addListener({ _: Observable ->
            prefs.put(PREF_RECENT_HISTORY, recentHistory.list.joinToString(File.pathSeparator))
        })
        
        if (recentHistory.list.isNotEmpty()) {
            open(File(recentHistory.list[0]))
        }
    }
    
    fun open(inDirectory: File?) {
        if (inDirectory == null || !inDirectory.exists()) {
            return
        }

        recentHistory.add(inDirectory.absolutePath)
        var outDirectory = File(prefs.get(PREF_OUT_DIR_PREFIX + inDirectory.absolutePath, ""))
        if (!outDirectory.isDirectory) {
            outDirectory = inDirectory;
        }

        directoryConverter.open(inDirectory, outDirectory);
    }

    fun saveTo(outDirectory: File?) {
        val inDirectory = directoryConverter.directory
        val outDirectoryPrefKey = PREF_OUT_DIR_PREFIX + inDirectory.absolutePath
        if (outDirectory == null || !outDirectory.exists()) {
            prefs.put(outDirectoryPrefKey, "");
            directoryConverter.open(inDirectory, inDirectory)
        } else {
            prefs.put(outDirectoryPrefKey, outDirectory.absolutePath);
            directoryConverter.open(inDirectory, outDirectory)
        }
    }

    fun showErrorsWindow() {
    }

    fun shutdown() {
        directoryConverter.shutdown()
    }
}
