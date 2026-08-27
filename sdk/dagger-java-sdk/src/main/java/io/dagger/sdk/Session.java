package io.dagger.sdk;

import io.dagger.sdk.engineconn.Connection;
import java.io.IOException;

/**
 * A connection to a Dagger engine session — the handle every generated client hangs off.
 *
 * <p>It carries the session and nothing schema-derived: a fresh root {@link QueryBuilder}, the
 * node-by-ID builder generated deserializers need, and the lifecycle. The typed core API is reached
 * through it with {@code core(session)}, a dependency through {@code <dep>(session)}, exactly
 * alike. {@link Dagger#dag()} returns a shared, process-wide {@code Session}; {@link
 * Dagger#connect()} returns one to close in a try-with-resources.
 */
public final class Session implements AutoCloseable {

  private final Connection connection;

  Session(Connection connection) {
    this.connection = connection;
  }

  /**
   * Internal factory for {@link Dagger}: opens a session against the engine reachable from {@code
   * workingDir}.
   */
  static Session connect(String workingDir, boolean loadWorkspaceModules) throws IOException {
    return new Session(Connection.get(workingDir, loadWorkspaceModules));
  }

  /** A fresh query builder rooted at this session. */
  public QueryBuilder queryBuilder() {
    return new QueryBuilder(connection.getGraphQLClient());
  }

  /** A query builder for {@code node(id:)} scoped to a type, for loading an object from its ID. */
  public QueryBuilder nodeQueryBuilder(String typeName, Object id) {
    return queryBuilder().chainNode(typeName, id);
  }

  @Override
  public void close() throws Exception {
    connection.close();
  }
}
