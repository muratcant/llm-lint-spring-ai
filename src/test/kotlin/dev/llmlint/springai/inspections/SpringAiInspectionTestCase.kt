package dev.llmlint.springai.inspections

import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.PathUtil
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import dev.llmlint.springai.LlmLintBundle

/**
 * Light test case with a real JDK and minimal Spring AI 2.x API stubs (src/test/testData/stubs).
 * Test files mark expected problems with `<warning descr="...">call</warning>`; [checkFile] fails on
 * any missing or extra warning and on unresolved code, so the snippets must compile.
 */
abstract class SpringAiInspectionTestCase : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun getProjectDescriptor(): LightProjectDescriptor = JDK_DESCRIPTOR

    override fun setUp() {
        super.setUp()
        // The inspection reads @Bean method bodies in other files on purpose; allow that AST loading.
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter.NONE, testRootDisposable)
        (myFixture as CodeInsightTestFixtureImpl).setVirtualFileFilter(null)
        myFixture.copyDirectoryToProject("stubs", "")
        providerStubs.forEach(::addProviderStubs)
        // All inspections are on in every test, so each test also proves the others stay silent.
        myFixture.enableInspections(MissingOutputLimitInspection(), UnboundedChatMemoryInspection())
    }

    /**
     * Provider stub directories (src/test/testData/stubs-<name>) copied for every test. OpenAI by default,
     * so tests see the same classpath as before provider defaults existed.
     */
    protected open val providerStubs: List<String> = listOf("openai")

    /** Puts a provider's ChatModel/ChatOptions stubs on the test project's classpath. */
    protected fun addProviderStubs(provider: String) {
        myFixture.copyDirectoryToProject("stubs-$provider", "")
    }

    protected fun checkFile(fileName: String, text: String) {
        myFixture.configureByText(fileName, text.trimIndent())
        myFixture.checkHighlighting(true, false, false)
    }

    /** Like [checkFile], but also checks `<weak_warning>` markers. */
    protected fun checkFileWithWeakWarnings(fileName: String, text: String) {
        myFixture.configureByText(fileName, text.trimIndent())
        myFixture.checkHighlighting(true, false, true)
    }

    protected fun addConfig(path: String, text: String) {
        myFixture.addFileToProject(path, text.trimIndent())
    }

    companion object {
        val MSG: String = LlmLintBundle.message("inspection.missing.output.limit.message")

        /** `<warning descr="MSG">call</warning>` */
        fun warn(method: String = "call"): String = """<warning descr="$MSG">$method</warning>"""

        val REPLACED_MSG: String = LlmLintBundle.message("inspection.missing.output.limit.replaced.defaults.message")

        /** Request options dropped the client's defaultOptions limit. */
        fun warnReplaced(method: String = "call"): String = """<warning descr="$REPLACED_MSG">$method</warning>"""

        val PARTIAL_MSG: String = LlmLintBundle.message("inspection.missing.output.limit.partial.message")

        /** Some paths have a limit, some don't. */
        fun warnPartial(method: String = "call"): String = """<warning descr="$PARTIAL_MSG">$method</warning>"""

        /** `<weak_warning descr="relies on the Anthropic default ...">call</weak_warning>` */
        fun weakProviderDefault(provider: String, tokens: Int, method: String = "call"): String {
            val message = LlmLintBundle.message("inspection.missing.output.limit.provider.default.message", provider, tokens.toString())
            return """<weak_warning descr="$message">$method</weak_warning>"""
        }

        val SESSION_MSG: String = LlmLintBundle.message("inspection.unbounded.memory.session.message")

        /** `<warning descr="SESSION_MSG">builder</warning>` */
        fun warnSession(): String = """<warning descr="$SESSION_MSG">builder</warning>"""

        /** `<warning descr="window of N messages">expression</warning>` */
        fun warnWindow(size: Long, expression: String): String =
            """<warning descr="${LlmLintBundle.message("inspection.unbounded.memory.window.message", size.toString())}">$expression</warning>"""

        /** A real JDK plus the Kotlin stdlib (scope functions such as `apply` live there). */
        private val JDK_DESCRIPTOR = object : DefaultLightProjectDescriptor() {
            override fun getSdk(): Sdk =
                JavaSdk.getInstance().createJdk("test-jdk", System.getProperty("java.home"), false)

            override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                super.configureModule(module, model, contentEntry)
                val stdlib = PathUtil.getJarPathForClass(Unit::class.java)
                VfsRootAccess.allowRootAccess(module, PathUtil.getParentPath(stdlib))
                PsiTestUtil.addLibrary(model, "kotlin-stdlib", PathUtil.getParentPath(stdlib), PathUtil.getFileName(stdlib))
            }
        }
    }
}
