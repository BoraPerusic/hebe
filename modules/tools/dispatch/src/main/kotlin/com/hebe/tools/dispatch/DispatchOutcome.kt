package com.hebe.tools.dispatch

import com.hebe.api.ToolResult

sealed interface DispatchOutcome {
    data class Result(
        val result: ToolResult,
    ) : DispatchOutcome
}
