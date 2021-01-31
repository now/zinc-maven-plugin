package se.disu.maven.plugin.zinc;

import java.util.List;
import java.util.Optional;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** Compiles Java and Scala test sources using Zinc. */
@Mojo(
    name = "testCompile",
    defaultPhase = LifecyclePhase.TEST_COMPILE,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true)
public final class TestCompileMojo extends AbstractCompileMojo {
  /** Directory containing Scala test sources. */
  @Parameter(defaultValue = "${project.build.testSourceDirectory}/../scala")
  private String scalaTestSourceDirectory;

  @Override
  protected List<String> classpath() {
    try {
      return session.getCurrentProject().getTestClasspathElements();
    } catch (DependencyResolutionRequiredException e) {
      throw UncheckedMojoExecutionException.of(e, "cannotDetermineTestClasspath");
    }
  }

  @Override
  protected String outputDirectory() {
    return session.getCurrentProject().getBuild().getTestOutputDirectory();
  }

  @Override
  protected List<String> sourceDirectories() {
    Optional.ofNullable(scalaTestSourceDirectory)
        .ifPresent(session.getCurrentProject()::addTestCompileSourceRoot);
    return session.getCurrentProject().getTestCompileSourceRoots();
  }
}
