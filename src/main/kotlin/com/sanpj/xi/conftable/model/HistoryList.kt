package com.sanpj.xi.conftable.model

import tornadofx.*
import java.util.*

class HistoryList(val capacity: Int = 20)  {
    val list = LinkedList<String>().observable()
    
    fun add(item: String) {
        list.remove(item)
        list.add(0, item)
        ensureCapacity()
    }
    
    private fun ensureCapacity() {
        if (list.size > capacity) {
            list.remove(capacity, list.size)
        }
    }
}

