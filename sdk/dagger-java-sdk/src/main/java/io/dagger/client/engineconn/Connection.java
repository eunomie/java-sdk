package io.dagger.client.engineconn;

import io.dagger.client.graphql.GraphQLClient;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Connection {

  static final Logger LOG = LoggerFactory.getLogger(Connection.class);

  private final GraphQLClient graphQLClient;
  private final CLISession session;

  Connection(GraphQLClient graphQLClient, CLISession session) {
    this.graphQLClient = graphQLClient;
    this.session = session;
  }

  public GraphQLClient getGraphQLClient() {
    return this.graphQLClient;
  }

  public void close() throws Exception {
    try {
      this.graphQLClient.close();
    } finally {
      if (this.session != null) {
        this.session.close();
      }
    }
  }

  public static Connection get(String workingDir) throws IOException {
    return get(workingDir, false);
  }

  public static Connection get(String workingDir, boolean loadWorkspaceModules) throws IOException {
    return get(
        workingDir,
        loadWorkspaceModules,
        System.getenv("DAGGER_SESSION_PORT"),
        System.getenv("DAGGER_SESSION_TOKEN"));
  }

  static Connection get(
      String workingDir, boolean loadWorkspaceModules, String portStr, String sessionToken)
      throws IOException {
    if (portStr == null || sessionToken == null) {
      CLISession session = CLISession.start(Path.of(workingDir), loadWorkspaceModules);
      return getConnection(session.port(), session.sessionToken(), session);
    }
    try {
      return getConnection(Integer.parseInt(portStr), sessionToken, null);
    } catch (NumberFormatException nfe) {
      throw new IOException("invalid port value in DAGGER_SESSION_PORT", nfe);
    }
  }

  /** The session this connection started, or null when it attached to one already running. */
  CLISession session() {
    return this.session;
  }

  static Connection getConnection(int port, String token, CLISession session) {
    // Inject OpenTelemetry context into headers
    Map<String, String> headers = new HashMap<>();
    GlobalOpenTelemetry.getPropagators()
        .getTextMapPropagator()
        .inject(Context.current(), headers, (carrier, key, value) -> carrier.put(key, value));

    return new Connection(
        new GraphQLClient(String.format("http://127.0.0.1:%d/query", port), token, headers),
        session);
  }
}
