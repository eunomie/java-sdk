package io.dagger.modules.daggermoduleplaceholder;

import static io.dagger.client.modules.core.Core.core;

import io.dagger.client.exception.DaggerQueryException;
import io.dagger.client.modules.core.Container;
import io.dagger.client.modules.core.Directory;
import io.dagger.client.modules.core.Workspace;
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
    return core()
    .container()
    .from(this.baseImageAddress)
    .withDirectory("/src", this.source)
    .withWorkdir("/src");
  }
}
