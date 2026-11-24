package dev.llmlint.springai.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.uast.UastHintedVisitorAdapter
import dev.llmlint.springai.LlmLintBundle
import dev.llmlint.springai.SpringAiNames
import dev.llmlint.springai.config.SpringAiConfigFiles
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Reports `ChatClient...call()` / `.stream()` when no output token limit is set on the request,
 * on the client (defaultOptions / model options) or in Spring configuration.
 */
class MissingOutputLimitInspection : LocalInspectionTool() {

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
        if (SpringAiConfigFiles.isOutputLimitConfigured(sourcePsi)) return
        if (OutputLimitAnalyzer(holder.project).analyzeRequestSpec(node.receiver) != Limit.NOT_SET) return

        val anchor = node.methodIdentifier?.sourcePsi ?: sourcePsi
        holder.registerProblem(anchor, LlmLintBundle.message("inspection.missing.output.limit.message"))
    }
}
