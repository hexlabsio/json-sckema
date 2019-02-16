package io.hexlabs.sckema

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import kotlin.test.expect

class SckemaTest {

    private val mapper = jacksonObjectMapper().forSckema()
    private fun loadSckema(string: String): JsonSchema = mapper.readValue(string)
    private fun loadFile(name: String) = loadSckema(SckemaTest::class.java.classLoader.getResource(name).readText())

    private fun String.type() = JsonTypes(listOf(this))

    @Test
    fun `do something`() {
        val sckema = Sckema.Extractor { Transpiler { loadFile("siren.json").extract().transpile()  } }
        sckema.forEach { it.writeTo(File("out/production/generated-sources")) }
    }

    @Test
    fun `should return primitive string type with description, pattern and default`() {
        Sckema.Extractor {
            fun schemaFrom(property: JsonSchema) = (JsonSchema(type = "object".type(), properties = JsonDefinitions(mapOf("a" to property)))
                .extract("a", "B").type as SckemaType.JsonClass).properties["a"]
            expect(SckemaType.StringType()) { schemaFrom(primitive<String>()) }
            expect(SckemaType.StringType(description = "Hello, World!")) { schemaFrom(primitive<String>().copy(description = "Hello, World!")) }
            expect(SckemaType.StringType(pattern = "[^abc]", default = "5")) { schemaFrom(primitive<String>().copy(pattern = "[^abc]", default = 5)) }
        }
    }

    @Test
    fun `should have correct required properties`() {
        Sckema.Extractor {
            val sckemaType = JsonSchema(
                type = "object".type(),
                required = listOf("one", "three"),
                properties = JsonDefinitions(
                    mapOf(
                        "one" to primitive<String>(),
                        "two" to primitive<Int>()
                    )
                )
            ).extract("com.sckema", "Parent").type
            expect(true) { sckemaType is SckemaType.JsonClass }
            expect(setOf("one", "three")) { (sckemaType as SckemaType.JsonClass).requiredProperties }
        }
    }

    @Test
    fun `should build simple type from primitive properties`() {
        val sckema = Sckema.Extractor {
            JsonSchema(
                `$id` = "https://sckema.com",
                type = "object".type(),
                title = "Parent",
                properties = JsonDefinitions(mapOf(
                    "one" to primitive<String>(),
                    "two" to primitive<Int>(),
                    "three" to primitive<BigDecimal>(),
                    "four" to primitive<Boolean>(),
                    "five" to JsonSchema(`$ref` = "#/definitions/otherType")
                ))
            ).extract()
        }
        expect(SckemaType.JsonClass("com.sckema", "Parent", additionalProperties = SckemaType.AnyType,
            properties = mapOf(
                "one" to SckemaType.StringType(),
                "two" to SckemaType.IntegerType,
                "three" to SckemaType.NumberType,
                "four" to SckemaType.BooleanType,
                "five" to SckemaType.ClassRef("com.sckema", "OtherType")
            ))) { sckema.first() }
    }

    @Test
    fun `should resolve name from title and package from id`() {
        val sckema = Sckema.Extractor {
            JsonSchema(
                `$id` = "https://abc.def.com/other/part",
                type = "object".type(),
                title = "TestType",
                description = "I am a description",
                additionalProperties = AdditionalProperties(include = false)
            ).extract()
        }
        expect(1, "Expected one type") { sckema.size }
        with(sckema.first() as SckemaType.JsonClass) {
            expect("TestType") { name }
            expect("com.def.abc.other") { pkg }
            expect("I am a description") { description }
        }
    }

    @Test
    fun `should resolve name from id when title is not there`() {
        val sckema = Sckema.Extractor {
            JsonSchema(
                `$id` = "https://abc.def.com/other/part",
                type = "object".type(),
                additionalProperties = AdditionalProperties(include = false)
            ).extract()
        }
        expect(1, "Expected one type") { sckema.size }
        with(sckema.first() as SckemaType.JsonClass) {
            expect("Part") { name }
            expect("com.def.abc.other") { pkg }
        }
    }

    @Test
    fun `should resolve subobject name from key`() {
        val types = Sckema.Extractor {
            JsonSchema(
                title = "Foo",
                type = "object".type(),
                properties = JsonDefinitions(mapOf(
                    "bar" to JsonSchema(additionalProperties = AdditionalProperties(include = false))
                )),
                additionalProperties = AdditionalProperties(include = false)
            ).extract()
        }
        expect(2, "Expected one type") { types.size }
        with(types.first() as SckemaType.JsonClass) {
            expect("Bar") { name }
        }
        with(types[1] as SckemaType.JsonClass) {
            expect("Foo") { name }
        }
    }

    @Test
    fun `should resolve self reference`() {
        val sckema = Sckema.Extractor {
            JsonSchema(
                `$id` = "https://abc.def.com/other/part",
                type = "object".type(),
                title = "TestType",
                additionalProperties = AdditionalProperties(include = false),
                properties = JsonDefinitions(mapOf(
                    "abc" to JsonSchema(`$ref` = "#")
                ))
            ).extract()
        }
        expect(1, "Expected one type") { sckema.size }
        with(sckema.first() as SckemaType.JsonClass) {
            expect("TestType") { name }
            expect("com.def.abc.other") { pkg }
            expect(1) { properties.size }
            expect("abc") { properties.keys.first() }
            expect(SckemaType.ClassRef(pkg = "com.def.abc.other", name = "TestType")) { properties["abc"] }
        }
    }

    @Test fun `should resolve object reference`() {
        val bar = JsonSchema(
            title = "Bar",
            type = JsonTypes(listOf("object")),
            additionalProperties = AdditionalProperties(include = false),
            properties = JsonDefinitions(mapOf("baz" to primitive<String>()))
        )
        JsonSchema(
            id = "http://a.b.com",
            title = "Foo",
            additionalProperties = AdditionalProperties(include = false),
            properties = JsonDefinitions(mapOf("fooBar" to JsonSchema(`$ref` = "#/definitions/Bar"))),
            otherProperties = mutableMapOf("definitions" to JsonSchema(
                otherProperties = mutableMapOf("Bar" to bar)
            ))
        ).verifySimpleObjectReference()
    }

    @Test fun `should resolve object reference when nested`() {
        val bar = JsonSchema(
            title = "Bar",
            type = JsonTypes(listOf("object")),
            additionalProperties = AdditionalProperties(include = false),
            properties = JsonDefinitions(mapOf("baz" to primitive<String>()))
        )
        JsonSchema(
            id = "http://a.b.com",
            title = "Foo",
            additionalProperties = AdditionalProperties(include = false),
            properties = JsonDefinitions(mapOf("fooBar" to JsonSchema(`$ref` = "#/components/schemas/Bar"))),
            otherProperties = mutableMapOf("components" to JsonSchema(
                otherProperties = mutableMapOf("schemas" to JsonSchema(otherProperties = mutableMapOf("Bar" to bar)))
            ))
        ).verifySimpleObjectReference()
    }

    @Test fun `should resolve array property`() {
        val sckema = Sckema.Extractor { JsonSchema(
            id = "http://a.b.com",
            title = "Foo",
            additionalProperties = AdditionalProperties(include = false),
            properties = JsonDefinitions(mapOf("fooBar" to JsonSchema(type = "array".type(), items = JsonItems(listOf(primitive<String>())))))
        ).extract() }
        with(sckema) {
            expect(1) { size }
            with(first() as SckemaType.JsonClass) {
                expect(1) { properties.size }
                with(properties["fooBar"]) {
                    expect(true) { this is SckemaType.ListType }
                    with(this as SckemaType.ListType) {
                        expect(1) { types.size }
                        expect(true) { types.first() is SckemaType.StringType }
                    }
                }
            }
        }
    }

    @Test fun `should resolve remote type`() {
        val rootSchema = JsonSchema(
            id = "http://a.b.com/Foo",
            additionalProperties = AdditionalProperties(include = false),
            properties = JsonDefinitions(mapOf(
                "bar" to JsonSchema(`$ref` = "http://a.b.com/Bar")
            ))
        )
        val remoteSchema = JsonSchema(
            id = "http://c.d.com/Bar",
            additionalProperties = AdditionalProperties(include = false)
        )
        val types = Sckema.Extractor {
            rootSchema.extract(urlResolver = {remoteSchema})
        }
        expect(2) { types.size }
        with(types.first() as SckemaType.JsonClass) {
            expect("Foo") { name }
            expect("com.b.a") { pkg }
            expect(SckemaType.ClassRef("com.d.c", "Bar")) { properties["bar"] }
        }

        with(types[1] as SckemaType.JsonClass) {
            expect("Bar") { name }
            expect("com.d.c") { pkg }
        }
    }



    private fun JsonSchema.verifySimpleObjectReference(){
        with(Sckema.Extractor { extract() }) {
            expect(2) { size }
            with(first() as SckemaType.JsonClass) {
                expect("Bar") { name }
                expect(1) { properties.size }
                expect(io.hexlabs.sckema.SckemaType.StringType()) { properties["baz"] }
            }
            with(get(1) as SckemaType.JsonClass) {
                expect("Foo") { name }
                expect(1) { properties.size }
                expect(io.hexlabs.sckema.SckemaType.ClassRef("com.b.a", "Bar")) { properties["fooBar"] }
            }
        }
    }

}

inline fun <reified T : Any> primitive() = JsonSchema(type = JsonTypes(listOf(when (T::class) {
    String::class -> "string"
    Int::class -> "integer"
    BigDecimal::class -> "number"
    Boolean::class -> "boolean"
    else -> "unknown"
})))
