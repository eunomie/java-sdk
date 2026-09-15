package io.dagger.client;

import io.dagger.client.engineconn.Connection;

public class AutoCloseableSession extends Session implements AutoCloseable {
  AutoCloseableSession(Connection connection) {
    super(connection);
  }
}
