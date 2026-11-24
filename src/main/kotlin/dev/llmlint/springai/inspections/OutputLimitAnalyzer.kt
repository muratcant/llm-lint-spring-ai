package dev.llmlint.springai.inspections

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
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
import org.jetbrains.uast.UDeclarationsExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UExpressionList
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.USwitchClauseExpressionWithBody
import org.jetbrains.uast.USwitchExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.skipParenthesizedExprDown
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Finds out, from the code alone, whether a ChatClient request ends up with an output token limit.
 * One instance per analysed call.
 *
 * Results are [Coverage] values. Alternatives (branches, several returns or assignments) are combined with
 * [Coverage.join], settings that apply together (request options over the client over the model) with
 * [Coverage.layer]. Internally `null` means "no information" (a reference cycle): it is neutral for both.
 */
class OutputLimitAnalyzer(private val project: Project) {

    /** Results per (element, [skipClientDefaults]), so an element reached twice gives the same answer. */
    private val memo = HashMap<Pair<PsiElement, Boolean>, Coverage?>()
    private val inProgress = HashSet<Pair<PsiElement, Boolean>>()

    /** Incremented on every cycle; results computed while it changed are partial and not memoized. */
    private var cycles = 0

    /**
     * Concrete ChatModel classes the client is built from, when the code shows them (a parameter of type
     * AnthropicChatModel, `OllamaChatModel.builder()...build()`, ...). Empty when only the ChatModel interface
     * or an auto-configured builder is visible.
     */
    val modelClasses: MutableSet<PsiClass> = LinkedHashSet()

    /**
     * True when the request's `.options(...)` replaced a client `defaultOptions(...)` that had a limit,
     * which is why [analyzeRequestSpec] found none.
     */
    var clientDefaultsReplaced: Boolean = false
        private set

    /** While true, [scan] ignores `ChatClient.Builder.defaultOptions(...)`: request options replaced them. */
    private var skipClientDefaults = false

    /**
     * [specExpression] is the receiver of `.call()` / `.stream()`, i.e. an expression of type
     * ChatClientRequestSpec. Looks at the request options and at the client defaults.
     *
     * Spring AI 2.0 and 2.1: `.options(x)` on the request replaces the request's options customizer, which
     * starts as the client's `defaultOptions` (with any `prompt(Prompt)` options combined in). Only the last
     * `.options(...)` applies, and it drops both. The model's own options stay underneath.
     */
    fun analyzeRequestSpec(specExpression: UExpression?): Coverage = requestSpec(specExpression, 0) ?: Coverage.UNKNOWN

    private fun requestSpec(specExpression: UExpression?, depth: Int): Coverage? {
        if (specExpression == null || depth > MAX_DEPTH) return Coverage.UNKNOWN
        var result: Coverage? = Coverage.UNSAFE
        var optionsSeen = false
        var current: UExpression? = specExpression.skipParenthesizedExprDown()
        while (true) {
            val call = UastFlow.asCall(current) ?: break
            when (call.methodName) {
                // Walking from .call() backwards, the first .options(...) met is the last one applied.
                "options" -> if (!optionsSeen) {
                    optionsSeen = true
                    result = scan(call.valueArguments.firstOrNull(), depth)
                }
                "prompt" -> if (UastFlow.isMethodOf(call, SpringAiNames.CHAT_CLIENT)) {
                    // prompt(Prompt) may carry options; prompt(String) scans to UNSAFE.
                    if (!optionsSeen) call.valueArguments.firstOrNull()?.let { result = layer(result, scan(it, depth)) }
                    val client = call.receiver ?: return Coverage.UNKNOWN // implicit receiver: can't follow
                    val clientCoverage = if (optionsSeen) clientWithoutDefaults(client, depth) else scan(client, depth)
                    return layer(result, clientCoverage ?: Coverage.UNKNOWN)
                }
            }
            current = call.receiver?.skipParenthesizedExprDown()
        }
        // The chain did not start with prompt(): the spec lives in a variable (or worse). Its earlier
        // options are not tracked in order, so keep counting them (no new warnings from what we can't see).
        return layer(result, requestSpecVariable(current, depth) ?: Coverage.UNKNOWN)
    }

    /** The client's coverage when request options replaced its defaultOptions: only the model layer is left. */
    private fun clientWithoutDefaults(client: UExpression, depth: Int): Coverage? {
        skipClientDefaults = true
        val result = try {
            scan(client, depth)
        } finally {
            skipClientDefaults = false
        }
        if (result == Coverage.UNSAFE && OutputLimitAnalyzer(project).scan(client, depth) == Coverage.SAFE) {
            clientDefaultsReplaced = true
        }
        return result
    }

    private fun requestSpecVariable(root: UExpression?, depth: Int): Coverage? {
        val target = UastFlow.resolveReference(root) ?: return Coverage.UNKNOWN
        val variable = target.toUElementOfType<UVariable>() ?: return Coverage.UNKNOWN
        if (variable is UParameter) return Coverage.UNKNOWN
        val anchor = root?.sourcePsi ?: return Coverage.UNKNOWN
        return memoized(target) {
            var result = requestSpec(variable.uastInitializer ?: return@memoized Coverage.UNKNOWN, depth + 1)
            // A request spec is mutable: `spec.options(...)` on its own line counts too, if it runs before the call.
            val optionCalls = UastFlow.callsOnVariable(target, variable)
                .filter { it.methodName == "options" }
                .sortedBy { it.sourcePsi?.textRange?.startOffset ?: 0 }
            for (call in optionCalls) {
                val options = scan(call.valueArguments.firstOrNull(), depth + 1)
                result = when (UastFlow.placement(call, anchor)) {
                    UastFlow.Placement.BEFORE -> layer(result, options)
                    UastFlow.Placement.CONDITIONAL -> join(result, layer(result, options))
                    UastFlow.Placement.AFTER -> result
                }
            }
            result
        }
    }

    /**
     * Scans an expression that produces options, a prompt, a client, a client builder or a model,
     * following variables, helper methods and Spring beans. Everything found in one expression applies
     * together (layers), except the branches of `if` / `?:` / `when`, which are alternatives.
     */
    private fun scan(expression: UExpression?, depth: Int): Coverage? {
        if (expression == null) return Coverage.UNSAFE
        if (depth > MAX_DEPTH) return Coverage.UNKNOWN
        ProgressManager.checkCanceled()
        var result: Coverage? = null
        var contributed = false
        fun add(value: Coverage?) {
            contributed = true
            result = layer(result, value)
        }
        expression.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                val method = node.resolve()
                if (node.methodName in SpringAiNames.OUTPUT_LIMIT_SETTERS) {
                    when (isOptionsSetter(method)) {
                        true -> {
                            add(Coverage.SAFE)
                            return true
                        }
                        null -> add(Coverage.UNKNOWN) // unresolved: can't tell
                        false -> Unit // e.g. the app's own maxTokens() getter: not a limit
                    }
                }
                if (skipClientDefaults && node.methodName == "defaultOptions" &&
                    method?.containingClass?.qualifiedName == SpringAiNames.CHAT_CLIENT_BUILDER
                ) {
                    return true // replaced by the request's options: its arguments don't count
                }
                recordModel(node.returnType)
                if (method != null && UastFlow.isProjectSource(project, method) && isCarrier(node.returnType)) {
                    add(methodResult(method, depth + 1))
                }
                return false
            }

            override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                add(variable(node.resolve(), depth + 1))
                return false
            }

            // `c ? a : b`, `if (c) a else b`: the condition does not matter, the branches are alternatives.
            override fun visitIfExpression(node: UIfExpression): Boolean {
                add(join(scan(node.thenExpression, depth), scan(node.elseExpression, depth)))
                return true
            }

            // Kotlin `when`: each branch is an alternative.
            override fun visitSwitchExpression(node: USwitchExpression): Boolean {
                val branches = node.body.expressions.filterIsInstance<USwitchClauseExpressionWithBody>()
                if (branches.isEmpty()) return false
                add(branches.map { scan(it.body, depth) }.reduce(::join))
                return true
            }

            // Kotlin `a ?: b`: a temporary variable holding `a`, then `if (tmp != null) tmp else b`.
            override fun visitExpressionList(node: UExpressionList): Boolean {
                if (node.kind.name != ELVIS_KIND) return false
                val tmp = (node.expressions.firstOrNull() as? UDeclarationsExpression)?.declarations?.firstOrNull() as? UVariable
                val left = tmp?.uastInitializer ?: return false
                val right = (node.expressions.lastOrNull() as? UIfExpression)?.elseExpression ?: return false
                add(join(scan(left, depth), scan(right, depth)))
                return true
            }
        })
        // Nothing found means nothing set; only cycles found means no information.
        return if (contributed) result else Coverage.UNSAFE
    }

    private fun variable(target: PsiElement?, depth: Int): Coverage? {
        if (target == null) return Coverage.UNSAFE
        val variable = target.toUElementOfType<UVariable>() ?: return Coverage.UNSAFE // a class, a method, ...
        return memoized(target) {
            if (variable is UParameter) return@memoized byType(variable.type, depth, variable)
            val values = listOfNotNull(variable.uastInitializer) + UastFlow.assignedValues(target, variable)
            // Injected field (@Autowired, lateinit, ...): judge by what Spring would inject.
            if (values.isEmpty()) return@memoized byType(variable.type, depth, variable)
            // Each assignment is an alternative (if/else branches, several constructors).
            values.map { scan(it, depth) }.reduce(::join)
        }
    }

    private fun recordModel(type: PsiType?) {
        val modelClass = (type as? PsiClassType)?.resolve() ?: return
        if (modelClass.qualifiedName != SpringAiNames.CHAT_MODEL &&
            InheritanceUtil.isInheritor(modelClass, SpringAiNames.CHAT_MODEL)
        ) {
            modelClasses += modelClass
        }
    }

    /** What Spring would inject at [injectionPoint] (a parameter or an injected field) of type [type]. */
    private fun byType(type: PsiType, depth: Int, injectionPoint: UVariable): Coverage? = when {
        type is PsiPrimitiveType -> Coverage.UNSAFE
        // Spring Boot auto-configures these from spring.ai.* properties unless the project defines its own bean.
        InheritanceUtil.isInheritor(type, SpringAiNames.CHAT_MODEL) -> {
            recordModel(type)
            beansOf(SpringAiNames.CHAT_MODEL, depth, injectionPoint) ?: Coverage.UNSAFE
        }
        InheritanceUtil.isInheritor(type, SpringAiNames.CHAT_CLIENT_BUILDER) ->
            beansOf(SpringAiNames.CHAT_CLIENT_BUILDER, depth, injectionPoint) ?: Coverage.UNSAFE
        // Spring AI does not auto-configure a ChatClient: a bean we can't see means we can't tell.
        InheritanceUtil.isInheritor(type, SpringAiNames.CHAT_CLIENT) ->
            beansOf(SpringAiNames.CHAT_CLIENT, depth, injectionPoint) ?: Coverage.UNKNOWN
        // Options or prompts handed in from outside: can't tell.
        isOptionsOrPrompt(type) -> Coverage.UNKNOWN
        else -> Coverage.UNSAFE
    }

    /**
     * Coverage of the project `@Bean` method returning [baseClass] that Spring would inject at [injectionPoint],
     * or null when the project defines none. When Spring's choice can't be narrowed to one bean
     * ([SpringBeans.narrow]) and the candidates disagree, the answer is UNKNOWN.
     */
    private fun beansOf(baseClass: String, depth: Int, injectionPoint: UVariable): Coverage? {
        val beans = beanMethods(project).filter { InheritanceUtil.isInheritor(it.returnType, baseClass) }
        if (beans.isEmpty()) return null
        val candidates = SpringBeans.narrow(beans, injectionPoint)
        return candidates.mapNotNull { methodResult(it, depth + 1) }.distinct().singleOrNull() ?: Coverage.UNKNOWN
    }

    private fun methodResult(method: PsiMethod, depth: Int): Coverage? = memoized(method) {
        val returned = UastFlow.returnedExpressions(method)
        if (returned.isNullOrEmpty()) Coverage.UNKNOWN
        else returned.map { scan(it, depth) }.reduce(::join) // several returns are alternatives
    }

    /** Computes [compute] once per element; an element met again while being computed is a cycle (null). */
    private inline fun memoized(element: PsiElement, compute: () -> Coverage?): Coverage? {
        val key = element to skipClientDefaults
        if (key in inProgress) {
            cycles++
            return null
        }
        if (key in memo) return memo[key]
        val cyclesBefore = cycles
        inProgress += key
        val result = try {
            compute()
        } finally {
            inProgress -= key
        }
        if (cycles == cyclesBefore) memo[key] = result
        return result
    }

    /**
     * True for a Spring AI options builder setter (ChatOptions.Builder or a provider builder such as
     * OpenAiChatOptions.Builder), false for any other method of that name, null when it doesn't resolve.
     */
    private fun isOptionsSetter(method: PsiMethod?): Boolean? {
        val owner = method?.containingClass ?: return null
        val name = owner.qualifiedName ?: return false
        return name == SpringAiNames.CHAT_OPTIONS_BUILDER ||
            name.startsWith(SpringAiNames.SPRING_AI_PACKAGE_PREFIX) ||
            InheritanceUtil.isInheritor(owner, SpringAiNames.CHAT_OPTIONS_BUILDER)
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

        /** Name of the UAST expression-list kind Kotlin uses for `a ?: b`. */
        private const val ELVIS_KIND = "elvis"

        /** Alternatives, with null (no information) as the neutral element. */
        private fun join(a: Coverage?, b: Coverage?): Coverage? = if (a == null) b else if (b == null) a else a join b

        /** Layers that apply together, with null (no information) as the neutral element. */
        private fun layer(a: Coverage?, b: Coverage?): Coverage? = if (a == null) b else if (b == null) a else a layer b

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
