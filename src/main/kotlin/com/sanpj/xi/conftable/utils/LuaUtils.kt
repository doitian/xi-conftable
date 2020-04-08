package com.sanpj.xi.conftable.utils

import com.google.common.io.Files
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import java.io.File

class LuaUtils {
    companion object {
        fun loadFile(L: Globals, file: File): LuaValue =
            L.load(Files.toString(file, Charsets.UTF_8), file.name).call();

        fun extraceMessageWithDebugTraceback(L: Globals, t: Throwable) =
            when (t) {
                is LuaError -> "${t.message}${SystemProperties.LINE_SEPARATOR}${L.debuglib.traceback(2)}"
                else -> t.message ?: t.toString()
            }
    }
}

