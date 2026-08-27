package io.dagger.codegen;

import io.dagger.codegen.GenerationPlan.Entry;
import io.dagger.codegen.introspection.ClientEntryPoint;
import io.dagger.codegen.introspection.CodegenVisitor;
import io.dagger.codegen.introspection.Helpers;
import io.dagger.codegen.introspection.Schema;
import io.dagger.codegen.introspection.SchemaPartition;
import io.dagger.codegen.introspection.TypeRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Runs a {@link GenerationPlan}: core into {@code io.dagger.core}, each module client into {@code
 * io.dagger.client.<module>}, both against the hand-written runtime in {@code io.dagger.sdk}.
 *
 * <p>Each package root is deleted before it is written, so a package never carries a type its
 * schema no longer has. A plan that carries core is the whole picture, so it also removes every
 * client package it does not mention — a dependency that was dropped, an alias that was renamed —
 * except the one named by {@code keep}: a module's previously generated self client, carried
 * through so the module still compiles while its own schema is read, and rewritten by a second,
 * one-entry pass. A plan without core touches nothing but the roots it writes, which is what that
 * second pass relies on.
 */
public final class Generator {

  public static final String CORE_PACKAGE = "io.dagger.core";
  public static final String CLIENT_PACKAGE_PREFIX = "io.dagger.client";
  public static final String RUNTIME_PACKAGE = "io.dagger.sdk";

  private Generator() {}

  public static String clientPackage(String module) {
    return CLIENT_PACKAGE_PREFIX + "." + Helpers.packageSegment(module);
  }

  public static void generate(
      List<Entry> entries, String version, Path out, Charset encoding, Consumer<String> log)
      throws IOException {
    generate(entries, version, out, encoding, null, log);
  }

  /**
   * @param keep the module whose existing client package survives a full plan that does not
   *     regenerate it, or null
   */
  public static void generate(
      List<Entry> entries,
      String version,
      Path out,
      Charset encoding,
      String keep,
      Consumer<String> log)
      throws IOException {
    if (entries.stream().filter(Entry::isCore).count() > 1) {
      throw new IllegalArgumentException("a plan holds at most one core entry");
    }
    rejectClashingPackages(entries);
    if (entries.stream().anyMatch(Entry::isCore)) {
      cleanUnplannedClients(out, entries, keep, log);
    }
    for (Entry entry : entries) {
      Schema schema;
      try (InputStream in = Files.newInputStream(entry.schema())) {
        schema = Schema.initialize(in, version);
      }
      if (entry.isCore()) {
        SchemaPartition core = SchemaPartition.core(schema);
        clean(out, CORE_PACKAGE);
        log.accept(String.format("Generating %s (%d types)", CORE_PACKAGE, core.types().size()));
        core.visit(
            new CodegenVisitor(
                schema,
                TypeRegistry.core(CORE_PACKAGE, RUNTIME_PACKAGE),
                ClientEntryPoint.core(core),
                out,
                encoding));
      } else {
        SchemaPartition client = SchemaPartition.client(schema, entry.module());
        ClientEntryPoint entryPoint = new ClientEntryPoint(client, entry.binding());
        String pkg = clientPackage(entry.module());
        clean(out, pkg);
        log.accept(
            String.format(
                "Generating %s for module %s (%d types, root %s)",
                pkg, entry.module(), client.types().size(), entryPoint.rootTypeName()));
        client.visit(
            new CodegenVisitor(
                schema,
                TypeRegistry.client(pkg, CORE_PACKAGE, RUNTIME_PACKAGE, client.ownedTypeNames()),
                entryPoint,
                out,
                encoding));
      }
    }
  }

  /**
   * Two module names that normalize to the same package segment ({@code my-module} and {@code
   * mymodule}) would emit into one package, the later entry deleting the earlier one's client.
   */
  private static void rejectClashingPackages(List<Entry> entries) {
    Map<String, String> bySegment = new LinkedHashMap<>();
    for (Entry entry : entries) {
      if (entry.isCore()) {
        continue;
      }
      String previous = bySegment.put(Helpers.packageSegment(entry.module()), entry.module());
      if (previous != null && !previous.equals(entry.module())) {
        throw new IllegalArgumentException(
            String.format(
                "modules %s and %s both name the package %s; alias one of them",
                previous, entry.module(), clientPackage(entry.module())));
      }
    }
  }

  private static void cleanUnplannedClients(
      Path out, List<Entry> entries, String keep, Consumer<String> log) throws IOException {
    Path clients = out.resolve(CLIENT_PACKAGE_PREFIX.replace('.', '/'));
    if (!Files.isDirectory(clients)) {
      return;
    }
    Set<String> wanted = new HashSet<>();
    entries.stream()
        .filter(e -> !e.isCore())
        .forEach(e -> wanted.add(Helpers.packageSegment(e.module())));
    if (keep != null && !keep.isBlank()) {
      wanted.add(Helpers.packageSegment(keep));
    }
    try (Stream<Path> roots = Files.list(clients)) {
      for (Path root : roots.filter(Files::isDirectory).sorted().toList()) {
        if (!wanted.contains(root.getFileName().toString())) {
          log.accept("Removing stale client package " + clients.relativize(root));
          clean(out, CLIENT_PACKAGE_PREFIX + "." + root.getFileName());
        }
      }
    }
  }

  private static void clean(Path out, String pkg) throws IOException {
    Path root = out.resolve(pkg.replace('.', '/'));
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(file);
      }
    }
  }
}
