@file:Suppress("NewLineAtEndOfFile")

package com.hebe.scheduler.cron

import kotlinx.datetime.TimeZone
import kotlin.time.Duration

sealed interface Cron {
    data object Hourly : Cron
    data object Daily : Cron
    data class Every(val interval: kotlin.time.Duration) : Cron
    data class Standard(
        val minute: Int,
        val hour: Int,
        val dom: Int,
        val month: Int,
        val dow: Int,
    ) : Cron
}