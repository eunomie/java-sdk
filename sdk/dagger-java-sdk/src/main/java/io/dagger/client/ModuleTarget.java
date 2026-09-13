package io.dagger.client;

/**
 * Where a target lives, as the generator recorded it.
 *
 * <p>A target is reached either through the caller's workspace or from a git reference, never both.
 * The two are separate implementations of a sealed type rather than one descriptor with nullable
 * fields, so a half-filled descriptor cannot be built in the first place.
 */
public sealed interface ModuleTarget permits InWorkspace, AtGitRef {

  /** The final module name the bindings were generated against. */
  String name();

  /** A target in the caller's workspace, at a workspace-relative path. */
  static ModuleTarget inWorkspace(String name, String path) {
    return new InWorkspace(name, path);
  }

  /**
   * A target in a git repository, at the commit its reference resolved to when the bindings were
   * generated.
   */
  static ModuleTarget atGitRef(String name, String ref, String pin) {
    return new AtGitRef(name, ref, pin);
  }
}
