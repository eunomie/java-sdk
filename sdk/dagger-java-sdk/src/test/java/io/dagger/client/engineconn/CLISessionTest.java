package io.dagger.client.engineconn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CLISessionTest {

  private static final Duration SOON = Duration.ofMillis(500);

  // The real CLI exits when its standard input closes, and so does every fake here.
  private static final String WAIT_FOR_STDIN = "cat > /dev/null\n";

  @TempDir Path dir;

  @Test
  void aStartedSessionAnnouncesWhereToReachIt() throws Exception {
    Path cli =
        fakeCli(
            "echo \"$@\" > \"$PWD/args\"\n"
                + "echo '{\"port\":54321,\"session_token\":\"tok\"}'\n"
                + WAIT_FOR_STDIN);

    CLISession session = CLISession.start(cli, dir, false, SOON, SOON);

    assertThat(session.port()).isEqualTo(54321);
    assertThat(session.sessionToken()).isEqualTo("tok");
    assertThat(session.isAlive()).isTrue();
    assertThat(Files.readString(dir.resolve("args")))
        .contains("session")
        .doesNotContain("--load-workspace-modules");
    session.close();
  }

  @Test
  void workspaceModulesAreLoadedOnlyWhenAsked() throws Exception {
    Path cli =
        fakeCli(
            "echo \"$@\" > \"$PWD/args\"\n"
                + "echo '{\"port\":54321,\"session_token\":\"tok\"}'\n"
                + WAIT_FOR_STDIN);

    try (CLISession session = CLISession.start(cli, dir, true, SOON, SOON)) {
      assertThat(Files.readString(dir.resolve("args"))).contains("--load-workspace-modules");
    }
  }

  @Test
  void closeIsIdempotentAndLeavesNoProcessBehind() throws Exception {
    Path cli = fakeCli("echo '{\"port\":54321,\"session_token\":\"tok\"}'\n" + WAIT_FOR_STDIN);

    CLISession session = CLISession.start(cli, dir, false, SOON, SOON);
    session.close();
    assertThat(session.isAlive()).isFalse();
    session.close();
    assertThat(session.isAlive()).isFalse();
  }

  @Test
  void anAnnouncementThisSdkCannotReadIsAnError() throws Exception {
    Path cli = failing("echo '{\"session_token\" oops}'\n");

    assertThatThrownBy(() -> CLISession.start(cli, dir, false, SOON, SOON))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("cannot read")
        .hasMessageContaining("engine says no");
  }

  @Test
  void anAnnouncementWithoutAPortIsAnError() throws Exception {
    Path cli = failing("echo '{\"session_token\":\"tok\"}'\n");

    assertThatThrownBy(() -> CLISession.start(cli, dir, false, SOON, SOON))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("cannot read")
        .hasMessageContaining("engine says no");
  }

  @Test
  void aPortOutsideTheTcpRangeIsAnError() throws Exception {
    Path cli = failing("echo '{\"port\":70000,\"session_token\":\"tok\"}'\n");

    assertThatThrownBy(() -> CLISession.start(cli, dir, false, SOON, SOON))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("70000, which is not a TCP port")
        .hasMessageContaining("engine says no");
  }

  @Test
  void anEmptySessionTokenIsAnError() throws Exception {
    Path cli = failing("echo '{\"port\":54321,\"session_token\":\"\"}'\n");

    assertThatThrownBy(() -> CLISession.start(cli, dir, false, SOON, SOON))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("empty session token")
        .hasMessageContaining("engine says no");
  }

  @Test
  void sayingNothingBeforeTheTimeoutIsAnError() throws Exception {
    Path cli = failing("");

    assertThatThrownBy(() -> CLISession.start(cli, dir, false, SOON, SOON))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("said nothing within")
        .hasMessageContaining("engine says no");
  }

  @Test
  void exitingBeforeAnnouncingIsAnError() throws Exception {
    Path cli = fakeCli("echo 'engine says no' >&2\nexit 3\n");

    assertThatThrownBy(() -> CLISession.start(cli, dir, false, SOON, SOON))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("exited with code 3")
        .hasMessageContaining("engine says no");
  }

  /**
   * The CLI packs a cache export while it shuts down, so closing a session waits for it rather than
   * killing it. A session that exits on its own is never forced.
   */
  @Test
  void aSessionThatTakesItsTimeShuttingDownIsNotKilled() throws Exception {
    Path cli =
        fakeCli(
            "echo '{\"port\":54321,\"session_token\":\"tok\"}'\n"
                + WAIT_FOR_STDIN
                + "sleep 1\n"
                + "exit 0\n");

    CLISession session = CLISession.start(cli, dir, false, SOON, Duration.ofSeconds(30));
    session.close();

    assertThat(session.isAlive()).isFalse();
    assertThat(session.exitValue()).isZero();
  }

  @Test
  void aSessionThatWillNotExitIsKilledOnceTheGraceIsUp() throws Exception {
    Path cli =
        fakeCli(
            "echo '{\"port\":54321,\"session_token\":\"tok\"}'\n"
                + "while true; do sleep 0.1; done\n");

    CLISession session = CLISession.start(cli, dir, false, SOON, SOON);
    session.close();

    assertThat(session.isAlive()).isFalse();
    assertThat(session.exitValue()).isNotZero();
  }

  @Test
  void theConfiguredBinaryWins() throws Exception {
    Path cli = fakeCli("");

    assertThat(CLISession.resolveCLI(cli.toString(), "/nowhere")).isEqualTo(cli);
  }

  @Test
  void theBinaryIsOtherwiseLookedUpOnThePath() throws Exception {
    Path cli = fakeCli("");

    assertThat(CLISession.resolveCLI(null, "/nowhere:" + dir)).isEqualTo(cli);
  }

  @Test
  void noBinaryAnywhereNamesBothPlacesItLookedAndDownloadsNothing() {
    assertThatThrownBy(() -> CLISession.resolveCLI(dir.resolve("absent").toString(), "/nowhere"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("_EXPERIMENTAL_DAGGER_CLI_BIN")
        .hasMessageContaining("PATH")
        .hasMessageContaining("does not download");
  }

  /** A CLI that says something on standard error, then waits to be shut down. */
  private Path failing(String body) throws IOException {
    return fakeCli("echo 'engine says no' >&2\n" + body + WAIT_FOR_STDIN);
  }

  private Path fakeCli(String body) throws IOException {
    Path cli = dir.resolve("dagger");
    Files.writeString(cli, "#!/bin/sh\n" + body);
    Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwxr-xr-x"));
    return cli;
  }
}
