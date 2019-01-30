package io.hexlabs.sckema

import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URL

fun main(args: Array<String>){
    val mapper = jacksonObjectMapper().forSckema()
    val schemaBuilder = Sckema.Builder()
    with(schemaBuilder){
        mapper.readValue<JsonSchema>(URL("https://schema.management.azure.com/schemas/2015-01-01/deploymentTemplate.json").readText())
                .extract("com.sckema", "Parent")
    }
    schemaBuilder.classPool.forEach {
        println(it)
    }
    schemaBuilder.references.forEach {
        println(it)
    }
}

class Sckema{
    sealed class Type(val types: JsonTypes){
        class OBJECT: Type(JsonTypes(listOf("object")))
        class STRING: Type(JsonTypes(listOf("string")))
        class NUMBER: Type(JsonTypes(listOf("number")))
        class INTEGER: Type(JsonTypes(listOf("integer")))
        class BOOLEAN: Type(JsonTypes(listOf("boolean")))
        class ARRAY: Type(JsonTypes(listOf("array")))
        class ANY(types: JsonTypes): Type(types)
        class NONE: Type(JsonTypes(emptyList()))
        companion object {
            fun from(types: JsonTypes?) = types?.types.orEmpty().let {
                if(it.size > 1) ANY(types!!) else when(it.firstOrNull()){
                    "string" -> STRING()
                    "number" -> NUMBER()
                    "integer" -> INTEGER()
                    "array" -> ARRAY()
                    else -> OBJECT()
                }
            }
        }
    }
    class Builder {
        val classPool = mutableListOf<JsonType>()
        private val names = mutableMapOf<String, Int>()
        val references = mutableMapOf<String, JsonType>()

        private fun nameFrom(key: String) = key.capitalize().let { canditate ->
            names[canditate] = (names[canditate] ?: 0) + 1
            names[canditate].let { index ->
                if(index == 1) canditate else "$canditate$index"
            }
        }

        private fun JsonSchema.definitions() = definitions?.definitions.orEmpty().filter { it.value is JsonSchema }.map { it.key to it.value as JsonSchema }

        fun JsonSchema.extract(pkg: String, name: String): JsonType{
            return SchemaExtractor(pkg, name).run {
                references.putAll(definitions().map { (key, value) -> "#/definitions/$key" to value.extract(key) })
                extract()
            }
        }

        inner class SchemaExtractor(val pkg: String, val name: String){

            fun JsonSchema.extract(key: String? = null): JsonType {
                fun newName() = nameFrom(key ?: name)
                return when {
                    anyOf != null -> SchemaExtractor(pkg, newName()).extractAnyOf(anyOf)
                    allOf != null -> SchemaExtractor(pkg, newName()).extractAllOf(allOf)
                    oneOf != null -> SchemaExtractor(pkg, newName()).extractOneOf(oneOf)
                    `$ref` != null -> SchemaExtractor(pkg, name).extractReferenceFrom(`$ref`)
                    else -> when(Type.from(type)){
                        is Type.OBJECT -> SchemaExtractor(pkg, newName()).extractObjectFrom(this)
                        is Type.ARRAY -> SchemaExtractor(pkg, newName()).extractArrayFrom(this)
                        is Type.STRING -> if(enum != null) EnumType(pkg, newName(), enum) else StringType.from(this)
                        is Type.BOOLEAN -> BooleanType
                        is Type.INTEGER -> IntegerType
                        is Type.NUMBER -> NumberType
                        is Type.ANY -> AnyType
                        else -> TODOTYPE
                    }
                }
            }

            fun extractObjectFrom(schema: JsonSchema): JsonType {
                val properties = schema.properties?.definitions.orEmpty().map { (key, value) ->
                    key to when(value){
                        is JsonSchema -> value.extract(key)
                        else -> TODOTYPE
                    }
                }.toMap()
                val clazz = JsonClass(pkg, name, properties)
                classPool.add(clazz)
                return clazz
            }

            fun extractArrayFrom(schema: JsonSchema): JsonType = ListType(schema.items?.schemas.orEmpty().map { it.extract("${name}Item") })

            fun extractReferenceFrom(reference: String): JsonType = if(reference.startsWith("#/")) Reference(name, reference) else RemoteReference(name, reference)

            fun extractAllOf(schemas: List<JsonSchema>): JsonType = AllOf(schemas.map { it.extract(name) })

            fun extractAnyOf(schemas: List<JsonSchema>): JsonType = AnyOf(schemas.map { it.extract(name) })

            fun extractOneOf(schemas: List<JsonSchema>): JsonType = OneOf(schemas.map { it.extract(name) })
        }
    }
}
interface JsonType
data class JsonClass(val pkg: String, val name: String, val properties: Map<String, JsonType>): JsonType
object AnyType: JsonType
object BooleanType: JsonType
object IntegerType: JsonType
object NumberType: JsonType
object TODOTYPE: JsonType
data class EnumType(val pkg: String, val name: String, val values: List<String>): JsonType

data class StringType(val description: String? = null, val enum: List<String>? = null): JsonType {
    companion object {
        fun from(schema: JsonSchema) = StringType(description = schema.description, enum = schema.enum)
    }
}

data class Reference(val key: String, val reference: String): JsonType
data class RemoteReference(val key: String, val reference: String): JsonType
data class ListType(val types: List<JsonType>): JsonType
data class AllOf(val types: List<JsonType>): JsonType
data class AnyOf(val types: List<JsonType>): JsonType
data class OneOf(val types: List<JsonType>): JsonType