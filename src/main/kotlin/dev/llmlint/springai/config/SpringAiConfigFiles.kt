package dev.llmlint.springai.config

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
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
import java.io.StringReader
import java.util.Properties

/**
 * Reads Spring Boot configuration files (application*.yml / .yaml / .properties) visible
 * from a module, without depending on the YAML plugin.
 */
object SpringAiConfigFiles {

    private val CACHE_KEY = Key.create<CachedValue<Boolean>>("llmlint.springai.outputLimitConfigured")

    private val CONFIG_FILE_NAME = Regex("""^(application|bootstrap)(-[^.]+)?\.(ya?ml|properties)$""")

    /**
     * spring.ai.<provider>.chat.options.max-tokens and friends, after Spring's relaxed binding
     * normalisation (lower case, no '-' or '_'). The provider may span several segments
     * (e.g. azure.openai), and OpenAI also accepts the shortcut without ".options".
     */
    private val OUTPUT_LIMIT_KEY =
        Regex("""^spring\.ai\.[a-z0-9.]+\.chat\.(options\.)?(maxtokens|maxcompletiontokens|maxoutputtokens|numpredict)$""")

    /** True when any config file visible from [context]'s module sets an output token limit. */
    fun isOutputLimitConfigured(context: PsiElement): Boolean {
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

    private fun compute(project: Project, module: Module?): Boolean {
        val scope = module?.let { GlobalSearchScope.moduleWithDependenciesScope(it) }
            ?: GlobalSearchScope.projectScope(project)
        return configFiles(project, scope).any { file -> keysOf(file).any(::isOutputLimit) }
    }

    private fun configFiles(project: Project, scope: GlobalSearchScope): List<VirtualFile> =
        listOf("yml", "yaml", "properties")
            .flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }
            .filter { CONFIG_FILE_NAME.matches(it.name) }

    private fun isOutputLimit(entry: Pair<String, String>): Boolean {
        val (key, value) = entry
        val normalized = key.lowercase().replace("-", "").replace("_", "")
        val v = value.trim().trim('"', '\'')
        return OUTPUT_LIMIT_KEY.matches(normalized) && v.isNotEmpty() && v != "~" && v != "null"
    }

    private fun keysOf(file: VirtualFile): List<Pair<String, String>> {
        val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text
            ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull()
            ?: return emptyList()
        return if (file.extension == "properties") propertiesEntries(text) else yamlEntries(text)
    }

    internal fun propertiesEntries(text: String): List<Pair<String, String>> {
        val props = Properties()
        runCatching { props.load(StringReader(text)) }
        return props.stringPropertyNames().map { it to props.getProperty(it) }
    }

    /**
     * Minimal YAML flattener: nested and dotted mapping keys become "a.b.c" -> value.
     * Lists, flow mappings and block scalars are skipped; that is enough for Spring config keys.
     */
    internal fun yamlEntries(text: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val parents = ArrayDeque<Pair<Int, String>>() // indent -> key
        for (raw in text.lines()) {
            val line = stripComment(raw).trimEnd()
            if (line.isBlank()) continue
            if (line == "---") {
                parents.clear()
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
            if (value.isEmpty()) parents.addLast(indent to key) else result += fullKey to value
        }
        return result
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
