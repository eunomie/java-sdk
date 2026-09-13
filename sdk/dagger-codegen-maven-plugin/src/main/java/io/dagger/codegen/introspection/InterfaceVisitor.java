package io.dagger.codegen.introspection;

import com.palantir.javapoet.*;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import javax.lang.model.element.Modifier;

/**
 * Generates a Java interface and a concrete FooClient class for each GraphQL INTERFACE type. The
 * interface defines the contract, and FooClient provides a query-builder implementation for use
 * when loading from ID or returning from fields.
 */
class InterfaceVisitor extends AbstractVisitor {
  public InterfaceVisitor(
      Schema schema, TypeRegistry registry, Path targetDirectory, Charset encoding) {
    super(schema, registry, targetDirectory, encoding);
  }

  @Override
  void visit(Type type) throws IOException {
    // Generate the interface
    TypeSpec interfaceSpec = generateType(type);
    write(interfaceSpec);

    // Generate the client implementation class
    TypeSpec clientSpec = generateClientType(type);
    write(clientSpec);
  }

  @Override
  TypeSpec generateType(Type type) {
    TypeSpec.Builder interfaceBuilder =
        TypeSpec.interfaceBuilder(Helpers.formatName(type))
            .addJavadoc(Helpers.escapeJavadoc(type.getDescription()))
            .addModifiers(Modifier.PUBLIC);

    // An interface exposing an id field is IDAble like any object, so a value
    // typed by it (a Node argument, say) serializes by ID through the same
    // Arguments.Builder overloads.
    if (type.providesId()) {
      interfaceBuilder.addSuperinterface(
          ParameterizedTypeName.get(registry().runtime("IDAble"), registry().forType("ID")));
    }

    if (type.getFields() != null) {
      for (Field field : type.getFields()) {
        MethodSpec.Builder methodBuilder =
            MethodSpec.methodBuilder(Helpers.formatName(field))
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);

        TypeName returnType = resolveReturnType(field);
        // A field coerced to Optional because another type binds it to a nullable declaration is
        // wrapped the same way as a directly nullable one — including the widening, since an
        // implementation may still narrow the element type and Optional is invariant.
        if (isNullableObject(field) || requiresOptionalObjectField(field)) {
          if (field.getTypeRef().isInterface()) {
            returnType = WildcardTypeName.subtypeOf(returnType);
          }
          returnType = ParameterizedTypeName.get(ClassName.get(Optional.class), returnType);
        }
        methodBuilder.returns(returnType);

        // Add parameters for required args
        for (InputObject arg : field.getRequiredArgs()) {
          TypeName argType = resolveArgType(arg);
          methodBuilder.addParameter(
              ParameterSpec.builder(argType, Helpers.formatName(arg))
                  .addJavadoc(Helpers.escapeJavadoc(arg.getDescription()) + "\n")
                  .build());
        }

        // Add optional args parameter if needed
        if (field.hasOptionalArgs()) {
          // We don't add optional args overload to interfaces for simplicity
        }

        methodBuilder.addJavadoc(Helpers.escapeJavadoc(field.getDescription()));

        // Add exceptions for leaf/scalar/list fields
        if (needsExceptions(field)) {
          methodBuilder
              .addException(InterruptedException.class)
              .addException(ExecutionException.class)
              .addException(registry().runtime("exception", "DaggerQueryException"));
        }

        if (field.isDeprecated()) {
          methodBuilder.addAnnotation(Deprecated.class);
        }

        interfaceBuilder.addMethod(methodBuilder.build());
      }
    }

    return interfaceBuilder.build();
  }

  /** Generates the FooClient class that implements the Foo interface via query building. */
  TypeSpec generateClientType(Type type) {
    String clientName = Helpers.formatName(type) + "Client";
    ClassName interfaceName = registry().forType(type.getName());

    TypeSpec.Builder classBuilder =
        TypeSpec.classBuilder(clientName)
            .addJavadoc("Query-builder client implementation of {@link $T}.\n", interfaceName)
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(interfaceName)
            .addField(
                FieldSpec.builder(
                        registry().runtime("QueryBuilder"), "queryBuilder", Modifier.PRIVATE)
                    .build());

    // Constructor
    MethodSpec constructor =
        MethodSpec.constructorBuilder()
            .addParameter(registry().runtime("QueryBuilder"), "queryBuilder")
            .addCode("this.queryBuilder = queryBuilder;")
            .build();
    classBuilder.addMethod(constructor);

    if (type.getFields() != null) {
      for (Field field : type.getFields()) {
        buildFieldMethod(classBuilder, field, false);
      }
    }

    return classBuilder.build();
  }

  private void buildFieldMethod(
      TypeSpec.Builder classBuilder, Field field, boolean withOptionalArgs) {
    MethodSpec.Builder fieldMethodBuilder =
        MethodSpec.methodBuilder(Helpers.formatName(field))
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class);

    TypeName returnType = resolveReturnType(field);
    TypeName objectReturnType = returnType;
    boolean nullableObject = isNullableObject(field);
    boolean presentObject = !nullableObject && requiresOptionalObjectField(field);
    if (nullableObject || presentObject) {
      returnType = ParameterizedTypeName.get(ClassName.get(Optional.class), returnType);
    }
    fieldMethodBuilder.returns(returnType);

    List<ParameterSpec> mandatoryParams =
        field.getRequiredArgs().stream()
            .map(
                arg ->
                    ParameterSpec.builder(resolveArgType(arg), Helpers.formatName(arg))
                        .addJavadoc(Helpers.escapeJavadoc(arg.getDescription()) + "\n")
                        .build())
            .toList();
    fieldMethodBuilder.addParameters(mandatoryParams);
    fieldMethodBuilder.addJavadoc(Helpers.escapeJavadoc(field.getDescription()));

    // Build the query
    if (field.hasArgs()) {
      fieldMethodBuilder.addStatement("Arguments.Builder builder = Arguments.newBuilder()");
    }
    field
        .getRequiredArgs()
        .forEach(
            arg ->
                fieldMethodBuilder.addStatement(
                    "builder.add($1S, $2L)", arg.getName(), Helpers.formatName(arg)));
    if (field.hasArgs()) {
      fieldMethodBuilder.addStatement("Arguments fieldArgs = builder.build()");
    }
    if (field.hasArgs()) {
      fieldMethodBuilder.addStatement(
          "QueryBuilder nextQueryBuilder = this.queryBuilder.chain($S, fieldArgs)",
          field.getName());
    } else {
      fieldMethodBuilder.addStatement(
          "QueryBuilder nextQueryBuilder = this.queryBuilder.chain($S)", field.getName());
    }

    if (field.getTypeRef().isListOfObject()) {
      String objName = field.getTypeRef().getListElementType().getName();
      ClassName clientClass =
          field.getTypeRef().getListElementType().isInterface()
              ? registry().forInterfaceClient(objName)
              : registry().forType(objName);
      fieldMethodBuilder.addStatement(
          "nextQueryBuilder = nextQueryBuilder.chain(List.of($S))", "id");
      fieldMethodBuilder.addStatement(
          "List<QueryBuilder> builders = nextQueryBuilder.executeObjectListQuery($S)", objName);
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
      fieldMethodBuilder.addStatement("return this");
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
          "QueryBuilder objectQueryBuilder = nextQueryBuilder.executeNullableObjectQuery($S)",
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
    }

    classBuilder.addMethod(fieldMethodBuilder.build());
  }

  private TypeName resolveReturnType(Field field) {
    if ("id".equals(field.getName())) {
      return field.getTypeRef().formatOutput(registry());
    }
    if (Helpers.isIdToConvert(field)) {
      // sync-like: return the parent object type
      return registry().forType(field.getParentObject().getName());
    }
    String expectedType = field.getExpectedType();
    return field.getTypeRef().formatInput(registry(), expectedType);
  }

  private TypeName resolveArgType(InputObject arg) {
    String expectedType = arg.getExpectedType();
    return arg.getType().formatInput(registry(), expectedType);
  }

  private boolean isNullableObject(Field field) {
    return getSchema().supportsNullableObjects()
        && field.getTypeRef().isOptional()
        && field.getTypeRef().isObjectOrInterface();
  }

  private boolean needsExceptions(Field field) {
    if (field.getTypeRef().isListOfObject() || field.getTypeRef().isList()) {
      return true;
    }
    if (Helpers.isIdToConvert(field)) {
      return true;
    }
    if (field.getTypeRef().isObjectOrInterface()) {
      // A field coerced to Optional because an implemented interface declares it nullable stays
      // lazy: it cannot be absent, so nothing is resolved and nothing can fail.
      return isNullableObject(field);
    }
    return true; // scalar fields need exceptions
  }
}
