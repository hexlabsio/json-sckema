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
    private inline fun <reified T> primitive() = JsonSchema(type = JsonTypes(listOf(when(T::class) {
        String::class -> "string"
        Int::class -> "integer"
        BigDecimal::class -> "number"
        Boolean::class -> "boolean"
        else -> "unknown"
    })))

    @Test
    fun `do something`() {
        val sckemas = SckemaResolver { loadFile("azure.json").resolve() }.flatMap {
            Transpiler().transpile(it)
        }
        sckemas.forEach { it.writeTo(File("out/production/generated-sources")) }
    }

    @Test
    fun `should build simple type from primitives`() {
        val sckema = Sckema {
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
        expect(SckemaType.JsonClass("com.sckema", "Parent", properties = mapOf(
            "one" to SckemaType.StringType(),
            "two" to SckemaType.IntegerType,
            "three" to SckemaType.NumberType,
            "four" to SckemaType.BooleanType,
            "five" to SckemaType.Reference("five", "#/definitions/otherType", "/definitions/otherType"),
            "six" to SckemaType.RemoteReference("six", "http://other.type")
        ))) { sckema.types[0] }

    }
}