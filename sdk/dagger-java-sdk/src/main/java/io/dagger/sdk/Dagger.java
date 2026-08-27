package io.dagger.sdk;

import java.io.IOException;

public class Dagger {
  private static Session dag = null;

  /**
   * The global Dagger session.
   *
   * <p>Managed as a process-wide singleton: always the same instance. The typed core API is reached
   * through it — {@code core(dag())} — and a dependency through {@code <dep>(dag())}.
   *
   * @return the shared session
   */
  public static synchronized Session dag() {
    if (dag == null) {
      try {
        dag = Session.connect(System.getProperty("user.dir"), false);
      } catch (IOException e) {
        throw new RuntimeException("Could not connect to Dagger engine", e);
      }
    }
    return dag;
  }

  /**
   * Open a new session with a Dagger engine, to close in a try-with-resources.
   *
   * @return a session
   */
  public static Session connect() throws IOException {
    return connect(System.getProperty("user.dir"), false);
  }

  /**
   * @param loadWorkspaceModules whether to opt into loading workspace modules
   */
  public static Session connect(boolean loadWorkspaceModules) throws IOException {
    return connect(System.getProperty("user.dir"), loadWorkspaceModules);
  }

  /**
   * @param workingDir the host working directory
   */
  public static Session connect(String workingDir) throws IOException {
    return connect(workingDir, false);
  }

  /**
   * @param workingDir the host working directory
   * @param loadWorkspaceModules whether to opt into loading workspace modules
   */
  public static Session connect(String workingDir, boolean loadWorkspaceModules)
      throws IOException {
    return Session.connect(workingDir, loadWorkspaceModules);
  }
}
