package ee.carlrobert.codegpt.toolwindow.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AcpConfigOptionsTest {

    @Test
    fun summaryPartsIncludesBooleanAndCustomOptions() {
        val options = listOf(
            AcpConfigOption(
                id = "model",
                name = "Model",
                category = "model",
                type = "select",
                currentValue = "gpt-5",
                options = listOf(AcpConfigOptionChoice("gpt-5", "GPT-5"))
            ),
            AcpConfigOption(
                id = "sandbox",
                name = "Sandbox",
                type = "boolean",
                currentValue = "true",
                options = listOf(
                    AcpConfigOptionChoice("true", "Enabled"),
                    AcpConfigOptionChoice("false", "Disabled")
                )
            ),
            AcpConfigOption(
                id = "approval_mode",
                name = "Approval",
                type = "select",
                currentValue = "manual",
                options = listOf(
                    AcpConfigOptionChoice("manual", "Manual"),
                    AcpConfigOptionChoice("auto", "Automatic")
                )
            )
        )

        assertEquals(
            listOf("GPT-5", "Sandbox: Enabled", "Approval: Manual"),
            AcpConfigOptions.summaryParts(options)
        )
    }
}
