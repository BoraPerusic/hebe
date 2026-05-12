package com.hebe.tools.builtin.k8s

import com.hebe.api.RiskLevel
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import com.hebe.api.ToolSpec
import com.hebe.tools.builtin.shell.ProcessRunner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Path

class KubectlTool(
    private val workspaceRoot: Path,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val READ_ONLY_VERBS = setOf(
        "get", "describe", "logs", "top", "events", "version",
        "config", "view", "auth", "can-i", "explain", "get",
    )

    private val MUTATING_VERBS = setOf(
        "apply", "create", "delete", "patch", "replace",
        "scale", "rollout", "cordon", "drain", "uncordon",
        "taint", "label", "annotate", "exec", "port-forward",
        "set", "edit",
    )

    override val spec = ToolSpec(
        name = "kubectl",
        description = "Run kubectl commands. Read-only verbs are Medium risk. " +
            "Mutating verbs are High risk and always require approval.",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "verb",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("kubectl verb (get, describe, apply, delete, etc.)"))
                        },
                    )
                    put(
                        "args",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Additional kubectl arguments"))
                        },
                    )
                    put(
                        "kubeconfig",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Optional path to kubeconfig file"))
                        },
                    )
                    put(
                        "context",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Optional kubeconfig context"))
                        },
                    )
                },
            )
        },
        pathScope = com.hebe.api.PathScope.WorkspaceOnly,
    )

    override val risk: RiskLevel
        get() = RiskLevel.Medium

    override val readOnly: Boolean
        get() = false

    override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
        val verb = args["verb"]?.jsonPrimitive?.content
            ?: return ToolResult.Err("missing required argument: verb")
        val extraArgsArr = args["args"]?.jsonArray
        val kubeconfig = args["kubeconfig"]?.jsonPrimitive?.content
        val context = args["context"]?.jsonPrimitive?.content

        logger.debug("kubectl verb={}", verb)

        val extraArgs = extraArgsArr?.mapNotNull { it.jsonPrimitive?.content } ?: emptyList()
        val kubectlArgs = buildKubectlArgs(verb, extraArgs, kubeconfig, context)

        val result = ProcessRunner.run("kubectl $kubectlArgs", workspaceRoot, 120_000)

        return if (result.timedOut) {
            ToolResult.Err("timeout after 120s")
        } else if (result.exitCode == 0) {
            ToolResult.Ok(
                buildJsonObject {
                    put("stdout", JsonPrimitive(result.stdout))
                    put("stderr", JsonPrimitive(result.stderr))
                    put("exitCode", JsonPrimitive(result.exitCode))
                },
            )
        } else {
            ToolResult.Err("kubectl failed: ${result.stderr.take(500)}")
        }
    }

    private fun buildKubectlArgs(verb: String, extraArgs: List<String>, kubeconfig: String?, context: String?): String {
        val parts = mutableListOf<String>()
        kubeconfig?.let { parts.add("--kubeconfig=$it") }
        context?.let { parts.add("--context=$it") }
        parts.add(verb)
        parts.addAll(extraArgs)
        return parts.joinToString(" ")
    }
}