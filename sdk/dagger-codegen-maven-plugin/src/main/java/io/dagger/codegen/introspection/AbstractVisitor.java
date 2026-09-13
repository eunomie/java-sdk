package io.dagger.codegen.introspection;

import com.palantir.javapoet.TypeSpec;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

abstract class AbstractVisitor extends CodeWriter {

  private final Schema schema;
  private final TypeRegistry registry;

  public AbstractVisitor(
      Schema schema, TypeRegistry registry, Path targetDirectory, Charset encoding) {
    super(registry.targetPackage(), targetDirectory, encoding);
    this.schema = schema;
    this.registry = registry;
  }

  TypeRegistry registry() {
    return registry;
  }

  void visit(Type type) throws IOException {
    TypeSpec typeSpec = generateType(type);
    write(typeSpec);
  }

  public Schema getSchema() {
    return schema;
  }

  /**
   * Whether a non-null object field has to be generated as {@code Optional} anyway, because a type
   * that has to agree with it on this field declares it nullable.
   *
   * <p>GraphQL lets an implementation narrow a nullable field to non-null, and lets an object
   * implement several interfaces that disagree on that. Java has one return type per method, so
   * every type bound to the same declaration has to agree — otherwise the generated class cannot
   * satisfy its own {@code implements} clause.
   */
  boolean requiresOptionalObjectField(Field field) {
    if (!getSchema().supportsNullableObjects() || !field.getTypeRef().isObjectOrInterface()) {
      return false;
    }
    return overrideComponent(field.getParentObject(), field.getName()).stream()
        .map(related -> declaredField(related, field.getName()))
        .filter(Objects::nonNull)
        .anyMatch(
            declared ->
                declared.getTypeRef().isOptional() && declared.getTypeRef().isObjectOrInterface());
  }

  /**
   * Every type whose declaration of this field the given type's has to stay compatible with.
   *
   * <p>Implementing an interface only binds the two types for the fields that interface actually
   * declares. So the walk follows {@code implements} edges in either direction but never passes
   * through a type that says nothing about the field: types that merely share an ancestor impose
   * nothing on each other, and their nullability stays independent.
   */
  private Set<Type> overrideComponent(Type type, String fieldName) {
    Set<Type> component = new LinkedHashSet<>();
    Deque<Type> pending = new ArrayDeque<>(List.of(type));
    while (!pending.isEmpty()) {
      Type current = pending.poll();
      if (declaredField(current, fieldName) == null || !component.add(current)) {
        continue;
      }
      Stream.concat(
              current.getImplementedInterfaceNames().stream().map(this::typeNamed),
              getSchema().getTypes().stream()
                  .filter(
                      other -> other.getImplementedInterfaceNames().contains(current.getName())))
          .filter(Objects::nonNull)
          .forEach(pending::add);
    }
    return component;
  }

  private Field declaredField(Type type, String fieldName) {
    if (type.getFields() == null) {
      return null;
    }
    return type.getFields().stream()
        .filter(declared -> fieldName.equals(declared.getName()))
        .findFirst()
        .orElse(null);
  }

  private Type typeNamed(String name) {
    return getSchema().getTypes().stream()
        .filter(type -> name.equals(type.getName()))
        .findFirst()
        .orElse(null);
  }

  abstract TypeSpec generateType(Type type);
}
