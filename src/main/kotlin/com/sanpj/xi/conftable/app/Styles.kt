package com.sanpj.xi.conftable.app

import javafx.geometry.Pos
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import tornadofx.*

class Styles : Stylesheet() {
    companion object {
        val primaryButton by cssclass()
        val dangerButton by cssclass()
        val swatch400 = c("#42A5F5")
        val swatch500 = c("#2196F3")
        val red400 = c("#f03e3e")
        val red500 = c("#c92a2a")
    }

    val systemFamilies = Font.getFamilies().map { it to true }.toMap()

    fun fallbackFontFamily(vararg families: String): String = families.find { systemFamilies.containsKey(it) } ?: families.last()

    init {
        root {
            fontFamily = fallbackFontFamily("Segoe UI", "Oxygen", "Ubuntu", "Cantarell", "Fira Sans", "Droid Sans", "Hiragino Sans GB", "Microsoft YaHei", "WenQuanYi Micro Hei", "sans-serif")
        }

        primaryButton {
            alignment = Pos.CENTER
            fontWeight = FontWeight.BOLD
            padding = box(0.7.em)
            backgroundColor = multi(swatch500)
            backgroundInsets = multi(box(0.px))
            textFill = Color.WHITE
            and(hover) {
                backgroundColor = multi(swatch400)
            }
            and(pressed) {
                backgroundColor = multi(swatch500.derive(0.5), swatch500.derive(-0.2))
                backgroundInsets = multi(box(0.em), box(0.2.em))
            }
        }
        dangerButton {
            alignment = Pos.CENTER
            fontWeight = FontWeight.BOLD
            padding = box(0.7.em)
            backgroundColor = multi(red500)
            backgroundInsets = multi(box(0.px))
            textFill = Color.WHITE
            and(hover) {
                backgroundColor = multi(red400)
            }
            and(pressed) {
                backgroundColor = multi(red500.derive(0.5), red500.derive(-0.2))
                backgroundInsets = multi(box(0.em), box(0.2.em))
            }
        }
    }
}
