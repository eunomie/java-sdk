package io.dagger.modules.dep;

import static io.dagger.core.Core.core;
import static io.dagger.sdk.Dagger.dag;

import io.dagger.core.Directory;
import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Object;

@Object
public class Dep {
  @Function
  public String greet(String name) {
    return "hello " + name;
  }

  /** A core type handed across the client boundary. */
  @Function
  public Directory scratch() {
    return core(dag()).directory().withNewFile("dep.txt", "from dep");
  }
}
