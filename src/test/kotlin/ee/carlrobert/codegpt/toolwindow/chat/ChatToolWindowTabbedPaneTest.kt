package ee.carlrobert.codegpt.toolwindow.chat

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.toolwindow.ToolWindowInitialState
import org.assertj.core.api.Assertions.assertThat
import java.awt.event.ActionEvent

class ChatToolWindowTabbedPaneTest : BasePlatformTestCase() {

    fun testClearAllTabs() {
        val tabbedPane = ChatToolWindowTabbedPane(Disposer.newDisposable())
        tabbedPane.addNewTab(createNewTabPanel())

        tabbedPane.clearAll()

        assertThat(tabbedPane.activeTabMapping).isEmpty()
    }

    fun testAddingNewTabs() {
        val tabbedPane = ChatToolWindowTabbedPane(Disposer.newDisposable())

        tabbedPane.addNewTab(createNewTabPanel())
        tabbedPane.addNewTab(createNewTabPanel())
        tabbedPane.addNewTab(createNewTabPanel())

        assertThat(tabbedPane.activeTabMapping.keys)
            .containsExactly("Chat 1", "Chat 2", "Chat 3")
    }

    fun testResetCurrentlyActiveTabPanel() {
        val tabbedPane = ChatToolWindowTabbedPane(Disposer.newDisposable())
        val conversation = ConversationService.getInstance().startConversation(project)
        conversation.addMessage(Message("TEST_PROMPT", "TEST_RESPONSE"))
        tabbedPane.addNewTab(ChatToolWindowTabPanel(project, ToolWindowInitialState(conversation)))

        tabbedPane.resetCurrentlyActiveTabPanel(project)

        val tabPanel = tabbedPane.activeTabMapping["Chat 1"]
        assertThat(tabPanel!!.conversation.messages).isEmpty()
    }

    fun testCanCloseFirstTabWhenMultipleTabsExist() {
        val tabbedPane = ChatToolWindowTabbedPane(Disposer.newDisposable())
        tabbedPane.addNewTab(createNewTabPanel())
        tabbedPane.addNewTab(createNewTabPanel())

        tabbedPane.CloseActionListener("Chat 1")
            .actionPerformed(ActionEvent(tabbedPane, ActionEvent.ACTION_PERFORMED, "close"))

        assertThat(tabbedPane.activeTabMapping.keys).containsExactly("Chat 2")
        assertThat(tabbedPane.tabCount).isEqualTo(1)
    }

    fun testCanCloseLastRemainingTab() {
        val tabbedPane = ChatToolWindowTabbedPane(Disposer.newDisposable())
        tabbedPane.addNewTab(createNewTabPanel())

        tabbedPane.CloseActionListener("Chat 1")
            .actionPerformed(ActionEvent(tabbedPane, ActionEvent.ACTION_PERFORMED, "close"))

        assertThat(tabbedPane.activeTabMapping).isEmpty()
        assertThat(tabbedPane.tabCount).isZero()
    }

    private fun createNewTabPanel(): ChatToolWindowTabPanel {
        return ChatToolWindowTabPanel(
            project,
            ToolWindowInitialState(ConversationService.getInstance().startConversation(project))
        )
    }
}
