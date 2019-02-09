package io.hexlabs.sckema

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.squareup.kotlinpoet.*
import org.junit.Test
import java.math.BigDecimal
import kotlin.test.expect

import io.hexlabs.sckema.Transpiler.Companion.ofType
import io.hexlabs.sckema.Transpiler.Companion.type

class TranspilerTest {

    @Test fun `should create type with no properties`() {
        transpile(JsonSchema(additionalProperties = AdditionalProperties(include = false)), pkg = "a", name = "B") {
            shouldHaveOneFileAndType(pkg = "a").run {
                expect("B") { name }
                expect(0, "Expected no properties") { propertySpecs.size }
            }
        }
    }

    @Test fun `should create type with additionalProperties`() {
        transpile(JsonSchema(additionalProperties = AdditionalProperties(include = true)), pkg = "a", name = "B") {
            shouldHaveOneFileAndType(pkg = "a").run {
                expect("B") { name }
                expect(1, "Expected 1 property") { propertySpecs.size }
                with(propertySpecs.first()) {
                    expect("additionalProperties") { name }
                    expect(true, "Expected private property") { modifiers.contains(KModifier.PRIVATE) }
                    expect(MutableMap::class.ofType(String::class, Any::class)) { type }
                    expect("mutableMapOf()") { initializer.toString() }
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
                        expect(Any::class.type()) { type }
                    }
                    expect("additionalProperties[name] = value\n") { body.toString() }
                }
                with(funSpecs[1]) {
                    expect("additionalProperties") { name }
                    expect(1, "Expected single annotation") { annotations.size }
                    expect(JsonAnyGetter::class.type()) { annotations.first().className }
                    expect(0, "Expected 0 parameters") { parameters.size }
                    expect(Map::class.ofType(String::class, Any::class)) { returnType }
                    expect("return additionalProperties\n") { body.toString() }
                }
            }
        }
    }

    @Test fun `should have single property of type String`() = matchesPrimitive<String>()
    @Test fun `should have single property of type Int`() = matchesPrimitive<Int>()
    @Test fun `should have single property of type BigDecimal`() = matchesPrimitive<BigDecimal>()
    @Test fun `should have single property of type Boolean`() = matchesPrimitive<Boolean>()

    private fun transpile(schema: JsonSchema, pkg: String = "a", name: String = "B", fileSpecs: List<FileSpec>.() -> Unit) {
        Transpiler {
            Sckema.Extractor { schema.extract(pkg = pkg, name = name) }.transpile()
            this.fileSpecs.apply(fileSpecs)
        }
    }

    private inline fun <reified T : Any> matchesPrimitive() { Transpiler {
        singlePropertySckema<T>("abc").transpile()
        expect(T::class.asTypeName()) { (fileSpecs.first().members.first() as TypeSpec).propertySpecs.first().type }
    } }

    private inline fun <reified T : Any> singlePropertySckema(name: String) = Sckema.Extractor {
        JsonSchema(
            type = JsonTypes(listOf("object")),
            properties = JsonDefinitions(mapOf(name to primitive<T>()))
        ).extract("a", "B")
    }

    private fun List<FileSpec>.shouldHaveOneFileAndType(pkg: String): TypeSpec {
        expect(1, "Expected exactly 1 file") { size }
        return first().run {
            expect(1, "Expected exactly 1 member in file") { members.size }
            expect(true, "Expected member to be a TypeSpec") { members.first() is TypeSpec }
            expect(pkg) { packageName }
            (members.first() as TypeSpec).apply {
                expect(true, "Expected Data Class") { modifiers.contains(KModifier.DATA) }
            }
        }
    }
}