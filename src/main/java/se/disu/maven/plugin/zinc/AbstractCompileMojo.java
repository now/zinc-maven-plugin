package se.disu.maven.plugin.zinc;

import static java.lang.System.getProperty;
import static java.lang.System.getenv;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.newInputStream;
import static java.nio.file.Files.walk;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.Optional.empty;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.partitioningBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.apache.maven.artifact.Artifact.SCOPE_RUNTIME;
import static sbt.internal.inc.FileAnalysisStore.binary;
import static sbt.internal.inc.ZincUtil.compilers;
import static sbt.internal.inc.ZincUtil.defaultIncrementalCompiler;
import static sbt.internal.inc.classpath.ClasspathUtil.rootLoader;
import static sbt.io.IO.jar;
import static sbt.io.IO.unzip;
import static sbt.util.Level.Debug;
import static sbt.util.Level.Error;
import static sbt.util.Level.Info;
import static sbt.util.Level.Warn;
import static scala.jdk.CollectionConverters.IterableHasAsScala;
import static scala.jdk.FunctionWrappers.FromJavaConsumer;
import static scala.jdk.FunctionWrappers.FromJavaSupplier;
import static xsbti.compile.AnalysisStore.getCachedStore;
import static xsbti.compile.ClasspathOptionsUtil.auto;
import static xsbti.compile.ClasspathOptionsUtil.boot;
import static xsbti.compile.CompileOrder.Mixed;
import static xsbti.compile.CompilerCache.fresh;
import static xsbti.compile.ZincCompilerUtil.constantBridgeProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ResolutionErrorHandler;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.util.DirectoryScanner;
import sbt.internal.inc.AnalyzingCompiler;
import sbt.internal.inc.FileAnalysisStore;
import sbt.internal.inc.Locate;
import sbt.internal.inc.PlainVirtualFile;
import sbt.internal.inc.PlainVirtualFileConverter;
import sbt.internal.inc.RawCompiler;
import sbt.internal.inc.ScalaInstance;
import sbt.io.AllPassFilter$;
import sbt.util.Logger;
import scala.Enumeration;
import scala.Function0;
import scala.Option;
import scala.Tuple2;
import se.disu.util.Choice;
import se.disu.util.Couple;
import xsbti.PathBasedFile;
import xsbti.Position;
import xsbti.Problem;
import xsbti.Reporter;
import xsbti.Severity;
import xsbti.T2;
import xsbti.VirtualFile;
import xsbti.compile.AnalysisContents;
import xsbti.compile.AnalysisStore;
import xsbti.compile.CompileAnalysis;
import xsbti.compile.CompileOptions;
import xsbti.compile.CompileResult;
import xsbti.compile.DefinesClass;
import xsbti.compile.IncOptions;
import xsbti.compile.Inputs;
import xsbti.compile.PerClasspathEntryLookup;
import xsbti.compile.PreviousResult;
import xsbti.compile.Setup;

public abstract class AbstractCompileMojo extends AbstractMojo {
  private static final Pattern CROSS_ID_PATTERN =
      Pattern.compile("^(.+)_(\\d+\\.\\d+(?:\\.\\d+)?(?:-.+)?)$");
  private static final String INCREMENTALCOMPILER_VERSION_PROPERTIES =
      "incrementalcompiler.version.properties";
  private static final Path META_INF_MANIFEST_MF = Paths.get("META-INF", "MANIFEST.MF");
  private static final String SBT_GROUP_ID = "org.scala-sbt";
  private static final String SCALA_GROUP_ID = "org.scala-lang";
  private static final String SCALA_COMPILER_ARTIFACT_ID = "scala-compiler";
  private static final String SCALA_LIBRARY_ARTIFACT_ID = "scala-library";

  private static final Function<String, String> compilerBridges =
      Stream.<Couple<Predicate<String>, String>>of(
              Couple.of(v -> v.equals("2.13.0-M1"), "compiler-bridge_2.12"),
              Couple.of(v -> v.startsWith("2.11."), "compiler-bridge_2.11"),
              Couple.of(v -> v.startsWith("2.12."), "compiler-bridge_2.12"),
              Couple.of(v -> v.startsWith("2.13."), "compiler-bridge_2.13"))
          .collect(
              collectingAndThen(
                  toList(),
                  cs ->
                      v ->
                          cs.stream()
                              .filter(c -> c.fst().test(v))
                              .findFirst()
                              .map(Couple<Predicate<String>, String>::snd)
                              .orElseThrow(
                                  () -> UncheckedMojoExecutionException.of("scalaTooNew", v))));
  private static final Function<Enumeration.Value, BiConsumer<Log, String>> levels =
      Stream.<Couple<Enumeration.Value, BiConsumer<Log, String>>>of(
              Couple.of(Debug(), Log::debug),
              Couple.of(Error(), Log::error),
              Couple.of(Info(), Log::info),
              Couple.of(Warn(), Log::warn))
          .collect(
              collectingAndThen(
                  toMap(c -> c.fst(), c -> c.snd()),
                  levels ->
                      l ->
                          Optional.ofNullable(levels.get(l))
                              .orElseThrow(
                                  () ->
                                      UncheckedMojoExecutionException.of(
                                          "unsupportedLogLevel", l))));
  private static final List<
          Function<Couple<Enumeration.Value, String>, Couple<Enumeration.Value, String>>>
      logFilters =
          asList(
              r ->
                  Optional.of("[" + r.fst().toString() + "] ")
                      .filter(p -> r.snd().regionMatches(true, 0, p, 0, p.length()))
                      .map(p -> r.mapSnd(s -> s.substring(p.length())))
                      .orElse(r),
              logFilter(Warn(), "(?s)Unexpected javac output: error: invalid flag: ([^\n]+)\n.*"));
  private static final ArtifactVersion minimumScalaVersion = new DefaultArtifactVersion("2.11.12");
  private static final List<String> scalaArtifactIds =
      asList(SCALA_LIBRARY_ARTIFACT_ID, "scala-reflect", SCALA_COMPILER_ARTIFACT_ID);

  @Parameter(property = "session", readonly = true, required = true)
  protected MavenSession session;

  /**
   * Directory where Zinc analysis files will be stored and where Zinc compiler bridges will be
   * built.
   */
  @Parameter(defaultValue = "${project.build.directory}/zinc")
  private File cacheDirectory;

  /** Patterns to exclude from compilation. Defaults to excluding {@code package-info.java}. */
  @Parameter private String[] excludes = {"**/package-info.java"};

  /**
   * Patters to include during compilation. Defaults to including {@code *.java} and {@code
   * *.scala}.
   */
  @Parameter private String[] includes = {"**/*.java", "**/*.scala"};

  /** Arguments to pass to the Java compiler. */
  @Parameter private String[] javacArguments = {};

  /** Directory where compiler bridges will be installed. */
  @Parameter(defaultValue = "${user.home}/.sbt/1.0/zinc/org.scala-sbt")
  private File sbtZincCacheDirectory;

  /** Arguments to pass to the Scala compiler. */
  @Parameter private String[] scalacArguments = {};

  @Component private RepositorySystem repositorySystem;

  @Component private ResolutionErrorHandler resolutionErrorHandler;

  @Component private ToolchainManager toolchainManager;

  private final Function<Path, Optional<File>> findAnalysisCacheFile =
      Stream.<Couple<Function<Build, String>, String>>of(
              Couple.of(Build::getOutputDirectory, "compile"),
              Couple.of(Build::getTestOutputDirectory, "test-compile"))
          .collect(
              collectingAndThen(
                  toList(),
                  cs ->
                      p ->
                          cs.stream()
                              .filter(
                                  c ->
                                      p.equals(
                                          Paths.get(
                                              c.fst()
                                                  .apply(session.getCurrentProject().getBuild()))))
                              .findFirst()
                              .map(c -> c.snd())
                              .map(n -> new File(new File(cacheDirectory, "analysis"), n))));

  @Override
  public void execute() throws MojoExecutionException {
    try {
      doExecute();
    } catch (UncheckedMojoExecutionException e) {
      throw e.getCause();
    }
  }

  protected abstract List<String> classpath();

  protected abstract String outputDirectory();

  protected abstract List<String> sourceDirectories();

  private void doExecute() {
    if (session.getCurrentProject().getPackaging().equals("pom")) {
      return;
    }
    ArtifactVersion scalaVersion =
        Stream.concat(
                session.getCurrentProject().getArtifacts().stream(),
                This.dependencyArtifacts(session, repositorySystem).stream())
            .filter(a -> a.getGroupId().equals(SCALA_GROUP_ID))
            .filter(a -> a.getArtifactId().equals(SCALA_LIBRARY_ARTIFACT_ID))
            .findAny()
            .map(Artifact::getVersion)
            .map(DefaultArtifactVersion::new)
            .orElseThrow(
                () ->
                    UncheckedMojoExecutionException.of(
                        "missingDependency", SCALA_GROUP_ID, SCALA_LIBRARY_ARTIFACT_ID));
    if (scalaVersion.compareTo(minimumScalaVersion) < 0)
      throw UncheckedMojoExecutionException.of("scalaTooOld", scalaVersion, minimumScalaVersion);
    session.getCurrentProject().getArtifacts().stream()
        .filter(a -> crossId(a, "").isEmpty())
        .collect(
            groupingBy(
                a -> a.getGroupId() + ":" + crossId(a, "$1"),
                mapping(a -> crossId(a, "$2"), toSet())))
        .entrySet()
        .stream()
        .filter(e -> e.getValue().size() > 1)
        .sorted(comparing(e -> e.getKey()))
        .map(e -> new Object[] {e.getKey(), e.getValue().stream().sorted().toArray(Object[]::new)})
        .collect(collectingAndThen(toList(), Optional::of))
        .filter(os -> !os.isEmpty())
        .map(os -> UncheckedMojoExecutionException.of("conflictingCrossVersionSuffixes", os))
        .ifPresent(
            e -> {
              throw e;
            });
    List<Path> normalizedSourceDirectories =
        sourceDirectories().stream()
            .distinct()
            .map(Paths::get)
            .map(Path::normalize)
            .collect(toList());
    List<Path> existingSourceDirectories =
        normalizedSourceDirectories.stream().filter(Files::isDirectory).collect(toList());
    List<Path> sources =
        existingSourceDirectories.stream()
            .flatMap(
                d -> {
                  DirectoryScanner s = new DirectoryScanner();
                  s.setBasedir(d.toFile());
                  s.setIncludes(includes);
                  s.setExcludes(excludes);
                  s.addDefaultExcludes();
                  s.scan();
                  return Stream.of(s.getIncludedFiles()).map(d::resolve);
                })
            .sorted()
            .collect(toList());
    if (sources.isEmpty()) {
      List<Path> nonExistingSourceDirectories =
          normalizedSourceDirectories.stream()
              .filter(p -> !existingSourceDirectories.contains(p))
              .collect(toList());
      getLog()
          .info(
              Messages.format(
                  "noSourcesToCompile",
                  existingSourceDirectories,
                  existingSourceDirectories.size(),
                  nonExistingSourceDirectories,
                  nonExistingSourceDirectories.size()));
      return;
    }
    Logger logger =
        new Logger() {
          @Override
          public void log(Enumeration.Value level, Function0<String> message) {
            logFilters.stream()
                .reduce(
                    Couple.of(level, message.apply()),
                    (r, f) -> f.apply(r),
                    (a, b) -> {
                      throw new AssertionError();
                    })
                .mapFst(levels::apply)
                .accept((f, s) -> f.accept(getLog(), s));
          }

          @Override
          public void success(Function0<String> message) {
            log(Info(), message);
          }

          @Override
          public void trace(Function0<Throwable> t) {
            getLog().debug(t.apply());
          }
        };
    List<File> scalaJars =
        scalaArtifactIds.stream()
            .map(
                id -> {
                  Artifact a =
                      repositorySystem.createArtifact(
                          SCALA_GROUP_ID, id, scalaVersion.toString(), SCOPE_RUNTIME, "jar");
                  resolve(a, true, false, "cannotResolveScalaArtifact");
                  return a.getFile();
                })
            .collect(toList());
    ScalaInstance scalaInstance =
        new ScalaInstance(
            scalaVersion.toString(),
            new URLClassLoader(
                scalaJars.stream()
                    .map(File::toURI)
                    .map(
                        UncheckedMojoExecutionException.of(
                            URI::toURL, "cannotConvertUriToUrlForClassLoader"))
                    .toArray(URL[]::new)),
            rootLoader(),
            scalaJars.get(0),
            scalaJars.get(2),
            Stream.concat(
                    scalaJars.stream(),
                    resolve(
                            repositorySystem.createArtifact(
                                SCALA_GROUP_ID,
                                SCALA_COMPILER_ARTIFACT_ID,
                                scalaVersion.toString(),
                                "",
                                "pom"),
                            false,
                            true,
                            "cannotResolveScalaCompilerArtifact")
                        .stream()
                        .map(Artifact::getFile))
                .distinct()
                .toArray(File[]::new),
            Option.apply(scalaVersion.toString()));
    String bridgeArtifactId = compilerBridges.apply(scalaInstance.actualVersion());
    Properties zincProperties = new Properties();
    try (InputStream is =
        getClass().getClassLoader().getResourceAsStream(INCREMENTALCOMPILER_VERSION_PROPERTIES)) {
      zincProperties.load(
          Optional.ofNullable(is)
              .orElseThrow(
                  () ->
                      UncheckedMojoExecutionException.of(
                          "cannotFindResource", INCREMENTALCOMPILER_VERSION_PROPERTIES)));
    } catch (IOException e) {
      throw UncheckedMojoExecutionException.of(
          e, "cannotLoadPropertiesFromResource", INCREMENTALCOMPILER_VERSION_PROPERTIES);
    }
    String zincVersion = zincProperties.getProperty("version");
    File compilerBridgeJar =
        new File(
            sbtZincCacheDirectory,
            String.format(
                "%s-%s-%s-bin_%s__%s-%s_%s.jar",
                SBT_GROUP_ID,
                bridgeArtifactId,
                zincVersion,
                scalaInstance.actualVersion(),
                getProperty("java.class.version"),
                zincVersion,
                zincProperties.getProperty("timestamp")));
    if (!compilerBridgeJar.exists()) {
      Artifact bridgeSources =
          repositorySystem.createArtifactWithClassifier(
              SBT_GROUP_ID, bridgeArtifactId, zincVersion, "jar", "sources");
      resolve(bridgeSources, true, false, "cannotResolveBridgeSourcesArtifact");
      Path sourcesDirectory = new File(cacheDirectory, "sources").toPath();
      createDirectories(sourcesDirectory, "cannotCreateDirectoryForCompilerBridgeSources");
      unzip(bridgeSources.getFile(), sourcesDirectory.toFile(), AllPassFilter$.MODULE$, true);
      Path classesDirectory = new File(cacheDirectory, "classes").toPath();
      Map<Boolean, List<Path>> paths;
      try (Stream<Path> ps = walk(sourcesDirectory)) {
        paths =
            ps.filter(Files::isRegularFile)
                .collect(partitioningBy(p -> p.toString().endsWith(".scala")));
      } catch (IOException e) {
        throw UncheckedMojoExecutionException.of(e, "cannotWalkSourcesDirectory", sourcesDirectory);
      }
      createDirectories(classesDirectory, "cannotCreateDirectoryForComplierBrideClasses");
      new RawCompiler(scalaInstance, auto(), logger)
          .apply(
              IterableHasAsScala(paths.get(true)).asScala().toSeq(),
              Stream.concat(
                      resolve(bridgeSources, true, true, "cannotResolveBridgeSourcesArtifact")
                          .stream()
                          .filter(a -> a.getScope() != null)
                          .filter(a -> !a.getScope().equals("provided"))
                          .map(Artifact::getFile),
                      stream(scalaInstance.allJars()))
                  .map(File::toPath)
                  .collect(collectingAndThen(toSet(), s -> IterableHasAsScala(s).asScala()))
                  .toSeq(),
              classesDirectory,
              IterableHasAsScala(singletonList("-nowarn")).asScala().toSeq());
      Path metaInfManifestMf = sourcesDirectory.resolve(META_INF_MANIFEST_MF);
      Manifest manifest = new Manifest();
      try (InputStream is = newInputStream(metaInfManifestMf)) {
        manifest.read(is);
      } catch (IOException e) {
        throw UncheckedMojoExecutionException.of(e, "cannotLoadManifest", metaInfManifestMf);
      }
      sbtZincCacheDirectory.mkdirs();
      try (Stream<Path> ps = walk(classesDirectory)) {
        jar(
            Stream.of(
                    Couple.of(ps, classesDirectory),
                    Couple.of(
                        paths.get(false).stream().filter(p -> !p.endsWith(META_INF_MANIFEST_MF)),
                        sourcesDirectory))
                .flatMap(c -> c.fst().map(p -> new Tuple2<>(p.toFile(), zipPath(p, c.snd()))))
                .collect(collectingAndThen(toList(), l -> IterableHasAsScala(l).asScala())),
            compilerBridgeJar,
            manifest,
            Option.apply(null));
      } catch (IOException e) {
        throw UncheckedMojoExecutionException.of(e, "cannotBuildJar", classesDirectory);
      }
    }
    File analysisCacheFile = findAnalysisCacheFile.apply(Paths.get(outputDirectory())).get();
    AnalysisStore analysisStore = getCachedStore(binary(analysisCacheFile));
    @SuppressWarnings({"rawtypes", "unchecked"})
    T2<String, String>[] t2s = (T2<String, String>[]) new T2[] {};
    CompileResult cr =
        defaultIncrementalCompiler()
            .compile(
                Inputs.of(
                    compilers(
                        scalaInstance,
                        boot(),
                        Option.apply(
                            Optional.of(Paths.get(tool("java")))
                                .map(Path::getParent)
                                .map(Path::getParent)
                                .orElse(null)),
                        new AnalyzingCompiler(
                            scalaInstance,
                            constantBridgeProvider(scalaInstance, compilerBridgeJar),
                            auto(),
                            new FromJavaConsumer<>(p -> {}),
                            Option.apply(null))),
                    CompileOptions.of(
                        Stream.concat(Stream.of(outputDirectory()), classpath().stream())
                            .map(Paths::get)
                            .map(PlainVirtualFile::new)
                            .toArray(VirtualFile[]::new),
                        sources.stream().map(PlainVirtualFile::new).toArray(VirtualFile[]::new),
                        Paths.get(outputDirectory()),
                        scalacArguments,
                        javacArguments,
                        0,
                        identity(),
                        Mixed,
                        empty(),
                        Optional.of(PlainVirtualFileConverter.converter()),
                        empty(),
                        empty()),
                    Setup.of(
                        new PerClasspathEntryLookup() {
                          @Override
                          public Optional<CompileAnalysis> analysis(VirtualFile cpe) {
                            return Optional.of(((PathBasedFile) cpe).toPath())
                                .filter(Files::isDirectory)
                                .flatMap(findAnalysisCacheFile)
                                .filter(File::exists)
                                .map(FileAnalysisStore::binary)
                                .map(AnalysisStore::getCachedStore)
                                .flatMap(AnalysisStore::get)
                                .map(AnalysisContents::getAnalysis);
                          }

                          @Override
                          public DefinesClass definesClass(VirtualFile cpe) {
                            return Locate.definesClass(cpe);
                          }
                        },
                        false,
                        analysisCacheFile,
                        fresh(),
                        IncOptions.of(),
                        new LoggerReporter(logger),
                        empty(),
                        t2s),
                    analysisStore
                        .get()
                        .map(c -> Couple.of(c.getAnalysis(), c.getMiniSetup()))
                        .map(c -> c.map(Optional::of, Optional::of))
                        .map(c -> c.apply(PreviousResult::of))
                        .orElseGet(() -> PreviousResult.of(empty(), empty()))),
                logger);
    Choice.of(cr.hasModified())
        .filter(b -> b)
        .ifPresentOrElse(
            b -> analysisStore.set(AnalysisContents.create(cr.analysis(), cr.setup())),
            () -> getLog().info(Messages.format("allClassesAreUpToDate")));
    Optional.of(new File(outputDirectory()))
        .filter(File::isDirectory)
        .ifPresent(session.getCurrentProject().getArtifact()::setFile);
  }

  private static final Function<
          Couple<Enumeration.Value, String>, Couple<Enumeration.Value, String>>
      logFilter(Enumeration.Value level, String pattern) {
    Pattern p = Pattern.compile(pattern);
    return c ->
        Optional.of(c)
            .filter(r -> r.fst().equals(level))
            .map(r -> p.matcher(r.snd()))
            .filter(Matcher::matches)
            .map(m -> Couple.of(Error(), m.group(1)))
            .orElse(c);
  }

  private String crossId(Artifact a, String replacement) {
    return CROSS_ID_PATTERN.matcher(a.getArtifactId()).replaceFirst(replacement);
  }

  private String tool(String name) {
    return Choice.of(toolchainManager.getToolchainFromBuildContext("jdk", session))
        .map(c -> c.findTool(name))
        .or(
            () ->
                Choice.of(getenv("JAVA_HOME"))
                    .or(
                        () ->
                            Choice.of(getProperty("java.home"))
                                .map(Paths::get)
                                .map(Path::getParent)
                                .map(Path::toString))
                    .map(Paths::get)
                    .map(p -> p.resolve("bin"))
                    .map(p -> p.resolve(name))
                    .map(Path::toString))
        .orElseThrow(() -> UncheckedMojoExecutionException.of("cannotLocateJava"));
  }

  private void createDirectories(Path p, String messageKey) {
    try {
      Files.createDirectories(p);
    } catch (IOException e) {
      throw UncheckedMojoExecutionException.of(e, messageKey, p);
    }
  }

  private Set<Artifact> resolve(
      Artifact a, boolean resolveRoot, boolean resolveTransitively, String messageKey) {
    return resolve(
        session,
        repositorySystem,
        resolutionErrorHandler,
        a,
        resolveRoot,
        resolveTransitively,
        messageKey);
  }

  static Set<Artifact> resolve(
      MavenSession s,
      RepositorySystem rs,
      ResolutionErrorHandler h,
      Artifact a,
      boolean resolveRoot,
      boolean resolveTransitively,
      String messageKey) {
    ArtifactResolutionRequest q =
        new ArtifactResolutionRequest()
            .setArtifact(a)
            .setCollectionFilter(c -> !c.isOptional())
            .setLocalRepository(s.getLocalRepository())
            .setMirrors(s.getRequest().getMirrors())
            .setProxies(s.getRequest().getProxies())
            .setResolveRoot(resolveRoot)
            .setResolveTransitively(resolveTransitively)
            .setRemoteRepositories(s.getRequest().getRemoteRepositories())
            .setServers(s.getRequest().getServers());
    ArtifactResolutionResult r = rs.resolve(q);
    try {
      h.throwErrors(q, r);
    } catch (ArtifactResolutionException e) {
      throw UncheckedMojoExecutionException.of(e, messageKey, a);
    }
    return r.getArtifacts();
  }

  private String zipPath(Path p, Path root) {
    return root.relativize(p).toString().replace(File.separator, "/") + (isDirectory(p) ? "/" : "");
  }

  private static final class LoggerReporter implements Reporter {
    private static final Map<Severity, Enumeration.Value> values =
        Stream.<Couple<Severity, Enumeration.Value>>of(
                Couple.of(Severity.Error, Error()),
                Couple.of(Severity.Info, Info()),
                Couple.of(Severity.Warn, Warn()))
            .collect(
                collectingAndThen(
                    toMap(
                        c -> c.fst(),
                        c -> c.snd(),
                        (a, b) -> {
                          throw new AssertionError();
                        },
                        () -> new EnumMap<Severity, Enumeration.Value>(Severity.class)),
                    Collections::unmodifiableMap));
    private Logger logger;
    private List<Problem> problems;

    LoggerReporter(Logger l) {
      logger = l;
      reset();
    }

    @Override
    public void comment(Position p, String comment) {
      log(Severity.Info, p, comment);
    }

    @Override
    public boolean hasErrors() {
      return problems.stream().anyMatch(p -> p.severity() == Severity.Error);
    }

    @Override
    public boolean hasWarnings() {
      return problems.stream().anyMatch(p -> p.severity() == Severity.Warn);
    }

    @Override
    public void log(Problem p) {
      problems.add(p);
      log(p.severity(), p.position(), p.message());
    }

    private void log(Severity s, Position p, String message) {
      logger.log(
          Optional.of(values.get(s))
              .orElseThrow(() -> UncheckedMojoExecutionException.of("unknownSeverity", s)),
          new FromJavaSupplier<>(
              () ->
                  new StringBuilder()
                      .append(p.sourcePath().orElse("<unknown source>"))
                      .append(":")
                      .append(p.line().map(l -> l + ":").orElse(""))
                      .append(p.offset().map(o -> (o + 1) + ":").orElse(""))
                      .append(" ")
                      .append(message.trim())
                      .append(p.lineContent().isEmpty() ? "" : "\n")
                      .append(p.lineContent().replaceFirst("((\r?)\n)+\\z", ""))
                      .toString()));
    }

    @Override
    public void printSummary() {}

    @Override
    public Problem[] problems() {
      return problems.toArray(new Problem[problems.size()]);
    }

    @Override
    public void reset() {
      problems = new ArrayList<>();
    }
  }
}
