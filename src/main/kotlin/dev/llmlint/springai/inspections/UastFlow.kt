package dev.llmlint.springai.inspections

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReturnExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.skipParenthesizedExprDown
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.visitor.AbstractUastVisitor

/** Small, language-neutral (UAST) helpers to follow values through variables and methods. */
object UastFlow {

    /** `a.b()` or `b()` as a call; anything else is null. */
    fun asCall(expression: UExpression?): UCallExpression? = when (val e = expression?.skipParenthesizedExprDown()) {
        is UCallExpression -> e
        is UQualifiedReferenceExpression -> e.selector.skipParenthesizedExprDown() as? UCallExpression
        else -> null
    }

    fun isMethodOf(call: UCallExpression, classFqn: String): Boolean =
        call.resolve()?.containingClass?.qualifiedName == classFqn

    /** The declaration behind `x` or `this.x`, or null. */
    fun resolveReference(expression: UExpression?): PsiElement? = when (val e = expression?.skipParenthesizedExprDown()) {
        is USimpleNameReferenceExpression -> e.resolve()
        is UQualifiedReferenceExpression -> (e.selector as? USimpleNameReferenceExpression)?.resolve()
        else -> null
    }

    /** Field → its class, local → its method (or lambda). */
    fun enclosingScope(variable: UVariable): LocalSearchScope? {
        val container: UElement? = when (variable) {
            is ULocalVariable -> variable.getParentOfType<UMethod>() ?: variable.getParentOfType<ULambdaExpression>()
            else -> variable.getParentOfType<UClass>()
        }
        val psi = container?.sourcePsi ?: return null
        return LocalSearchScope(psi)
    }

    /** Right-hand sides of `x = ...` for [target] inside its enclosing scope (e.g. constructor assignments). */
    fun assignedValues(target: PsiElement, variable: UVariable): List<UExpression> {
        val scope = enclosingScope(variable) ?: return emptyList()
        return ReferencesSearch.search(target, scope).findAll().mapNotNull { ref ->
            val refElement = ref.element.toUElement() ?: return@mapNotNull null
            val assignment = refElement.getParentOfType<UBinaryExpression>(strict = true)
                ?.takeIf { it.operator == UastBinaryOperator.ASSIGN }
                ?: return@mapNotNull null
            val left = assignment.leftOperand.sourcePsi
            if (left != null && PsiTreeUtil.isAncestor(left, ref.element, false)) assignment.rightOperand else null
        }
    }

    /**
     * Calls made directly on [target] in its scope, e.g. `builder.eventFilter(...)` on its own line,
     * including the rest of a chain started there (`builder.a().b()`).
     */
    fun callsOnVariable(target: PsiElement, variable: UVariable): List<UCallExpression> {
        val scope = enclosingScope(variable) ?: return emptyList()
        val calls = mutableListOf<UCallExpression>()
        for (ref in ReferencesSearch.search(target, scope).findAll()) {
            var parent = ref.element.toUElement()?.uastParent
            while (parent is UQualifiedReferenceExpression) {
                (parent.selector as? UCallExpression)?.let(calls::add)
                parent = parent.uastParent
            }
        }
        return calls
    }

    /** Values returned by a method body, ignoring returns of nested lambdas and classes. */
    fun returnedExpressions(method: PsiMethod): List<UExpression>? {
        val uMethod = method.toUElement() as? UMethod ?: return null
        val body = uMethod.uastBody ?: return null
        val values = mutableListOf<UExpression>()
        body.accept(object : AbstractUastVisitor() {
            override fun visitLambdaExpression(node: ULambdaExpression) = true
            override fun visitClass(node: UClass) = true
            override fun visitReturnExpression(node: UReturnExpression): Boolean {
                node.returnExpression?.let(values::add)
                return false
            }
        })
        return values
    }

    /** A method written in this project (not a library, not Spring itself). */
    fun isProjectSource(project: Project, method: PsiMethod): Boolean {
        if (method is PsiCompiledElement) return false
        if (method.containingClass?.qualifiedName?.startsWith("org.springframework.") == true) return false
        val file = method.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInSourceContent(file)
    }
}
