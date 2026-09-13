package io.dagger.client;

import io.dagger.client.exception.DaggerQueryException;
import io.dagger.client.graphql.GraphQLClient;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

/**
 * Serves a target into the session the first time generated code reaches for it.
 *
 * <p>Every way into a generated client package calls {@link #serve(QueryBuilder, ModuleTarget)}
 * before it builds anything: until the target is served, the field the caller is about to select
 * does not exist in the session. The package carries the descriptor it was generated against, so a
 * plain program and a Dagger module take the same path.
 *
 * <p>A client generated against a target the engine serves on its own carries no descriptor and
 * never reaches this class.
 */
public final class ModuleTargets {

  // Weak in the session: a closed session must not be pinned by what it served.
  private static final Map<GraphQLClient, ConcurrentMap<String, Serve>> SERVED =
      Collections.synchronizedMap(new WeakHashMap<>());

  private ModuleTargets() {}

  /**
   * Make {@code target} resolvable in {@code root}'s session, at most once per session.
   *
   * <p>The session is passed in rather than read from a global, so a client from {@link
   * Dagger#connect()} serves into its own session rather than into the one {@link Dagger#dag()}
   * happens to hold.
   *
   * @throws RuntimeException when the engine refuses to serve the target
   */
  public static void serve(QueryBuilder root, ModuleTarget target) {
    SERVED
        .computeIfAbsent(root.client(), session -> new ConcurrentHashMap<>())
        .computeIfAbsent(target.name(), name -> new Serve())
        .once(root, target);
  }

  /** One target's serve in one session: at most one round trip, whoever asks and however often. */
  private static final class Serve {

    private boolean done;

    synchronized void once(QueryBuilder root, ModuleTarget target) {
      if (done) {
        return;
      }
      try {
        source(root, target)
            .chain("withName", Arguments.newBuilder().add("name", target.name()).build())
            .chain("asModule")
            .chain("serve")
            .executeQuery();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw refused(target, e);
      } catch (ExecutionException | DaggerQueryException e) {
        throw refused(target, e);
      }
      done = true;
    }

    private static QueryBuilder source(QueryBuilder root, ModuleTarget target) {
      if (target instanceof AtGitRef git) {
        return root.chain(
            "moduleSource",
            Arguments.newBuilder().add("refString", git.ref()).add("refPin", git.pin()).build());
      }
      if (target instanceof InWorkspace local) {
        return root.chain("currentWorkspace")
            .chain("moduleSource", Arguments.newBuilder().add("path", local.path()).build());
      }
      throw new IllegalStateException("no way to reach the module target " + target);
    }

    /**
     * The accessor this runs from returns a lazy object and declares no checked exception, so the
     * refusal has to be unchecked. It is not swallowed: without it the next query would fail on an
     * unknown field, which says nothing about why the target is missing.
     */
    private static RuntimeException refused(ModuleTarget target, Exception cause) {
      return new RuntimeException("could not serve the module target " + target, cause);
    }
  }
}
