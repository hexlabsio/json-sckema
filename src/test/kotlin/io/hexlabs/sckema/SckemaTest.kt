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
        val sckemas = Sckema.Extractor { loadFile("siren.json").extract() }.flatMap { sckema ->
            Transpiler { sckema.transpile() }
        }
        sckemas.forEach { it.writeTo(File("out/production/generated-sources")) }
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
        }.first()
        expect(SckemaType.JsonClass("com.sckema", "Parent", additionalProperties = SckemaType.AnyType,
            properties = mapOf(
                "one" to SckemaType.StringType(),
                "two" to SckemaType.IntegerType,
                "three" to SckemaType.NumberType,
                "four" to SckemaType.BooleanType,
                "five" to SckemaType.ClassRef("com.sckema", "OtherType")
            ))) { sckema.types.first() }
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
        }.first()
        expect(1, "Expected one type") { sckema.types.size }
        with(sckema.types.first() as SckemaType.JsonClass) {
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
        }.first()
        expect(1, "Expected one type") { sckema.types.size }
        with(sckema.types.first() as SckemaType.JsonClass) {
            expect("Part") { name }
            expect("com.def.abc.other") { pkg }
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
        }.first()
        expect(1, "Expected one type") { sckema.types.size }
        with(sckema.types.first() as SckemaType.JsonClass) {
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

    private fun JsonSchema.verifySimpleObjectReference(){
        val sckemas = Sckema.Extractor { extract() }
        expect(1) { sckemas.size }
        with(sckemas.first()) {
            expect(2) { types.size }
            with(types.first() as SckemaType.JsonClass) {
                expect("Bar") { name }
                expect(1) { properties.size }
                expect(io.hexlabs.sckema.SckemaType.StringType()) { properties["baz"] }
            }
            with(types[1] as SckemaType.JsonClass) {
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
