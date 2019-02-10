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
        val sckemas = SckemaResolver { loadFile("siren.json").resolve() }.flatMap { sckema ->
            Transpiler { sckema.transpile() }
        }
        sckemas.forEach { it.writeTo(File("out/production/generated-sources")) }
    }

    @Test
    fun `should return primitive string type with description, pattern and default`() {
        Sckema.Extractor {
            expect(SckemaType.StringType()) { primitive<String>().extract("a", "B") }
            expect(SckemaType.StringType(description = "Hello, World!")) { primitive<String>().copy(description = "Hello, World!").extract("a", "B") }
            expect(SckemaType.StringType(pattern = "[^abc]", default = "5")) { primitive<String>().copy(pattern = "[^abc]", default = 5).extract("a", "B") }
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
            ).extract("com.sckema", "Parent")
            expect(true) { sckemaType is SckemaType.JsonClass }
            expect(setOf("one", "three")) { (sckemaType as SckemaType.JsonClass).requiredProperties }
        }
    }

    @Test
    fun `should build simple type from primitive properties`() {
        val sckema = Sckema.Extractor {
            JsonSchema(
                type = "object".type(),
                properties = JsonDefinitions(mapOf(
                    "one" to primitive<String>(),
                    "two" to primitive<Int>(),
                    "three" to primitive<BigDecimal>(),
                    "four" to primitive<Boolean>(),
                    "five" to JsonSchema(`$ref` = "#/definitions/otherType"),
                    "six" to JsonSchema(`$ref` = "http://other.type")
                ))
            ).extract("com.sckema", "Parent")
        }
        expect(1) { sckema.types.size }
        expect(SckemaType.JsonClass("com.sckema", "Parent", additionalProperties = SckemaType.AnyType,
            properties = mapOf(
                "one" to SckemaType.StringType(),
                "two" to SckemaType.IntegerType,
                "three" to SckemaType.NumberType,
                "four" to SckemaType.BooleanType,
                "five" to SckemaType.Reference("five", "#/definitions/otherType"),
                "six" to SckemaType.RemoteReference("six", "http://other.type")
            ))) { sckema.types[0] }
    }
}

inline fun <reified T : Any> primitive() = JsonSchema(type = JsonTypes(listOf(when (T::class) {
    String::class -> "string"
    Int::class -> "integer"
    BigDecimal::class -> "number"
    Boolean::class -> "boolean"
    else -> "unknown"
})))
