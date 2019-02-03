package io.hexlabs.sckema

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URL

data class SckemaClassName(val pkg: String, val name: String)

class SckemaResolver {

    private fun String.escape() = replace(Regex("[^a-zA-Z.]"), "X")

    val defaultNameResolver = { schema: JsonSchema ->
        schema.`$id`?.substringBeforeLast("#")?.let {
            val parts = it.substringAfter("://").split("/")
            val reverseDomain = parts[0].split(".").reversed().joinToString(".")
            val others = parts.subList(1, parts.size - 1).joinToString(".")
            val name = parts.last().substringBefore(".json").split(".")
            val packageParts = name.dropLast(1).joinToString(".").let { if (it.isEmpty()) null else it.decapitalize() }
            val pkg = listOfNotNull(reverseDomain, others, packageParts).joinToString(".").escape()
            val className = name.last().capitalize().escape()
            SckemaClassName(pkg, className)
        } ?: SckemaClassName("com.sckema.unknown", "Unknown")
    }

    fun JsonSchema.resolve(nameResolver: (JsonSchema) -> SckemaClassName = defaultNameResolver) = nameResolver(this).run {
        Sckema { extract(pkg, name) }
    }.let { sckema ->
        resolve(listOf(sckema), sckema.remoteReferences.map { it.reference }, nameResolver)
    }

    private fun resolve(sckemas: List<Sckema>, unresolved: List<String>, nameResolver: (JsonSchema) -> SckemaClassName): List<Sckema>{
        val newSckemas = unresolved.map { it.substringBefore("#") }
            .toSet()
            .fold(sckemas) { acc, unresolvedFile ->
                acc + Sckema(sckemas) {
                    jacksonObjectMapper().forSckema().readValue<JsonSchema>(URL(unresolvedFile).readText())
                        .copy(`$id` = "$unresolvedFile#")
                        .run { nameResolver(this).let { extract(it.pkg, it.name) } }
                }
            }
        val leftovers = newSckemas.flatMap {
            it.remoteReferences.filter { it.resolvedType == null }.mapNotNull { remoteReference ->
                val existing = newSckemas.find { it.findReference(remoteReference.reference) != null }?.findReference(remoteReference.reference)
                if(existing != null) {
                    remoteReference.resolvedType = existing
                    null
                } else remoteReference.reference
            }
        }
        return if(leftovers.isEmpty()) newSckemas else resolve(newSckemas, leftovers, nameResolver)
    }
    companion object {
        operator fun <R> invoke(resolver: SckemaResolver.() -> R) = SckemaResolver().run(resolver)
    }
}