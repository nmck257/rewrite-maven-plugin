package org.openrewrite.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.marker.BuildTool;
import org.openrewrite.marker.Generated;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.marker.Marker;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.cache.RocksdbMavenPomCache;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.style.NamedStyles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.openrewrite.Tree.randomId;

public class MavenMojoProjectParser {
    private final Log logger;
    private final RuntimeInformation runtime;
    private final MavenProject mavenProject;
    private final List<Marker> projectProvenance;

    public MavenMojoProjectParser(Log logger, MavenProject mavenProject, RuntimeInformation runtime) {
        this.logger = logger;
        this.mavenProject = mavenProject;
        this.runtime = runtime;

        String javaRuntimeVersion = System.getProperty("java.runtime.version");
        String javaVendor = System.getProperty("java.vm.vendor");
        String sourceCompatibility = javaRuntimeVersion;
        String targetCompatibility = javaRuntimeVersion;

        String propertiesSourceCompatibility = (String) mavenProject.getProperties().get("maven.compiler.source");
        if (propertiesSourceCompatibility != null) {
            sourceCompatibility = propertiesSourceCompatibility;
        }
        String propertiesTargetCompatibility = (String) mavenProject.getProperties().get("maven.compiler.target");
        if (propertiesTargetCompatibility != null) {
            targetCompatibility = propertiesTargetCompatibility;
        }

        projectProvenance = Arrays.asList(
                new BuildTool(randomId(), BuildTool.Type.Maven, runtime.getMavenVersion()),
                new JavaVersion(randomId(), javaRuntimeVersion, javaVendor, sourceCompatibility, targetCompatibility),
                new JavaProject(randomId(), mavenProject.getName(), new JavaProject.Publication(
                        mavenProject.getGroupId(),
                        mavenProject.getArtifactId(),
                        mavenProject.getVersion()
                ))
        );
    }

    public Maven parseMaven(Path baseDir, boolean pomCacheEnabled, @Nullable String pomCacheDirectory, ExecutionContext ctx) {
        List<Path> allPoms = new ArrayList<>();
        allPoms.add(mavenProject.getFile().toPath());

        // children
        if (mavenProject.getCollectedProjects() != null) {
            mavenProject.getCollectedProjects().stream()
                    .filter(collectedProject -> collectedProject != mavenProject)
                    .map(collectedProject -> collectedProject.getFile().toPath())
                    .forEach(allPoms::add);
        }

        MavenProject parent = mavenProject.getParent();
        while (parent != null && parent.getFile() != null) {
            allPoms.add(parent.getFile().toPath());
            parent = parent.getParent();
        }
        MavenParser.Builder mavenParserBuilder = MavenParser.builder()
                .mavenConfig(baseDir.resolve(".mvn/maven.config"));

        if (pomCacheEnabled) {
            try {
                if (pomCacheDirectory == null) {
                    //Default directory in the RocksdbMavenPomCache is ".rewrite-cache"
                    mavenParserBuilder.cache(new RocksdbMavenPomCache(Paths.get(System.getProperty("user.home"))));
                } else {
                    mavenParserBuilder.cache(new RocksdbMavenPomCache(Paths.get(pomCacheDirectory)));
                }
            } catch (Exception e) {
                logger.warn("Unable to initialize RocksdbMavenPomCache, falling back to InMemoryMavenPomCache");
                logger.debug(e);
                mavenParserBuilder.cache(new InMemoryMavenPomCache());
            }
        }

        Path mavenSettings = Paths.get(System.getProperty("user.home")).resolve(".m2/settings.xml");
        if (mavenSettings.toFile().exists()) {
            MavenSettings settings = MavenSettings.parse(new Parser.Input(mavenSettings,
                            () -> {
                                try {
                                    return Files.newInputStream(mavenSettings);
                                } catch (IOException e) {
                                    logger.warn("Unable to load Maven settings from user home directory. Skipping.", e);
                                    return null;
                                }
                            }),
                    ctx);
            if (settings != null) {
                new MavenExecutionContextView(ctx).setMavenSettings(settings);
                if (settings.getActiveProfiles() != null) {
                    mavenParserBuilder.activeProfiles(settings.getActiveProfiles().getActiveProfiles().toArray(new String[]{}));
                }
            }
        }

        Maven mavenSource = mavenParserBuilder
                .build()
                .parse(allPoms, baseDir, ctx)
                .iterator()
                .next();
        for (Marker marker : projectProvenance) {
            mavenSource = mavenSource.withMarkers(mavenSource.getMarkers().addIfAbsent(marker));
        }
        return mavenSource;
    }

    public List<SourceFile> listSourceFiles(Path baseDir, Iterable<NamedStyles> styles,
                                            ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {
        JavaParser javaParser = JavaParser.fromJavaVersion()
                .relaxedClassTypeMatching(true)
                .styles(styles)
                .logCompilationWarningsAndErrors(false)
                .build();

        // Some annotation processors output generated sources to the /target directory
        List<Path> generatedSourcePaths = listJavaSources(mavenProject.getBuild().getDirectory());
        List<Path> mainJavaSources = Stream.concat(
                generatedSourcePaths.stream(),
                listJavaSources(mavenProject.getBuild().getSourceDirectory()).stream()
            ).collect(toList());

        logger.info("Parsing Java main files...");
        List<SourceFile> mainJavaSourceFiles = new ArrayList<>(512);
        List<Path> dependencies = mavenProject.getCompileClasspathElements().stream()
                .distinct()
                .map(Paths::get)
                .collect(toList());

        javaParser.setClasspath(dependencies);
        mainJavaSourceFiles.addAll(javaParser.parse(mainJavaSources, baseDir, ctx));

        //Add provenance information to main source files
        JavaSourceSet mainProvenance = JavaSourceSet.build("main", dependencies, ctx);
        List<SourceFile> sourceFiles = new ArrayList<>(
                ListUtils.map(mainJavaSourceFiles, addProvenance(baseDir, projectProvenance, mainProvenance, generatedSourcePaths))
        );

        logger.info("Parsing Java test files...");
        List<SourceFile> testJavaSourceFiles = new ArrayList<>(512);
        List<Path> testDependencies = mavenProject.getTestClasspathElements().stream()
                .distinct()
                .map(Paths::get)
                .collect(toList());

        javaParser.setClasspath(testDependencies);
        testJavaSourceFiles.addAll(javaParser.parse(listJavaSources(mavenProject.getBuild().getTestSourceDirectory()), baseDir, ctx));

        JavaSourceSet testProvenance = JavaSourceSet.build("test", dependencies, ctx);
        sourceFiles.addAll(
                ListUtils.map(testJavaSourceFiles, addProvenance(baseDir, projectProvenance, testProvenance, generatedSourcePaths))
        );

        GitProvenance gitProvenance = GitProvenance.fromProjectDirectory(baseDir);
        return sourceFiles.stream()
                .map(sourceFile -> (SourceFile) sourceFile.withMarkers(sourceFile.getMarkers().add(gitProvenance)))
                .collect(Collectors.toList());
    }

    private <S extends SourceFile> UnaryOperator<S> addProvenance(Path baseDir,
            List<Marker> provenance, @Nullable JavaSourceSet sourceSet, Collection<Path> generatedSources) {
        return s -> {
            for (Marker marker : provenance) {
                s = s.withMarkers(s.getMarkers().addIfAbsent(marker));
            }
            if (sourceSet != null) {
                s = s.withMarkers(s.getMarkers().addIfAbsent(sourceSet));
            }
            if(generatedSources.contains(baseDir.resolve(s.getSourcePath()))) {
                s = s.withMarkers(s.getMarkers().addIfAbsent(new Generated(randomId())));
            }
            return s;
        };
    }

    public static List<Path> listJavaSources(String sourceDirectory) throws MojoExecutionException {
        File sourceDirectoryFile = new File(sourceDirectory);
        if (!sourceDirectoryFile.exists()) {
            return emptyList();
        }

        Path sourceRoot = sourceDirectoryFile.toPath();
        try {
            return Files.walk(sourceRoot)
                    .filter(f -> !Files.isDirectory(f) && f.toFile().getName().endsWith(".java"))
                    .collect(toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to list Java source files", e);
        }
    }
}