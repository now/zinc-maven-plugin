package se.disu.maven.plugin.zinc;

import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.repository.RepositorySystem;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

final class This {
  private static final String META_INF_MAVEN_PLUGIN_XML = "META-INF/maven/plugin.xml";

  private static final This INSTANCE = new This();

  private final String groupId;
  private final String artifactId;

  private This() {
    String metaInfMavenPluginXml =
        Optional.ofNullable(getClass().getClassLoader().getResource(META_INF_MAVEN_PLUGIN_XML))
            .map(UncheckedMojoExecutionException.of(URL::toURI, "cannotConvertUrlToUri"))
            .map(URI::toString)
            .orElseThrow(
                () ->
                    UncheckedMojoExecutionException.of(
                        "cannotFindResource", META_INF_MAVEN_PLUGIN_XML));
    try {
      Document d =
          DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(metaInfMavenPluginXml);
      XPath xp = XPathFactory.newInstance().newXPath();
      groupId = xp.evaluate("/plugin/groupId", d);
      artifactId = xp.evaluate("/plugin/artifactId", d);
    } catch (ParserConfigurationException e) {
      throw UncheckedMojoExecutionException.of(e, "cannotCreateXmlParser");
    } catch (IOException | SAXException e) {
      throw UncheckedMojoExecutionException.of(e, "cannotParse", metaInfMavenPluginXml);
    } catch (XPathExpressionException e) {
      throw UncheckedMojoExecutionException.of(e, "cannotCreateXpathExpression");
    }
  }

  static List<Artifact> dependencyArtifacts(MavenSession s, RepositorySystem rs) {
    return s.getCurrentProject().getBuildPlugins().stream()
        .filter(p -> p.getGroupId().equals(INSTANCE.groupId))
        .filter(p -> p.getArtifactId().equals(INSTANCE.artifactId))
        .map(Plugin::getDependencies)
        .flatMap(List<Dependency>::stream)
        .distinct()
        .map(rs::createDependencyArtifact)
        .collect(toList());
  }
}
