package io.dagger.modules.daggermoduleplaceholder;

import static io.dagger.core.Core.core;
import static io.dagger.sdk.Dagger.dag;

import io.dagger.core.Container;
import io.dagger.sdk.exception.DaggerQueryException;
import io.dagger.core.Directory;
import io.dagger.core.Workspace;
import io.dagger.module.annotation.Default;
import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Object;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Object
public class DaggerModule {
  private Directory source;
  private String baseImageAddress;

  public DaggerModule() {}

  public DaggerModule(Workspace ws, @Default("alpine:3.24") String baseImageAddress) {
    this.source = ws.directory("/");
    this.baseImageAddress = baseImageAddress;
  }

  /** A container with the workspace source, ready to build. */
  @Function
  public Container container() {
    return core(dag())
    .container()
    .from(this.baseImageAddress)
    .withDirectory("/src", this.source)
    .withWorkdir("/src");
  }
}
