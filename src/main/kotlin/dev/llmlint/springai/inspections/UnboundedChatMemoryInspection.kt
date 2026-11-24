package dev.llmlint.springai.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.options.OptPane
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.uast.UastHintedVisitorAdapter
import dev.llmlint.springai.LlmLintBundle
import dev.llmlint.springai.SpringAiNames
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.getContainingUFile
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Chat memory that replays (almost) the whole conversation on every call:
 *  - `MessageWindowChatMemory.builder().maxMessages(N)` with a huge constant N (the deprecated 2.0 API);
 *  - `SessionMemoryAdvisor` / `SessionAdvisors` (the 2.1 session API) without `EventFilter.lastN(...)` or
 *    compaction. Unlike the old window memory, session memory sends the whole active history by default.
 */
class UnboundedChatMemoryInspection : LocalInspectionTool() {

    @JvmField
    var maxMessagesThreshold: Int = DEFAULT_MAX_MESSAGES_THRESHOLD

    override fun getOptionsPane(): OptPane = OptPane.pane(
        OptPane.number(
            "maxMessagesThreshold",
            LlmLintBundle.message("inspection.unbounded.memory.option.threshold"),
            1,
            1_000_000,
        ),
    )

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    when (node.methodName) {
                        "maxMessages" -> checkMessageWindow(node, holder)
                        "build" -> checkSessionMemory(node, holder)
                    }
                    return true
                }
            },
            arrayOf(UCallExpression::class.java),
        )

    private fun checkMessageWindow(node: UCallExpression, holder: ProblemsHolder) {
        if (!UastFlow.isMethodOf(node, SpringAiNames.MESSAGE_WINDOW_CHAT_MEMORY_BUILDER)) return
        val argument = node.valueArguments.firstOrNull() ?: return
        val size = (argument.evaluate() as? Number)?.toLong() ?: return // not a constant: can't tell
        if (size < maxMessagesThreshold) return
        val anchor = argument.sourcePsi ?: return
        holder.registerProblem(anchor, LlmLintBundle.message("inspection.unbounded.memory.window.message", size.toString()))
    }

    private fun checkSessionMemory(node: UCallExpression, holder: ProblemsHolder) {
        val builderClass = node.resolve()?.containingClass?.qualifiedName ?: return
        val isSessionAdvisors = when (builderClass) {
            SpringAiNames.SESSION_ADVISORS_BUILDER -> true
            SpringAiNames.SESSION_MEMORY_ADVISOR_BUILDER -> false
            else -> return
        }
        val factoryClass = if (isSessionAdvisors) SpringAiNames.SESSION_ADVISORS else SpringAiNames.SESSION_MEMORY_ADVISOR
        val chain = BuilderChain.collect(node.receiver, factoryClass) ?: return // can't see the whole builder

        val search = windowSearch(holder.project)
        var window = Limit.NOT_SET
        for (call in chain.calls) {
            val argument = call.valueArguments.firstOrNull()
            when {
                isSessionAdvisors && call.methodName == "compaction" -> window = Limit.SET
                isSessionAdvisors && call.methodName == "memory" -> window = window or search.scan(argument)
                !isSessionAdvisors && call.methodName == "eventFilter" -> window = window or search.scan(argument)
            }
        }
        if (window != Limit.NOT_SET) return
        if (usesPerRequestEventFilter(holder.project)) return
        // A standalone SessionMemoryAdvisor is usually paired with a SessionTurnAdvisor that compacts.
        if (!isSessionAdvisors && fileCompactsSameService(node, chain.factoryCall)) return

        val anchor = chain.factoryCall.methodIdentifier?.sourcePsi ?: node.methodIdentifier?.sourcePsi ?: return
        holder.registerProblem(anchor, LlmLintBundle.message("inspection.unbounded.memory.session.message"))
    }

    /**
     * True when the file has a compacting `SessionTurnAdvisor` for the same SessionService as [memoryFactory]
     * (`SessionMemoryAdvisor.builder(service)`). Compaction rewrites that service's history, which bounds what the
     * memory advisor replays. When either service is not a plain reference, it is assumed to be the same one.
     */
    private fun fileCompactsSameService(node: UCallExpression, memoryFactory: UCallExpression): Boolean {
        val memoryService = UastFlow.resolveReference(memoryFactory.valueArguments.firstOrNull())
        var found = false
        node.getContainingUFile()?.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                if (node.methodName == "compactionTrigger" &&
                    UastFlow.isMethodOf(node, SpringAiNames.SESSION_TURN_ADVISOR_BUILDER)
                ) {
                    val turnFactory = BuilderChain.collect(node.receiver, SpringAiNames.SESSION_TURN_ADVISOR)?.factoryCall
                    val turnService = UastFlow.resolveReference(turnFactory?.valueArguments?.firstOrNull())
                    if (memoryService == null || turnService == null || memoryService == turnService) found = true
                }
                return found
            }
        })
        return found
    }

    /** Builder calls of one `X.builder(...)...build()` expression, also when the builder sits in a local. */
    private class BuilderChain(val factoryCall: UCallExpression, val calls: List<UCallExpression>) {
        companion object {
            private val SCOPE_FUNCTIONS = setOf("apply", "also", "run", "let", "with")

            fun collect(start: UExpression?, factoryClass: String, depth: Int = 0): BuilderChain? {
                val calls = mutableListOf<UCallExpression>()
                var current = start
                while (true) {
                    val call = UastFlow.asCall(current) ?: break
                    if (call.methodName == "builder" && UastFlow.isMethodOf(call, factoryClass)) {
                        return BuilderChain(call, calls)
                    }
                    calls += call
                    if (call.methodName in SCOPE_FUNCTIONS) calls += callsInLambdas(call)
                    current = call.receiver
                }
                // `val b = X.builder(..); b.foo(..); b.build()`
                if (depth > 2) return null
                val target = UastFlow.resolveReference(current) ?: return null
                val local = target.toUElementOfType<ULocalVariable>() ?: return null
                val inner = collect(local.uastInitializer, factoryClass, depth + 1) ?: return null
                return BuilderChain(inner.factoryCall, calls + inner.calls + UastFlow.callsOnVariable(target, local))
            }

            /** Kotlin `builder.apply { compaction(..) }`: the calls inside the block count as builder calls. */
            private fun callsInLambdas(call: UCallExpression): List<UCallExpression> {
                val found = mutableListOf<UCallExpression>()
                call.valueArguments.filterIsInstance<ULambdaExpression>().forEach { lambda ->
                    lambda.body.accept(object : AbstractUastVisitor() {
                        override fun visitCallExpression(node: UCallExpression): Boolean {
                            found += node
                            return false
                        }
                    })
                }
                return found
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_MESSAGES_THRESHOLD = 200

        private val EVENT_FILTER_KEY_USED = Key.create<CachedValue<Boolean>>("llmlint.springai.eventFilterContextKeyUsed")

        /** Looks for `EventFilter.lastN(...)` / `EventFilter.builder().lastN(...)` in an expression. */
        private fun windowSearch(project: Project) = CallSearch(
            project,
            names = setOf("lastN"),
            opaqueTypes = listOf(SpringAiNames.EVENT_FILTER, SpringAiNames.EVENT_FILTER_BUILDER, "java.util.function.Consumer"),
            owners = setOf(SpringAiNames.EVENT_FILTER, SpringAiNames.EVENT_FILTER_BUILDER),
        )

        /**
         * True when the project passes a windowed (or unknown) EventFilter per request, so a builder default may be
         * overridden. A per-request `EventFilter.all()` does not count.
         */
        fun usesPerRequestEventFilter(project: Project): Boolean =
            CachedValuesManager.getManager(project).getCachedValue(project, EVENT_FILTER_KEY_USED, {
                val advisor = JavaPsiFacade.getInstance(project)
                    .findClass(SpringAiNames.SESSION_MEMORY_ADVISOR, GlobalSearchScope.allScope(project))
                val field = advisor?.findFieldByName(SpringAiNames.EVENT_FILTER_CONTEXT_KEY, false)
                val used = field != null &&
                    ReferencesSearch.search(field, GlobalSearchScope.projectScope(project)).findAll()
                        .any { windowedValueFor(project, it.element) }
                CachedValueProvider.Result.create(used, PsiModificationTracker.MODIFICATION_COUNT)
            }, false)

        /**
         * The value passed with the context key [keyReference]: the next argument (`Map.of(KEY, filter)`,
         * `a.param(KEY, filter)`) or the right side of Kotlin's `KEY to filter`. True when it is windowed or can't be
         * read; false only for a value that is visibly not windowed.
         */
        private fun windowedValueFor(project: Project, keyReference: PsiElement): Boolean {
            var child: UElement = keyReference.toUElement() ?: return true
            var parent = child.uastParent
            while (parent is UQualifiedReferenceExpression || parent is UParenthesizedExpression) {
                child = parent
                parent = parent.uastParent
            }
            val value = when (parent) {
                is UCallExpression -> {
                    val arguments = parent.valueArguments
                    val index = arguments.indexOfFirst { it.sourcePsi == child.sourcePsi }
                    if (index < 0) return true
                    arguments.getOrNull(index + 1)
                }
                is UBinaryExpression -> parent.rightOperand.takeIf { parent.leftOperand.sourcePsi == child.sourcePsi }
                else -> null
            } ?: return true
            return windowSearch(project).scan(value) != Limit.NOT_SET
        }
    }
}
