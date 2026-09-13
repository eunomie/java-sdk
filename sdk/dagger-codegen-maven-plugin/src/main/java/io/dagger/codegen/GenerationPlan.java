package io.dagger.codegen;

import io.dagger.codegen.introspection.ModuleTargetRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * What one run of the generator emits: the core package, and one package per target.
 *
 * <p>A plan is a directory the caller assembles:
 *
 * <pre>
 *   &lt;plan&gt;/core/schema.json        the schema core is generated from
 *   &lt;plan&gt;/target-*&#47;schema.json    one target's client-facing schema
 *   &lt;plan&gt;/target-*&#47;module         that target's module name, one line
 *   &lt;plan&gt;/target-*&#47;source         where that module lives, when the client serves it
 * </pre>
 *
 * <p>A target with no {@code source} is one the engine serves on its own, and its bindings ask for
 * nothing. A target with one carries it into the generated code as a constant, so what a client
 * loads is decided when its bindings are written rather than by what is on the class path when they
 * run.
 *
 * <p>The directory names carry no meaning beyond ordering the targets; a module name can hold
 * characters a path cannot, so it travels in a file. Targets are read in module-name order, so a
 * plan generates the same bytes however its caller happened to lay it out.
 */
public record GenerationPlan(Path coreSchema, List<Target> targets) {

  /**
   * One target: the module to generate bindings for, the schema to generate them from, and where
   * the bindings should load it from — null when the engine serves it already.
   */
  public record Target(String module, Path schema, ModuleTargetRef source) {}

  private static final String CORE = "core";
  private static final String SCHEMA = "schema.json";
  private static final String MODULE = "module";
  private static final String SOURCE = "source";

  public static GenerationPlan read(Path planDirectory) throws IOException {
    Path coreSchema = planDirectory.resolve(CORE).resolve(SCHEMA);
    if (!Files.isRegularFile(coreSchema)) {
      throw new IOException("generation plan has no core schema at " + coreSchema);
    }
    List<Target> targets = new ArrayList<>();
    try (Stream<Path> entries = Files.list(planDirectory)) {
      for (Path entry : entries.sorted().toList()) {
        if (!Files.isDirectory(entry) || entry.getFileName().toString().equals(CORE)) {
          continue;
        }
        Path module = entry.resolve(MODULE);
        Path schema = entry.resolve(SCHEMA);
        if (!Files.isRegularFile(module) || !Files.isRegularFile(schema)) {
          throw new IOException(
              "generation plan entry " + entry + " needs both a " + MODULE + " and a " + SCHEMA);
        }
        Path source = entry.resolve(SOURCE);
        targets.add(
            new Target(
                Files.readString(module, StandardCharsets.UTF_8).trim(),
                schema,
                Files.isRegularFile(source) ? ModuleTargetRef.read(source) : null));
      }
    }
    targets.sort(Comparator.comparing(Target::module));
    return new GenerationPlan(coreSchema, List.copyOf(targets));
  }
}
