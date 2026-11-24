package dev.llmlint.springai.config

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiModificationTracker
import java.io.InputStreamReader

/** One row of provider-defaults.json. */
data class ProviderDefault(
    val provider: String,
    val chatModelClass: String,
    /** Output tokens the provider caps an answer at when nothing is configured; null = no limit or not documented. */
    val defaultMaxTokens: Int?,
    val property: String?,
    val source: String?,
    val verifiedFor: String?,
)

/** What the providers of a call say about the output limit when code and config set none. */
sealed interface ProviderVerdict {
    /** No known provider: behave as without this data. */
    data object NoKnownProvider : ProviderVerdict

    /** Every candidate provider applies a default limit. */
    data class AllDefaulted(val providers: List<ProviderDefault>) : ProviderVerdict

    /** Every candidate provider runs without a limit. */
    data object NoDefault : ProviderVerdict

    /** Some candidates have a default, some don't, and we can't tell which one the call uses. */
    data object Mixed : ProviderVerdict
}

/**
 * Provider default output limits, read from `provider-defaults.json` (data, not code, so values can be
 * updated without touching the inspections).
 */
object ProviderDefaults {

    private const val RESOURCE = "/provider-defaults.json"

    private val PRESENT_KEY = Key.create<CachedValue<List<ProviderDefault>>>("llmlint.springai.presentProviders")

    val all: List<ProviderDefault> by lazy { load() }

    /** Providers whose ChatModel class is on the classpath of [context]'s module (sources, dependencies, libraries). */
    fun presentFor(context: PsiElement): List<ProviderDefault> {
        val project = context.project
        val module = ModuleUtilCore.findModuleForPsiElement(context)
        val holder = module ?: project
        return CachedValuesManager.getManager(project).getCachedValue(holder, PRESENT_KEY, {
            val scope = module?.getModuleWithDependenciesAndLibrariesScope(false) ?: GlobalSearchScope.allScope(project)
            val facade = JavaPsiFacade.getInstance(project)
            val present = all.filter { facade.findClass(it.chatModelClass, scope) != null }
            CachedValueProvider.Result.create(
                present,
                ProjectRootModificationTracker.getInstance(project),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }, false)
    }

    /** Providers that match concrete ChatModel classes seen in the code (e.g. a parameter of type AnthropicChatModel). */
    fun forModelClasses(modelClasses: Collection<PsiClass>): List<ProviderDefault> =
        all.filter { entry ->
            modelClasses.any { it.qualifiedName == entry.chatModelClass || InheritanceUtil.isInheritor(it, entry.chatModelClass) }
        }

    fun verdict(candidates: List<ProviderDefault>): ProviderVerdict {
        if (candidates.isEmpty()) return ProviderVerdict.NoKnownProvider
        // Several rows can share one model class (OpenAI and Azure OpenAI): judge per class.
        val byClass = candidates.groupBy { it.chatModelClass }.values
        val defaulted = byClass.filter { rows -> rows.all { it.defaultMaxTokens != null } }
        return when (defaulted.size) {
            byClass.size -> ProviderVerdict.AllDefaulted(defaulted.flatten())
            0 -> ProviderVerdict.NoDefault
            else -> ProviderVerdict.Mixed
        }
    }

    private fun load(): List<ProviderDefault> {
        val stream = ProviderDefaults::class.java.getResourceAsStream(RESOURCE) ?: return emptyList()
        val root = InputStreamReader(stream, Charsets.UTF_8).use { JsonParser.parseReader(it) }.asJsonObject
        return root.getAsJsonArray("providers").map { element ->
            val row = element.asJsonObject
            ProviderDefault(
                provider = row.string("provider") ?: "",
                chatModelClass = row.string("chatModelClass") ?: "",
                defaultMaxTokens = row.nullable("defaultMaxTokens")?.asInt,
                property = row.string("property"),
                source = row.string("source"),
                verifiedFor = row.string("verifiedFor"),
            )
        }.filter { it.chatModelClass.isNotEmpty() }
    }

    private fun JsonObject.nullable(name: String): JsonElement? = get(name)?.takeUnless { it.isJsonNull }

    private fun JsonObject.string(name: String): String? = nullable(name)?.asString
}
