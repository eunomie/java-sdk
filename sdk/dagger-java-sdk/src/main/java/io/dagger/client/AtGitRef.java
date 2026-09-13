package io.dagger.client;

import java.util.Objects;

/** A {@link ModuleTarget} in a git repository, at the commit its reference resolved to. */
record AtGitRef(String name, String ref, String pin) implements ModuleTarget {

  AtGitRef {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(pin, "pin");
  }
}
