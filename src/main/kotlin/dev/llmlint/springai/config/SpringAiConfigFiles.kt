package dev.llmlint.springai.config

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.llmlint.springai.inspections.Coverage
import java.io.StringReader
import java.util.Properties

/**
 * Reads Spring Boot configuration files (application*.yml / .yaml / .properties) visible
 * from a module, without depending on the YAML plugin.
 */
object SpringAiConfigFiles {

    /** One output-limit setting found in a configuration file. */
    internal data class ConfigLimit(
        /** Normalized key up to `.chat`, e.g. `spring.ai.openai.chat`: tells which provider it configures. */
        val providerPrefix: String,
        /** SAFE for a positive limit, UNSAFE for 0 / -1, UNKNOWN for a placeholder without default. */
        val value: Coverage,
        /** In application-<profile>.* or a document with spring.config.activate.on-profile. */
        val profileSpecific: Boolean,
        val inTestSources: Boolean,
    )

    private val CACHE_KEY = Key.create<CachedValue<List<ConfigLimit>>>("llmlint.springai.outputLimitSettings")

    private val CONFIG_FILE_NAME = Regex("""^(application|bootstrap)(-[^.]+)?\.(ya?ml|properties)$""")

    /**
     * spring.ai.<provider>.chat.options.max-tokens and friends, after Spring's relaxed binding
     * normalisation (lower case, no '-' or '_'). The provider may span several segments
     * (e.g. google.genai), and the documented shortcut without ".options" is accepted too.
     */
    private val OUTPUT_LIMIT_KEY =
        Regex("""^(spring\.ai\.[a-z0-9.]+\.chat)\.(options\.)?(maxtokens|maxcompletiontokens|maxoutputtokens|numpredict)$""")

    /** Keys that make a whole document apply only under some condition (a profile, a cloud platform). */
    private val CONDITIONAL_DOCUMENT_KEYS =
        setOf("spring.config.activate.onprofile", "spring.config.activate.oncloudplatform", "spring.profiles")

    /** `${NAME}` or `${NAME:default}`; the default may itself be a placeholder. */
    private val PLACEHOLDER = Regex("^\\$\\{[^:}]*(?::(.*))?\\}$")

    /**
     * Whether configuration visible from [context]'s module sets an output limit for the call, which may run on
     * any of [providers] (empty when the provider is not known).
     *
     * - A setting counts only for its own provider. Settings of providers not in provider-defaults.json may
     *   apply to the call, so they make the answer UNKNOWN rather than being ignored.
     * - A profile-specific setting may or may not be active (`spring.profiles.active` is usually set at runtime),
     *   so when it differs from the base configuration the answer is UNKNOWN.
     * - Production code ignores configuration under test roots.
     */
    fun outputLimit(context: PsiElement, providers: List<ProviderDefault>): Coverage {
        val project = context.project
        val inTest = context.containingFile?.virtualFile
            ?.let { ProjectFileIndex.getInstance(project).isInTestSourceContent(it) } ?: false
        val visible = settings(context).filter { inTest || !it.inTestSources }
        return resolve(visible, providers.mapNotNull { configPrefix(it) }.distinct())
    }

    internal fun resolve(settings: List<ConfigLimit>, candidatePrefixes: List<String>): Coverage {
        val byPrefix = settings.groupBy { it.providerPrefix }
        if (candidatePrefixes.isEmpty()) {
            // Provider unknown: a setting of any provider may apply, one with a limit is enough.
            return byPrefix.values.map(::resolveOne).fold(Coverage.UNSAFE) { acc, value -> acc layer value }
        }
        // The call may run on any candidate: each of them must be covered.
        val known = candidatePrefixes.map { resolveOne(byPrefix[it].orEmpty()) }.distinct().singleOrNull() ?: Coverage.UNKNOWN
        if (known == Coverage.SAFE) return known
        val knownPrefixes = ProviderDefaults.all.mapNotNull(::configPrefix).toSet()
        val unmapped = byPrefix.filterKeys { it !in knownPrefixes }.values.map(::resolveOne)
        return if (unmapped.any { it != Coverage.UNSAFE }) Coverage.UNKNOWN else known
    }

    /** Settings of one provider: the base value, unless a profile-specific value may replace it. */
    private fun resolveOne(settings: List<ConfigLimit>): Coverage {
        val base = settings.filterNot { it.profileSpecific }.map { it.value }.distinct()
        val baseValue = when (base.size) {
            0 -> Coverage.UNSAFE
            1 -> base.single()
            else -> Coverage.UNKNOWN // several files disagree: precedence not modelled
        }
        val alternatives = (listOf(baseValue) + settings.filter { it.profileSpecific }.map { it.value }).distinct()
        return alternatives.singleOrNull() ?: Coverage.UNKNOWN
    }

    /** `spring.ai.openai.chat` for the provider whose limit property is `spring.ai.openai.chat.max-tokens`. */
    fun configPrefix(provider: ProviderDefault): String? =
        provider.property?.let { OUTPUT_LIMIT_KEY.matchEntire(normalize(it))?.groupValues?.get(1) }

    private fun settings(context: PsiElement): List<ConfigLimit> {
        val project = context.project
        val module = ModuleUtilCore.findModuleForPsiElement(context)
        val holder = module ?: project
        return CachedValuesManager.getManager(project).getCachedValue(holder, CACHE_KEY, {
            CachedValueProvider.Result.create(
                compute(project, module),
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }, false)
    }

    private fun compute(project: Project, module: Module?): List<ConfigLimit> {
        val scope = module?.let { GlobalSearchScope.moduleWithDependenciesScope(it) }
            ?: GlobalSearchScope.projectScope(project)
        val index = ProjectFileIndex.getInstance(project)
        return configFiles(project, scope).flatMap { file ->
            val text = textOf(file) ?: return@flatMap emptyList()
            settingsIn(file.name, text, index.isInTestSourceContent(file))
        }
    }

    /** Output-limit settings in one configuration file. */
    internal fun settingsIn(fileName: String, text: String, inTestSources: Boolean): List<ConfigLimit> {
        val profileFile = CONFIG_FILE_NAME.matchEntire(fileName)?.groups?.get(2) != null
        val documents = if (fileName.endsWith(".properties")) propertiesDocuments(text) else yamlDocuments(text)
        return documents.flatMap { document ->
            val entries = document.map { (key, value) -> normalize(key) to value }
            val conditional = profileFile || entries.any { it.first in CONDITIONAL_DOCUMENT_KEYS }
            entries.mapNotNull { (key, value) ->
                val prefix = OUTPUT_LIMIT_KEY.matchEntire(key)?.groupValues?.get(1) ?: return@mapNotNull null
                val coverage = valueCoverage(value) ?: return@mapNotNull null
                ConfigLimit(prefix, coverage, conditional, inTestSources)
            }
        }
    }

    /** SAFE for a positive number, UNSAFE for 0 or a negative number (Ollama's -1), UNKNOWN when unresolved. */
    internal fun valueCoverage(raw: String): Coverage? {
        var value = raw.trim().trim('"', '\'').trim()
        if (value.isEmpty() || value == "~" || value == "null") return null // no value: not a setting
        while (true) {
            val placeholder = PLACEHOLDER.matchEntire(value) ?: break
            value = placeholder.groups[1]?.value?.trim() ?: return Coverage.UNKNOWN // ${X}: set at runtime
        }
        val number = value.toLongOrNull() ?: return Coverage.UNKNOWN
        return if (number > 0) Coverage.SAFE else Coverage.UNSAFE
    }

    /** Spring's relaxed binding: `maxTokens`, `max-tokens` and `max_tokens` are the same key. */
    private fun normalize(key: String): String = key.lowercase().replace("-", "").replace("_", "")

    private fun configFiles(project: Project, scope: GlobalSearchScope): List<VirtualFile> =
        listOf("yml", "yaml", "properties")
            .flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }
            .filter { CONFIG_FILE_NAME.matches(it.name) }

    private fun textOf(file: VirtualFile): String? =
        FileDocumentManager.getInstance().getCachedDocument(file)?.text
            ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull()

    /** Documents of a .properties file, separated by `#---` or `!---` lines (Spring Boot 2.4+). */
    internal fun propertiesDocuments(text: String): List<List<Pair<String, String>>> {
        val documents = mutableListOf(StringBuilder())
        for (line in text.lines()) {
            if (line.trim() == "#---" || line.trim() == "!---") documents += StringBuilder() else documents.last().appendLine(line)
        }
        return documents.map { document ->
            val props = Properties()
            runCatching { props.load(StringReader(document.toString())) }
            props.stringPropertyNames().map { it to props.getProperty(it) }
        }
    }

    internal fun yamlEntries(text: String): List<Pair<String, String>> = yamlDocuments(text).flatten()

    /**
     * Minimal YAML flattener, one list per `---` document: nested and dotted mapping keys become "a.b.c" -> value.
     * Lists, flow mappings and block scalars are skipped; that is enough for Spring config keys.
     */
    internal fun yamlDocuments(text: String): List<List<Pair<String, String>>> {
        val documents = mutableListOf(mutableListOf<Pair<String, String>>())
        val parents = ArrayDeque<Pair<Int, String>>() // indent -> key
        for (raw in text.lines()) {
            val line = stripComment(raw).trimEnd()
            if (line.isBlank()) continue
            if (line == "---") {
                parents.clear()
                documents += mutableListOf<Pair<String, String>>()
                continue
            }
            val indent = line.indexOfFirst { it != ' ' }
            val content = line.substring(indent)
            if (content.startsWith("-")) continue
            val colon = keyColon(content) ?: continue
            val key = content.substring(0, colon).trim().trim('"', '\'')
            val value = content.substring(colon + 1).trim()
            while (parents.isNotEmpty() && parents.last().first >= indent) parents.removeLast()
            val fullKey = (parents.map { it.second } + key).joinToString(".")
            if (value.isEmpty()) parents.addLast(indent to key) else documents.last() += fullKey to value
        }
        return documents
    }

    private fun keyColon(content: String): Int? {
        val inline = content.indexOf(": ")
        if (inline > 0) return inline
        return if (content.endsWith(":") && content.length > 1) content.length - 1 else null
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        for ((i, c) in line.withIndex()) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '#' && (i == 0 || line[i - 1].isWhitespace()) -> return line.substring(0, i)
            }
        }
        return line
    }
}
