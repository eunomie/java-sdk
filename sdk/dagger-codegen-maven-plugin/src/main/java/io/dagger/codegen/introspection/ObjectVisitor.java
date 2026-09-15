package io.dagger.codegen.introspection;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

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

  /** The constant each entry point serves before it selects anything. */
  private static final String TARGET = "TARGET";

  /** What the two forms of the core entry say, which no schema field describes. */
  private static final String CORE_JAVADOC = "The core API.\n";

  private final ClientEntryPoint entryPoint;
  private final ModuleTargetRef source;

  public ObjectVisitor(
      Schema schema,
      TypeRegistry registry,
      ClientEntryPoint entryPoint,
      ModuleTargetRef source,
      Path targetDirectory,
      Charset encoding) {
    super(schema, registry, targetDirectory, encoding);
    this.entryPoint = entryPoint;
    this.source = source;
  }

  /**
   * A field a module contributes to a core type, emitted as a static method on the module's root
   * type because the core class it belongs to is in another package and Java has no partial
   * classes.
   *
   * @param receiverType the core type the field was reached through, or null on {@code Query},
   *     whose receiver is the session and is therefore named rather than passed
   */
  private record Entry(String module, ClassName receiverType, String receiverName) {

    boolean onQuery() {
      return receiverType == null;
    }

    /** Every entry lands in one class, so two fields of the same name need telling apart. */
    String argumentsPrefix() {
      return onQuery() ? "" : receiverType.simpleName();
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
                  "QueryBuilder qb = this.queryBuilder.chainNode(clazz.getSimpleName(), id)")
              .addStatement(
                  "return clazz.getDeclaredConstructor(QueryBuilder.class).newInstance(qb)")
              .nextControlFlow("catch (Exception e)")
              .addStatement("throw new RuntimeException(\"Failed to load object from ID\", e)")
              .endControlFlow()
              .build());

      // nodeQueryBuilder: create a QueryBuilder for node(id:) + inline fragment
      classBuilder.addMethod(
          MethodSpec.methodBuilder("nodeQueryBuilder")
              .addModifiers(Modifier.PUBLIC)
              .returns(registry().runtime("QueryBuilder"))
              .addParameter(ClassName.get(String.class), "typeName")
              .addParameter(registry().forType("ID"), "id")
              .addJavadoc(
                  "Create a QueryBuilder for node(id:) scoped to the given type via an inline fragment.\n")
              .addStatement("return this.queryBuilder.chainNode(typeName, id)")
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
                            "$T o = new $T($T.dag().queryBuilder().chainNode($S, new $T(id)))",
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

    // Object constructor for query building
    MethodSpec constructor =
        MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(registry().runtime("QueryBuilder"), "queryBuilder")
            .addCode("this.queryBuilder = queryBuilder;")
            .build();
    classBuilder.addMethod(constructor);

    // A client package chains from a core object it did not generate: the session a module is
    // served into, and the receiver of every field the module contributes to a core type.
    classBuilder.addMethod(
        MethodSpec.methodBuilder("queryBuilder")
            .addModifiers(Modifier.PUBLIC)
            .returns(registry().runtime("QueryBuilder"))
            .addJavadoc("The query builder this object chains from.\n")
            .addStatement("return this.queryBuilder")
            .build());

    for (Field field : type.getFields()) {
      if (field.hasOptionalArgs()) {
        buildFieldArgumentsHelpers(classBuilder, field, type, null);
        buildFieldMethod(classBuilder, field, true, null);
      }

      buildFieldMethod(classBuilder, field, false, null);
    }

    if (entryPoint != null && entryPoint.rootTypeName().equals(type.getName())) {
      buildEntryPoints(classBuilder, type);
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

  /** The way into this generated package, which core and a module reach differently. */
  private void buildEntryPoints(TypeSpec.Builder classBuilder, Type type) {
    if (entryPoint instanceof ClientEntryPoint.Module module) {
      buildModuleEntryPoints(classBuilder, type, module);
    } else {
      buildCoreEntryPoint(classBuilder);
    }
  }

  /**
   * Core: the session is the receiver and there is no field to single out, so the entry wraps the
   * session's own builder rather than chaining anything onto it. Nothing is served either — core is
   * what a session already answers.
   */
  private void buildCoreEntryPoint(TypeSpec.Builder classBuilder) {
    ClassName core = registry().forType("Query");
    MethodSpec entry =
        MethodSpec.methodBuilder(entryPoint.entryName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(core)
            .addParameter(
                ParameterSpec.builder(registry().runtime("Session"), "dag")
                    .addJavadoc("the session to reach core in\n")
                    .build())
            .addJavadoc(CORE_JAVADOC)
            .addStatement("return new $T(dag.queryBuilder())", core)
            .build();
    classBuilder.addMethod(entry);
    classBuilder.addMethod(ambient(entry, CORE_JAVADOC));
  }

  /**
   * A module: the {@code Query} field it owns, and every field it contributes to another core type.
   */
  private void buildModuleEntryPoints(
      TypeSpec.Builder classBuilder, Type type, ClientEntryPoint.Module module) {
    if (source != null) {
      classBuilder.addField(targetConstant(module));
    }
    Entry onQuery = new Entry(module.module(), null, null);
    buildEntry(classBuilder, module.entryField(), type, onQuery);
    module
        .shims()
        .forEach(
            (typeName, fields) -> {
              ClassName receiverType = registry().forType(typeName);
              Entry shim = new Entry(module.module(), receiverType, uncapitalize(typeName));
              fields.forEach(field -> buildEntry(classBuilder, field, type, shim));
            });
  }

  /**
   * One entry, and — on {@code Query} alone — a second form over the session {@code Dagger.dag()}
   * holds. A field on any other core type takes that type as its receiver and reads the session off
   * it, so there is no session left for a caller to choose.
   */
  private void buildEntry(TypeSpec.Builder classBuilder, Field field, Type type, Entry entry) {
    if (field.hasOptionalArgs()) {
      buildFieldArgumentsHelpers(classBuilder, field, type, entry);
      MethodSpec withOptArgs = buildFieldMethod(classBuilder, field, true, entry);
      if (entry.onQuery()) {
        classBuilder.addMethod(ambient(withOptArgs, Helpers.escapeJavadoc(field.getDescription())));
      }
    }
    MethodSpec method = buildFieldMethod(classBuilder, field, false, entry);
    if (entry.onQuery()) {
      classBuilder.addMethod(ambient(method, Helpers.escapeJavadoc(field.getDescription())));
    }
  }

  /**
   * Where this client's module lives, held by the package that talks to it. A client that carries
   * one loads its own module; one generated against a module the engine serves already carries
   * none, and the entry points below ask for nothing.
   */
  private FieldSpec targetConstant(ClientEntryPoint.Module module) {
    ClassName target = registry().runtime("ModuleTarget");
    CodeBlock initializer;
    if (source instanceof ModuleTargetRef.InWorkspace workspace) {
      initializer =
          CodeBlock.of("$T.inWorkspace($S, $S)", target, module.module(), workspace.path());
    } else if (source instanceof ModuleTargetRef.AtGitRef git) {
      initializer =
          CodeBlock.of("$T.atGitRef($S, $S, $S)", target, module.module(), git.ref(), git.pin());
    } else {
      throw new IllegalStateException("no way to reach the module target " + source);
    }
    return FieldSpec.builder(target, TARGET, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
        .initializer(initializer)
        .build();
  }

  /**
   * The same entry over the ambient session, so a caller that never named one still has a way in.
   */
  private MethodSpec ambient(MethodSpec entry, String javadoc) {
    List<ParameterSpec> withoutSession = entry.parameters().subList(1, entry.parameters().size());
    CodeBlock.Builder call =
        CodeBlock.builder().add("return $L($T.dag()", entry.name(), registry().runtime("Dagger"));
    withoutSession.forEach(parameter -> call.add(", $L", parameter.name()));
    call.add(")");
    return MethodSpec.methodBuilder(entry.name())
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .addAnnotations(entry.annotations())
        .returns(entry.returnType())
        .addParameters(withoutSession)
        .addExceptions(entry.exceptions())
        .addJavadoc(javadoc)
        .addJavadoc("\n@see $T#dag()\n", registry().runtime("Dagger"))
        .addStatement(call.build())
        .build();
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

  private MethodSpec buildFieldMethod(
      TypeSpec.Builder classBuilder, Field field, boolean withOptionalArgs, Entry entry) {
    MethodSpec.Builder fieldMethodBuilder =
        MethodSpec.methodBuilder(Helpers.formatName(field)).addModifiers(Modifier.PUBLIC);
    if (entry != null) {
      fieldMethodBuilder.addModifiers(Modifier.STATIC);
      if (entry.onQuery()) {
        fieldMethodBuilder.addParameter(
            ParameterSpec.builder(registry().runtime("Session"), "dag")
                .addJavadoc("the session to reach the target in\n")
                .build());
      } else {
        fieldMethodBuilder.addParameter(
            ParameterSpec.builder(entry.receiverType(), entry.receiverName())
                .addJavadoc("the $L to chain from\n", entry.receiverType().simpleName())
                .build());
      }
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
          ParameterSpec.builder(argumentsClass(field, entry), "optArgs")
              .addJavadoc("$L optional arguments\n", Helpers.formatName(field))
              .build());
    }
    fieldMethodBuilder.addJavadoc(Helpers.escapeJavadoc(field.getDescription()));

    String chainFrom = "this.queryBuilder";
    if (entry != null) {
      // The field this method selects does not exist in the session until the module is served,
      // so a client that carries a descriptor serves before it builds anything.
      if (entry.onQuery()) {
        if (source != null) {
          fieldMethodBuilder.addStatement(
              "$T.serve(dag.queryBuilder(), $L)", registry().runtime("ModuleTargets"), TARGET);
        }
        chainFrom = "dag.queryBuilder()";
      } else {
        // The receiver names the session this has to serve into, and its builder is mid-chain:
        // serving off the session Dagger.dag() holds would land the module beside the query the
        // caller is building rather than in it.
        if (source != null) {
          fieldMethodBuilder.addStatement(
              "$T.serve(new $T($L.queryBuilder().client()), $L)",
              registry().runtime("ModuleTargets"),
              registry().runtime("QueryBuilder"),
              entry.receiverName(),
              TARGET);
        }
        chainFrom = entry.receiverName() + ".queryBuilder()";
      }
    } else if (field.getTypeRef().isScalar()
        && !Helpers.isIdToConvert(field)
        && !"Query".equals(field.getParentObject().getName())) {
      fieldMethodBuilder.beginControlFlow("if (this.$L != null)", Helpers.formatName(field));
      fieldMethodBuilder.addStatement("return $L", Helpers.formatName(field));
      fieldMethodBuilder.endControlFlow();
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
          chainFrom,
          field.getName());
    } else {
      fieldMethodBuilder.addStatement(
          "$T nextQueryBuilder = $L.chain($S)",
          registry().runtime("QueryBuilder"),
          chainFrom,
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
      fieldMethodBuilder.addStatement(
          "return $L", entry == null ? "this" : entry.onQuery() ? "dag" : entry.receiverName());
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
      TypeSpec.Builder classBuilder, Field field, Type type, Entry entry) {
    ClassName fieldArgumentsClassName = argumentsClass(field, entry);

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
  private ClassName argumentsClass(Field field, Entry entry) {
    String prefix = entry == null ? "" : entry.argumentsPrefix();
    return ClassName.bestGuess(prefix + capitalize(Helpers.formatName(field)) + "Arguments");
  }
}
