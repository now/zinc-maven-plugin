package se.disu.maven.plugin.zinc;

import static java.io.File.pathSeparator;
import static java.util.stream.Collectors.joining;
import static org.apache.maven.plugins.annotations.LifecyclePhase.INITIALIZE;
import static org.apache.maven.plugins.annotations.ResolutionScope.COMPILE;
import static se.disu.maven.plugin.zinc.AbstractCompileMojo.resolve;

import java.io.File;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ResolutionErrorHandler;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.repository.RepositorySystem;

@Mojo(
    name = "initialize",
    defaultPhase = INITIALIZE,
    requiresDependencyResolution = COMPILE,
    threadSafe = true)
public final class InitializeMojo extends AbstractMojo {
  @Parameter(property = "session", readonly = true, required = true)
  private MavenSession session;

  @Component private RepositorySystem repositorySystem;

  @Component private ResolutionErrorHandler resolutionErrorHandler;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      doExecute();
    } catch (UncheckedMojoExecutionException e) {
      throw e.getCause();
    }
  }

  private void doExecute() {
    This.dependencyArtifacts(session, repositorySystem).stream()
        .forEach(
            a ->
                set(
                    a.getDependencyConflictId() + ".classpath",
                    resolve(
                            session,
                            repositorySystem,
                            resolutionErrorHandler,
                            a,
                            true,
                            true,
                            "cannotResolveDependencyArtifact")
                        .stream()
                        .map(Artifact::getFile)
                        .map(File::getAbsolutePath)
                        .collect(joining(pathSeparator))));
  }

  private void set(String property, String value) {
    // TODO Better message and move to messages.properties.
    getLog().debug("Setting property “" + property + "” to “" + value + "”");
    session.getCurrentProject().getProperties().setProperty(property, value);
  }
}
