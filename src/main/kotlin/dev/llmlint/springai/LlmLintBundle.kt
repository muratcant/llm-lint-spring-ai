package dev.llmlint.springai

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.LlmLintBundle"

object LlmLintBundle : DynamicBundle(LlmLintBundle::class.java, BUNDLE) {
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
