package se.disu.text;

import static com.google.common.truth.Truth.assertWithMessage;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Locale.ROOT;
import static java.util.function.Function.identity;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.yaml.snakeyaml.Yaml;

@Test
public final class MessagesTest {
  @Test(dataProvider = "successes")
  public void shouldFormat(
      String description, String expected, String template, List<String> arguments) {
    assertWithMessage(description)
        .that(Messages.format(identity(), () -> ROOT, template, arguments.toArray()))
        .isEqualTo(expected);
  }

  @Test(dataProvider = "failures")
  public void shouldFail(
      String description, String expected, String template, List<String> arguments) {
    try {
      Messages.format(identity(), () -> ROOT, template, arguments.toArray());
      assertWithMessage(format("%s given %s should have thrown %s", template, arguments, expected))
          .fail();
    } catch (IllegalArgumentException actual) {
      assertWithMessage(description).that(actual).hasMessageThat().isEqualTo(expected);
    }
  }

  @DataProvider
  Object[][] successes() throws IOException {
    return yaml("successes.yaml");
  }

  @DataProvider
  Object[][] failures() throws IOException {
    return yaml("failures.yaml");
  }

  private Object[][] yaml(String resourcePath) throws IOException {
    try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new FileNotFoundException("Can’t open test resource “" + resourcePath + "”");
      }
      @SuppressWarnings("unchecked")
      Object[][] r =
          Optional.ofNullable(((List<Map<String, Object>>) new Yaml().load(is))).orElse(emptyList())
              .stream()
              .map(
                  e ->
                      new Object[] {
                        e.get("description"),
                        Optional.ofNullable(e.get("expected")).orElse(e.get("description")),
                        e.get("template"),
                        e.getOrDefault("arguments", emptyList())
                      })
              .toArray(Object[][]::new);
      return r;
    }
  }
}
