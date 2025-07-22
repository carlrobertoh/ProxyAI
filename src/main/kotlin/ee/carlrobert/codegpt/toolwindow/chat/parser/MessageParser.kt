package ee.carlrobert.codegpt.toolwindow.chat.parser

interface MessageParser {

    fun parse(input: String): List<Segment>
}