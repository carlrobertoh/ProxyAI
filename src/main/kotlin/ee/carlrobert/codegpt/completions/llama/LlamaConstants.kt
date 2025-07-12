package ee.carlrobert.codegpt.completions.llama

object LlamaConstants {
    const val PROGRESS_CMAKE_SETUP = 0.0
    const val PROGRESS_CMAKE_BUILD = 0.33
    const val PROGRESS_SERVER_START = 0.66

    const val BUILD_PARALLEL_JOBS = 4
    const val BUILD_CONFIGURATION = "Release"
    const val BUILD_DIRECTORY = "build"
    
    const val SERVER_EXECUTABLE_PATH = "./build/bin/llama-server"
    const val SERVER_LISTENING_MESSAGE = "server is listening"
    
    const val MAX_LOG_ENTRIES = 10000
    const val MAX_LOG_SESSIONS = 5
}