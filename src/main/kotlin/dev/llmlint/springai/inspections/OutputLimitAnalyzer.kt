package dev.llmlint.springai.inspections

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiModificationTracker
import dev.llmlint.springai.SpringAiNames
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.skipParenthesizedExprDown
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/** Three-valued answer: inspections only warn on a definite [NOT_SET]. */
enum class Limit {
    SET, NOT_SET, UNKNOWN;

    infix fun or(other: Limit): Limit = when {
        this == SET || other == SET -> SET
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        else -> NOT_SET
    }
}

/**
 * Finds out, from the code alone, whether a ChatClient request ends up with an output token limit.
 * One instance per analysed call (it keeps a visited set to stop on cycles).
 */
class OutputLimitAnalyzer(private val project: Project) {

    private val visited = HashSet<PsiElement>()

    /**
     * [specExpression] is the receiver of `.call()` / `.stream()`, i.e. an expression of type
     * ChatClientRequestSpec. Looks at the request options and at the client defaults.
     */
    fun analyzeRequestSpec(specExpression: UExpression?, depth: Int = 0): Limit {
        if (specExpression == null || depth > MAX_DEPTH) return Limit.UNKNOWN
        var result = Limit.NOT_SET
        var current: UExpression? = specExpression.skipParenthesizedExprDown()
        while (true) {
            val call = UastFlow.asCall(current) ?: break
            when (call.methodName) {
                "options" -> result = result or scan(call.valueArguments.firstOrNull(), depth)
                "prompt" -> if (UastFlow.isMethodOf(call, SpringAiNames.CHAT_CLIENT)) {
                    // prompt(Prompt) may carry options; prompt(String) scans to NOT_SET.
                    call.valueArguments.firstOrNull()?.let { result = result or scan(it, depth) }
                    val client = call.receiver ?: return Limit.UNKNOWN // implicit receiver: can't follow
                    return result or scan(client, depth)
                }
            }
            current = call.receiver?.skipParenthesizedExprDown()
        }
        // The chain did not start with prompt(): the spec lives in a variable (or worse).
        return result or requestSpecVariable(current, depth)
    }

    private fun requestSpecVariable(root: UExpression?, depth: Int): Limit {
        val target = UastFlow.resolveReference(root) ?: return Limit.UNKNOWN
        val variable = target.toUElementOfType<UVariable>() ?: return Limit.UNKNOWN
        if (variable is UParameter || !visited.add(target)) return Limit.UNKNOWN
        var result = analyzeRequestSpec(variable.uastInitializer ?: return Limit.UNKNOWN, depth + 1)
        // A request spec is mutable: `spec.options(...)` on its own line counts too.
        for (call in UastFlow.callsOnVariable(target, variable)) {
            if (call.methodName == "options") result = result or scan(call.valueArguments.firstOrNull(), depth + 1)
        }
        return result
    }

    /**
     * Scans an expression that produces options, a prompt, a client, a client builder or a model,
     * following variables, helper methods and Spring beans.
     */
    private fun scan(expression: UExpression?, depth: Int): Limit {
        if (expression == null) return Limit.NOT_SET
        if (depth > MAX_DEPTH) return Limit.UNKNOWN
        var result = Limit.NOT_SET
        expression.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                if (node.methodName in SpringAiNames.OUTPUT_LIMIT_SETTERS) {
                    result = Limit.SET
                    return true
                }
                val method = node.resolve()
                if (method != null && UastFlow.isProjectSource(project, method) && isCarrier(node.returnType)) {
                    result = result or methodResult(method, depth + 1)
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
        if (target == null) return Limit.NOT_SET
        val variable = target.toUElementOfType<UVariable>() ?: return Limit.NOT_SET // a class, a method, ...
        if (!visited.add(target)) return Limit.NOT_SET
        if (variable is UParameter) return byType(variable.type, depth)

        val initializer = variable.uastInitializer
        val assigned = UastFlow.assignedValues(target, variable)
        if (initializer == null && assigned.isEmpty()) {
            // Injected field (@Autowired, lateinit, ...): judge by what Spring would inject.
            return byType(variable.type, depth)
        }
        return (listOfNotNull(initializer) + assigned)
            .fold(Limit.NOT_SET) { acc, value -> acc or scan(value, depth) }
    }

    private fun byType(type: PsiType, depth: Int): Limit = when {
        type is PsiPrimitiveType -> Limit.NOT_SET
        // Spring Boot auto-configures these from spring.ai.* properties unless the project defines its own bean.
        InheritanceUtil.isInheritor(type, SpringAiNames.CHAT_MODEL) ->
            beansOf(SpringAiNames.CHAT_MODEL, depth) ?: Limit.NOT_SET
        InheritanceUtil.isInheritor(type, SpringAiNames.CHAT_CLIENT_BUILDER) ->
            beansOf(SpringAiNames.CHAT_CLIENT_BUILDER, depth) ?: Limit.NOT_SET
        // Spring AI does not auto-configure a ChatClient: a bean we can't see means we can't tell.
        InheritanceUtil.isInheritor(type, SpringAiNames.CHAT_CLIENT) ->
            beansOf(SpringAiNames.CHAT_CLIENT, depth) ?: Limit.UNKNOWN
        // Options or prompts handed in from outside: can't tell.
        isOptionsOrPrompt(type) -> Limit.UNKNOWN
        else -> Limit.NOT_SET
    }

    /** OR of all project `@Bean` methods returning [baseClass], or null when there is none. */
    private fun beansOf(baseClass: String, depth: Int): Limit? {
        val beans = beanMethods(project).filter { InheritanceUtil.isInheritor(it.returnType, baseClass) }
        if (beans.isEmpty()) return null
        return beans.fold(Limit.NOT_SET) { acc, bean -> acc or methodResult(bean, depth + 1) }
    }

    private fun methodResult(method: PsiMethod, depth: Int): Limit {
        if (!visited.add(method)) return Limit.NOT_SET
        val returned = UastFlow.returnedExpressions(method)
        if (returned.isNullOrEmpty()) return Limit.UNKNOWN
        return returned.fold(Limit.NOT_SET) { acc, value -> acc or scan(value, depth) }
    }

    private fun isCarrier(type: PsiType?): Boolean = type != null && (
        isOptionsOrPrompt(type) ||
            InheritanceUtil.isInheritor(type, SpringAiNames.CHAT_CLIENT) ||
            InheritanceUtil.isInheritor(type, SpringAiNames.CHAT_CLIENT_BUILDER) ||
            InheritanceUtil.isInheritor(type, SpringAiNames.CHAT_MODEL)
        )

    private fun isOptionsOrPrompt(type: PsiType): Boolean =
        InheritanceUtil.isInheritor(type, SpringAiNames.CHAT_OPTIONS) ||
            InheritanceUtil.isInheritor(type, SpringAiNames.CHAT_OPTIONS_BUILDER) ||
            InheritanceUtil.isInheritor(type, SpringAiNames.PROMPT)

    companion object {
        private const val MAX_DEPTH = 8

        /** All `@Bean` methods in project sources, cached until the next PSI change. */
        fun beanMethods(project: Project): List<PsiMethod> =
            CachedValuesManager.getManager(project).getCachedValue(project) {
                val beanAnnotation = JavaPsiFacade.getInstance(project)
                    .findClass(SpringAiNames.SPRING_BEAN, GlobalSearchScope.allScope(project))
                val methods = if (beanAnnotation == null) emptyList()
                else AnnotatedElementsSearch.searchPsiMethods(beanAnnotation, GlobalSearchScope.projectScope(project)).findAll().toList()
                CachedValueProvider.Result.create(methods, PsiModificationTracker.MODIFICATION_COUNT)
            }
    }
}
