package ee.carlrobert.codegpt.toolwindow.chat

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.message.Message
import org.assertj.core.api.Assertions.assertThat

class ChatToolWindowTabbedPaneTest : BasePlatformTestCase() {

  fun testClearAllTabs() {
    val tabbedPane = ChatToolWindowTabbedPane(project, Disposer.newDisposable())
    tabbedPane.addNewTab(createNewTabPanel())

    tabbedPane.clearAll()

    assertThat(tabbedPane.activeTabMapping).isEmpty()
  }


  fun testAddingNewTabs() {
    val tabbedPane = ChatToolWindowTabbedPane(project, Disposer.newDisposable())

    tabbedPane.addNewTab(createNewTabPanel())
    tabbedPane.addNewTab(createNewTabPanel())
    tabbedPane.addNewTab(createNewTabPanel())

    assertThat(tabbedPane.activeTabMapping.keys)
      .containsExactly("Chat 1", "Chat 2", "Chat 3")
  }

  fun testResetCurrentlyActiveTabPanel() {
    val tabbedPane = ChatToolWindowTabbedPane(project, Disposer.newDisposable())
    val conversation = ConversationService.getInstance(project).startConversation()
    conversation.addMessage(Message("TEST_PROMPT", "TEST_RESPONSE"))
    tabbedPane.addNewTab(ChatToolWindowTabPanel(project, conversation))

    tabbedPane.resetCurrentlyActiveTabPanel(project)

    val tabPanel = tabbedPane.activeTabMapping["Chat 1"]
    assertThat(tabPanel!!.conversation.messages).isEmpty()
  }

  private fun createNewTabPanel(): ChatToolWindowTabPanel {
    return ChatToolWindowTabPanel(
      project,
      ConversationService.getInstance(project).startConversation()
    )
  }
}
