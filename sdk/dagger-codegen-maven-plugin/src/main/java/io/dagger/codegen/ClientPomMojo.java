package io.dagger.codegen;

import java.io.File;
import java.io.IOException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Registers the generated Dagger client sources with a {@code pom.xml} the SDK does not own, by
 * writing a single {@code dagger-clients} profile into it. Everything else in the file is left as
 * it was.
 */
@Mojo(name = "client-pom", requiresProject = false, threadSafe = true)
public class ClientPomMojo extends AbstractMojo {

  /** The {@code pom.xml} of the scope to register the generated sources with. */
  @Parameter(property = "dagger.pom", defaultValue = "${basedir}/pom.xml", required = true)
  private File pomFile;

  @Override
  public void execute() throws MojoFailureException {
    try {
      if (ClientPom.insertInto(pomFile.toPath())) {
        getLog().info(String.format("Wrote the %s profile to %s", ClientPom.PROFILE_ID, pomFile));
      } else {
        getLog().info(String.format("%s is already up to date", pomFile));
      }
    } catch (IOException | IllegalStateException e) {
      throw new MojoFailureException(e.getMessage(), e);
    }
  }
}
