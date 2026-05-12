package com.hebe.tools.builtin.git

import com.hebe.api.RiskLevel
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import com.hebe.api.ToolSpec
import com.hebe.tools.builtin.shell.ProcessResult
import com.hebe.tools.builtin.shell.ProcessRunner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryBuilder
import org.slf4j.LoggerFactory
import java.nio.file.Path

class GitPushTool(
    private val workspaceRoot: Path,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec = ToolSpec(
        name = "git_push",
        description = "Push to a remote git repository using shell-out. Always requires approval.",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "dir",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Repo directory (default: workspace root)"))
                        },
                    )
                    put(
                        "remote",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Remote name (default: origin)"))
                            put("default", JsonPrimitive("origin"))
                        },
                    )
                    put(
                        "branch",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Branch to push (default: current branch)"))
                        },
                    )
                },
            )
        },
        pathScope = com.hebe.api.PathScope.WorkspaceOnly,
    )

    override val risk = RiskLevel.High
    override val readOnly = false

    override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
        val dirStr = args["dir"]?.jsonPrimitive?.content
        val remote = args["remote"]?.jsonPrimitive?.content ?: "origin"
        val branch = args["branch"]?.jsonPrimitive?.content

        val cwd = if (dirStr != null) {
            val absPath = workspaceRoot.resolve(dirStr)
            if (!absPath.toString().startsWith(workspaceRoot.toString())) {
                return ToolResult.Err("dir outside workspace: $dirStr")
            }
            absPath
        } else {
            workspaceRoot
        }

        logger.debug("git_push dir={} remote={} branch={}", cwd, remote, branch)

        val isGitRepo = RepositoryBuilder().findGitDir(cwd.toFile()) != null
        if (!isGitRepo) {
            return ToolResult.Err("not a git repo: $cwd")
        }

        val branchArg = if (branch != null) "$remote $branch" else remote
        val result = ProcessRunner.run("git push $branchArg", cwd, 120_000)

        return if (result.exitCode == 0) {
            ToolResult.Ok(
                buildJsonObject {
                    put("stdout", JsonPrimitive(result.stdout))
                    put("stderr", JsonPrimitive(result.stderr))
                },
            )
        } else {
            ToolResult.Err("push failed: ${result.stderr.take(500)}")
        }
    }
}