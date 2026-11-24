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
        // All inspections are on in every test, so each test also proves the others stay silent.
        myFixture.enableInspections(MissingOutputLimitInspection(), UnboundedChatMemoryInspection())
    }

    protected fun checkFile(fileName: String, text: String) {
        myFixture.configureByText(fileName, text.trimIndent())
        myFixture.checkHighlighting(true, false, false)
    }

    protected fun addConfig(path: String, text: String) {
        myFixture.addFileToProject(path, text.trimIndent())
    }

    companion object {
        val MSG: String = LlmLintBundle.message("inspection.missing.output.limit.message")

        /** `<warning descr="MSG">call</warning>` */
        fun warn(method: String = "call"): String = """<warning descr="$MSG">$method</warning>"""

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
