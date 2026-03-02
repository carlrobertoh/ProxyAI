package ee.carlrobert.codegpt.settings.service.codegpt

import com.intellij.util.messages.Topic

interface CodeGPTUserDetailsNotifier {
    fun userDetailsObtained(userDetails: CodeGPTUserDetails?)

    companion object {
        @JvmStatic
        val CODEGPT_USER_DETAILS_TOPIC =
            Topic.create("codegptUserDetails", CodeGPTUserDetailsNotifier::class.java)
    }
}
