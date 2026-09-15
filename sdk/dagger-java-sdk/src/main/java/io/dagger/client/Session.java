package io.dagger.client;

import io.dagger.client.engineconn.Connection;

/**
 * A connection to an engine, and the root every generated package chains its first selection from.
 *
 * <p>Hand-written, because nothing generated is privileged. Core is a client package like any
 * other, reached as {@code core(dag())}, so what {@link Dagger#dag()} hands back cannot be a
 * generated class: it is the one thing every package takes, core and module alike.
 */
public class Session {

  private final Connection connection;
  private final QueryBuilder queryBuilder;

  public Session(Connection connection) {
    this.connection = connection;
    this.queryBuilder = new QueryBuilder(connection.getGraphQLClient());
  }

  /** The builder a generated entry point chains from, and the identity of this session. */
  public QueryBuilder queryBuilder() {
    return this.queryBuilder;
  }

  public void close() throws Exception {
    this.connection.close();
  }
}
