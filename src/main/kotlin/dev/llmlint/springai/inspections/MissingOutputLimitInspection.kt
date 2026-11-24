package dev.llmlint.springai.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.options.OptPane
import com.intellij.psi.PsiElementVisitor
import com.intellij.uast.UastHintedVisitorAdapter
import dev.llmlint.springai.LlmLintBundle
import dev.llmlint.springai.SpringAiNames
import dev.llmlint.springai.config.ProviderDefault
import dev.llmlint.springai.config.ProviderDefaults
import dev.llmlint.springai.config.ProviderVerdict
import dev.llmlint.springai.config.SpringAiConfigFiles
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Reports `ChatClient...call()` / `.stream()` when no output token limit is set on the request,
 * on the client (defaultOptions / model options), in Spring configuration, or by the provider's own default.
 */
class MissingOutputLimitInspection : LocalInspectionTool() {

    /** Also report (as a weak warning) calls whose only limit is the provider's default. Off by default. */
    @JvmField
    var reportProviderDefaults: Boolean = false

    override fun getOptionsPane(): OptPane = OptPane.pane(
        OptPane.checkbox(
            "reportProviderDefaults",
            LlmLintBundle.message("inspection.missing.output.limit.option.report.provider.defaults"),
        ),
    )

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    check(node, holder)
                    return true
                }
            },
            arrayOf(UCallExpression::class.java),
        )

    private fun check(node: UCallExpression, holder: ProblemsHolder) {
        val name = node.methodName
        if ((name != "call" && name != "stream") || node.valueArgumentCount != 0) return
        if (!UastFlow.isMethodOf(node, SpringAiNames.CHAT_CLIENT_REQUEST_SPEC)) return

        val sourcePsi = node.sourcePsi ?: return
        val analyzer = OutputLimitAnalyzer(holder.project)
        // SAFE: every path has a limit. UNKNOWN: something we can't see may set one; stay silent.
        val inCode = analyzer.analyzeRequestSpec(node.receiver)
        if (inCode == Coverage.SAFE || inCode == Coverage.UNKNOWN) return

        // The providers the call may run on: the model the code shows, if any, otherwise every provider on the
        // module's classpath. They decide which configuration keys and which default limits apply.
        val candidates = if (analyzer.modelClasses.isNotEmpty()) {
            ProviderDefaults.forModelClasses(analyzer.modelClasses)
        } else {
            ProviderDefaults.presentFor(sourcePsi)
        }
        // Configuration sets the model's options, which apply underneath whatever the code sets.
        val coverage = inCode layer SpringAiConfigFiles.outputLimit(sourcePsi, candidates)
        if (coverage == Coverage.SAFE || coverage == Coverage.UNKNOWN) return

        // Nothing explicit: the provider's own default decides.
        val anchor = node.methodIdentifier?.sourcePsi ?: sourcePsi
        when (val verdict = ProviderDefaults.verdict(candidates)) {
            is ProviderVerdict.AllDefaulted -> if (reportProviderDefaults) {
                holder.registerProblem(anchor, providerDefaultMessage(verdict.providers), ProblemHighlightType.WEAK_WARNING)
            }
            ProviderVerdict.Mixed -> Unit // can't tell which provider runs: stay silent
            ProviderVerdict.NoDefault, ProviderVerdict.NoKnownProvider -> {
                val key = when {
                    coverage == Coverage.MIXED -> "inspection.missing.output.limit.partial.message"
                    analyzer.clientDefaultsReplaced -> "inspection.missing.output.limit.replaced.defaults.message"
                    else -> "inspection.missing.output.limit.message"
                }
                holder.registerProblem(anchor, LlmLintBundle.message(key))
            }
        }
    }

    private fun providerDefaultMessage(providers: List<ProviderDefault>): String {
        val distinct = providers.distinctBy { it.provider }
        val names = distinct.joinToString(" / ") { it.provider }
        val limits = distinct.mapNotNull { it.defaultMaxTokens }.distinct().joinToString(" / ")
        return LlmLintBundle.message("inspection.missing.output.limit.provider.default.message", names, limits)
    }
}
