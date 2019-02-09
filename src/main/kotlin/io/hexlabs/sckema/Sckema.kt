package io.hexlabs.sckema

data class Sckema(val id: String, val types: List<SckemaType>, val references: Map<String, SckemaType>, val remoteReferences: List<SckemaType.RemoteReference>) {

    fun findReference(reference: String) = if (id == reference.substringBefore("#")) {
        references["#" + reference.substringAfter("#").let { if (it.startsWith("/")) it else "/$it" } ]
    } else null

    class Extractor(private val referenceList: List<Sckema>) {
        private var id: String = ""
        private val names = mutableMapOf<String, Int>()
        val classPool = mutableListOf<SckemaType>()
        private val references = mutableMapOf<String, SckemaType>()
        val remoteReferences = mutableListOf<SckemaType.RemoteReference>()
        val localReferences = mutableListOf<SckemaType.Reference>()

        fun findReference(reference: String) = referenceList.find { it.findReference(reference) != null }?.findReference(reference)

        fun build() = Sckema(id, classPool, references, remoteReferences)

        private fun nameFrom(pkg: String, key: String) = key.capitalize().let { candidate ->
            names["$pkg.$candidate"] = (names["$pkg.$candidate"] ?: 0) + 1
            names["$pkg.$candidate"].let { index ->
                if (index == 1) candidate else "$candidate$index"
            }
        }

        fun JsonSchema.extract(pkg: String, name: String): SckemaType {
            this@Extractor.id = (`$id` ?: id)?.substringBeforeLast("#") ?: ""
            return SchemaExtractor(pkg, name).run {
                references.putAll(otherProperties.flatMap { (propKey, propValue) -> propValue.map { (key, value) -> "#/$propKey/$key" to value.extract(key, name) } })
                extract().also { root ->
                    localReferences.forEach {
                        if(it.reference == "#") it.resolvedType = root
                        else it.resolvedType = references[it.reference]
                    }
                }
            }
        }

        inner class SchemaExtractor(private val pkg: String, private val name: String) {

            fun JsonSchema.extract(key: String? = null, parent: String? = null): SckemaType {
                fun newName() = nameFrom(pkg, key ?: name)
                return when {
                    anyOf != null -> SchemaExtractor(pkg, newName()).extractAnyOf(anyOf)
                    allOf != null -> SchemaExtractor(pkg, nameFrom(pkg, parent+key)).extractAllOf(allOf)
                    oneOf != null -> SchemaExtractor(pkg, newName()).extractOneOf(oneOf)
                    `$ref` != null -> SchemaExtractor(pkg, key ?: name).extractReferenceFrom(`$ref`)
                    else -> when (SchemaType.from(type)) {
                        is SchemaType.OBJECT -> SchemaExtractor(pkg, newName()).extractObjectFrom(this)
                        is SchemaType.ARRAY -> SchemaExtractor(pkg, (key ?: name) + "Item").extractArrayFrom(this)
                        is SchemaType.STRING -> if (enum != null) SckemaType.EnumType(pkg, newName(), enum) else SckemaType.StringType.from(this)
                        is SchemaType.BOOLEAN -> SckemaType.BooleanType
                        is SchemaType.INTEGER -> SckemaType.IntegerType
                        is SchemaType.NUMBER -> SckemaType.NumberType
                        is SchemaType.ANY -> SckemaType.AnyType
                    }
                }
            }

            private fun extractObjectFrom(schema: JsonSchema): SckemaType = schema.properties?.definitions.orEmpty()
                    .filter { it.value is JsonSchema }
                    .map { it.key to (it.value as JsonSchema).extract(it.key) }
                    .let { properties ->
                        val additionalProperties = schema.additionalProperties.let { if (it.include) it.type?.extract() ?: SckemaType.AnyType else null }
                        SckemaType.JsonClass(pkg, name, properties.toMap(), additionalProperties).also { classPool.add(it) }
                    }

            private fun extractArrayFrom(schema: JsonSchema): SckemaType = SckemaType.ListType(schema.items?.schemas.orEmpty().map { it.extract("${name}Item") })

            private fun extractReferenceFrom(reference: String): SckemaType =
                if (reference.startsWith("#")) SckemaType.Reference(name, reference).also { localReferences.add(it) }
                else findReference(reference) ?: SckemaType.RemoteReference(name, reference).also { remoteReferences.add(it) }

            private fun extractAllOf(schemas: List<JsonSchema>): SckemaType = SckemaType.AllOf(pkg, name, schemas.map { it.extract(name) })

            private fun extractAnyOf(schemas: List<JsonSchema>): SckemaType = SckemaType.AnyOf(pkg, name, schemas.map { it.extract(name) })

            private fun extractOneOf(schemas: List<JsonSchema>): SckemaType = SckemaType.OneOf(pkg, name, schemas.map { it.extract(name) })
        }
        companion object {
            operator fun invoke(referenceList: List<Sckema> = emptyList(), builder: Sckema.Extractor.() -> Unit = { }) = Sckema.Extractor(referenceList).run { builder(); build() }
        }
    }
}