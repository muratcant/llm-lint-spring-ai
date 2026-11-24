package dev.llmlint.springai.inspections

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiType
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/** Three-valued answer of [CallSearch]: the memory inspection only warns on a definite [NOT_SET]. */
enum class Limit {
    SET, NOT_SET, UNKNOWN;

    infix fun or(other: Limit): Limit = when {
        this == SET || other == SET -> SET
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        else -> NOT_SET
    }
}

/**
 * Looks for a call named one of [names] inside an expression, following local variables, fields
 * and helper methods of the project. A value of one of [opaqueTypes] that comes from outside
 * (a parameter, an injected field) makes the answer [Limit.UNKNOWN].
 */
class CallSearch(
    private val project: Project,
    private val names: Set<String>,
    private val opaqueTypes: List<String>,
    /** When not empty, a call named one of [names] counts only if declared in one of these classes. */
    private val owners: Set<String> = emptySet(),
) {
    private val visited = HashSet<PsiElement>()

    fun scan(expression: UExpression?, depth: Int = 0): Limit {
        if (expression == null) return Limit.NOT_SET
        if (depth > MAX_DEPTH) return Limit.UNKNOWN
        var result = Limit.NOT_SET
        expression.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                val method = node.resolve()
                if (node.methodName in names) {
                    val owner = method?.containingClass?.qualifiedName
                    when {
                        owners.isEmpty() || owner in owners -> {
                            result = Limit.SET
                            return true
                        }
                        method == null -> result = result or Limit.UNKNOWN // unresolved: can't tell
                        else -> Unit // same name, another class: not what we look for
                    }
                }
                if (method != null && UastFlow.isProjectSource(project, method) && visited.add(method)) {
                    val returned = UastFlow.returnedExpressions(method)
                    result = result or (
                        if (returned.isNullOrEmpty()) Limit.UNKNOWN
                        else returned.fold(Limit.NOT_SET) { acc, value -> acc or scan(value, depth + 1) }
                        )
                }
                return false
            }

            override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                result = result or variable(node.resolve(), depth + 1)
                return false
            }
        })
        return result
    }

    private fun variable(target: PsiElement?, depth: Int): Limit {
        val variable = target?.toUElementOfType<UVariable>() ?: return Limit.NOT_SET
        if (!visited.add(target)) return Limit.NOT_SET
        if (variable is UParameter) return byType(variable.type)
        val values = listOfNotNull(variable.uastInitializer) + UastFlow.assignedValues(target, variable)
        if (values.isEmpty()) return byType(variable.type)
        return values.fold(Limit.NOT_SET) { acc, value -> acc or scan(value, depth) }
    }

    private fun byType(type: PsiType): Limit =
        if (opaqueTypes.any { InheritanceUtil.isInheritor(type, it) }) Limit.UNKNOWN else Limit.NOT_SET

    private companion object {
        const val MAX_DEPTH = 6
    }
}
