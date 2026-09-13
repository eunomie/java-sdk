package io.dagger.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dagger.client.graphql.GraphQLClient;
import jakarta.json.Json;
import jakarta.json.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** A GraphQL endpoint recording the documents it is sent and answering with canned JSON. */
record FakeEngine(HttpServer http, GraphQLClient client, List<String> queries)
    implements AutoCloseable {

  static FakeEngine replying(String payload) throws IOException {
    return replying(query -> payload);
  }

  static FakeEngine replying(Function<String, String> responder) throws IOException {
    List<String> queries = new CopyOnWriteArrayList<>();
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/query", exchange -> respond(exchange, responder, queries));
    http.setExecutor(Executors.newCachedThreadPool());
    http.start();
    String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/query";
    return new FakeEngine(http, new GraphQLClient(url, "token", Map.of()), queries);
  }

  /** The last GraphQL document received. */
  String query() {
    return queries.get(queries.size() - 1);
  }

  // GraphQLClient sets no request timeout, so every path must send a response.
  private static void respond(
      HttpExchange exchange, Function<String, String> responder, List<String> queries)
      throws IOException {
    try (exchange) {
      String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String query;
      try (JsonReader reader = Json.createReader(new StringReader(request))) {
        query = reader.readObject().getString("query");
      }
      queries.add(query);
      byte[] payload = responder.apply(query).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("content-type", "application/json");
      exchange.sendResponseHeaders(200, payload.length);
      exchange.getResponseBody().write(payload);
    }
  }

  @Override
  public void close() {
    client.close();
    http.stop(0);
  }
}
