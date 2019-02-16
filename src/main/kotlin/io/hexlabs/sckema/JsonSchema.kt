package io.hexlabs.sckema

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.JsonNodeType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.sun.tools.extcheck.Main.MISSING
import java.math.BigDecimal

@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonSchema(
    val `$id`: String? = null,
    val id: String? = null,
    val `$schema`: String? = null,
    val title: String? = null,
    val description: String? = null,
    val default: Any? = null,
    val `$ref`: String? = null,
    @JsonDeserialize(using = TypesDeserializer::class) val type: JsonTypes? = null,
    @JsonDeserialize(using = ItemsDeserializer::class) val items: JsonItems? = null,
    val format: String? = null,
    val enum: List<String>? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val maxLength: Int? = null,
    val minLength: Int? = null,
    val pattern: String? = null,
    val required: List<String>? = null,
    val additionalItems: Boolean? = null,
    @JsonDeserialize(using = AdditionalPropertiesDeserializer::class) val additionalProperties: AdditionalProperties = AdditionalProperties(),
    val uniqueItems: Boolean? = null,
    val multipleOf: BigDecimal? = null,
    val maximum: BigDecimal? = null,
    val exclusiveMaximum: BigDecimal? = null,
    val minimum: BigDecimal? = null,
    val exclusiveMinimum: BigDecimal? = null,
    private val `$comment`: String? = null,
    @JsonDeserialize(using = DefinitionsDeserializer::class) val properties: JsonDefinitions? = null,
    val oneOf: List<JsonSchema>? = null,
    val allOf: List<JsonSchema>? = null,
    val anyOf: List<JsonSchema>? = null,
    val metadata: Map<String, String>? = null,
    @JsonIgnore val otherProperties: MutableMap<String, JsonSchema> = mutableMapOf()
) : JsonOrStringDefinition {
    @JsonAnySetter fun set(name: String, value: Any?) {
        if(value is Map<*,*> && value.isNotEmpty() && value.all { it.value is JsonSchema }) {
            otherProperties[name] = JsonSchema(otherProperties = value.map { (key, value) -> key.toString() to value as JsonSchema }.toMap().toMutableMap())
        }
    }
}

interface JsonOrStringDefinition
data class AdditionalProperties(val include: Boolean = true, val type: JsonSchema? = null)
data class JsonStringDefinition(val value: String = "") : JsonOrStringDefinition
data class JsonDefinitions(val definitions: Map<String, JsonOrStringDefinition>)
data class JsonTypes(val types: List<String>)
data class JsonItems(val schemas: List<JsonSchema>)

class AnyDeserializer : JsonDeserializer<Any>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Map<String, JsonSchema>? {
        val codec = parser.codec
        val node: JsonNode = codec.readTree(parser)
        return if (node.isObject) {
            node.fields().asSequence().mapNotNull {
                if (it.value.isObject) it.key to codec.treeToValue(it.value, JsonSchema::class.java)
                else null
            }.toMap()
        } else null
    }
}

class AdditionalPropertiesDeserializer : JsonDeserializer<AdditionalProperties>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): AdditionalProperties {
        val codec = parser.codec
        val node: JsonNode = codec.readTree(parser)
        return if (node.isBoolean) AdditionalProperties(node.booleanValue())
        else AdditionalProperties(true, codec.treeToValue(node, JsonSchema::class.java))
    }
}

class DefinitionsDeserializer : JsonDeserializer<JsonDefinitions>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): JsonDefinitions {
        val codec = parser.codec
        val node: JsonNode = codec.readTree(parser)
        val map = mutableMapOf<String, JsonOrStringDefinition>()
        node.fieldNames().forEach {
            map[it] = when {
                node[it].isTextual -> JsonStringDefinition(node[it].asText())
                else -> codec.treeToValue(node[it], JsonSchema::class.java)
            }
        }
        return JsonDefinitions(map)
    }
}

class TypesDeserializer : JsonDeserializer<JsonTypes>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): JsonTypes {
        val codec = parser.codec
        val node: JsonNode = codec.readTree(parser)
        return when {
            node.isTextual -> JsonTypes(listOf(node.textValue()))
            else -> JsonTypes(node.fields().asSequence().map { it.value.textValue() }.toList())
        }
    }
}

class ItemsDeserializer : JsonDeserializer<JsonItems>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): JsonItems {
        val codec = parser.codec
        val node: JsonNode = codec.readTree(parser)
        val schemas = if (node.isArray) node.toList() else listOf(node)
        return JsonItems(schemas.map { codec.treeToValue(it, JsonSchema::class.java) })
    }
}

val defaultObjectMapper = jacksonObjectMapper().forSckema()

fun ObjectMapper.forSckema() = registerModule(SimpleModule().apply {
    addDeserializer(JsonDefinitions::class.java, DefinitionsDeserializer())
    addDeserializer(JsonTypes::class.java, TypesDeserializer())
    addDeserializer(JsonItems::class.java, ItemsDeserializer())
    addDeserializer(AdditionalProperties::class.java, AdditionalPropertiesDeserializer())
    addDeserializer(Any::class.java, AnyDeserializer())
})