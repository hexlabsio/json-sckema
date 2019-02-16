package io.hexlabs.sckema

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import sun.text.normalizer.UTF16.append
import java.math.BigDecimal
import kotlin.reflect.KClass

class Transpiler {

    private val fileMappings: MutableMap<Pair<String, String>, TypeName> = mutableMapOf()
    private val typeMappings: MutableMap<TypeName, TypeSpec> = mutableMapOf()

    fun List<SckemaType>.transpile(): List<FileSpec> = forEach { it.transpile() }.let { fileMappings.map {
            (name, typeName) -> FileSpec.get(name.first, typeMappings[typeName]!!)
    } }

    private fun append(type: TypeSpec, pkg: String, name: String) = ClassName(pkg, name).also { typeName ->
        fileMappings[pkg to name] = typeName
        typeMappings[typeName] = type
    }

    private fun SckemaType.transpile(): TypeName = when (this) {
        is SckemaType.JsonClass -> transpile()
        is SckemaType.EnumType -> transpile()
        is SckemaType.StringType -> String::class.asTypeName()
        is SckemaType.BooleanType -> Boolean::class.asTypeName()
        is SckemaType.NumberType -> BigDecimal::class.asTypeName()
        is SckemaType.IntegerType -> Int::class.asTypeName()
        is SckemaType.ListType -> transpile()
        is SckemaType.ClassRef -> ClassName(pkg, name)
        is SckemaType.AllOf -> {
            if (types.size == 1) types.first().transpile()
            else transpile()
        }
        is SckemaType.AnyOf -> {
            if (types.size == 1) types.first().transpile()
            else transpile()
        }
        is SckemaType.OneOf -> {
            if (types.size == 1) types.first().transpile()
            else transpile()
        }
        is SckemaType.Reference -> if (reference == "#")
            (resolvedType as SckemaType.JsonClass).let { ClassName(it.pkg, it.name) }
            else resolvedType?.transpile() ?: Any::class.asTypeName()
        else -> Any::class.asTypeName()
    }

    private fun SckemaType.JsonClass.transpile() = ClassName(pkg, name).let {
        if (!typeMappings.contains(it)) append(
            TypeSpec.classBuilder(name)
                .apply { description?.let { addKdoc("%S", it) } }
                .parameters(this.properties.mapNotNull { (key, value) -> key to value.transpile() })
                .add(additionalProperties)
                .build(),
            pkg, name
        )
        else it
    }

    private fun SckemaType.EnumType.transpile() = append(
        TypeSpec.enumBuilder(name)
            .parameters(listOf("value" to String::class.asTypeName()))
            .apply { values.forEach { value ->
                // addEnumConstant(SckemaResolver.run { value.escape().toUpperCase() }, TypeSpec.anonymousClassBuilder()
                  //  .addSuperclassConstructorParameter("%S", value).build())
            } }
            .build(),
        pkg, name
    )

    private fun SckemaType.ListType.transpile(): TypeName {
        val typeNames = types.map { it.transpile() }
        return if (typeNames.size == 1)
            List::class.ofType(typeNames.first())
        else Any::class.type()
    }

    private fun SckemaType.AnyOf.transpile(): TypeName = append(
        TypeSpec.classBuilder(name)
            .build(),
        pkg, name
    )

    private fun SckemaType.OneOf.transpile(): TypeName = append(

        TypeSpec.classBuilder(if(name == "package") "`$name`" else name)
            .build(),
        pkg, name
    )

    private fun SckemaType.AllOf.transpile(): TypeName {
        return if (!types.any { it.primitive || it is SckemaType.RemoteReference }) {
            val typeNames = types.map { it.transpile() }
            val typeSpecs = typeNames.mapIndexed { index, typeName ->
                if (index == 0) typeMappings[typeName] = typeMappings[typeName]!!.open()
                typeMappings[typeName]!!
            }
            val ancestor = typeNames.first()
            val properties = typeSpecs.flatMap { it.propertySpecs }
            append(
                TypeSpec.classBuilder(name)
                    .superclass(ancestor)
                    .parameters(properties.map { it.name to it.type })
                    .build(),
                pkg, name
            )
        } else Any::class.asTypeName()
    }

    private fun TypeSpec.Builder.add(additionalProperties: SckemaType?) = apply {
        additionalProperties?.let {
            it.transpile().let { type ->
                addProperty(
                    PropertySpec.builder("additionalProperties", Map::class.ofType(String::class.type(), type))
                        .addModifiers(KModifier.PRIVATE)
                        .addAnnotation(JsonIgnore::class)
                        .initializer("mapOf()")
                        .build()
                )
                addFunction(FunSpec.builder("set")
                    .addAnnotation(JsonAnySetter::class)
                    .addParameter("name", String::class)
                    .addParameter("value", type)
                    .addStatement("additionalProperties.toMutableMap()[name] = value")
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

    private fun TypeSpec.open() = toBuilder()
        .addModifiers(KModifier.OPEN)
        .build()

    companion object {
        operator fun <R> invoke(transpile: Transpiler.() -> R) = Transpiler().run(transpile)

        fun KClass<*>.type() = asTypeName()
        // fun KClass<*>.ofType(vararg types: KClass<*>) = type().parameterizedBy(*types.map { it.type() }.toTypedArray())
        fun KClass<*>.ofType(vararg types: TypeName) = type().parameterizedBy(*types)
    }
}