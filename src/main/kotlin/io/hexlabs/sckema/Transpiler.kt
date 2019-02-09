package io.hexlabs.sckema

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import java.math.BigDecimal
import kotlin.reflect.KClass

class Transpiler {

    val fileSpecs: MutableList<FileSpec> = mutableListOf()

    fun Sckema.transpile() = types.forEach { it.transpile() }.let { fileSpecs }

    private fun SckemaType.transpile(): TypeName? = when (this) {
        is SckemaType.JsonClass -> transpile().let { fileSpecs.add(it); ClassName(it.packageName, it.name) }
        is SckemaType.EnumType -> transpile().let { fileSpecs.add(it); ClassName(it.packageName, it.name) }
        is SckemaType.StringType -> String::class.asTypeName()
        is SckemaType.BooleanType -> Boolean::class.asTypeName()
        is SckemaType.NumberType -> BigDecimal::class.asTypeName()
        is SckemaType.IntegerType -> Int::class.asTypeName()
        else -> Any::class.asTypeName()
    }

    fun SckemaType.JsonClass.transpile() = FileSpec.get(pkg, TypeSpec.classBuilder(name)
        .addModifiers(KModifier.DATA)
        .parameters(this.properties.mapNotNull { (key, value) -> value.transpile()?.let { key to it } })
        .add(additionalProperties)
        .build()
    )

    private fun SckemaType.EnumType.transpile() = FileSpec.get(pkg, TypeSpec.enumBuilder(name)
        .parameters(listOf("value" to String::class.asTypeName()))
        .apply { values.forEach { value ->
            addEnumConstant(SckemaResolver.run { value.escape().toUpperCase() }, TypeSpec.anonymousClassBuilder()
                .addSuperclassConstructorParameter("%S", value).build())
        } }
        .build()
    )

    private fun TypeSpec.Builder.add(additionalProperties: SckemaType?) = apply {
        additionalProperties?.let {
            it.transpile()?.let { type ->
                addProperty(
                    PropertySpec.builder("additionalProperties", MutableMap::class.ofType(String::class.type(), type))
                        .addModifiers(KModifier.PRIVATE)
                        .addAnnotation(JsonIgnore::class)
                        .initializer("mutableMapOf()")
                        .build()
                )
                addFunction(FunSpec.builder("set")
                    .addAnnotation(JsonAnySetter::class)
                    .addParameter("name", String::class)
                    .addParameter("value", type)
                    .addStatement("additionalProperties[name] = value")
                    .build()
                )
                addFunction(FunSpec.builder("additionalProperties")
                    .addAnnotation(JsonAnyGetter::class)
                    .returns(Map::class.ofType(String::class.type(), type))
                    .addStatement("return additionalProperties")
                    .build()
                )
            }
        }
    }

    private fun TypeSpec.Builder.parameters(parameters: List<Pair<String, TypeName>>) = apply {
        primaryConstructor(FunSpec.constructorBuilder().apply {
            parameters.forEach { (name, type) ->
                addParameter(ParameterSpec.builder(name, type).build())
                addProperty(PropertySpec.builder(name, type).initializer(name).build())
            }
        }.build())
    }

    companion object {
        operator fun <R> invoke(transpile: Transpiler.() -> R) = Transpiler().run(transpile)

        fun KClass<*>.type() = asTypeName()
        fun KClass<*>.ofType(vararg types: KClass<*>) = type().parameterizedBy(*types.map { it.type() }.toTypedArray())
        fun KClass<*>.ofType(vararg types: TypeName) = type().parameterizedBy(*types)
    }
}