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

fun SckemaType.Type.ref() = SckemaType.ClassRef(pkg, name)
sealed class SckemaType(val primitive: Boolean = false) {
    abstract class Type(open val pkg: String, open val name: String, open val parent: ClassRef?) : SckemaType()
    data class ClassRef(val pkg: String, val name: String) : SckemaType()
    data class JsonClass(
        override val pkg: String,
        override val name: String,
        override val parent: ClassRef? = null,
        val description: String? = null,
        val properties: Map<String, SckemaType>,
        val requiredProperties: Set<String> = emptySet(),
        val additionalProperties: SckemaType? = null
    ) : Type(pkg = pkg, name = name, parent = parent)
    data class EnumType(
        override val pkg: String,
        override val name: String,
        override val parent: ClassRef? = null,
        val values: List<String>
    ) : Type(pkg = pkg, name = name, parent = parent)
    data class Reference(val reference: String, var resolvedType: SckemaType? = null) : SckemaType()
    data class RemoteReference(val reference: String) : SckemaType()
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
