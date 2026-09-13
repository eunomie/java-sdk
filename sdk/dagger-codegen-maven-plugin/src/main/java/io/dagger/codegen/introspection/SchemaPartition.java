package io.dagger.codegen.introspection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One generated package's worth of a schema.
 *
 * <p>The engine marks every type and field a module contributes with {@code @sourceMap(module:)};
 * core carries no mark. Java has no partial classes, so a type is generated once, in one package,
 * and the split follows from that:
 *
 * <ul>
 *   <li>{@link #core}: every unmarked type, with every module-contributed field removed. Core is
 *       then the same whichever targets a scope happens to have, and nothing in it names a client
 *       package.
 *   <li>{@link #client}: every type one module owns, plus that module's fields on core types — its
 *       {@link #extensions() extensions}, {@code Query.hello} and {@code Binding.asHello}, which
 *       have no class of their own and are emitted as static entry points on the module's root
 *       type. A client package is what a caller needs and nothing else.
 * </ul>
 *
 * <p>The whole schema stays reachable through {@link #schema()} for lookups. Only what is emitted
 * is narrowed.
 */
public final class SchemaPartition {

  private final Schema schema;
  private final String module;
  private final List<Type> types;
  private final Map<String, List<Field>> extensions;

  private SchemaPartition(
      Schema schema, String module, List<Type> types, Map<String, List<Field>> extensions) {
    this.schema = schema;
    this.module = module;
    this.types = types;
    this.extensions = extensions;
  }

  /** The unmarked part of a schema, with every module-contributed field stripped from it. */
  public static SchemaPartition core(Schema schema) {
    List<Type> types =
        emittable(schema)
            .filter(type -> type.getOwningModule() == null)
            .map(type -> type.withFields(fieldsOwnedBy(type, null)))
            .toList();
    return new SchemaPartition(schema, null, types, Map.of());
  }

  /**
   * The part of a schema {@code module} owns.
   *
   * <p>An empty result is refused. The engine returns core alone for a module source whose SDK does
   * not resolve as a runtime, so a partition with nothing in it means the target cannot be bound,
   * not that it is empty.
   */
  public static SchemaPartition client(Schema schema, String module) {
    Objects.requireNonNull(module, "module");
    List<Type> types =
        emittable(schema).filter(type -> module.equals(type.getOwningModule())).toList();
    Map<String, List<Field>> extensions = new LinkedHashMap<>();
    emittable(schema)
        .filter(type -> type.getOwningModule() == null)
        .forEach(
            type -> {
              List<Field> owned = fieldsOwnedBy(type, module);
              if (owned != null && !owned.isEmpty()) {
                extensions.put(type.getName(), owned);
              }
            });
    if (types.isEmpty() && extensions.isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "the schema holds nothing owned by module %s; it owns %s."
                  + " A module source whose SDK is not a runtime yields core alone.",
              module, ownedModules(schema)));
    }
    // Not Map.copyOf: the entry points come out in this order, and an unordered copy would
    // reshuffle them from one run to the next.
    return new SchemaPartition(schema, module, types, Collections.unmodifiableMap(extensions));
  }

  /** The schema this partition was cut from, whole, for type lookups. */
  public Schema schema() {
    return schema;
  }

  /** The module this partition emits for, or null for core. */
  public String module() {
    return module;
  }

  /** The types this partition emits, in schema order. */
  public List<Type> types() {
    return types;
  }

  /** The names of the types this partition emits, in schema order. */
  public List<String> typeNames() {
    return types.stream().map(Type::getName).toList();
  }

  /** Core types carrying fields this partition's module contributes, by type name. */
  public Map<String, List<Field>> extensions() {
    return extensions;
  }

  /** Every module named by a {@code @sourceMap} mark anywhere in the schema, in schema order. */
  public static List<String> ownedModules(Schema schema) {
    return Stream.concat(
            emittable(schema).map(Type::getOwningModule),
            emittable(schema)
                .flatMap(type -> type.getFields() == null ? Stream.of() : type.getFields().stream())
                .map(Field::getOwningModule))
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  /**
   * Walk what this partition emits, in the order the generator needs.
   *
   * <p>The emissions that are not per-type — the version constant and the JSON converter — belong
   * to core alone. Emitted into a module's package too, they would be a second copy of a class core
   * already has.
   */
  public void visit(SchemaVisitor visitor) {
    types.stream()
        .filter(t -> t.getKind() == TypeKind.SCALAR)
        .filter(t -> !Schema.BUILTIN_SCALARS.contains(t.getName()))
        .forEach(visitor::visitScalar);
    types.stream().filter(t -> t.getKind() == TypeKind.INPUT_OBJECT).forEach(visitor::visitInput);
    types.stream().filter(t -> t.getKind() == TypeKind.INTERFACE).forEach(visitor::visitInterface);
    types.stream().filter(t -> t.getKind() == TypeKind.OBJECT).forEach(visitor::visitObject);
    types.stream().filter(t -> t.getKind() == TypeKind.ENUM).forEach(visitor::visitEnum);
    if (module == null) {
      visitor.visitVersion(schema.getVersion());
      visitor.visitIDAbles(
          types.stream().filter(t -> t.getKind() == TypeKind.OBJECT && t.providesId()).toList());
    }
  }

  private static Stream<Type> emittable(Schema schema) {
    return schema.getTypes().stream().filter(t -> !t.getName().startsWith("_"));
  }

  private static List<Field> fieldsOwnedBy(Type type, String module) {
    if (type.getFields() == null) {
      return null;
    }
    return type.getFields().stream()
        .filter(f -> Objects.equals(module, f.getOwningModule()))
        .toList();
  }
}
