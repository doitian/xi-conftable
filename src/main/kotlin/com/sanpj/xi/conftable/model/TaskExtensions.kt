package com.sanpj.xi.conftable.model

import javafx.concurrent.Task
import javafx.concurrent.WorkerStateEvent
import javafx.event.EventHandler

fun <T> Task<T>.setOnCompleted(value: EventHandler<WorkerStateEvent>) {
    setOnSucceeded(value)
    setOnFailed(value)
    setOnCancelled(value)
}

fun <T> Task<T>.setOnCompleted(value: (WorkerStateEvent) -> Unit) {
    setOnSucceeded(value)
    setOnFailed(value)
    setOnCancelled(value)
}
