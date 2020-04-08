package com.sanpj.xi.conftable.view

import com.sanpj.xi.conftable.utils.TextPropertyLogHandler
import javafx.scene.layout.Priority
import tornadofx.*

class LogsFragment : Fragment() {
    val textPropertyLogHandler: TextPropertyLogHandler by param()

    override val root = vbox(10) {
        textarea {
            vgrow = Priority.ALWAYS
            isEditable = false
            textPropertyLogHandler.textProperty.addListener {_, _, newValue ->
                text = newValue
                selectPositionCaret(length)
                deselect()
            }
        }
        hbox {
            paddingAll = 10
            button(messages["label.clear_logs"]) {
                action {
                    textPropertyLogHandler.clear()
                }
            }
        }
    }
}