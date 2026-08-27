package io.dagger.codegen.introspection;

import static org.apache.commons.lang3.StringUtils.capitalize;

import com.palantir.javapoet.*;
import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.json.bind.annotation.JsonbTypeSerializer;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.JsonbDeserializer;
import jakarta.json.stream.JsonParser;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.UnaryOperator;
import javax.lang.model.element.Modifier;

class ObjectVisitor extends AbstractVisitor {

  private final ClientEntryPoint entryPoint;

  public ObjectVisitor(
      Schema schema,
      TypeRegistry registry,
      ClientEntryPoint entryPoint,
      Path targetDirectory,
      Charset encoding) {
    super(schema, registry, targetDirectory, encoding);
    this.entryPoint = entryPoint;
  }

  /**
   * Who a generated field method chains from. An instance method chains from {@code
   * this.queryBuilder}; a static one takes its receiver as a first parameter and chains from that
   * receiver's builder, optionally after a preamble — the serve call of a module entry point.
   */
  private record Receiver(ClassName type, String name, String method, CodeBlock preamble) {
    static final Receiver THIS = new Receiver(null, null, null, null);

    boolean isStatic() {
      return type != null;
    }

    /**
     * A shim on a core type: static, and named after the schema field. The entry point is static
     * too but renames itself to {@code from}.
     */
    boolean isShim() {
      return isStatic() && method == null;
    }

    /**
     * Every shim is emitted into the module's one root class, so two receivers carrying the same
     * field name would name the same nested optional-arguments class.
     */
    String helperPrefix() {
      return isShim() ? type.simpleName() : "";
    }
  }

  @Override
  TypeSpec generateType(Type type) {
    ClassName thisType = registry().forType(type.getName());
    TypeSpec.Builder classBuilder =
        TypeSpec.classBuilder(Helpers.formatName(type))
            .addJavadoc(Helpers.escapeJavadoc(type.getDescription()))
            .addModifiers(Modifier.PUBLIC)
            .addField(
                FieldSpec.builder(
                        registry().runtime("QueryBuilder"), "queryBuilder", Modifier.PRIVATE)
                    .build());

    // Add implements for any interfaces this object implements
    for (String ifaceName : type.getImplementedInterfaceNames()) {
      classBuilder.addSuperinterface(registry().forType(ifaceName));
    }

    if ("Query".equals(type.getName())) {
      // loadObjectFromID: load any object by its ID using node(id:) + inline fragment
      classBuilder.addMethod(
          MethodSpec.methodBuilder("loadObjectFromID")
              .addModifiers(Modifier.PUBLIC)
              .addTypeVariable(TypeVariableName.get("T"))
              .returns(TypeVariableName.get("T"))
              .addParameter(
                  ParameterizedTypeName.get(ClassName.get(Class.class), TypeVariableName.get("T")),
                  "clazz")
              .addParameter(registry().forType("ID"), "id")
              .addJavadoc("Load any object by its ID using node(id:) with an inline fragment.\n")
              .beginControlFlow("try")
              .addStatement(
                  "$T qb = this.queryBuilder.chainNode(clazz.getSimpleName(), id)",
                  registry().runtime("QueryBuilder"))
              .addStatement(
                  "return clazz.getDeclaredConstructor($T.class).newInstance(qb)",
                  registry().runtime("QueryBuilder"))
              .nextControlFlow("catch (Exception e)")
              .addStatement("throw new RuntimeException(\"Failed to load object from ID\", e)")
              .endControlFlow()
              .build());
    } else {
      // Object constructor for JSON deserialization
      MethodSpec constructor =
          MethodSpec.constructorBuilder()
              .addModifiers(Modifier.PROTECTED)
              .addJavadoc("Empty constructor for JSON-B deserialization")
              .build();
      classBuilder.addMethod(constructor);

      // If Object has an "id" field, implement IDAble interface
      if (type.providesId()) {
        // With unified IDs, id() returns the ID scalar type
        classBuilder.addSuperinterface(
            ParameterizedTypeName.get(registry().runtime("IDAble"), registry().forType("ID")));
        classBuilder.addAnnotation(
            AnnotationSpec.builder(JsonbTypeSerializer.class)
                .addMember("value", "$T.class", registry().runtime("IDAbleSerializer"))
                .build());
        classBuilder.addAnnotation(
            AnnotationSpec.builder(JsonbTypeDeserializer.class)
                .addMember("value", "$T.class", thisType.nestedClass("Deserializer"))
                .build());
        classBuilder.addType(
            TypeSpec.classBuilder("Deserializer")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addSuperinterface(
                    ParameterizedTypeName.get(ClassName.get(JsonbDeserializer.class), thisType))
                .addMethod(
                    MethodSpec.methodBuilder("deserialize")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .returns(thisType)
                        .addParameter(JsonParser.class, "parser")
                        .addParameter(DeserializationContext.class, "ctx")
                        .addParameter(java.lang.reflect.Type.class, "type")
                        .addStatement(
                            "$T id = ctx.deserialize($T.class, parser)", String.class, String.class)
                        .addStatement(
                            "$T o = new $T($T.dag().nodeQueryBuilder($S, new $T(id)))",
                            thisType,
                            thisType,
                            registry().runtime("Dagger"),
                            type.getName(),
                            registry().forType("ID"))
                        .addStatement("return o")
                        .build())
                .build());
      }

      for (Field scalarField :
          type.getFields().stream().filter(f -> f.getTypeRef().isScalar()).toList()) {
        classBuilder.addField(
            scalarField.getTypeRef().formatOutput(registry()),
            Helpers.formatName(scalarField),
            Modifier.PRIVATE);
      }
    }

    // Object constructor for query building. Public: a generated client package builds core
    // types it returns, and a core type is loaded by ID from any package.
    MethodSpec constructor =
        MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(registry().runtime("QueryBuilder"), "queryBuilder")
            .addCode("this.queryBuilder = queryBuilder;")
            .build();
    classBuilder.addMethod(constructor);

    // The builder behind this object, for code that chains from it without being it: the serve
    // preamble of a module client, and the shims a module adds to core types.
    classBuilder.addMethod(
        MethodSpec.methodBuilder("queryBuilder")
            .addModifiers(Modifier.PUBLIC)
            .returns(registry().runtime("QueryBuilder"))
            .addJavadoc("The query builder this object chains from.\n")
            .addStatement("return this.queryBuilder")
            .build());

    for (Field field : type.getFields()) {
      if (field.hasOptionalArgs()) {
        buildFieldArgumentsHelpers(classBuilder, field, type, Receiver.THIS);
        buildFieldMethod(classBuilder, field, true);
      }

      buildFieldMethod(classBuilder, field, false);
    }

    if (entryPoint != null && type.getName().equals(entryPoint.rootTypeName())) {
      buildEntryPoint(classBuilder, type);
    }

    if (List.of("Container", "Directory").contains(type.getName())) {
      String argName = type.getName().toLowerCase() + "Func";
      classBuilder.addMethod(
          MethodSpec.methodBuilder("with")
              .addModifiers(Modifier.PUBLIC)
              .addParameter(
                  ParameterizedTypeName.get(ClassName.get(UnaryOperator.class), thisType), argName)
              .returns(thisType)
              .addStatement("return $L.apply(this)", argName)
              .build());
    }
    return classBuilder.build();
  }

  private TypeName resolveArgType(InputObject arg, Field field) {
    // For Query.node(id: ID!), keep as raw ID scalar type
    if ("Query".equals(field.getParentObject().getName()) && "id".equals(arg.getName())) {
      return arg.getType().formatOutput(registry());
    }
    String expectedType = arg.getExpectedType();
    return arg.getType().formatInput(registry(), expectedType);
  }

  private TypeName resolveReturnType(Field field) {
    if ("id".equals(field.getName())) {
      // id() field: with unified IDs, returns String
      return field.getTypeRef().formatOutput(registry());
    }
    if (Helpers.isIdToConvert(field)) {
      // sync-like fields: return the parent object type
      return registry().forType(field.getParentObject().getName());
    }
    String expectedType = field.getExpectedType();
    return field.getTypeRef().formatInput(registry(), expectedType);
  }

  /**
   * Core is reached like a module client but serves nothing: {@code Core.from(session)} wraps the
   * session's query builder, and {@code core(session)} is the alias for a static import.
   */
  private void buildCoreEntryPoint(TypeSpec.Builder classBuilder) {
    ClassName session = registry().runtime("Session");
    ClassName core = registry().forType("Query");
    MethodSpec from =
        MethodSpec.methodBuilder("from")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(session, "session")
            .returns(core)
            .addJavadoc("The core API over {@code session}.\n")
            .addStatement("return new $T(session.queryBuilder())", core)
            .build();
    classBuilder.addMethod(from);
    classBuilder.addMethod(
        MethodSpec.methodBuilder("core")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(session, "session")
            .returns(core)
            .addJavadoc("Alias for {@link #from}, for a static import: {@code core(dag())}.\n")
            .addStatement("return from(session)")
            .build());
  }

  /**
   * The entry point on a root type: for a module client, {@code from(Session, <constructor args>)}
   * serves the bound module and returns its root, the alias named after the module delegates to it
   * for a static import, and one static shim is emitted per field the module adds to a core type,
   * taking that core object as its first argument since Java has no extension methods; for core,
   * {@link #buildCoreEntryPoint}.
   */
  private void buildEntryPoint(TypeSpec.Builder classBuilder, Type type) {
    if (entryPoint.isCore()) {
      buildCoreEntryPoint(classBuilder);
      return;
    }
    ClientBinding binding = entryPoint.binding();
    CodeBlock serve =
        CodeBlock.of(
            "$T.ensureServed(root, $S, $S, $S, $S);\n",
            registry().runtime("ModuleBinding"),
            binding.module(),
            binding.kind(),
            binding.ref(),
            binding.pin());
    Field entry = entryPoint.entryField();
    Receiver dag = new Receiver(registry().runtime("Session"), "dag", "from", serve);
    if (entry.hasOptionalArgs()) {
      buildFieldArgumentsHelpers(classBuilder, entry, type, dag);
      classBuilder.addMethod(alias(buildFieldMethod(classBuilder, entry, true, dag), entry));
    }
    classBuilder.addMethod(alias(buildFieldMethod(classBuilder, entry, false, dag), entry));

    entryPoint
        .shims()
        .forEach(
            (typeName, fields) -> {
              ClassName coreType = registry().forType(typeName);
              String receiverName =
                  Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
              Receiver receiver = new Receiver(coreType, receiverName, null, serve);
              for (Field shim : fields) {
                if (shim.hasOptionalArgs()) {
                  buildFieldArgumentsHelpers(classBuilder, shim, type, receiver);
                  buildFieldMethod(classBuilder, shim, true, receiver);
                }
                buildFieldMethod(classBuilder, shim, false, receiver);
              }
            });
  }

  /** The static-import alias of {@code from}: same signature, named after the module. */
  private MethodSpec alias(MethodSpec from, Field entry) {
    String args =
        from.parameters().stream()
            .map(p -> p.name())
            .collect(java.util.stream.Collectors.joining(", "));
    return MethodSpec.methodBuilder(Helpers.formatName(entry))
        .addModifiers(from.modifiers())
        .returns(from.returnType())
        .addParameters(from.parameters())
        .addExceptions(from.exceptions())
        .addJavadoc(
            "Alias for {@link #from}, for a static import: {@code $L(dag())}.\n",
            Helpers.formatName(entry))
        .addStatement("return from($L)", args)
        .build();
  }

  private void buildFieldMethod(
      TypeSpec.Builder classBuilder, Field field, boolean withOptionalArgs) {
    buildFieldMethod(classBuilder, field, withOptionalArgs, Receiver.THIS);
  }

  private MethodSpec buildFieldMethod(
      TypeSpec.Builder classBuilder, Field field, boolean withOptionalArgs, Receiver receiver) {
    String methodName = receiver.method() != null ? receiver.method() : Helpers.formatName(field);
    MethodSpec.Builder fieldMethodBuilder =
        MethodSpec.methodBuilder(methodName).addModifiers(Modifier.PUBLIC);
    if (receiver.isStatic()) {
      fieldMethodBuilder.addModifiers(Modifier.STATIC);
      fieldMethodBuilder.addParameter(
          ParameterSpec.builder(receiver.type(), receiver.name())
              .addJavadoc("the $L to chain from\n", receiver.type().simpleName())
              .build());
    }
    TypeName returnType = resolveReturnType(field);
    TypeName objectReturnType = returnType;
    boolean nullableObject =
        getSchema().supportsNullableObjects()
            && field.getTypeRef().isOptional()
            && field.getTypeRef().isObjectOrInterface();
    // A non-null field still has to return Optional when an interface it shares an `implements`
    // relation with declares the field nullable, or the class does not satisfy its own `implements`
    // clause. It stays lazy: the value cannot be absent, so there is nothing to resolve.
    boolean presentObject = !nullableObject && requiresOptionalObjectField(field);
    if (nullableObject || presentObject) {
      returnType = ParameterizedTypeName.get(ClassName.get(Optional.class), returnType);
    }
    fieldMethodBuilder.returns(returnType);
    List<ParameterSpec> mandatoryParams =
        field.getRequiredArgs().stream()
            .map(
                arg ->
                    ParameterSpec.builder(resolveArgType(arg, field), Helpers.formatName(arg))
                        .addJavadoc(Helpers.escapeJavadoc(arg.getDescription()) + "\n")
                        .build())
            .toList();
    fieldMethodBuilder.addParameters(mandatoryParams);
    if (withOptionalArgs && field.hasOptionalArgs()) {
      fieldMethodBuilder.addParameter(
          ParameterSpec.builder(argumentsClass(field, receiver), "optArgs")
              .addJavadoc("$L optional arguments\n", Helpers.formatName(field))
              .build());
    }
    fieldMethodBuilder.addJavadoc(Helpers.escapeJavadoc(field.getDescription()));

    if (!receiver.isStatic()
        && field.getTypeRef().isScalar()
        && !Helpers.isIdToConvert(field)
        && !"Query".equals(field.getParentObject().getName())) {
      fieldMethodBuilder.beginControlFlow("if (this.$L != null)", Helpers.formatName(field));
      fieldMethodBuilder.addStatement("return $L", Helpers.formatName(field));
      fieldMethodBuilder.endControlFlow();
    }
    String builder = "this.queryBuilder";
    if (receiver.isStatic()) {
      builder = receiver.name() + ".queryBuilder()";
      if (receiver.preamble() != null) {
        // A shim's receiver is a core object mid-chain, so its builder carries a selection path
        // the serve query has no business continuing.
        fieldMethodBuilder.addStatement(
            "$T root = $L.queryBuilder().root()",
            registry().runtime("QueryBuilder"),
            receiver.name());
        fieldMethodBuilder.addCode(receiver.preamble());
      }
    }
    if (field.hasArgs()) {
      fieldMethodBuilder.addStatement(
          "$1T.Builder builder = $1T.newBuilder()", registry().runtime("Arguments"));
    }
    field
        .getRequiredArgs()
        .forEach(
            arg ->
                fieldMethodBuilder.addStatement(
                    "builder.add($1S, $2L)", arg.getName(), Helpers.formatName(arg)));
    if (field.hasArgs()) {
      fieldMethodBuilder.addStatement(
          "$T fieldArgs = builder.build()", registry().runtime("Arguments"));
    }
    if (withOptionalArgs && field.hasOptionalArgs()) {
      fieldMethodBuilder.addStatement("fieldArgs = fieldArgs.merge(optArgs.toArguments())");
    }
    if (field.hasArgs()) {
      fieldMethodBuilder.addStatement(
          "$T nextQueryBuilder = $L.chain($S, fieldArgs)",
          registry().runtime("QueryBuilder"),
          builder,
          field.getName());
    } else {
      fieldMethodBuilder.addStatement(
          "$T nextQueryBuilder = $L.chain($S)",
          registry().runtime("QueryBuilder"),
          builder,
          field.getName());
    }

    if (field.getTypeRef().isListOfObject()) {
      String objName = field.getTypeRef().getListElementType().getName();
      // For interface list elements, use the client class
      ClassName clientClass =
          field.getTypeRef().getListElementType().isInterface()
              ? registry().forInterfaceClient(objName)
              : registry().forType(objName);
      fieldMethodBuilder.addStatement(
          "nextQueryBuilder = nextQueryBuilder.chain(List.of($S))", "id");
      fieldMethodBuilder.addStatement(
          "List<$T> builders = nextQueryBuilder.executeObjectListQuery($S)",
          registry().runtime("QueryBuilder"),
          objName);
      fieldMethodBuilder.addStatement(
          "return builders.stream().map(qb -> new $T(qb)).toList()", clientClass);
      fieldMethodBuilder
          .addException(InterruptedException.class)
          .addException(ExecutionException.class)
          .addException(registry().runtime("exception", "DaggerQueryException"));
    } else if (field.getTypeRef().isList()) {
      fieldMethodBuilder.addStatement(
          "return nextQueryBuilder.executeListQuery($T.class)",
          field.getTypeRef().getListElementType().formatOutput(registry()));
      fieldMethodBuilder
          .addException(InterruptedException.class)
          .addException(ExecutionException.class)
          .addException(registry().runtime("exception", "DaggerQueryException"));
    } else if (Helpers.isIdToConvert(field)) {
      fieldMethodBuilder.addStatement("nextQueryBuilder.executeQuery()");
      fieldMethodBuilder.addStatement("return $L", receiver.isStatic() ? receiver.name() : "this");
      fieldMethodBuilder
          .addException(InterruptedException.class)
          .addException(ExecutionException.class)
          .addException(registry().runtime("exception", "DaggerQueryException"));
    } else if (nullableObject) {
      String graphqlTypeName = field.getTypeRef().getTypeName();
      TypeName clientClass =
          field.getTypeRef().isInterface()
              ? registry().forInterfaceClient(graphqlTypeName)
              : objectReturnType;
      fieldMethodBuilder.addStatement(
          "$T objectQueryBuilder = nextQueryBuilder.executeNullableObjectQuery($S)",
          registry().runtime("QueryBuilder"),
          graphqlTypeName);
      fieldMethodBuilder.addStatement(
          "return Optional.ofNullable(objectQueryBuilder).map(qb -> new $T(qb))", clientClass);
      fieldMethodBuilder
          .addException(InterruptedException.class)
          .addException(ExecutionException.class)
          .addException(registry().runtime("exception", "DaggerQueryException"));
    } else if (field.getTypeRef().isObjectOrInterface()) {
      // For interface return types, instantiate the client class
      CodeBlock instantiation =
          field.getTypeRef().isInterface()
              ? CodeBlock.of(
                  "new $T(nextQueryBuilder)",
                  registry().forInterfaceClient(field.getTypeRef().getTypeName()))
              : CodeBlock.of("new $T(nextQueryBuilder)", objectReturnType);
      if (presentObject) {
        fieldMethodBuilder.addStatement("return $T.of($L)", Optional.class, instantiation);
      } else {
        fieldMethodBuilder.addStatement("return $L", instantiation);
      }
    } else {
      fieldMethodBuilder.addStatement("return nextQueryBuilder.executeQuery($T.class)", returnType);
      fieldMethodBuilder
          .addException(InterruptedException.class)
          .addException(ExecutionException.class)
          .addException(registry().runtime("exception", "DaggerQueryException"));
    }

    if (receiver.preamble() != null) {
      // The serve call in the preamble can fail even when the field itself is lazy.
      fieldMethodBuilder
          .addException(InterruptedException.class)
          .addException(ExecutionException.class)
          .addException(registry().runtime("exception", "DaggerQueryException"));
    }

    if (field.isDeprecated()) {
      fieldMethodBuilder.addAnnotation(Deprecated.class);
      fieldMethodBuilder.addJavadoc("@deprecated $L\n", field.getDeprecationReason());
    }

    MethodSpec method = fieldMethodBuilder.build();
    classBuilder.addMethod(method);
    return method;
  }

  /**
   * Builds the class containing the optional arguments.
   *
   * @param classBuilder
   * @param field
   * @param type
   */
  private void buildFieldArgumentsHelpers(
      TypeSpec.Builder classBuilder, Field field, Type type, Receiver receiver) {
    ClassName fieldArgumentsClassName = argumentsClass(field, receiver);

    /* Inner class XXXArguments */
    TypeSpec.Builder fieldArgumentsClassBuilder =
        TypeSpec.classBuilder(fieldArgumentsClassName.simpleName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC);
    List<FieldSpec> optionalArgFields =
        field.getOptionalArgs().stream()
            .map(
                arg ->
                    FieldSpec.builder(
                            resolveArgType(arg, field), Helpers.formatName(arg), Modifier.PRIVATE)
                        .build())
            .toList();
    fieldArgumentsClassBuilder.addFields(optionalArgFields);

    List<MethodSpec> optionalArgFieldWithMethods =
        field.getOptionalArgs().stream()
            .map(
                arg ->
                    Helpers.withSetter(
                        arg,
                        resolveArgType(arg, field),
                        fieldArgumentsClassName,
                        arg.getDescription()))
            .toList();
    fieldArgumentsClassBuilder.addMethods(optionalArgFieldWithMethods);

    List<CodeBlock> blocks =
        field.getOptionalArgs().stream()
            .map(
                arg ->
                    CodeBlock.builder()
                        .beginControlFlow("if ($1L != null)", Helpers.formatName(arg))
                        .addStatement(
                            "builder.add($1S, this.$2L)", arg.getName(), Helpers.formatName(arg))
                        .endControlFlow()
                        .build())
            .toList();
    MethodSpec toArguments =
        MethodSpec.methodBuilder("toArguments")
            .returns(registry().runtime("Arguments"))
            .addStatement("$1T.Builder builder = $1T.newBuilder()", registry().runtime("Arguments"))
            .addCode(CodeBlock.join(blocks, "\n"))
            .addStatement("\nreturn builder.build()")
            .build();
    fieldArgumentsClassBuilder.addMethod(toArguments);
    fieldArgumentsClassBuilder.addJavadoc(
        "Optional arguments for {@link $L#$L}\n\n",
        registry().forType(type.getName()).simpleName(),
        Helpers.formatName(field));
    classBuilder.addType(fieldArgumentsClassBuilder.build());
  }

  /** The nested class holding a field's optional arguments, as the enclosing class names it. */
  private ClassName argumentsClass(Field field, Receiver receiver) {
    return ClassName.bestGuess(
        receiver.helperPrefix() + capitalize(Helpers.formatName(field)) + "Arguments");
  }
}
