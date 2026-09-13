package io.dagger.client.engineconn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionTest {

  @TempDir Path dir;

  @Test
  void aSessionInTheEnvironmentIsAttachedToAndNothingIsStarted() throws Exception {
    Connection connection = Connection.get(dir.toString(), false, "54321", "tok");

    assertThat(connection.session()).isNull();
    assertThat(connection.getGraphQLClient()).isNotNull();
    connection.close();
  }

  @Test
  void closingAConnectionClosesTheSessionItStarted() throws Exception {
    Path cli = fakeCli("echo '{\"port\":54321,\"session_token\":\"tok\"}'\n" + "cat > /dev/null\n");
    CLISession session =
        CLISession.start(cli, dir, false, Duration.ofMillis(500), Duration.ofMillis(500));

    Connection connection =
        Connection.getConnection(session.port(), session.sessionToken(), session);
    connection.close();

    assertThat(session.isAlive()).isFalse();
  }

  private Path fakeCli(String body) throws IOException {
    Path cli = dir.resolve("dagger");
    Files.writeString(cli, "#!/bin/sh\n" + body);
    Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwxr-xr-x"));
    return cli;
  }
}
