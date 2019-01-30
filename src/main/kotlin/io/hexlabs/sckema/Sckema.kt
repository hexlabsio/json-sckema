package io.hexlabs.sckema

class Sckema(val types: List<SckemaType>){

    companion object {
        operator fun invoke(builder: Sckema.Builder.() -> Unit = { }) = Sckema.Builder().run { builder(); build() }
    }

    class Builder {
        private val names = mutableMapOf<String, Int>()
        val classPool = mutableListOf<SckemaType>()
        val references = mutableMapOf<String, SckemaType>()

        fun build() = Sckema(classPool)

        private fun nameFrom(key: String) = key.capitalize().let { canditate ->
            names[canditate] = (names[canditate] ?: 0) + 1
            names[canditate].let { index ->
                if(index == 1) canditate else "$canditate$index"
            }
        }

        private fun JsonSchema.definitions() = definitions?.definitions.orEmpty().filter { it.value is JsonSchema }.map { it.key to it.value as JsonSchema }

        fun JsonSchema.extract(pkg: String, name: String): SckemaType {
            return SchemaExtractor(pkg, name).run {
                references.putAll(definitions().map { (key, value) -> "#/definitions/$key" to value.extract(key) })
                extract()
            }
        }

        inner class SchemaExtractor(val pkg: String, val name: String){

            fun JsonSchema.extract(key: String? = null): SckemaType {
                fun newName() = nameFrom(key ?: name)
                return when {
                    anyOf != null -> SchemaExtractor(pkg, newName()).extractAnyOf(anyOf)
                    allOf != null -> SchemaExtractor(pkg, newName()).extractAllOf(allOf)
                    oneOf != null -> SchemaExtractor(pkg, newName()).extractOneOf(oneOf)
                    `$ref` != null -> SchemaExtractor(pkg, name).extractReferenceFrom(`$ref`)
                    else -> when(SchemaType.from(type)){
                        is SchemaType.OBJECT -> SchemaExtractor(pkg, newName()).extractObjectFrom(this)
                        is SchemaType.ARRAY -> SchemaExtractor(pkg, newName()).extractArrayFrom(this)
                        is SchemaType.STRING -> if(enum != null) SckemaType.EnumType(pkg, newName(), enum) else SckemaType.StringType.from(this)
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
                    .toMap()
                    .let { properties -> SckemaType.JsonClass(pkg, name, properties).also { classPool.add(it) } }

            private fun extractArrayFrom(schema: JsonSchema): SckemaType = SckemaType.ListType(schema.items?.schemas.orEmpty().map { it.extract("${name}Item") })

            private fun extractReferenceFrom(reference: String): SckemaType =
                if(reference.startsWith("#/")) SckemaType.Reference(name, reference)
                else SckemaType.RemoteReference(name, reference)

            private fun extractAllOf(schemas: List<JsonSchema>): SckemaType = SckemaType.AllOf(schemas.map { it.extract(name) })

            private fun extractAnyOf(schemas: List<JsonSchema>): SckemaType =SckemaType.AnyOf(schemas.map { it.extract(name) })

            private fun extractOneOf(schemas: List<JsonSchema>): SckemaType = SckemaType.OneOf(schemas.map { it.extract(name) })
        }
    }
}