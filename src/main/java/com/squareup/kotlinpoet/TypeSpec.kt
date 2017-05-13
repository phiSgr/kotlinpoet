/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.kotlinpoet

import com.squareup.kotlinpoet.Util.hasDefaultModifier
import com.squareup.kotlinpoet.Util.requireExactlyOneOf
import java.io.IOException
import java.io.StringWriter
import java.lang.reflect.Type
import java.util.EnumSet
import java.util.Locale
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier
import kotlin.reflect.KClass

/** A generated class, interface, or enum declaration.  */
class TypeSpec private constructor(builder: TypeSpec.Builder) {
  val kind = builder.kind
  val name = builder.name
  val anonymousTypeArguments = builder.anonymousTypeArguments
  val kdoc = builder.kdoc.build()
  val annotations: List<AnnotationSpec> = Util.immutableList(builder.annotations)
  val modifiers: Set<Modifier> = Util.immutableSet(builder.modifiers)
  val typeVariables: List<TypeVariableName> = Util.immutableList(builder.typeVariables)
  val superclass = builder.superclass
  val superinterfaces: List<TypeName> = Util.immutableList(builder.superinterfaces)
  val enumConstants: Map<String, TypeSpec> = Util.immutableMap(builder.enumConstants)
  val propertySpecs: List<PropertySpec> = Util.immutableList(builder.propertySpecs)
  val staticBlock = builder.staticBlock.build()
  val initializerBlock = builder.initializerBlock.build()
  val funSpecs: List<FunSpec> = Util.immutableList(builder.funSpecs)
  val typeSpecs: List<TypeSpec> = Util.immutableList(builder.typeSpecs)
  val originatingElements: List<Element>

  init {
    val originatingElementsMutable = mutableListOf<Element>()
    originatingElementsMutable.addAll(builder.originatingElements)
    for (typeSpec in builder.typeSpecs) {
      originatingElementsMutable.addAll(typeSpec.originatingElements)
    }
    this.originatingElements = Util.immutableList(originatingElementsMutable)
  }

  fun hasModifier(modifier: Modifier) = modifiers.contains(modifier)

  fun toBuilder(): Builder {
    val builder = Builder(kind, name, anonymousTypeArguments)
    builder.kdoc.add(kdoc)
    builder.annotations.addAll(annotations)
    builder.modifiers.addAll(modifiers)
    builder.typeVariables.addAll(typeVariables)
    builder.superclass = superclass
    builder.superinterfaces.addAll(superinterfaces)
    builder.enumConstants.putAll(enumConstants)
    builder.propertySpecs.addAll(propertySpecs)
    builder.funSpecs.addAll(funSpecs)
    builder.typeSpecs.addAll(typeSpecs)
    builder.initializerBlock.add(initializerBlock)
    builder.staticBlock.add(staticBlock)
    return builder
  }

  @Throws(IOException::class)
  internal fun emit(codeWriter: CodeWriter, enumName: String?, implicitModifiers: Set<Modifier>) {
    // Nested classes interrupt wrapped line indentation. Stash the current wrapping state and put
    // it back afterwards when this type is complete.
    val previousStatementLine = codeWriter.statementLine
    codeWriter.statementLine = -1

    try {
      if (enumName != null) {
        codeWriter.emitKdoc(kdoc)
        codeWriter.emitAnnotations(annotations, false)
        codeWriter.emit("%L", enumName)
        if (!anonymousTypeArguments!!.formatParts.isEmpty()) {
          codeWriter.emit("(")
          codeWriter.emit(anonymousTypeArguments)
          codeWriter.emit(")")
        }
        if (propertySpecs.isEmpty() && funSpecs.isEmpty() && typeSpecs.isEmpty()) {
          return  // Avoid unnecessary braces "{}".
        }
        codeWriter.emit(" {\n")
      } else if (anonymousTypeArguments != null) {
        val supertype = if (!superinterfaces.isEmpty()) superinterfaces[0] else superclass
        codeWriter.emit("new %T(", supertype)
        codeWriter.emit(anonymousTypeArguments)
        codeWriter.emit(") {\n")
      } else {
        codeWriter.emitKdoc(kdoc)
        codeWriter.emitAnnotations(annotations, false)
        codeWriter.emitModifiers(modifiers, implicitModifiers + kind.asMemberModifiers)
        if (kind == Kind.ANNOTATION) {
          codeWriter.emit("%L %L", "@interface", name)
        } else {
          codeWriter.emit("%L %L", kind.name.toLowerCase(Locale.US), name)
        }
        codeWriter.emitTypeVariables(typeVariables)

        val extendsTypes: List<TypeName>
        val implementsTypes: List<TypeName>
        if (kind == Kind.INTERFACE) {
          extendsTypes = superinterfaces
          implementsTypes = emptyList()
        } else {
          extendsTypes = if (superclass == ANY) emptyList() else listOf(superclass)
          implementsTypes = superinterfaces
        }

        if (!extendsTypes.isEmpty()) {
          codeWriter.emit(" extends")
          var firstType = true
          for (type in extendsTypes) {
            if (!firstType) codeWriter.emit(",")
            codeWriter.emit(" %T", type)
            firstType = false
          }
        }

        if (!implementsTypes.isEmpty()) {
          codeWriter.emit(" implements")
          var firstType = true
          for (type in implementsTypes) {
            if (!firstType) codeWriter.emit(",")
            codeWriter.emit(" %T", type)
            firstType = false
          }
        }

        codeWriter.emit(" {\n")
      }

      codeWriter.pushType(this)
      codeWriter.indent()
      var firstMember = true
      val i = enumConstants.entries.iterator()
      while (i.hasNext()) {
        val enumConstant = i.next()
        if (!firstMember) codeWriter.emit("\n")
        enumConstant.value
            .emit(codeWriter, enumConstant.key, emptySet<Modifier>())
        firstMember = false
        if (i.hasNext()) {
          codeWriter.emit(",\n")
        } else if (!propertySpecs.isEmpty() || !funSpecs.isEmpty() || !typeSpecs.isEmpty()) {
          codeWriter.emit(";\n")
        } else {
          codeWriter.emit("\n")
        }
      }

      // Static properties.
      for (propertySpec in propertySpecs) {
        if (!propertySpec.hasModifier(Modifier.STATIC)) continue
        if (!firstMember) codeWriter.emit("\n")
        propertySpec.emit(codeWriter, kind.implicitPropertyModifiers)
        firstMember = false
      }

      if (!staticBlock.isEmpty()) {
        if (!firstMember) codeWriter.emit("\n")
        codeWriter.emit(staticBlock)
        firstMember = false
      }

      // Non-static properties.
      for (propertySpec in propertySpecs) {
        if (propertySpec.hasModifier(Modifier.STATIC)) continue
        if (!firstMember) codeWriter.emit("\n")
        propertySpec.emit(codeWriter, kind.implicitPropertyModifiers)
        firstMember = false
      }

      // Initializer block.
      if (!initializerBlock.isEmpty()) {
        if (!firstMember) codeWriter.emit("\n")
        codeWriter.emit(initializerBlock)
        firstMember = false
      }

      // Constructors.
      for (funSpec in funSpecs) {
        if (!funSpec.isConstructor) continue
        if (!firstMember) codeWriter.emit("\n")
        funSpec.emit(codeWriter, name!!, kind.implicitFunctionModifiers)
        firstMember = false
      }

      // Functions (static and non-static).
      for (funSpec in funSpecs) {
        if (funSpec.isConstructor) continue
        if (!firstMember) codeWriter.emit("\n")
        funSpec.emit(codeWriter, name, kind.implicitFunctionModifiers)
        firstMember = false
      }

      // Types.
      for (typeSpec in typeSpecs) {
        if (!firstMember) codeWriter.emit("\n")
        typeSpec.emit(codeWriter, null, kind.implicitTypeModifiers)
        firstMember = false
      }

      codeWriter.unindent()
      codeWriter.popType()

      codeWriter.emit("}")
      if (enumName == null && anonymousTypeArguments == null) {
        codeWriter.emit("\n") // If this type isn't also a value, include a trailing newline.
      }
    } finally {
      codeWriter.statementLine = previousStatementLine
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null) return false
    if (javaClass != other.javaClass) return false
    return toString() == other.toString()
  }

  override fun hashCode(): Int {
    return toString().hashCode()
  }

  override fun toString(): String {
    val out = StringWriter()
    try {
      val codeWriter = CodeWriter(out)
      emit(codeWriter, null, emptySet<Modifier>())
      return out.toString()
    } catch (e: IOException) {
      throw AssertionError()
    }
  }

  enum class Kind(
      internal val implicitPropertyModifiers: Set<Modifier>,
      internal val implicitFunctionModifiers: Set<Modifier>,
      internal val implicitTypeModifiers: Set<Modifier>,
      internal val asMemberModifiers: Set<Modifier>) {
    CLASS(
        emptySet<Modifier>(),
        emptySet<Modifier>(),
        emptySet<Modifier>(),
        emptySet<Modifier>()),

    INTERFACE(
        setOf(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL),
        setOf(Modifier.PUBLIC, Modifier.ABSTRACT),
        setOf(Modifier.PUBLIC, Modifier.STATIC),
        setOf(Modifier.STATIC)),

    ENUM(
        emptySet<Modifier>(),
        emptySet<Modifier>(),
        emptySet<Modifier>(),
        setOf(Modifier.STATIC)),

    ANNOTATION(
        setOf(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL),
        setOf(Modifier.PUBLIC, Modifier.ABSTRACT),
        setOf(Modifier.PUBLIC, Modifier.STATIC),
        setOf(Modifier.STATIC))
  }

  class Builder internal constructor(
      internal val kind: Kind,
      internal val name: String?,
      internal val anonymousTypeArguments: CodeBlock?) {
    internal val kdoc = CodeBlock.builder()
    internal val annotations = mutableListOf<AnnotationSpec>()
    internal val modifiers = mutableListOf<Modifier>()
    internal val typeVariables = mutableListOf<TypeVariableName>()
    internal var superclass: TypeName = ANY
    internal val superinterfaces = mutableListOf<TypeName>()
    internal val enumConstants = mutableMapOf<String, TypeSpec>()
    internal val propertySpecs = mutableListOf<PropertySpec>()
    internal val staticBlock = CodeBlock.builder()
    internal val initializerBlock = CodeBlock.builder()
    internal val funSpecs = mutableListOf<FunSpec>()
    internal val typeSpecs = mutableListOf<TypeSpec>()
    internal val originatingElements = mutableListOf<Element>()

    init {
      require(name == null || SourceVersion.isName(name)) { "not a valid name: $name" }
    }

    fun addKdoc(format: String, vararg args: Any): Builder {
      kdoc.add(format, *args)
      return this
    }

    fun addKdoc(block: CodeBlock): Builder {
      kdoc.add(block)
      return this
    }

    fun addAnnotations(annotationSpecs: Iterable<AnnotationSpec>): Builder {
      for (annotationSpec in annotationSpecs) {
        this.annotations.add(annotationSpec)
      }
      return this
    }

    fun addAnnotation(annotationSpec: AnnotationSpec): Builder {
      this.annotations.add(annotationSpec)
      return this
    }

    fun addAnnotation(annotation: ClassName)
        = addAnnotation(AnnotationSpec.builder(annotation).build())

    fun addAnnotation(annotation: Class<*>) = addAnnotation(ClassName.get(annotation))

    fun addAnnotation(annotation: KClass<*>) = addAnnotation(ClassName.get(annotation))

    fun addModifiers(vararg modifiers: Modifier): Builder {
      check(anonymousTypeArguments == null) { "forbidden on anonymous types." }
      this.modifiers += modifiers
      return this
    }

    fun addTypeVariables(typeVariables: Iterable<TypeVariableName>): Builder {
      check(anonymousTypeArguments == null) { "forbidden on anonymous types." }
      for (typeVariable in typeVariables) {
        this.typeVariables.add(typeVariable)
      }
      return this
    }

    fun addTypeVariable(typeVariable: TypeVariableName): Builder {
      check(anonymousTypeArguments == null) { "forbidden on anonymous types." }
      typeVariables.add(typeVariable)
      return this
    }

    fun superclass(superclass: TypeName): Builder {
      check(kind == Kind.CLASS) { "only classes have super classes, not $kind" }
      check(this.superclass === ANY) { "superclass already set to ${this.superclass}" }
      this.superclass = superclass
      return this
    }

    fun superclass(superclass: Type): Builder {
      return superclass(TypeName.get(superclass))
    }

    fun addSuperinterfaces(superinterfaces: Iterable<TypeName>): Builder {
      for (superinterface in superinterfaces) {
        addSuperinterface(superinterface)
      }
      return this
    }

    fun addSuperinterface(superinterface: TypeName): Builder {
      this.superinterfaces.add(superinterface)
      return this
    }

    fun addSuperinterface(superinterface: Type)
        = addSuperinterface(TypeName.get(superinterface))

    fun addSuperinterface(superinterface: KClass<*>)
        = addSuperinterface(TypeName.get(superinterface))

    @JvmOverloads fun addEnumConstant(
        name: String,
        typeSpec: TypeSpec = anonymousClassBuilder("").build()): Builder {
      check(kind == Kind.ENUM) { "${this.name} is not enum" }
      require(typeSpec.anonymousTypeArguments != null) {
          "enum constants must have anonymous type arguments" }
      require(SourceVersion.isName(name)) { "not a valid enum constant: $name" }
      enumConstants.put(name, typeSpec)
      return this
    }

    fun addProperties(propertySpecs: Iterable<PropertySpec>): Builder {
      for (propertySpec in propertySpecs) {
        addProperty(propertySpec)
      }
      return this
    }

    fun addProperty(propertySpec: PropertySpec): Builder {
      if (kind == Kind.INTERFACE || kind == Kind.ANNOTATION) {
        requireExactlyOneOf(propertySpec.modifiers, Modifier.PUBLIC, Modifier.PRIVATE)
        val check = EnumSet.of(Modifier.STATIC, Modifier.FINAL)
        check(propertySpec.modifiers.containsAll(check)) {
          "$kind $name.${propertySpec.name} requires modifiers $check" }
      }
      propertySpecs.add(propertySpec)
      return this
    }

    fun addProperty(type: TypeName, name: String, vararg modifiers: Modifier)
        = addProperty(PropertySpec.builder(type, name, *modifiers).build())

    fun addProperty(type: Type, name: String, vararg modifiers: Modifier)
        = addProperty(TypeName.get(type), name, *modifiers)

    fun addProperty(type: KClass<*>, name: String, vararg modifiers: Modifier)
        = addProperty(TypeName.get(type), name, *modifiers)

    fun addStaticBlock(block: CodeBlock): Builder {
      staticBlock.beginControlFlow("static").add(block).endControlFlow()
      return this
    }

    fun addInitializerBlock(block: CodeBlock): Builder {
      if (kind != Kind.CLASS && kind != Kind.ENUM) {
        throw UnsupportedOperationException(kind.toString() + " can't have initializer blocks")
      }
      initializerBlock.add("{\n")
          .indent()
          .add(block)
          .unindent()
          .add("}\n")
      return this
    }

    fun addFunctions(funSpecs: Iterable<FunSpec>): Builder {
      for (funSpec in funSpecs) {
        addFun(funSpec)
      }
      return this
    }

    fun addFun(funSpec: FunSpec): Builder {
      if (kind == Kind.INTERFACE) {
        requireExactlyOneOf(funSpec.modifiers, Modifier.ABSTRACT, Modifier.STATIC, Util.DEFAULT)
        requireExactlyOneOf(funSpec.modifiers, Modifier.PUBLIC, Modifier.PRIVATE)
      } else if (kind == Kind.ANNOTATION) {
        check(funSpec.modifiers == kind.implicitFunctionModifiers) {
            "$kind $name.${funSpec.name} requires modifiers ${kind.implicitFunctionModifiers}" }
      }
      if (kind != Kind.ANNOTATION) {
        check(funSpec.defaultValue == null) {
          "$kind $name.${funSpec.name} cannot have a default value" }
      }
      if (kind != Kind.INTERFACE) {
        check(!hasDefaultModifier(funSpec.modifiers)) {
          "$kind $name.${funSpec.name} cannot be default" }
      }
      funSpecs.add(funSpec)
      return this
    }

    fun addTypes(typeSpecs: Iterable<TypeSpec>): Builder {
      for (typeSpec in typeSpecs) {
        addType(typeSpec)
      }
      return this
    }

    fun addType(typeSpec: TypeSpec): Builder {
      require(typeSpec.modifiers.containsAll(kind.implicitTypeModifiers)) {
          "$kind $name.${typeSpec.name} requires modifiers ${kind.implicitTypeModifiers}" }
      typeSpecs.add(typeSpec)
      return this
    }

    fun addOriginatingElement(originatingElement: Element): Builder {
      originatingElements.add(originatingElement)
      return this
    }

    fun build(): TypeSpec {
      require(kind != Kind.ENUM || !enumConstants.isEmpty()) {
          "at least one enum constant is required for $name" }

      val isAbstract = modifiers.contains(Modifier.ABSTRACT) || kind != Kind.CLASS
      for (funSpec in funSpecs) {
        require(isAbstract || !funSpec.hasModifier(Modifier.ABSTRACT)) {
            "non-abstract type $name cannot declare abstract function ${funSpec.name}" }
      }

      val superclassIsAny = superclass == ANY
      val interestingSupertypeCount = (if (superclassIsAny) 0 else 1) + superinterfaces.size
      require(anonymousTypeArguments == null || interestingSupertypeCount <= 1) {
          "anonymous type has too many supertypes" }

      return TypeSpec(this)
    }
  }

  companion object {
    @JvmStatic fun classBuilder(name: String) = Builder(Kind.CLASS, name, null)

    @JvmStatic fun classBuilder(className: ClassName) = classBuilder(className.simpleName())

    @JvmStatic fun interfaceBuilder(name: String) = Builder(Kind.INTERFACE, name, null)

    @JvmStatic fun interfaceBuilder(className: ClassName) = interfaceBuilder(className.simpleName())

    @JvmStatic fun enumBuilder(name: String) = Builder(Kind.ENUM, name, null)

    @JvmStatic fun enumBuilder(className: ClassName) = enumBuilder(className.simpleName())

    @JvmStatic fun anonymousClassBuilder(typeArgumentsFormat: String, vararg args: Any): Builder {
      return Builder(Kind.CLASS, null, CodeBlock.builder()
          .add(typeArgumentsFormat, *args)
          .build())
    }

    @JvmStatic fun annotationBuilder(name: String) = Builder(Kind.ANNOTATION, name, null)

    @JvmStatic fun annotationBuilder(className: ClassName)
        = annotationBuilder(className.simpleName())
  }
}