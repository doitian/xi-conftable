package com.sanpj.xi.conftable.utils

import com.google.common.io.Files
import org.luaj.vm2.lib.ResourceFinder
import java.io.*

class LuaFinder(val directories: List<File>): ResourceFinder {
    val cache = HashMap<String, ByteArray>()

    override fun findResource(filename: String?): InputStream? {
        if (filename == null) {
            return null
        }

        val classLoaderResult = javaClass.getClassLoader().getResourceAsStream("lua/" + filename);
        if (classLoaderResult != null) {
            return classLoaderResult
        }

        try {
            val cachedBytes = cache.get(filename)
            if (cachedBytes != null) {
                return ByteArrayInputStream(cachedBytes)
            }
            for (directory in directories) {
                val bytes = Files.toByteArray(File(directory, filename))
                if (bytes != null) {
                    cache.put(filename, bytes)
                    return ByteArrayInputStream(bytes)
                }
            }
            return null;
        } catch (_: IOException) {
            return null;
        }
    }
}
