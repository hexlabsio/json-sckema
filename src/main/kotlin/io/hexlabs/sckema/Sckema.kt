package io.hexlabs.sckema

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URL
import java.util.*

data class Sckema(val id: String, val types: List<SckemaType>) {

    data class ClassName(val pkg: String, val name: String)

    class Extractor {
        private fun JsonSchema.uri() = (`$id` ?: id ?: "").let { if(it.endsWith("#")) it.substringBefore("#") else it }
        fun JsonSchema.extract(
            nameResolver: (JsonSchema) -> ClassName = defaultNameResolver,
            urlResolver: (String) -> JsonSchema = {
                println(it)
                jacksonObjectMapper().forSckema()
                    .readValue<JsonSchema>(URL(it).readText())
                    .copy(`$id` = it)
            }
        ) = with(nameResolver(this)) {
            resolve(listOf(Extracted(this@extract.uri(), extract(pkg, name))), nameResolver, urlResolver).flatMap { it.types }
        }

        private fun resolve(
            extracted: List<Extracted>,
            nameFrom: (JsonSchema) -> ClassName,
            resolve: (String) -> JsonSchema
        ): List<Sckema> {
            val resolveActual: (String) -> Extraction = { url ->
                extracted.find { it.id.substringBeforeLast("#") == url }?.extraction ?: resolve(url).run {
                    with(nameFrom(this)) { extract(pkg, name) }
                }
            }
            return resolve(
                extracted
                .flatMap { it.extraction.info.remoteReferences.map { remote -> remote.reference.substringBeforeLast("#") } }
                .toSet()
                .fold(extracted) { acc, url -> acc + Extracted(url, resolveActual(url)) }
            ).map { Sckema(it.id, it.extraction.info.classes) }
        }

        private fun SckemaType.Type.resolve(canonicalReferences: Map<String, SckemaType>): SckemaType.Type {
            return when(this){
                is SckemaType.JsonClass -> copy(properties = properties.map { it.key to it.value.resolve(canonicalReferences) }.toMap())
                else -> this
            }
        }
        private fun SckemaType.resolve(canonicalReferences: Map<String, SckemaType>): SckemaType{
            return when(this){
                is SckemaType.RemoteReference -> {
                    canonicalReferences[reference]?.let {
                        if(it is SckemaType.Type) SckemaType.ClassRef(pkg = it.pkg, name = it.name)
                        else it
                    } ?: this
                }
                is SckemaType.ListType -> copy(types = types.map { it.resolve(canonicalReferences) })
                is SckemaType.AllOf -> copy(types = types.map { it.resolve(canonicalReferences) })
                is SckemaType.OneOf -> copy(types = types.map { it.resolve(canonicalReferences) })
                is SckemaType.AnyOf -> copy(types = types.map { it.resolve(canonicalReferences) })
                else -> this
            }
        }

        private fun resolve(extracted: List<Extracted>): List<Extracted> {
            val canonicalReferences = extracted.flatMap { ex ->
                ex.extraction.info.references.map { (ex.id + it.key) to it.value }
            }.toMap()
            return extracted.map {
                it.copy(
                    extraction = it.extraction.copy(
                        info = it.extraction.info.copy(classes = it.extraction.info.classes.map { it.resolve(canonicalReferences) })
                    )
                )
            }
        }


        internal fun JsonSchema.extract(pkg: String, name: String) = SchemaExtractor(listOf(), Info(), pkg).run { extract("#", name, name) }

        internal data class Info(
            val remoteReferences: List<SckemaType.RemoteReference> = emptyList(),
            val classes: List<SckemaType.Type> = emptyList(),
            val references: Map<String, SckemaType> = emptyMap()
        )
        internal data class Extraction(
            val type: SckemaType,
            val info: Info
        )
        internal data class Extracted(val id: String, val extraction: Extraction)
        private data class ExtractionInfo(val info: Info, val types: List<SckemaType> = emptyList())
        private data class ExtractionInfoMap(val info: Info, val types: Map<String, SckemaType> = emptyMap())

        private class SchemaExtractor(private val resolved: List<Sckema>, val info: Info, private val pkg: String) {

            internal fun JsonSchema.extract(path: String, name: String, key: String? = null): Extraction {
                return otherProperties.toList().fold(ExtractionInfoMap(info)) { acc, (key, schema) ->
                    SchemaExtractor(resolved, acc.info, pkg)
                        .run { schema.extract("$path/$key", name, key) }
                        .let {
                            if (it.type is SckemaType.Type) ExtractionInfoMap(
                                info = it.info.copy(references = it.info.references + ("$path/$key" to it.type)),
                                types = acc.types + (key to it.type)
                            ) else acc.copy(info = it.info)
                        }
                }.let { extractionInfoMap ->
                    if (this.properties?.definitions.orEmpty().isNotEmpty() || otherProperties.isEmpty())
                        SchemaExtractor(resolved, extractionInfoMap.info, pkg).run {
                            extract(key, parent = name).let {
                                if (it.type is SckemaType.Type)
                                    it.copy(info = it.info.copy(references = it.info.references + ("" to it.type)))
                                else it
                            }
                        }
                    else Extraction(SckemaType.AnyType, extractionInfoMap.info)
                }
            }

            internal fun JsonSchema.extract(key: String? = null, parent: String): Extraction {
                return when {
                    anyOf != null -> extractAnyOf(anyOf, key, parent)
                    allOf != null -> extractAllOf(allOf, key, parent)
                    oneOf != null -> extractOneOf(oneOf, key, parent)
                    `$ref` != null -> extractReferenceFrom(`$ref`, parent)
                    else -> when (SchemaType.from(type)) {
                        is SchemaType.OBJECT -> extractObjectFrom(this, key)
                        is SchemaType.ARRAY -> extractArrayFrom(this, key ?: parent)
                        is SchemaType.STRING -> Extraction(if (enum != null) SckemaType.EnumType(pkg, key ?: parent, enum) else SckemaType.StringType.from(this), info)
                        is SchemaType.BOOLEAN -> Extraction(SckemaType.BooleanType, info)
                        is SchemaType.INTEGER -> Extraction(SckemaType.IntegerType, info)
                        is SchemaType.NUMBER -> Extraction(SckemaType.NumberType, info)
                        is SchemaType.ANY -> Extraction(SckemaType.AnyType, info)
                    }
                }
            }

            private fun extractObjectFrom(schema: JsonSchema, key: String? = null): Extraction {
                val name = (schema.title ?: key ?: "Unknown").capitalize()
                if(name == "Unknown") {
                    println(schema)
                }
                return schema.properties?.definitions
                    .orEmpty()
                    .filter { it.value is JsonSchema }
                    .map { it.key to it.value as JsonSchema }
                    .fold(ExtractionInfoMap(info)) { acc, (key, schema) ->
                        val extraction = SchemaExtractor(resolved, acc.info, pkg).run { schema.extract(key = key, parent = name) }
                        ExtractionInfoMap(extraction.info, acc.types + (key to extraction.type))
                    }.let { extractionInfo ->
                        val additionalPropertyExtraction = if (schema.additionalProperties.include) {
                            schema.additionalProperties.type?.extract(null, parent = name)
                        } else null
                        val type = SckemaType.JsonClass(
                            pkg = pkg,
                            name = name,
                            description = schema.description,
                            properties = extractionInfo.types,
                            requiredProperties = schema.required.orEmpty().toSet(),
                            additionalProperties = schema.additionalProperties.let { if (it.include) (additionalPropertyExtraction?.type ?: SckemaType.AnyType) else null }
                        )
                        Extraction(type, (additionalPropertyExtraction?.info ?: extractionInfo.info).let { it.copy(classes = it.classes + type) })
                    }
            }

            private fun extractArrayFrom(schema: JsonSchema, key: String): Extraction =
                schema.items?.schemas.orEmpty().fold(ExtractionInfo(info)) {
                    acc, schema -> schema.extract("${key}Item", key).let { acc.copy(types = acc.types + it.type, info = it.info) }
                }.let { Extraction(SckemaType.ListType(it.types), it.info) }

            private fun extractReferenceFrom(reference: String, parent: String): Extraction {
                return if (reference.startsWith("#")) {
                    Extraction(type = if (reference == "#") SckemaType.ClassRef(pkg, parent) else SckemaType.ClassRef(pkg, reference.substringAfterLast("/").capitalize()), info = info)
                } else SckemaType.RemoteReference(reference).let { Extraction(it, info.copy(remoteReferences = info.remoteReferences + it)) }
            }

            private fun collect(key: String?, parent: String, schemas: List<JsonSchema>) = schemas.fold(ExtractionInfo(info)) {
                    (acc, types), schema ->
                        SchemaExtractor(resolved, acc, pkg).run {
                            schema.extract(key, parent).let { ExtractionInfo(it.info, types + it.type) }
                        }
            }

            private fun extractAllOf(schemas: List<JsonSchema>, key: String?, parent: String): Extraction {
                val name = key ?: parent
                return collect(key, parent, schemas).let { Extraction(SckemaType.AllOf(pkg, name, it.types), it.info) }
            }

            private fun extractAnyOf(schemas: List<JsonSchema>, key: String?, parent: String): Extraction {
                val name = key ?: parent
                return collect(key, name, schemas).let { Extraction(SckemaType.AnyOf(pkg, name, it.types), it.info) }
            }

            private fun extractOneOf(schemas: List<JsonSchema>, key: String?, parent: String): Extraction {
                val name = key ?: parent
                return collect(key, name, schemas).let { Extraction(SckemaType.OneOf(pkg, name, it.types), it.info) }
            }
        }
        companion object {
            operator fun <R> invoke(builder: Sckema.Extractor.() -> R) = Sckema.Extractor().run { builder() }

            private fun String.escape() = replace("-", "_").replace(Regex("[^a-zA-Z.0-9_]"), "").let { if (!it[0].isLetter()) "v$it" else it }

            val defaultNameResolver = { schema: JsonSchema ->
                (schema.`$id` ?: schema.id)?.substringBeforeLast("#")?.let {
                    val parts = it.substringAfter("://").split("/").map { part -> part.escape() }
                    val reverseDomain = parts[0].split(".").reversed().map { part -> part.escape() }.joinToString(".")
                    if (parts.size > 1) {
                        val others = parts.subList(1, parts.size - 1).joinToString(".").let { if (it.isEmpty()) null else it }
                        val name = parts.last().substringBefore(".json").split(".").map { part -> part.escape() }
                        val packageParts = name.dropLast(1).joinToString(".").let { part -> if (part.isEmpty()) null else part.decapitalize() }
                        val pkg = listOfNotNull(reverseDomain, others, packageParts).joinToString(".")
                        val className = name.last().capitalize()
                        ClassName(pkg, schema.title ?: className)
                    } else ClassName(reverseDomain, schema.title ?: "Unknown")
                } ?: ClassName("com.sckema.unknown", schema.title ?: "Unknown")
            }
        }
    }
}
