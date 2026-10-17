package dev.chiller.tools

import dev.chiller.contracts.ToolException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Opt-in live smoke entry point. Reports counts and bounded metadata only; never prints page content. */
object WebSmoke {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val service = BoundedWebService()
        try {
            val search = service.search("Kotlin documentation", 3).data!!.jsonObject
            val results = search.getValue("results").jsonArray
            val titleChars = results.sumOf { it.jsonObject.getValue("title").jsonPrimitive.content.length }
            println("search ok results=${results.size} title_chars=$titleChars")
        } catch (error: ToolException) {
            println("search failed code=${error.code}")
        } catch (_: Exception) {
            println("search failed code=UNEXPECTED")
        }

        listOf(
            "example" to "https://www.example.com/",
            "rfc9110" to "https://www.rfc-editor.org/rfc/rfc9110.txt",
            "github_meta" to "https://api.github.com/meta",
        ).forEach { (label, url) ->
            try {
                val data = service.fetch(url).data!!.jsonObject
                val contentType = data.getValue("content_type").jsonPrimitive.content.substringBefore(';').take(60)
                val contentChars = data.getValue("content").jsonPrimitive.content.length
                val titleChars = data.getValue("title").jsonPrimitive.content.length
                println("fetch $label ok type=$contentType chars=$contentChars title_chars=$titleChars")
            } catch (error: ToolException) {
                println("fetch $label failed code=${error.code}")
            } catch (_: Exception) {
                println("fetch $label failed code=UNEXPECTED")
            }
        }
    }
}
