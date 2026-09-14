package io.dagger.codegen.introspection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The way into a client package: the fields a module contributes to core types, and the class they
 * are emitted on.
 *
 * <p>A contributed field has no class of its own — Java generates a type once, in one package, and
 * {@code Query} and {@code Binding} belong to core. Each one becomes a static method on the
 * module's root type, named after the field, taking the core receiver it was reached through as its
 * first argument. {@code Query}'s receiver is the session, so it stays implicit.
 *
 * <p>The root type is read off the schema, as the return type of the one {@code Query} field the
 * module owns. Deriving it from the module name instead would give {@code E2e} where the engine
 * says {@code E2E}.
 */
public final class ClientEntryPoint {

  private static final String QUERY = "Query";

  private final SchemaPartition client;
  private final Field entryField;

  public ClientEntryPoint(SchemaPartition client) {
    if (client.module() == null) {
      throw new IllegalArgumentException("an entry point needs a client partition, not core");
    }
    this.client = client;
    this.entryField = requireOneQueryField(client);
    String root = entryField.getTypeRef().getTypeName();
    if (!client.typeNames().contains(root)) {
      throw new IllegalArgumentException(
          String.format(
              "module %s is reached as the core type %s, which it does not own, so there is no"
                  + " class to put its entry points on: a module named after a core type collides"
                  + " with it. Rename the module, or alias the target.",
              client.module(), root));
    }
  }

  /** The module this enters. */
  public String module() {
    return client.module();
  }

  /** The {@code Query} field the module owns: how a caller constructs its root. */
  public Field entryField() {
    return entryField;
  }

  /** The GraphQL name of the root type the entry points are emitted on. */
  public String rootTypeName() {
    return entryField.getTypeRef().getTypeName();
  }

  /**
   * The module's fields on core types other than {@code Query}, by type name, in the partition's
   * order so the emitted entry points come out the same on every run.
   */
  public Map<String, List<Field>> shims() {
    Map<String, List<Field>> shims = new LinkedHashMap<>();
    client
        .extensions()
        .forEach(
            (typeName, fields) -> {
              if (!QUERY.equals(typeName)) {
                shims.put(typeName, fields);
              }
            });
    return shims;
  }

  private static Field requireOneQueryField(SchemaPartition client) {
    List<Field> fields = client.extensions().getOrDefault(QUERY, List.of());
    if (fields.size() != 1) {
      throw new IllegalArgumentException(
          String.format(
              "module %s owns %d fields on Query, expected exactly one; it owns the types %s and"
                  + " contributes the Query fields %s",
              client.module(),
              fields.size(),
              client.typeNames(),
              fields.stream().map(Field::getName).toList()));
    }
    return fields.get(0);
  }
}
