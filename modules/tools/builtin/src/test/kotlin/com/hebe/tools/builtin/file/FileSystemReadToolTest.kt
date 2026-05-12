package com.hebe.tools.builtin.file

import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import com.hebe.api.workspace.WorkspacePath
import com.hebe.memory.workspace.WorkspaceFs
import io.mockk.mockk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

class FileSystemReadToolTest {
    @Test
    fun `read existing file returns Ok`(@TempDir tempDir: Path) {
        val fs = WorkspaceFs(tempDir)
        fs.write(WorkspacePath("test.txt"), "hello world")
        val tool = FileSystemReadTool(fs)

        val args = buildJsonObject {
            put("path", kotlinx.serialization.json.JsonPrimitive("test.txt"))
        }
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(args, ctx) }

        Assertions.assertTrue(result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        Assertions.assertTrue(ok.content.toString().contains("hello world"))
    }

    @Test
    fun `read non-existent file returns Err`(@TempDir tempDir: Path) {
        val fs = WorkspaceFs(tempDir)
        val tool = FileSystemReadTool(fs)

        val args = buildJsonObject {
            put("path", kotlinx.serialization.json.JsonPrimitive("nonexistent.txt"))
        }
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(args, ctx) }

        Assertions.assertTrue(result is ToolResult.Err)
        Assertions.assertTrue((result as ToolResult.Err).message.contains("not found"))
    }

    @Test
    fun `read missing path arg returns Err`(@TempDir tempDir: Path) {
        val fs = WorkspaceFs(tempDir)
        val tool = FileSystemReadTool(fs)

        val args = buildJsonObject { }
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(args, ctx) }

        Assertions.assertTrue(result is ToolResult.Err)
        Assertions.assertTrue((result as ToolResult.Err).message.contains("missing required argument"))
    }
}