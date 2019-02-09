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

sealed class SckemaType {
    data class JsonClass(val pkg: String, val name: String, val properties: Map<String, SckemaType>, val additionalProperties: SckemaType? = null) : SckemaType()
    data class EnumType(val pkg: String, val name: String, val values: List<String>) : SckemaType()
    data class Reference(val key: String, val reference: String, val canonicalReference: String) : SckemaType()
    data class RemoteReference(val key: String, val reference: String, var resolvedType: SckemaType? = null) : SckemaType()
    data class ListType(val types: List<SckemaType>) : SckemaType()
    data class AllOf(val types: List<SckemaType>) : SckemaType()
    data class AnyOf(val types: List<SckemaType>) : SckemaType()
    data class OneOf(val types: List<SckemaType>) : SckemaType()

    object AnyType : SckemaType()
    object BooleanType : SckemaType()
    object IntegerType : SckemaType()
    object NumberType : SckemaType()
    object NotFoundType : SckemaType()

    data class StringType(val description: String? = null, val enum: List<String>? = null) : SckemaType() {
        companion object {
            fun from(schema: JsonSchema) = StringType(description = schema.description, enum = schema.enum)
        }
    }
}
