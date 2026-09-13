package io.dagger.client;

import java.util.Objects;

/** A {@link ModuleTarget} in the caller's workspace, at a workspace-relative path. */
record InWorkspace(String name, String path) implements ModuleTarget {

  InWorkspace {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(path, "path");
  }
}
