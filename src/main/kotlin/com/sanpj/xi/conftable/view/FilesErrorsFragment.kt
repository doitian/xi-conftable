package com.sanpj.xi.conftable.view

import javafx.beans.property.SimpleStringProperty
import javafx.scene.layout.Priority
import tornadofx.*

class FilesErrorsFragment : Fragment() {
    var errorsTextProperty = SimpleStringProperty()
    var selectedFileTextProperty = SimpleStringProperty()

    override val root = vbox(10) {
        label {
            paddingAll = 10
            bind(selectedFileTextProperty.stringBinding {
                if (it.isNullOrBlank()) {
                    messages["label.errors_of_all_files"]
                } else {
                    it
                }
            })
        }
        textarea {
            vgrow = Priority.ALWAYS
            isEditable = false
            disableProperty().bind(errorsTextProperty.booleanBinding {
                it.isNullOrBlank()
            })
            bind(errorsTextProperty)
        }
    }
}
