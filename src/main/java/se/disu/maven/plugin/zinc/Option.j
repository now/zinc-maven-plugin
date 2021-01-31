import static java.io.File.pathSeparator;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptySet;
import static se.disu.maven.plugin.zinc.Fun.BiConsumers.partialC;
import static se.disu.maven.plugin.zinc.Fun.Predicates.identityP;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.function.Function;
import java.util.Arrays;
import static java.lang.String.join;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;

import java.util.regex.MatchResult;

import se.disu.maven.plugin.zinc.Fun.Couple;

  // TODO:
  // Java 8 only (not --release): -bootclasspath, -endorseddirs, -extdirs, -profile

  // Java 8: -implicit, -Xprint, -XprintProcessorInfo, -XprintRounds, -g, -Xpkginfo?, -Xdoclint,
  // -Xdiags, -J

  // Java 9: --add-modules, --limit-modules, --module, --module-path, --module-source-path,
  // --module-version, --processor-module-path, --upgrade-module-path, --add-exports, --add-reads,
  // --doclint-format, --patch-module

  // See
  // https://github.com/codehaus-plexus/plexus-compiler/blob/b81b608d249a6b598f3adb7285bee6bbd855dfed/plexus-compilers/plexus-compiler-javac/src/main/java/org/codehaus/plexus/compiler/javac/JavacCompiler.java#L294
  // for --processor-module-path

  // Java 11: --enable-preview, --default-module-for-created-files

  // Valid values for -source vary with version.

  // Scala-2.11 for Java 8 only (-bootclasspath, -extdirs, -javabootclasspath, -javaextdirs)
  // Scala-2.11: -explaintypes, -feature, -g, -language, -optimise, -target, -unchecked, -uniqid,
  // -usejavacp
  // Scala-2.13: -P, -opt, -opt-inline-from, -opt-warnings, -release, (-optimize deprecated)
  // Also a lot of -V, -W, -X, and -Y options, -J

  // TODO Perhaps allow cval to take Optional<String>.

  // TODO Remove and rely on -Xlint:deprecation instead.
  @Parameter(defaultValue = "true")
  private boolean deprecation;

  @Parameter(property = "project.build.sourceEncoding", defaultValue = "UTF-8")
  private String encoding;


  @OptionName("-Werror")
  private COption<Boolean> failOnWarning;

  public static class COption<T> {
    T value;

    public void set(T t) {
      System.out.println(t);
      value = t;
    }
  }

  @Parameter Map<String, String> javacAnnotationProcessorOptions = new HashMap<>();

  @Parameter(defaultValue = "true")
  private boolean javacDebug;

  // TODO Only for JDK lower than 9? (Not with --release)
  @Parameter private String[] javacExtdirs = {};

  @Parameter(defaultValue = "false")
  private boolean javacParameters;

  @Parameter private String[] javacPlugins = {};

  @Parameter private String[] javacProcessors = {};

  @Parameter private JavacLint javacLint = new JavacLint();

  // TODO Can’t be used if release is specified
  @Parameter private String source;

  // TODO Can’t be used if release is specified
  @Parameter private String target;

  @Parameter(defaultValue = "false")
  private boolean verbose;

  public abstract static class Lint {
    protected String[] includes;
    protected String[] excludes;

    protected Lint() {
      this.includes = new String[] {};
      this.excludes = new String[] {};
    }

    public abstract String toString(String compilerPath)
        throws IOException, InterruptedException, MojoFailureException;

    @Override
    public final String toString() {
      return Stream.concat(stream(includes), stream(excludes).map("-"::concat))
          .collect(joining(","));
    }
  }

  public static final class JavacLint extends Lint {
    @Override
    public String toString(String compilerPath)
        throws IOException, InterruptedException, MojoFailureException {
      Process p = new ProcessBuilder(compilerPath, "-X").start();
      p.getOutputStream().close();
      Set<String> valid = emptySet();
      try (InputStream is = p.getInputStream();
          Scanner s = new Scanner(is, UTF_8.name())) {
        if (s.findWithinHorizon("-Xlint:\\{([^}]+)}", 0) != null)
          valid = stream(s.match().group(1).split(",")).collect(toSet());
        else if (s.findWithinHorizon(
                "-Xlint:<key>\\(,<key>\\)\\*\n(?: {8}.*\n)*((?: {10}.*\n)+)", 0)
            != null) {
          valid =
              stream(s.match().group(1).split("\n"))
                  .map(
                      y ->
                          Optional.of(Pattern.compile("^ {10}([^ ]+).*").matcher(y))
                              .filter(Matcher::matches)
                              .map(x -> x.group(1)))
                  .filter(Optional<String>::isPresent)
                  .map(Optional<String>::get)
                  .collect(toSet());
        } else throw failure("javacDoesNotAcceptXlint");
      }
      p.waitFor();
      Exceptionals.Optional.of(valid)
          .filter(not(Set<String>::isEmpty))
          .map(
              v ->
                  Stream.concat(stream(includes), stream(excludes))
                      .filter(not(v::contains))
                      .map(partial(AbstractCompileMojo::format, "quote"))
                      .sorted()
                      .collect(toList()))
          .map(s -> format("illegalJavacXlintKeys", compilerPath, s))
          .map(MojoFailureException::new)
          .ifPresentE(
              e -> {
                throw e;
              });
      return toString();
    }
  }


  private class Args {
    private String compiler;
    private List<String> args;

    Args(String compiler, String... args) {
      this.compiler = compiler;
      this.args = new ArrayList<>();
      asIs(args);
      flag("-Werror", failOnWarning.value);
      flag("-deprecation", deprecation);
      sval("-encoding", encoding);
      flag("-verbose", verbose);
    }

    private Args asIs(String... args) {
      asIs(asList(args));
      return this;
    }

    private Args asIs(List<String> args) {
      this.args.addAll(args);
      return this;
    }

    Args flag(String arg, boolean value) {
      return opti(Optional.of(value).filter(identityP()).map(constant(arg)));
    }

    Args path(String arg, Stream<String> paths) {
      return sval(arg, paths.collect(joining(pathSeparator)));
    }

    Args juxt(String arg, Stream<String> values) {
      values.map(arg::concat).forEach(this::asIs);
      return this;
    }

    Args coln(String arg, String... values) {
      coln(arg, stream(values));
      return this;
    }

    Args coln(String arg, Stream<String> values) {
      values.forEach(partialC(this::cval, arg));
      return this;
    }

    Args comm(String arg, String... values) {
      return sval(arg, stream(values).collect(joining(",")));
    }

    Args cval(String arg, String value) {
      return opti(
          Optional.ofNullable(value)
              .filter(not(String::isEmpty))
              .map(":"::concat)
              .map(arg::concat));
    }

    Args sval(String arg, String value) {
      return optl(
          Optional.ofNullable(value)
              .filter(not(String::isEmpty))
              .map(partial(Arrays::asList, arg)));
    }

    String[] toArray() {
      getLog()
          .debug(format("invokingCompilerWithArgs", compiler, args.stream().collect(joining(" "))));
      return args.toArray(new String[args.size()]);
    }

    private Args optl(Optional<List<String>> arg) {
      arg.ifPresent(this::asIs);
      return this;
    }

    private Args opti(Optional<String> arg) {
      arg.ifPresent(this::asIs);
      return this;
    }
  }


  // TODO Perhaps cache results of running javac?
  static final class JavacArgs {
    private final Set<String> valid;

    JavacArgs(String compilerPath) throws IOException, MojoFailureException {
      Process p = new ProcessBuilder(compilerPath, "-X").start();
      try {
        p.getOutputStream().close();
        Set<String> valid = emptySet();
        try (InputStream is = p.getInputStream();
            Reader isr = new InputStreamReader(is, UTF_8);
            BufferedReader r = new BufferedReader(isr)) {
          r.lines()
              .filter(s -> s.matches("^  -.*"))
              .flatMap(s -> stream(s.split(", ")))
              .map(String::trim)
              .map(Option::of)
              .flatMap(Exceptionals.Optional<Option>::stream);
        }
        this.valid = valid;
      } finally {
        p.destroyForcibly();
      }
    }
  }

  //   private abstract class Option {
  //     private final String name;

  //     Option(String name) { this.name = name; }

  //     List<String> toArguments(Map<String, String> m) { throw wrongType(); }

  //     List<String> toArguments(boolean b) { throw wrongType(); }

  //     List<String> toArguments(String b) { throw wrongType(); }

  //     List<String> toArguments(List<String> ss) { throw wrongType(); }

  //     public final String toString() { return name; }

  //     abstract IllegalArgumentException wrongType();

  //     Option combine(Option o) {
  //       throw new IllegalArgumentException(
  //           String.format("Option %s can’t be combined with option %s", this, o));
  //     }

  //     // TODO -opt <a>[,<b>,<c>...], -opt <value>, --opt <a>(,<a>)*, -opt <a>|none
  //     private static final List<Couple<Pattern, Function<MatchResult, Option>>> specifications =
  //         Stream.<Couple<Pattern, Function<MatchResult, Option>>>of(
  //             Couple.of(
  //               Pattern.compile("(-[A-Z])[^=]+\\[=[^]]+\\]"),
  //               m ->
  //                   new Option(m.group(1)) {
  //                     @Override
  //                     List<String> toArguments(Map<String, String> m) {
  //                       return m.entrySet().stream()
  //                           .map(
  //                               e ->
  //                                   e.getKey()
  //                                       + Optional.ofNullable(e.getValue())
  //                                           .filter(not(String::isEmpty))
  //                                           .map("="::concat)
  //                                           .orElse(""))
  //                           .map(toString()::concat)
  //                           .collect(toList());
  //                     }

  //                     @Override
  //                     IllegalArgumentException wrongType() {
  //                       return new IllegalArgumentException(
  //                           String.format("Argument to option %s must be a map", this));
  //                     }
  //                   }),
  //             Couple.of(
  //                 Pattern.compile("-[^: ]+"),
  //                 m ->
  //                     new Option(m.group(1)) {
  //                       @Override
  //                       List<String> toArguments(boolean b) {
  //                         return b ? singletonList(toString()) : emptyList();
  //                       }

  //                       @Override
  //                       IllegalArgumentException wrongType() {
  //                         return new IllegalArgumentException(
  //                             String.format("Option %s is a boolean", this));
  //                       }
  //                     }),
  //             Couple.of(
  //                 Pattern.compile("(-[^-][^:]*):\\{(.*)}"),
  //                 m ->
  //                     new Option(m.group(1)) {
  //                       @Override
  //                       List<String> toArguments(String s) {
  //                         return singletonList(String.format("%s:%s", this, s));
  //                       }

  //                       @Override
  //                       IllegalArgumentException wrongType() {
  //                         return new IllegalArgumentException(
  //                             String.format("Argument to option %s must be a String", this));
  //                       }
  //                     }),
  //             Couple.of(
  //                 Pattern.compile("(-[^ :]+) (?:<path>|<dirs>)"),
  //                 m ->
  //                     new Option(m.group(1)) {
  //                       @Override
  //                       List<String> toArguments(List<String> ss) {
  //                         return singletonList(String.format("%s %s", this, join(",", ss)));
  //                       }

  //                       @Override
  //                       IllegalArgumentException wrongType() {
  //                         return new IllegalArgumentException(
  //                             String.format(
  //                                 "Argument to option %s must be a List of Strings", this));
  //                       }
  //                     }),
  //             Couple.of(
  //                 Pattern.compile("(-[^ :]+) <[^>]+>"),
  //                 m ->
  //                     new Option(m.group(1)) {
  //                       @Override
  //                       List<String> toArguments(String s) {
  //                         return singletonList(String.format("%s:%s", this, s));
  //                       }

  //                       @Override
  //                       IllegalArgumentException wrongType() {
  //                         return new IllegalArgumentException(
  //                             String.format("Argument to option %s must be a String", this));
  //                       }
  //                     })
  //         )
  //         .collect(collectingAndThen(toList(), Collections::unmodifiableList));

  //     static Exceptionals.Optional<Option> of(String specification) {
  //       return specifications.stream()
  //           .map(e -> e.mapFst(p -> p.matcher(specification)))
  //           filter(e -> e.fst().matches())
  //           .findFirst()
  //           .map(e -> e.snd().apply(e.fst().matchResult()));
  //       /*
  //       // TODO Uppercase, single-letter options (-A, -D, -J) can be repeated.
  //       if (specification.matches()) {
  //         optionType = MapOption.class; /// ???
  //       } else if (specification.matches("-([^-][^:]*):\\{(.*)}")) {
  //         optionType = EnumOption.class;
  //         values = m.group(2).split(",");
  //       }

  //       // TODO Determine if arg takes an argument, and, if so, what its valid values/type is and
  //       // how it’s separated from its argument.
  //       if (takesArgument) {
  //         return new Option() {
  //           @Override
  //           public List<String> toList(List<String> arguments) {
  //             return String.format("%s%s%s", name, separator, String.join(arguments, argumentsSeparator));
  //           }
  //         };
  //       } else {
  //         return new Option() {
  //           @Override
  //           public List<String> toList(List<String> arguments) {
  //             if (!arguments.isEmpty())
  //               throw new IllegalArgumentException(
  //                   String.format("Option %s doesn’t take an argument", name));
  //             return singletonList(name);
  //           }
  //         };
  //       }
  //       */
  //     }
  //   }
  // }

                        new Args("Scala", scalacArgs) {
                          {
                            Function<Stream<String>, String> f =
                                fpartial(Stream<String>::collect, joining(pathSeparator));
                            coln(
                                "-Xplugin",
                                Exceptionals.stream(scalacPlugins)
                                    .mapE(AbstractCompileMojo.this::dependenciesPaths)
                                    .map(
                                        partial(
                                            flip(Stream::filter),
                                            not(
                                                scalaJars.stream()
                                                        .map(File::getAbsolutePath)
                                                        .collect(toSet())
                                                    ::contains)))
                                    .map(f));
                            cval(
                                "-target",
                                scalaInstance.actualVersion().startsWith("2.11")
                                    ? Optional.ofNullable(target)
                                        .filter(not(String::isEmpty))
                                        .map("jvm-1."::concat)
                                        .orElse(null)
                                    : target);
                          }
                        }.toArray(),
                        new Args("Java", javacArgs)
                            .juxt(
                                "-A",
                                javacAnnotationProcessorOptions.entrySet().stream()
                                    .map(
                                        e ->
                                            e.getKey()
                                                + Optional.ofNullable(e.getValue())
                                                    .filter(not(String::isEmpty))
                                                    .map("="::concat)
                                                    .orElse("")))
                            .coln("-Xlint", javacLint.toString(tool("javac")))
                            .coln("-Xplugin", javacPlugins)
                            .comm("-processor", javacProcessors)
                            .flag("-g", javacDebug)
                            .sval(
                                "-s",
                                Optional.ofNullable(javacGeneratedSourcesDirectory())
                                    .map(File::getAbsolutePath)
                                    .orElse(null))
                            .sval("-source", source)
                            .flag("-parameters", javacParameters)
                            .path("--processor-path", dependenciesPaths(javacProcessorDependencies))
                            .sval("-target", target)
                            .toArray(),

  private Stream<String> dependenciesPaths(Dependency... dependencies)
      throws ArtifactResolutionException {
    // TODO Validate that groupId, artifactId, and version are set.
    return Exceptionals.stream(dependencies)
        .flatMapE(
            d ->
                resolve(
                    repositorySystem.createArtifact(
                        d.getGroupId(),
                        d.getArtifactId(),
                        d.getVersion(),
                        SCOPE_RUNTIME,
                        d.getType()),
                    true,
                    true)
                    .stream())
        .map(a -> a.getFile().getAbsolutePath());
  }

  @Parameter private Dependency[] javacProcessorDependencies = {};

  public static final class Dependency {
    private String groupId;
    private String artifactId;
    private String version;
    private String classifier;
    private String type = "jar";

    public String getGroupId() {
      return groupId;
    }

    public void setGroupId(String groupId) {
      this.groupId = groupId;
    }

    public String getArtifactId() {
      return artifactId;
    }

    public void setArtifactId(String artifactId) {
      this.artifactId = artifactId;
    }

    public String getVersion() {
      return version;
    }

    public void setVersion(String version) {
      this.version = version;
    }

    public String getClassifier() {
      return classifier;
    }

    public void setClassifier(String classifier) {
      this.classifier = classifier;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    @Override
    public int hashCode() {
      return Objects.hash(groupId, artifactId, version, classifier, type);
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || (o instanceof Dependency
              && Objects.equals(((Dependency) o).getGroupId(), getGroupId())
              && Objects.equals(((Dependency) o).getArtifactId(), getArtifactId())
              && Objects.equals(((Dependency) o).getVersion(), getVersion())
              && Objects.equals(((Dependency) o).getClassifier(), getClassifier())
              && Objects.equals(((Dependency) o).getType(), getType()));
    }

    @Override
    public String toString() {
      return asList(getGroupId(), getArtifactId(), getVersion(), getClassifier()).stream()
          .map(s -> Optional.ofNullable(s).orElse(""))
          .collect(joining(":", "", Optional.ofNullable(type).map("."::concat).orElse("")));
    }
  }
