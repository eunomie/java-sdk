package io.dagger.modules.daggermoduleplaceholder;

import static io.dagger.core.Core.core;
import static io.dagger.sdk.Dagger.dag;

import io.dagger.core.Container;
import io.dagger.sdk.exception.DaggerQueryException;
import io.dagger.core.Directory;
import io.dagger.module.annotation.Function;
import io.dagger.module.annotation.Object;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** DaggerModule main object */
@Object
public class DaggerModule {
  /** Returns a container that echoes whatever string argument is provided */
  @Function
  public Container containerEcho(String stringArg) {
    return core(dag()).container().from("alpine:latest").withExec(List.of("echo", stringArg));
  }

  /** Returns lines that match a pattern in the files of the provided Directory */
  @Function
  public String grepDir(Directory directoryArg, String pattern)
      throws InterruptedException, ExecutionException, DaggerQueryException {
    return core(dag())
        .container()
        .from("alpine:latest")
        .withMountedDirectory("/mnt", directoryArg)
        .withWorkdir("/mnt")
        .withExec(List.of("grep", "-R", pattern, "."))
        .stdout();
  }
}
