package panoramagraph.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.UnknownPluginException;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

/**
 * Plugin {@code panorama-graph}: a tarefa {@code architectureArtifacts} reúne em {@code build/architecture} o que o
 * build sabe do componente, e a {@code architecturePublish} os envia ao graph-receiver, que os publica no grafo.
 *
 * <p>Não pressupõe biblioteca nenhuma do projeto. Aplica ele mesmo os plugins de build de que tira artefatos (o
 * CycloneDX, para o SBOM, e o dependency-analysis, para o uso de dependências): o manifesto e o SBOM saem sempre. Os
 * demais só saem quando o projeto tem de onde tirá-los (o uso de dependências, num tipo de projeto que o
 * dependency-analysis analisa; uma API de persistência conhecida para o acesso a dados; os pacts que os testes
 * gravaram; e, num projeto Spring, o que a biblioteca de teste exporta). O que falta não é inventado: o artefato não existe, e o grafo mantém o que tinha.
 */
public class PanoramaGraphPlugin implements Plugin<Project> {

    static final String EXTENSION = "panoramaGraph";
    static final String TASK = "architectureArtifacts";
    static final String PUBLISH_TASK = "architecturePublish";

    /** Propriedades do Gradle ({@code -P}) que o pipeline passa ao {@code architecturePublish}. */
    static final String URL_PROPERTY = "panoramaGraph.url";
    static final String URL_ENV = "PANORAMA_GRAPH_URL";
    static final String DRY_RUN_PROPERTY = "panoramaGraph.dryRun";
    static final String VERSION_PROPERTY = "panoramaGraph.version";
    static final String KIND_PROPERTY = "panoramaGraph.kind";

    /** Propriedades de sistema que a biblioteca de teste lê (as mesmas em {@code ArchitectureExportListener}). */
    static final String EXPORT_DIR_PROPERTY = "panoramaGraph.exportDir";
    static final String SOURCE_DIRS_PROPERTY = "panoramaGraph.sourceDirs";
    static final String PROJECT_DIR_PROPERTY = "panoramaGraph.projectDir";
    static final String SERVICE_VERSION_PATTERN_PROPERTY = "panoramaGraph.serviceVersionPattern";

    static final String CYCLONEDX_PLUGIN = "org.cyclonedx.bom";
    static final String CYCLONEDX_TASK = "cyclonedxDirectBom";
    static final String DEPENDENCY_ANALYSIS_PLUGIN = "com.autonomousapps.dependency-analysis";
    static final String DEPENDENCY_ANALYSIS_TASK = "projectHealth";
    static final String SPRING_BOOT_PLUGIN = "org.springframework.boot";

    /**
     * Frameworks reconhecidos pelo classpath de execução: nome no manifesto e o módulo que dá a versão. A lista só
     * detecta; tecnologia que não está aqui entra por {@code panoramaGraph.technologies}.
     */
    static final Map<String, String> RUNTIME_TECHNOLOGIES = runtimeTechnologies();

    /**
     * Linguagens além do Java, reconhecidas pelo código-fonte principal (a extensão), e não pelo classpath: a
     * biblioteca padrão do Kotlin ou do Groovy costuma chegar como dependência de outras bibliotecas. A versão vem do
     * módulo no classpath de execução.
     */
    static final Map<String, String[]> LANGUAGES = Map.of(
            "kotlin", new String[] {"**/*.kt", "org.jetbrains.kotlin:kotlin-stdlib"},
            "groovy", new String[] {"**/*.groovy", "org.apache.groovy:groovy"});

    @Override
    public void apply(Project project) {
        PanoramaGraphExtension extension =
                project.getExtensions().create(EXTENSION, PanoramaGraphExtension.class);
        extension.getOutputDir().convention(project.getLayout().getBuildDirectory().dir("architecture"));
        extension.getPactsDir().convention(project.getLayout().getBuildDirectory().dir("pacts"));
        extension.getDataAccess().convention(true);
        extension.getServiceVersionPattern().convention("^(.+)-v(\\d+)$");
        extension.getServerUrl().convention(project.getProviders().gradleProperty(URL_PROPERTY)
                .orElse(project.getProviders().environmentVariable(URL_ENV)));
        extension.getComponentKind().convention(project.getProviders().gradleProperty(KIND_PROPERTY));

        // Os plugins de build de onde saem o SBOM e o uso de dependências são aplicados aqui: quem adota o plugin não
        // precisa declará-los nem configurá-los. Se o projeto também os declara, aplicar de novo não muda nada. Vêm
        // antes do java: o dependency-analysis registra as tarefas dele quando o plugin java é aplicado.
        applyBuildPlugin(project, CYCLONEDX_PLUGIN, org.cyclonedx.gradle.CyclonedxPlugin.class);
        applyBuildPlugin(project, DEPENDENCY_ANALYSIS_PLUGIN, com.autonomousapps.DependencyAnalysisPlugin.class);
        project.getPluginManager().withPlugin("java", java -> configure(project, extension));
        // A raiz de um projeto de vários módulos, sem código próprio, descreve o repositório inteiro: o manifesto, com
        // os pacotes que os módulos publicam e a stack de todos, e o SBOM e o uso de dependências juntados dos módulos.
        if (project == project.getRootProject()) {
            project.subprojects(module -> module.getPluginManager().withPlugin("java", java -> {
                if (!project.getPluginManager().hasPlugin("java")) {
                    configureModule(module);
                }
            }));
            project.afterEvaluate(p -> {
                if (!p.getPluginManager().hasPlugin("java")) {
                    configureAggregate(p, extension);
                }
            });
        }
    }

    /** Num módulo da raiz que descreve o repositório: só os plugins de build de que saem o SBOM e o uso de dependências. */
    private static void configureModule(Project module) {
        applyBuildPlugin(module, CYCLONEDX_PLUGIN, org.cyclonedx.gradle.CyclonedxPlugin.class);
        applyBuildPlugin(module, DEPENDENCY_ANALYSIS_PLUGIN, com.autonomousapps.DependencyAnalysisPlugin.class);
        configureCyclonedx(module);
    }

    /** Os módulos com código Java, de onde saem o SBOM, o uso de dependências e a stack do repositório. */
    private static List<Project> javaModules(Project root) {
        return root.getSubprojects().stream().filter(p -> p.getPluginManager().hasPlugin("java")).sorted().toList();
    }

    private void configureAggregate(Project project, PanoramaGraphExtension extension) {
        Provider<Directory> manifestDir = project.getLayout().getBuildDirectory().dir("tmp/architecture-manifest");
        TaskProvider<ManifestTask> manifest = project.getTasks().register("architectureManifest", ManifestTask.class, task -> {
            task.setGroup("architecture");
            task.setDescription("Descreve o repositório, a stack dos módulos e os pacotes que eles geram (manifest.json).");
            task.getComponent().set(Identity.of(project));
            task.getTechnologies().set(project.provider(() -> {
                Map<String, String> all = new LinkedHashMap<>();
                all.put("gradle", project.getGradle().getGradleVersion());
                for (Project module : javaModules(project)) {
                    SourceSet main = module.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
                    Configuration runtimeClasspath = module.getConfigurations().getByName(main.getRuntimeClasspathConfigurationName());
                    detectedTechnologies(module, main, runtimeClasspath).get().forEach((name, version) -> all.merge(name, version, PanoramaGraphPlugin::newest));
                }
                all.putAll(extension.getTechnologies().get());
                return all;
            }));
            task.getPackages().set(project.provider(() -> packages(project)));
            task.getOutputDir().set(manifestDir);
        });
        Provider<Directory> repositoryDir = project.getLayout().getBuildDirectory().dir("tmp/architecture-repository");
        TaskProvider<RepositoryArtifactsTask> repository = project.getTasks().register("architectureRepository", RepositoryArtifactsTask.class, task -> {
            task.setGroup("architecture");
            task.setDescription("Junta o SBOM e o uso de dependências dos módulos num retrato do repositório.");
            task.getComponent().set(project.provider(() -> Map.of(
                    "group", project.getGroup().toString(), "name", project.getName(), "version", project.getVersion().toString())));
            task.getModules().set(project.provider(() -> project.getAllprojects().stream().map(p -> p.getGroup() + ":" + p.getName()).toList()));
            task.dependsOn(project.provider(() -> javaModules(project).stream()
                    .flatMap(m -> m.getTasks().matching(t -> t.getName().equals(CYCLONEDX_TASK) || t.getName().equals(DEPENDENCY_ANALYSIS_TASK)).stream())
                    .toList()));
            task.getBoms().from(project.provider(() -> javaModules(project).stream().map(PanoramaGraphPlugin::bomFile).toList()));
            task.getUsageReports().from(project.provider(() -> javaModules(project).stream()
                    .map(m -> m.getLayout().getBuildDirectory().dir("reports/dependency-analysis").get().getAsFile())
                    .toList()));
            task.getOutputDir().set(repositoryDir);
        });
        TaskProvider<Sync> artifacts = project.getTasks().register(TASK, Sync.class, task -> {
            task.setGroup("architecture");
            task.setDescription("Reúne em build/architecture o retrato do repositório: o manifesto, o SBOM e o uso de dependências dos módulos.");
            task.dependsOn(manifest, repository);
            task.into(extension.getOutputDir());
            task.from(manifestDir);
            task.from(repositoryDir);
            File outputDir = extension.getOutputDir().get().getAsFile();
            task.doLast(t -> {
                List<String> generated = new ArrayList<>(List.of("manifest.json"));
                for (String file : List.of("bom.json", "dependency-usage")) {
                    if (new File(outputDir, file).exists()) {
                        generated.add(file);
                    }
                }
                t.getLogger().lifecycle("panorama-graph: gerados {} (raiz de vários módulos: o repositório inteiro)", String.join(", ", generated));
            });
        });
        registerPublish(project, extension, artifacts);
    }

    /** A versão mais nova entre duas, comparando os números um a um (a stack de módulos que divergem). */
    static String newest(String a, String b) {
        String[] x = a.split("\\D+");
        String[] y = b.split("\\D+");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (x[i].isEmpty() || y[i].isEmpty() || x[i].equals(y[i])) {
                continue;
            }
            return new java.math.BigInteger(x[i]).compareTo(new java.math.BigInteger(y[i])) >= 0 ? a : b;
        }
        return x.length >= y.length ? a : b;
    }

    /**
     * Os pacotes que o build gera, pelo purl: as publicações Maven do projeto e, na raiz, as de todos os módulos. Os
     * marcadores de plugin Gradle ficam de fora: apontam para o pacote do plugin, que já entra.
     */
    static List<String> packages(Project project) {
        List<Project> projects = project == project.getRootProject() ? List.copyOf(project.getAllprojects()) : List.of(project);
        java.util.Set<String> purls = new java.util.TreeSet<>();
        for (Project p : projects) {
            PublishingExtension publishing = p.getExtensions().findByType(PublishingExtension.class);
            if (publishing == null) {
                continue;
            }
            publishing.getPublications().withType(MavenPublication.class).forEach(publication -> {
                if (!publication.getName().endsWith("PluginMarkerMaven")) {
                    purls.add("pkg:maven/" + publication.getGroupId() + "/" + publication.getArtifactId() + "@" + publication.getVersion());
                }
            });
        }
        return List.copyOf(purls);
    }

    /**
     * Pelo id, para valer a versão que o projeto declare. Aplicado por um init script (o projeto sem o plugin no
     * build), o id não está no registro do projeto, porque o plugin chega pelo classpath do script: aí vale a classe
     * que vem com este plugin.
     */
    private static void applyBuildPlugin(Project project, String id, Class<?> type) {
        try {
            project.getPluginManager().apply(id);
        } catch (UnknownPluginException e) {
            project.getPluginManager().apply(type);
        }
    }

    private void configure(Project project, PanoramaGraphExtension extension) {
        Provider<Directory> exportDir = project.getLayout().getBuildDirectory().dir("tmp/architecture-export");
        Provider<Directory> manifestDir = project.getLayout().getBuildDirectory().dir("tmp/architecture-manifest");
        Provider<Directory> dataAccessDir = project.getLayout().getBuildDirectory().dir("tmp/architecture-data-access");
        SourceSet main = project.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        Configuration runtimeClasspath = project.getConfigurations().getByName(main.getRuntimeClasspathConfigurationName());

        TaskProvider<ManifestTask> manifest = project.getTasks().register("architectureManifest", ManifestTask.class, task -> {
            task.setGroup("architecture");
            task.setDescription("Descreve o componente e a stack com que ele é construído (manifest.json).");
            task.getComponent().set(Identity.of(project));
            task.getTechnologies().set(technologies(project, extension, main, runtimeClasspath));
            task.getPackages().set(project.provider(() -> packages(project)));
            task.getOutputDir().set(manifestDir);
        });

        TaskProvider<DataAccessTask> dataAccess = project.getTasks().register("dataAccess", DataAccessTask.class, task -> {
            task.setGroup("architecture");
            task.setDescription("Descreve, pelo bytecode, os datasets do componente e onde o código lê ou escreve neles.");
            task.onlyIf("panoramaGraph.dataAccess está ligado", t -> extension.getDataAccess().get());
            task.getClassesDirs().from(main.getOutput().getClassesDirs());
            task.getRuntimeClasspath().from(runtimeClasspath);
            task.getSourceDirs().from(main.getAllSource().getSrcDirs());
            task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
            task.getOutputDir().set(dataAccessDir);
        });

        String sourceDirs = main.getAllSource().getSrcDirs().stream()
                .map(File::getAbsolutePath)
                .collect(Collectors.joining(File.pathSeparator));
        project.getTasks().named("test", Test.class).configure(task -> {
            File dir = exportDir.get().getAsFile();
            task.systemProperty(EXPORT_DIR_PROPERTY, dir.getAbsolutePath());
            task.systemProperty(SOURCE_DIRS_PROPERTY, sourceDirs);
            task.systemProperty(PROJECT_DIR_PROPERTY, project.getProjectDir().getAbsolutePath());
            task.systemProperty(SERVICE_VERSION_PATTERN_PROPERTY, extension.getServiceVersionPattern().get());
            task.getOutputs().dir(dir).withPropertyName("architectureExport");
            task.doFirst(t -> {
                project.delete(dir);
                dir.mkdirs();
            });
        });

        TaskProvider<Sync> artifacts = project.getTasks().register(TASK, Sync.class, task -> {
            task.setGroup("architecture");
            task.setDescription("Reúne em build/architecture os artefatos de arquitetura do componente.");
            task.dependsOn("test", manifest, dataAccess);
            task.into(extension.getOutputDir());
            task.from(manifestDir);
            task.from(dataAccessDir);
            task.from(exportDir);
            task.from(extension.getPactsDir(), spec -> spec.into("pacts"));
            task.from(project.provider(() -> extension.getSbomFile().isPresent() ? extension.getSbomFile().get() : List.of()),
                    spec -> spec.rename(name -> "bom.json"));
            File outputDir = extension.getOutputDir().get().getAsFile();
            task.doLast(t -> report(t, outputDir));
        });

        registerPublish(project, extension, artifacts);

        configureSbom(project, extension, artifacts);
        // O dependency-analysis só registra o projectHealth nos projetos que ele analisa (java-library, application,
        // Spring Boot, Kotlin, Android); com o plugin java sozinho, não há uso de dependências.
        artifacts.configure(task -> {
            task.dependsOn(project.getTasks().named(name -> name.equals(DEPENDENCY_ANALYSIS_TASK)));
            task.from(project.getLayout().getBuildDirectory().dir("reports/dependency-analysis"), spec -> {
                spec.include("declarations.json", "usages-dependencies.json");
                spec.into("dependency-usage");
            });
        });

        project.afterEvaluate(p -> addSpringTestLibrary(p, extension));
    }

    private static void registerPublish(Project project, PanoramaGraphExtension extension, TaskProvider<Sync> artifacts) {
        project.getTasks().register(PUBLISH_TASK, PublishTask.class, task -> {
            task.setGroup("architecture");
            task.setDescription("Envia build/architecture ao graph-receiver, que publica no grafo de arquitetura.");
            task.mustRunAfter(artifacts);
            task.getArtifactsDir().set(extension.getOutputDir());
            task.getServerUrl().set(extension.getServerUrl());
            task.getComponentKind().set(extension.getComponentKind());
            task.getArtifactVersion().set(project.getProviders().gradleProperty(VERSION_PROPERTY));
            task.getDryRun().set(project.getProviders().gradleProperty(DRY_RUN_PROPERTY)
                    .map(v -> v.isEmpty() || Boolean.parseBoolean(v)).orElse(false));
            task.getPluginVersion().set(pluginProperties().getProperty("version"));
        });
    }

    /**
     * O SBOM do plugin CycloneDX, só com o classpath de execução: é o que o componente leva para produção, e o grafo
     * o lê como dependência de execução. As propriedades são lidas por reflexão: o projeto pode trazer outra versão do
     * plugin CycloneDX, e a configuração não deve quebrar o build por isso.
     */
    private static void configureSbom(Project project, PanoramaGraphExtension extension, TaskProvider<Sync> artifacts) {
        File bom = configureCyclonedx(project);
        artifacts.configure(task -> {
            if (extension.getSbomFile().isPresent()) {
                return;
            }
            task.dependsOn(CYCLONEDX_TASK);
            task.from(bom);
        });
    }

    private static File bomFile(Project project) {
        return project.getLayout().getBuildDirectory().file("reports/cyclonedx/bom.json").get().getAsFile();
    }

    private static File configureCyclonedx(Project project) {
        File bom = bomFile(project);
        project.getTasks().named(CYCLONEDX_TASK).configure(task -> {
            try {
                set(task, "getIncludeConfigs", List.of("runtimeClasspath"));
                set(task, "getSkipConfigs", List.of(".*[Tt]est.*"));
                set(task, "getIncludeBomSerialNumber", false);
                set(task, "getJsonOutput", bom);
            } catch (ReflectiveOperationException | RuntimeException e) {
                task.getLogger().warn("panorama-graph: não foi possível configurar o {} ({}); o SBOM sai como o plugin CycloneDX o gerar.",
                        CYCLONEDX_TASK, e.getMessage());
            }
        });
        return bom;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void set(Task task, String getter, Object value) throws ReflectiveOperationException {
        Method method = task.getClass().getMethod(getter);
        Object property = method.invoke(task);
        if (property instanceof org.gradle.api.provider.HasMultipleValues values) {
            values.set((Iterable) value);
        } else if (property instanceof org.gradle.api.file.RegularFileProperty file) {
            file.set((File) value);
        } else if (property instanceof org.gradle.api.provider.Property single) {
            single.set(value);
        } else {
            throw new IllegalStateException(getter + " não é uma propriedade");
        }
    }

    /** Java e Gradle; o que se reconhece no classpath de execução; e o que o build declara. */
    private static Provider<Map<String, String>> technologies(
            Project project, PanoramaGraphExtension extension, SourceSet main, Configuration runtimeClasspath) {
        return detectedTechnologies(project, main, runtimeClasspath).zip(extension.getTechnologies(), (base, declared) -> {
            Map<String, String> all = new LinkedHashMap<>(base);
            all.putAll(declared);
            return all;
        });
    }

    /** Java e Gradle, e o que se reconhece no classpath de execução. */
    private static Provider<Map<String, String>> detectedTechnologies(Project project, SourceSet main, Configuration runtimeClasspath) {
        Provider<String> java = project.getExtensions().getByType(JavaPluginExtension.class).getToolchain()
                .getLanguageVersion().map(Object::toString)
                .orElse(System.getProperty("java.specification.version"));
        String gradle = project.getGradle().getGradleVersion();
        Provider<List<String>> languages = project.provider(() -> LANGUAGES.entrySet().stream()
                .filter(l -> !main.getAllSource().matching(f -> f.include(l.getValue()[0])).isEmpty())
                .map(Map.Entry::getKey)
                .sorted()
                .toList());
        Provider<Map<String, String>> detected = runtimeClasspath.getIncoming().getResolutionResult().getRootComponent()
                .zip(languages, PanoramaGraphPlugin::detect);
        return java.zip(detected, (javaVersion, found) -> {
            Map<String, String> all = new LinkedHashMap<>();
            all.put("java", javaVersion);
            all.put("gradle", gradle);
            all.putAll(found);
            return all;
        });
    }

    static Map<String, String> detect(ResolvedComponentResult root, List<String> languages) {
        Map<String, String> versions = new LinkedHashMap<>();
        List<ResolvedComponentResult> pending = new ArrayList<>(List.of(root));
        java.util.Set<Object> seen = new java.util.HashSet<>();
        while (!pending.isEmpty()) {
            ResolvedComponentResult component = pending.remove(pending.size() - 1);
            if (!seen.add(component.getId())) {
                continue;
            }
            if (component.getId() instanceof ModuleComponentIdentifier module) {
                versions.put(module.getGroup() + ":" + module.getModule(), module.getVersion());
            }
            component.getDependencies().forEach(dependency -> {
                if (dependency instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult resolved) {
                    pending.add(resolved.getSelected());
                }
            });
        }
        Map<String, String> found = new LinkedHashMap<>();
        for (String language : languages) {
            found.put(language, versions.getOrDefault(LANGUAGES.get(language)[1], ""));
        }
        RUNTIME_TECHNOLOGIES.forEach((name, module) -> {
            String version = versions.get(module);
            if (version != null) {
                found.put(name, version);
            }
        });
        return found;
    }

    /**
     * A biblioteca que exporta, dos testes, o que só a aplicação Spring de pé sabe (OpenAPI, AsyncAPI, clientes HTTP,
     * nome). Vai como {@code testRuntimeOnly}: sem Spring, ela não é carregada.
     */
    private static void addSpringTestLibrary(Project project, PanoramaGraphExtension extension) {
        boolean spring = extension.getSpringTest().isPresent()
                ? extension.getSpringTest().get()
                : project.getPluginManager().hasPlugin(SPRING_BOOT_PLUGIN) || declaresSpring(project);
        if (!spring) {
            return;
        }
        Properties plugin = pluginProperties();
        project.getDependencies().add("testRuntimeOnly",
                plugin.getProperty("group") + ":panorama-graph-spring-test:" + plugin.getProperty("version"));
    }

    private static boolean declaresSpring(Project project) {
        return project.getConfigurations().stream()
                .flatMap(c -> c.getDependencies().stream())
                .anyMatch(d -> d.getGroup() != null && d.getGroup().startsWith("org.springframework"));
    }

    static Properties pluginProperties() {
        try (InputStream in = PanoramaGraphPlugin.class.getResourceAsStream("plugin.properties")) {
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Uma linha com o que saiu e o que não saiu, e por quê: quem adota vê na hora o que falta para cada artefato. */
    private static void report(Task task, File dir) {
        Map<String, String> missing = new LinkedHashMap<>();
        missing.put("bom.json", "o plugin CycloneDX não gerou o SBOM");
        missing.put("dependency-usage", "o dependency-analysis só analisa projetos java-library, application, Spring Boot ou Kotlin");
        missing.put("data-access.json", "nenhuma API de persistência conhecida no classpath");
        missing.put("pacts", "nenhum pact em panoramaGraph.pactsDir");
        missing.put("application.json", "precisa de Spring e de spring.application.name");
        missing.put("openapi.json", "precisa de Spring MVC, springdoc e de um @SpringBootTest da aplicação");
        missing.put("asyncapi.json", "precisa de Spring, Springwolf e de um @SpringBootTest da aplicação");
        missing.put("http-clients.json", "precisa dos clientes HTTP declarativos do Spring Framework 7 e de um @SpringBootTest");
        List<String> generated = new ArrayList<>(List.of("manifest.json"));
        List<String> absent = new ArrayList<>();
        missing.forEach((file, reason) -> {
            if (new File(dir, file).exists()) {
                generated.add(file);
            } else {
                absent.add(file + " (" + reason + ")");
            }
        });
        task.getLogger().lifecycle("panorama-graph: gerados {}", String.join(", ", generated));
        if (!absent.isEmpty()) {
            task.getLogger().lifecycle("panorama-graph: não gerados {}", String.join("; ", absent));
        }
    }

    private static Map<String, String> runtimeTechnologies() {
        Map<String, String> technologies = new LinkedHashMap<>();
        technologies.put("spring-boot", "org.springframework.boot:spring-boot");
        technologies.put("quarkus", "io.quarkus:quarkus-core");
        technologies.put("micronaut", "io.micronaut:micronaut-core");
        return technologies;
    }
}
