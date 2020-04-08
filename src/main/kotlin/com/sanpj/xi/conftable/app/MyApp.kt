package com.sanpj.xi.conftable.app

import com.sanpj.xi.conftable.view.MainView
import javafx.application.Platform
import javafx.stage.Stage
import tornadofx.*
import java.util.*

class MyApp: App(MainView::class, Styles::class) {
    override fun start(stage: Stage) {
        super.start(stage)
        stage.setOnCloseRequest { _ ->
            val view = find(MainView::class)
            view.shutdown()
            Platform.exit();
            System.exit(0);
        }
    }

    override fun init() {
        FX.locale = Locale("zh_CN")
        super.init()
    }
}
