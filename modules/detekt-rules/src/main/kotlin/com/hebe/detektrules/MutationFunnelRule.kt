package com.hebe.detektrules

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

class MutationFunnelRule(
    config: Config = Config.empty,
) : Rule(config) {
    override val issue =
        io.gitlab.arturbosch.detekt.api.Issue(
            id = "MutationFunnel",
            severity = Severity.Defect,
            description =
                "Functions should not use mutable collections as parameters " +
                    "and return immutable collections.",
            debt = io.gitlab.arturbosch.detekt.api.Debt.TWENTY_MINS,
        )

    override fun visitNamedFunction(function: KtNamedFunction) {
        for (param in function.valueParameters) {
            if (isMutableCollectionType(param.typeReference?.text)) {
                report(
                    io.gitlab.arturbosch.detekt.api.CodeSmell(
                        issue,
                        io.gitlab.arturbosch.detekt.api.Entity
                            .atName(param),
                        "Parameter '${param.name}' is a mutable collection type. " +
                            "Consider using an immutable collection or a sequence.",
                    ),
                )
            }
        }

        val returnType = function.typeReference?.text
        if (isMutableCollectionType(returnType)) {
            report(
                io.gitlab.arturbosch.detekt.api.CodeSmell(
                    issue,
                    io.gitlab.arturbosch.detekt.api.Entity
                        .atName(function),
                    "Return type '$returnType' is a mutable collection. " +
                        "Consider returning an immutable collection or a sequence.",
                ),
            )
        }
    }

    override fun visitProperty(property: KtProperty) {
        if (property.isVar) {
            report(
                io.gitlab.arturbosch.detekt.api.CodeSmell(
                    issue,
                    io.gitlab.arturbosch.detekt.api.Entity
                        .atName(property),
                    "Property '${property.name}' is a mutable var. " +
                        "Consider using a val or a property that returns a new instance.",
                ),
            )
        }
    }

    private fun isMutableCollectionType(typeText: String?): Boolean {
        if (typeText == null) return false
        val mutablePrefixes = listOf("MutableList", "MutableSet", "MutableMap", "ArrayList", "HashMap", "HashSet")
        return mutablePrefixes.any { typeText.startsWith(it) }
    }
}

class MutationFunnelRuleSetProvider : io.gitlab.arturbosch.detekt.api.RuleSetProvider {
    override val ruleSetId: String = "hebe-mutation-funnel"

    override fun instance(config: Config): io.gitlab.arturbosch.detekt.api.RuleSet =
        io.gitlab.arturbosch.detekt.api.RuleSet(
            ruleSetId,
            listOf(MutationFunnelRule(config)),
        )
}
