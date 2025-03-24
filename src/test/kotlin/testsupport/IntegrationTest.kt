package testsupport

import com.intellij.openapi.util.Key
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.llm.client.mixin.ExternalServiceTestMixin
import testsupport.mixin.ShortcutsTestMixin

open class IntegrationTest : BasePlatformTestCase(), ExternalServiceTestMixin, ShortcutsTestMixin {

    @Throws(Exception::class)
    override fun tearDown() {
        ExternalServiceTestMixin.clearAll()
        clearKeys()
        super.tearDown()
    }

    private fun clearKeys() {
        putUserData(CodeGPTKeys.IMAGE_ATTACHMENT_FILE_PATH, "")
    }

    private fun <T> putUserData(key: Key<T>, value: T) {
        project.putUserData(key, value)
    }

    companion object {
        init {
            ExternalServiceTestMixin.init()
        }
    }
}
