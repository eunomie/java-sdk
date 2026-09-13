package io.dagger.codegen.introspection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Where a client's module lives, as the caller resolved it at generation time.
 *
 * <p>A client that carries one of these serves its own module: the generated bindings ask the
 * engine to load it before they select anything on it. A client generated against a module the
 * engine serves on its own carries none, and asks for nothing.
 */
public sealed interface ModuleTargetRef {

  /** A module in the caller's workspace, at a workspace-root-absolute path. */
  record InWorkspace(String path) implements ModuleTargetRef {}

  /** A module in git, at the commit its reference resolved to when the bindings were written. */
  record AtGitRef(String ref, String pin) implements ModuleTargetRef {}

  /**
   * Read a descriptor from a plan entry: a kind on the first line, its fields on the lines after.
   * One field per line rather than a delimiter, because a path or a git reference may legally hold
   * whatever separator would otherwise be chosen.
   */
  static ModuleTargetRef read(Path file) throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    String kind = lines.isEmpty() ? "" : lines.get(0);
    if (kind.equals("workspace") && lines.size() == 2) {
      return new InWorkspace(lines.get(1));
    }
    if (kind.equals("git") && lines.size() == 3) {
      return new AtGitRef(lines.get(1), lines.get(2));
    }
    throw new IOException(
        file
            + " is not a module descriptor: expected \"workspace\" and a path, or \"git\","
            + " a reference and a commit; got "
            + lines);
  }
}
