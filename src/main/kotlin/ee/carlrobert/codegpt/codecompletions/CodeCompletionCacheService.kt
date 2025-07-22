package ee.carlrobert.codegpt.codecompletions

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class CodeCompletionCacheService() {
    private val cacheCounter = ConcurrentHashMap<String, Int>()
    private val cache: Cache<String, String> = CacheBuilder.newBuilder()
        .maximumSize(10)
        .recordStats()
        .build()

    fun get(key: String): String? {
        val value = cache.getIfPresent(key)
        if (value != null) {
            cache.invalidate(key)
            cache.put(key, value)
        }
        return value
    }

    fun clear() {
        cache.invalidateAll()
    }

    fun delete(key: String) {
        cache.invalidate(key)
    }

    fun set(key: String, value: String) {
        cache.put(key, value)
    }

    fun normalize(src: String): String {
        return src.replace("\n", "").replace("\\s+".toRegex(), "").replace("\\s".toRegex(), "")
    }

    fun getKey(prefix: String, suffix: String): String {
        return if (suffix.isNotEmpty()) {
            normalize("$prefix #### $suffix")
        } else {
            normalize(prefix)
        }
    }

    fun getCache(editor: Editor): String? {
        val caretOffset = runReadAction { editor.caretModel.offset }
        val prefix = editor.document.text.substring(0, caretOffset)
        val suffix = editor.document.text.substring(caretOffset)
        return getCache(prefix, suffix)
    }

    fun getCache(prefix: String, suffix: String): String? {
        val key = getKey(prefix, suffix)
        if (cacheCounter.containsKey(key)) {
            cacheCounter[key] = cacheCounter[key]!! + 1
        } else {
            cacheCounter[key] = 1
        }
        if (cacheCounter[key]!! > 3) {
            cache.invalidate(key)
            cacheCounter.remove(key)
        }

        return get(key)
    }

    fun setCache(prefix: String, suffix: String, completion: String) {
        val key = getKey(prefix, suffix)
        set(key, completion)
    }
}