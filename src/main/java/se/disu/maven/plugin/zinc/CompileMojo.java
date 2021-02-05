package se.disu.maven.plugin.zinc;

import java.io.File;
import java.util.List;
import java.util.Optional;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** Compiles Java and Scala sources using Zinc. */
@Mojo(
    name = "compile",
    defaultPhase = LifecyclePhase.COMPILE,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true)
public final class CompileMojo extends AbstractCompileMojo {
  /** Directory containing Scala sources. */
  @Parameter(defaultValue = "${project.build.sourceDirectory}/../scala")
  private String scalaSourceDirectory;

  @Override
  public void execute() throws MojoExecutionException {
    super.execute();
    Optional.of(new File(outputDirectory()))
        .filter(File::isDirectory)
        .ifPresent(session.getCurrentProject().getArtifact()::setFile);
  }

  @Override
  protected List<String> classpath() {
    try {
      return session.getCurrentProject().getCompileClasspathElements();
    } catch (DependencyResolutionRequiredException e) {
      throw UncheckedMojoExecutionException.of(e, "cannotDetermineCompileClasspath");
    }
  }

  @Override
  protected String outputDirectory() {
    return session.getCurrentProject().getBuild().getOutputDirectory();
  }

  @Override
  protected List<String> sourceDirectories() {
    Optional.ofNullable(scalaSourceDirectory)
        .ifPresent(session.getCurrentProject()::addCompileSourceRoot);
    return session.getCurrentProject().getCompileSourceRoots();
  }
}
