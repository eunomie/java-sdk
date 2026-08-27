package io.dagger.codegen.introspection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a module client exposes beyond its types: the static {@code from(Session)} factory on its
 * root type, the static-import alias named after the module, and one static shim per field the
 * module adds to a core type other than {@code Query}.
 *
 * <p>Everything here is read off the schema. The root type is the return type of the one {@code
 * Query} field the module owns; deriving it from the module name instead would give {@code E2e}
 * where the engine says {@code E2E}.
 */
public record ClientEntryPoint(SchemaPartition client, ClientBinding binding) {

  /** The core entry point: {@code core(session)}, over the core partition, serving nothing. */
  public static ClientEntryPoint core(SchemaPartition core) {
    if (core.module() != null) {
      throw new IllegalArgumentException("core entry point needs the core partition");
    }
    return new ClientEntryPoint(core, null);
  }

  public ClientEntryPoint {
    if (binding == null) {
      // core enters on the Query root itself, so it has no module, no binding and no owned field
      if (client.module() != null) {
        throw new IllegalArgumentException("core entry point needs the core partition");
      }
    } else {
      if (client.module() == null) {
        throw new IllegalArgumentException("an entry point needs a client partition, not core");
      }
      if (!client.module().equals(binding.module())) {
        throw new IllegalArgumentException(
            String.format(
                "binding is for module %s but the partition is for %s",
                binding.module(), client.module()));
      }
      String root = entryField(client).getTypeRef().getTypeName();
      if (client.types().stream().noneMatch(type -> root.equals(type.getName()))) {
        throw new IllegalArgumentException(
            String.format(
                "module %s enters on the core type %s, which it does not own, so there is no client"
                    + " to generate: a module named after a core type collides with it. Rename the"
                    + " module, or alias the dependency.",
                client.module(), root));
      }
    }
  }

  /** Whether this is the core entry point (no bound module to serve). */
  public boolean isCore() {
    return binding == null;
  }

  /** The module's constructor: the {@code Query} field it owns. */
  public Field entryField() {
    return entryField(client);
  }

  /** The GraphQL name of the root type this entry point sits on ({@code Query} for core). */
  public String rootTypeName() {
    return isCore() ? "Query" : entryField().getTypeRef().getTypeName();
  }

  private static Field entryField(SchemaPartition client) {
    List<Field> entries = client.extensions().getOrDefault("Query", List.of());
    if (entries.size() != 1) {
      throw new IllegalStateException(
          String.format(
              "module %s owns %d fields on Query, expected exactly one: %s",
              client.module(),
              entries.size(),
              entries.stream().map(Field::getName).collect(Collectors.toList())));
    }
    return entries.get(0);
  }

  /**
   * Module-owned fields on core types other than {@code Query}, by type name, in the partition's
   * order so the emitted shims come out the same on every run.
   */
  public Map<String, List<Field>> shims() {
    return client.extensions().entrySet().stream()
        .filter(e -> !"Query".equals(e.getKey()))
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (first, second) -> first,
                LinkedHashMap::new));
  }
}
