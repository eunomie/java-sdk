package io.dagger.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class ModuleTargetsTest {

  private static final String SERVED = "{\"data\":{\"moduleSource\":{}}}";

  private static final ModuleTarget HELLO =
      ModuleTarget.inWorkspace("workspaceTarget", "dagger/modules/hello");
  private static final ModuleTarget OTHER =
      ModuleTarget.inWorkspace("otherTarget", "dagger/modules/other");
  private static final ModuleTarget GIT =
      ModuleTarget.atGitRef("gitTarget", "github.com/dagger/hello@v1", "0123abc");
  private static final ModuleTarget GONE =
      ModuleTarget.atGitRef("brokenTarget", "github.com/dagger/gone@v1", "deadbee");

  @Test
  void aGitTargetIsServedAtItsPinUnderItsGeneratedName() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(SERVED)) {
      ModuleTargets.serve(new QueryBuilder(engine.client()), GIT);

      assertThat(engine.query())
          .startsWith("query {moduleSource(")
          .contains("refString:\"github.com/dagger/hello@v1\"")
          .contains("refPin:\"0123abc\"")
          .endsWith(") {withName(name:\"gitTarget\") {asModule {serve}}}}")
          .doesNotContain("currentWorkspace");
    }
  }

  @Test
  void aWorkspaceTargetIsServedByItsPath() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(SERVED)) {
      ModuleTargets.serve(new QueryBuilder(engine.client()), HELLO);

      assertThat(engine.query())
          .isEqualTo(
              "query {currentWorkspace {moduleSource(path:\"dagger/modules/hello\")"
                  + " {withName(name:\"workspaceTarget\") {asModule {serve}}}}}");
    }
  }

  @Test
  void aSecondCallForTheSameTargetSendsNothing() throws Exception {
    try (FakeEngine engine = FakeEngine.replying(SERVED)) {
      QueryBuilder root = new QueryBuilder(engine.client());
      ModuleTargets.serve(root, HELLO);
      ModuleTargets.serve(root, HELLO);

      assertThat(engine.queries()).hasSize(1);
    }
  }

  @Test
  void anotherSessionServesAgain() throws Exception {
    try (FakeEngine first = FakeEngine.replying(SERVED);
        FakeEngine second = FakeEngine.replying(SERVED)) {
      ModuleTargets.serve(new QueryBuilder(first.client()), HELLO);
      ModuleTargets.serve(new QueryBuilder(second.client()), HELLO);

      assertThat(first.queries()).hasSize(1);
      assertThat(second.queries()).hasSize(1);
    }
  }

  @Test
  void aTargetTheEngineRefusesDoesNotStopAnother() throws Exception {
    try (FakeEngine engine =
        FakeEngine.replying(
            query ->
                query.contains("gone")
                    ? "{\"errors\":[{\"message\":\"module gone: no such ref\"}]}"
                    : SERVED)) {
      QueryBuilder root = new QueryBuilder(engine.client());

      assertThatThrownBy(() -> ModuleTargets.serve(root, GONE))
          .hasMessageContaining("brokenTarget")
          .hasRootCauseMessage("module gone: no such ref");

      ModuleTargets.serve(root, OTHER);
      assertThat(engine.query())
          .isEqualTo(
              "query {currentWorkspace {moduleSource(path:\"dagger/modules/other\")"
                  + " {withName(name:\"otherTarget\") {asModule {serve}}}}}");
    }
  }

  @Test
  void concurrentCallsForOneTargetServeOnce() throws Exception {
    try (FakeEngine engine =
        FakeEngine.replying(
            query -> {
              sleep();
              return SERVED;
            })) {
      QueryBuilder root = new QueryBuilder(engine.client());
      CountDownLatch start = new CountDownLatch(1);
      List<Thread> callers = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        Thread caller =
            new Thread(
                () -> {
                  await(start);
                  ModuleTargets.serve(root, HELLO);
                });
        caller.start();
        callers.add(caller);
      }
      start.countDown();
      for (Thread caller : callers) {
        caller.join();
      }

      assertThat(engine.queries()).hasSize(1);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
