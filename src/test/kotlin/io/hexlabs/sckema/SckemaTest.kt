package io.hexlabs.sckema

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Test

class SckemaTest {

    private val mapper = jacksonObjectMapper().forSckema()
    private fun loadSckema(string: String): JsonSchema = mapper.readValue(string)
    private fun loadFile(name: String) = loadSckema(SckemaTest::class.java.classLoader.getResource(name).readText())

    @Test
    fun `do something`() {
        val sckemaBuilder = Sckema.Builder()
        val sckema = loadFile("azure.json")
        with(sckemaBuilder) {
            sckema.extract("com.sckema", "Parent")
            classPool.forEach(::println)
            references.forEach(::println)
        }
    }
}