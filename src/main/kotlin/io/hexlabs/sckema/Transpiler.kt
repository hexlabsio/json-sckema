package io.hexlabs.sckema

import com.squareup.kotlinpoet.*

class Transpiler {

    val typeSpecs: MutableList<FileSpec> = mutableListOf()

    fun transpile(sckema: Sckema) = sckema.types.forEach { it.transpile() }.let { typeSpecs }

    fun SckemaType.transpile(): TypeName? = when(this){
        is SckemaType.JsonClass -> {
            val type = transpile()
            typeSpecs.add(type)
            ClassName(type.packageName, type.name)
        }
        else -> null
    }

    fun SckemaType.JsonClass.transpile() = FileSpec.get(pkg, TypeSpec.classBuilder(name)
        .parameters(this.properties.mapNotNull { (key, value) -> value.transpile()?.let { key to it } })
        .build()
    )

    fun TypeSpec.Builder.parameters(parameters: List<Pair<String, TypeName>>) = apply {
        primaryConstructor(FunSpec.constructorBuilder().apply {
            parameters.forEach { (name, type) ->
                addParameter(ParameterSpec.builder(name, type).build())
                addProperty(PropertySpec.builder(name, type).initializer(name).build())
            }
        }.build())
    }
}