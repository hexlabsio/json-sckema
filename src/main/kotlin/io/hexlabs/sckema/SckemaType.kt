package io.hexlabs.sckema

sealed class SchemaType(val types: JsonTypes) {
    class OBJECT : SchemaType(JsonTypes(listOf("object")))
    class STRING : SchemaType(JsonTypes(listOf("string")))
    class NUMBER : SchemaType(JsonTypes(listOf("number")))
    class INTEGER : SchemaType(JsonTypes(listOf("integer")))
    class BOOLEAN : SchemaType(JsonTypes(listOf("boolean")))
    class ARRAY : SchemaType(JsonTypes(listOf("array")))
    class ANY(types: JsonTypes) : SchemaType(types)
    companion object {
        fun from(types: JsonTypes?) = types?.types.orEmpty().let {
            if (it.size > 1) ANY(types!!) else when (it.firstOrNull()) {
                "string" -> STRING()
                "number" -> NUMBER()
                "integer" -> INTEGER()
                "boolean" -> BOOLEAN()
                "array" -> ARRAY()
                else -> OBJECT()
            }
        }
    }
}

sealed class SckemaType(val primitive: Boolean = false) {
    data class JsonClass(
        val pkg: String,
        val name: String,
        val properties: Map<String, SckemaType>,
        val requiredProperties: Set<String> = emptySet(),
        val additionalProperties: SckemaType? = null
    ) : SckemaType()
    data class EnumType(val pkg: String, val name: String, val values: List<String>) : SckemaType()
    data class Reference(val key: String, val reference: String, var resolvedType: SckemaType? = null) : SckemaType()
    data class RemoteReference(val key: String, val reference: String, var resolvedType: SckemaType? = null) : SckemaType()
    data class ListType(val types: List<SckemaType>) : SckemaType()
    data class AllOf(val pkg: String, val name: String, val types: List<SckemaType>) : SckemaType()
    data class AnyOf(val pkg: String, val name: String, val types: List<SckemaType>) : SckemaType()
    data class OneOf(val pkg: String, val name: String, val types: List<SckemaType>) : SckemaType()

    object AnyType : SckemaType()
    object BooleanType : SckemaType(primitive = true)
    object IntegerType : SckemaType(primitive = true)
    object NumberType : SckemaType(primitive = true)

    data class StringType(val description: String? = null, val pattern: String? = null, val default: String? = null) : SckemaType(primitive = true) {
        companion object {
            fun from(schema: JsonSchema) = StringType(
                description = schema.description,
                pattern = schema.pattern,
                default = schema.default?.toString()
            )
        }
    }
}
