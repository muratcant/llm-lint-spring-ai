package dev.llmlint.springai.inspections

import com.intellij.psi.PsiMethod
import dev.llmlint.springai.SpringAiNames
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.toUElementOfType

/**
 * Which project `@Bean` methods Spring could inject at an injection point, following the order of
 * `DefaultListableBeanFactory` (Spring Framework 7.1): `@Qualifier` filters the candidates, then a single
 * `@Primary` candidate wins, then the bean whose name matches the parameter or field name.
 * `@Priority`, `@Fallback` and default-candidate rules are not modelled: the remaining candidates are returned.
 */
object SpringBeans {

    /** The candidates left after Spring's rules; one element when Spring's choice is clear. */
    fun narrow(candidates: List<PsiMethod>, injectionPoint: UVariable?): List<PsiMethod> {
        val beans = candidates.mapNotNull { it.toUElementOfType<UMethod>() }
        var left = beans
        injectionPoint?.let(::qualifier)?.let { wanted ->
            left = left.filter { wanted in names(it) || qualifier(it) == wanted }
        }
        if (left.size > 1) {
            val primary = left.filter { it.findAnnotation(SpringAiNames.SPRING_PRIMARY) != null }
            if (primary.isNotEmpty()) return primary.map { it.javaPsi }
            val name = injectionPoint?.name
            left.firstOrNull { name != null && name in names(it) }?.let { return listOf(it.javaPsi) }
        }
        return left.map { it.javaPsi }
    }

    /** Bean names: `@Bean(name = ...)` / `@Bean("...")` (first name and aliases), else the method name. */
    private fun names(bean: UMethod): List<String> {
        val annotation = bean.findAnnotation(SpringAiNames.SPRING_BEAN)
        val declared = listOf("name", "value").flatMap { strings(annotation?.findDeclaredAttributeValue(it)) }
        return declared.ifEmpty { listOf(bean.name) }
    }

    private fun qualifier(element: UAnnotated): String? =
        strings(element.findAnnotation(SpringAiNames.SPRING_QUALIFIER)?.findDeclaredAttributeValue("value"))
            .firstOrNull()

    /** A string or an array of strings in an annotation attribute (Java or Kotlin syntax). */
    private fun strings(value: UExpression?): List<String> = when (value) {
        null -> emptyList()
        is UCallExpression -> value.valueArguments.flatMap(::strings) // {"a", "b"} / ["a", "b"] / arrayOf(...)
        else -> listOfNotNull((value.evaluate() as? String)?.takeIf { it.isNotEmpty() })
    }
}
