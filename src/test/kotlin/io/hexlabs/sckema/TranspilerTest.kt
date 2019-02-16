package io.hexlabs.sckema

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.squareup.kotlinpoet.*
import org.junit.Test
import java.math.BigDecimal
import kotlin.test.expect

import io.hexlabs.sckema.Transpiler.Companion.ofType
import io.hexlabs.sckema.Transpiler.Companion.type
import io.hexlabs.sckema.TranspilerTest.C2.Companion.asC2
import java.awt.PageAttributes.MediaType.C2
import java.lang.Exception
import kotlin.reflect.KClass

class TranspilerTest {

    @Test fun `should create type with no properties`() {
        transpile(JsonSchema(id = "http://a", title = "B", additionalProperties = AdditionalProperties(include = false))) {
            shouldHaveOneFileAndType(pkg = "a").run {
                expect("B") { name }
                expect(0, "Expected no properties") { propertySpecs.size }
            }
        }
    }

    @Test fun `should create type with any additionalProperties by default`() = verifyAdditionalProperties(fullSchemaOverride = JsonSchema())
    @Test fun `should create type with any additionalProperties when set to true`() = verifyAdditionalProperties()
    @Test fun `should create type with typed additionalProperties`() = verifyAdditionalProperties(schemaType = primitive<String>(), typeName = String::class.type())
    @Test fun `should create additionalProperties when recursive`() =
        verifyAdditionalProperties(
            typeName = ClassName("a", "B"),
            fullSchemaOverride = JsonSchema(additionalProperties = AdditionalProperties(include = true, type = JsonSchema(`$ref` = "#")))
        )

    @Test fun `should have single property of type String`() = matchesPrimitive<String>()
    @Test fun `should have single property of type Int`() = matchesPrimitive<Int>()
    @Test fun `should have single property of type BigDecimal`() = matchesPrimitive<BigDecimal>()
    @Test fun `should have single property of type Boolean`() = matchesPrimitive<Boolean>()

    @Test fun `should create simple object reference`() {
        val bar = JsonSchema(
            title = "Bar",
            type = JsonTypes(listOf("object")),
            additionalProperties = AdditionalProperties(include = false),
            properties = JsonDefinitions(mapOf("baz" to primitive<String>()))
        )
        val foo = JsonSchema(
            `$id` = "http://foobarbaz.com",
            title = "Foo",
            additionalProperties = AdditionalProperties(include = false),
            properties = JsonDefinitions(mapOf("fooBar" to JsonSchema(`$ref` = "#/definitions/Bar"))),
            otherProperties = mutableMapOf("definitions" to JsonSchema(otherProperties = mutableMapOf("Bar" to bar)))
        )
        val files = Transpiler { Sckema.Extractor { foo.extract() }.transpile() }
        expect(2) { files.size }
        expect(
            """package com.foobarbaz
                |
                |import kotlin.String
                |
                |class Bar(val baz: String)
                |""".trimMargin()
        ) { files[0].toString() }
        expect(
            """package com.foobarbaz
                |
                |class Foo(val fooBar: Bar)
                |""".trimMargin()
        ) { files[1].toString() }
    }

    private fun verifyAdditionalProperties(
        schemaType: JsonSchema? = null,
        typeName: TypeName = Any::class.type(),
        fullSchemaOverride: JsonSchema = JsonSchema(additionalProperties = AdditionalProperties(include = true, type = schemaType))
    ) {
        transpile(fullSchemaOverride.copy(`$id` = "http://a", title = "B")) {
            shouldHaveOneFileAndType(pkg = "a").run {
                expect("B") { name }
                expect(1, "Expected 1 property") { propertySpecs.size }
                with(propertySpecs.first()) {
                    expect("additionalProperties") { name }
                    expect(true, "Expected private property") { modifiers.contains(KModifier.PRIVATE) }
                    expect(Map::class.ofType(String::class.type(), typeName)) { type }
                    expect("mapOf()") { initializer.toString() }
                    expect(1, "Expected single annotation") { annotations.size }
                    expect(JsonIgnore::class.type()) { annotations.first().className }
                }
                expect(2, "Expected 2 functions") { funSpecs.size }
                with(funSpecs.first()) {
                    expect("set") { name }
                    expect(1, "Expected single annotation") { annotations.size }
                    expect(JsonAnySetter::class.type()) { annotations.first().className }
                    expect(2, "Expected 2 parameters") { parameters.size }
                    with(parameters.first()) {
                        expect("name") { name }
                        expect(String::class.type()) { type }
                    }
                    with(parameters[1]) {
                        expect("value") { name }
                        expect(typeName) { type }
                    }
                    expect("additionalProperties.toMutableMap()[name] = value\n") { body.toString() }
                }
                with(funSpecs[1]) {
                    expect("additionalProperties") { name }
                    expect(1, "Expected single annotation") { annotations.size }
                    expect(JsonAnyGetter::class.type()) { annotations.first().className }
                    expect(0, "Expected 0 parameters") { parameters.size }
                    expect(Map::class.ofType(String::class.type(), typeName)) { returnType }
                    expect("return additionalProperties\n") { body.toString() }
                }
            }
        }
    }

    private fun transpile(schema: JsonSchema, fileSpecs: List<FileSpec>.() -> Unit) {
        Transpiler {
            Sckema.Extractor { schema.extract() }.transpile().apply(fileSpecs)
        }
    }

    private inline fun <reified T : Any> matchesPrimitive() { Transpiler {
        val files = singlePropertySckema<T>("abc").transpile()
        expect(T::class.asTypeName()) { (files.first().members.first() as TypeSpec).propertySpecs.first().type }
    } }

    private inline fun <reified T : Any> singlePropertySckema(name: String) = Sckema.Extractor {
        JsonSchema(
            type = JsonTypes(listOf("object")),
            properties = JsonDefinitions(mapOf(name to primitive<T>()))
        ).extract()
    }

    private fun List<FileSpec>.shouldHaveOneFileAndType(pkg: String): TypeSpec {
        expect(1, "Expected exactly 1 file") { size }
        return first().run {
            expect(1, "Expected exactly 1 member in file") { members.size }
            expect(true, "Expected member to be a TypeSpec") { members.first() is TypeSpec }
            expect(pkg) { packageName }
            members.first() as TypeSpec
        }
    }

    @Test
    fun go(){
        val json = """{
            "a": { "a": "Fred" }
        }
        """.trimIndent()
        val o = jacksonObjectMapper().readValue<O>(json)
        println(jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(o))
    }
    data class O(
        val a: C2
    )

    // OneOf
    data class A2(val a: String)
    data class B2(val b: String)

    @JsonSerialize(using = C2.Serializer::class)
    @JsonDeserialize(using = C2.Deserializer::class)
    data class C2 private constructor(private val item: Any) {
        fun asA2() = item as A2
        fun asB2() = item as B2
        class Serializer: StdSerializer<C2>(C2::class.java) {
            override fun serialize(value: C2, gen: JsonGenerator, provider: SerializerProvider) {
                gen.writeObject(value.item)
            }
        }
        class Deserializer: StdDeserializer<C2>(C2::class.java) {
            override fun deserialize(parser: JsonParser, context: DeserializationContext): C2 {
                val node = parser.readValueAsTree<JsonNode>()
                return listOfNotNull(
                    try { parser.codec.treeToValue(node,A2::class.java).asC2() } catch(e: Exception) { null },
                    try { parser.codec.treeToValue(node,B2::class.java).asC2() } catch(e: Exception) { null }
                ).first()
            }
        }
        companion object {
            fun A2.asC2() = C2(this)
            fun B2.asC2() = C2(this)
        }
    }
}