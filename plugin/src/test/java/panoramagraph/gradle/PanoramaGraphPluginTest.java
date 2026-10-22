package panoramagraph.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O plugin num projeto de verdade, pelo TestKit: nenhuma biblioteca é pressuposta, os plugins de build de onde saem o
 * SBOM e o uso de dependências são aplicados pelo próprio plugin, e os demais artefatos só saem quando o projeto tem de
 * onde tirá-los.
 */
class PanoramaGraphPluginTest {

    @TempDir
    Path project;

    private static final String TEST_REPOSITORY = System.getProperty("panoramaGraph.testRepository");

    @Test
    void projetoJavaSemNadaGeraManifestoESbom() throws IOException {
        build("""
                plugins { id 'java'; id 'panorama-graph' }
                group = 'com.example.catalog'
                version = '1.2.3'
                """);
        source("src/main/java/com/example/Hello.java", "package com.example; public class Hello {}");

        BuildResult result = run("architectureArtifacts");

        assertThat(files("build/architecture")).containsExactly("bom.json", "manifest.json");
        String manifest = read("build/architecture/manifest.json");
        assertThat(manifest)
                .contains("\"format\": \"panorama-graph-manifest/1\"")
                .contains("\"group\": \"com.example.catalog\"")
                .contains("\"version\": \"1.2.3\"")
                .contains("\"name\": \"java\"")
                .contains("\"name\": \"gradle\"")
                .doesNotContain("spring-boot")
                .doesNotContain("packages");
        assertThat(result.getOutput())
                .contains("panorama-graph: gerados manifest.json, bom.json")
                .contains("panorama-graph: não gerados dependency-usage (o dependency-analysis só analisa projetos java-library");
    }

    @Test
    void semProjetoSpringABibliotecaDeTesteNaoEntra() throws IOException {
        build("""
                plugins { id 'java'; id 'panorama-graph' }
                """);

        BuildResult result = run("dependencies", "--configuration", "testRuntimeClasspath");

        assertThat(result.getOutput()).doesNotContain("panorama-graph-spring-test");
    }

    @Test
    void comSpringTestLigadoABibliotecaEntraNaMesmaVersaoDoPlugin() throws IOException {
        build("""
                plugins { id 'java'; id 'panorama-graph' }
                repositories { maven { url = uri('%s') } }
                panoramaGraph { springTest = true }
                """.formatted(TEST_REPOSITORY.replace('\\', '/')));

        BuildResult result = run("dependencies", "--configuration", "testRuntimeClasspath");

        assertThat(result.getOutput())
                .contains("panorama-graph:panorama-graph-spring-test:" + System.getProperty("panoramaGraph.version"))
                .doesNotContain("FAILED");
    }

    @Test
    void tecnologiasDeclaradasEntramNoManifesto() throws IOException {
        build("""
                plugins { id 'java'; id 'panorama-graph' }
                panoramaGraph { technologies.put('plataforma-interna', '2.0.0') }
                """);

        run("architectureManifest");

        assertThat(read("build/tmp/architecture-manifest/manifest.json"))
                .contains("\"name\": \"plataforma-interna\"")
                .contains("\"version\": \"2.0.0\"");
    }

    @Test
    void linguagemPeloCodigoFonteENaoPeloClasspath() throws IOException {
        build("""
                plugins { id 'groovy'; id 'panorama-graph' }
                repositories { mavenCentral() }
                // O Groovy no classpath sozinho não faz do projeto um projeto Groovy.
                dependencies { implementation 'org.apache.groovy:groovy:5.0.6' }
                """);
        source("src/main/java/com/example/Hello.java", "package com.example; public class Hello {}");

        run("architectureManifest");
        assertThat(read("build/tmp/architecture-manifest/manifest.json")).doesNotContain("\"groovy\"");

        source("src/main/groovy/com/example/Script.groovy", "package com.example; class Script {}");
        run("architectureManifest");
        assertThat(read("build/tmp/architecture-manifest/manifest.json"))
                .contains("\"name\": \"groovy\"")
                .contains("\"version\": \"5.0.6\"");
    }

    @Test
    void acessoADadosSoComUmaApiDePersistenciaConhecida() throws IOException {
        build("""
                plugins { id 'java'; id 'panorama-graph' }
                repositories { mavenCentral() }
                dependencies { implementation 'jakarta.persistence:jakarta.persistence-api:3.2.0' }
                """);
        source("src/main/java/com/example/Order.java", """
                package com.example;
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "orders")
                public class Order { @jakarta.persistence.Id Long id; }
                """);

        run("architectureArtifacts");

        assertThat(read("build/architecture/data-access.json"))
                .contains("\"format\": \"panorama-graph-data-access/2\"")
                .contains("\"name\": \"orders\"")
                .contains("\"store\": \"relational\"");
    }

    @Test
    void sbomDeOutraFerramentaEntraComoBomJson() throws IOException {
        build("""
                plugins { id 'java'; id 'panorama-graph' }
                panoramaGraph { sbomFile = file('sbom/cyclonedx.json') }
                """);
        source("sbom/cyclonedx.json", "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\"}");

        run("architectureArtifacts");

        assertThat(files("build/architecture")).contains("bom.json");
    }

    @Test
    void pactsGravadosPelosTestesEntramNaPastaPacts() throws IOException {
        build("""
                plugins { id 'java'; id 'panorama-graph' }
                """);
        source("build/pacts/web-catalog-api.json", "{}");

        run("architectureArtifacts");

        assertThat(files("build/architecture/pacts")).containsExactly("web-catalog-api.json");
    }

    @Test
    void sbomSoDoClasspathDeExecucaoEUsoDeDependencias() throws IOException {
        build("""
                plugins { id 'java-library'; id 'panorama-graph' }
                repositories { mavenCentral() }
                dependencies {
                    implementation 'org.slf4j:slf4j-api:2.0.17'
                    testImplementation 'junit:junit:4.13.2'
                }
                """);

        run("architectureArtifacts");

        String bom = read("build/architecture/bom.json");
        assertThat(bom).contains("slf4j-api").doesNotContain("pkg:maven/junit/junit");
        assertThat(read("build/architecture/dependency-usage/declarations.json")).contains("org.slf4j:slf4j-api");
    }

    @Test
    void projetoQueTambemAplicaOsPluginsDeBuildNaoMudaNada() throws IOException {
        build("""
                plugins {
                    id 'java-library'
                    id 'org.cyclonedx.bom'
                    id 'com.autonomousapps.dependency-analysis'
                    id 'panorama-graph'
                }
                repositories { mavenCentral() }
                """);

        run("architectureArtifacts");

        assertThat(files("build/architecture")).containsExactly("bom.json", "dependency-usage", "manifest.json");
    }

    @Test
    void numSubprojetoOsPluginsDeBuildNaoPrecisamDoProjetoRaiz() throws IOException {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'sample'\ninclude 'api'\n");
        Files.writeString(project.resolve("build.gradle"), "");
        source("api/build.gradle", """
                plugins { id 'application'; id 'panorama-graph' }
                repositories { mavenCentral() }
                """);
        source("api/src/main/java/com/example/Hello.java", "package com.example; public class Hello {}");

        run(":api:architectureArtifacts");

        assertThat(files("api/build/architecture")).containsExactly("bom.json", "dependency-usage", "manifest.json");
    }

    @Test
    void bibliotecaPublicadaEntraNoManifestoComoPacote() throws IOException {
        build("""
                plugins { id 'java-library'; id 'maven-publish'; id 'panorama-graph' }
                repositories { mavenCentral() }
                group = 'com.example.framework'
                version = '2.1.0'
                publishing { publications { mavenJava(MavenPublication) { from components.java } } }
                """);

        run("architectureArtifacts");

        assertThat(read("build/architecture/manifest.json"))
                .contains("\"purl\": \"pkg:maven/com.example.framework/sample@2.1.0\"");
    }

    @Test
    void naRaizDeVariosModulosSaiORetratoDoRepositorioInteiro() throws IOException {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'framework'\ninclude 'framework-web', 'framework-test', 'internal'\n");
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'panorama-graph' }
                allprojects { group = 'com.example.framework'; version = '2.1.0'; repositories { mavenCentral() } }
                """);
        source("framework-web/build.gradle", """
                plugins { id 'java-library'; id 'maven-publish' }
                dependencies { api 'org.apache.commons:commons-lang3:3.17.0' }
                publishing { publications { mavenJava(MavenPublication) { from components.java } } }
                """);
        source("framework-web/src/main/java/com/example/web/Web.java",
                "package com.example.web; public class Web { public String blank() { return org.apache.commons.lang3.StringUtils.EMPTY; } }");
        // O módulo de testes usa o outro módulo do repositório: ele não é biblioteca do repositório.
        source("framework-test/build.gradle", """
                plugins { id 'java-library'; id 'maven-publish' }
                dependencies { api project(':framework-web'); api 'org.slf4j:slf4j-api:2.0.17' }
                publishing { publications { mavenJava(MavenPublication) { from components.java } } }
                """);
        source("framework-test/src/main/java/com/example/test/Probe.java",
                "package com.example.test; public class Probe { org.slf4j.Logger log; com.example.web.Web web; }");
        // Módulo que não publica não é pacote, mas o que ele usa entra no retrato do repositório.
        source("internal/build.gradle", "plugins { id 'java-library' }\n");

        BuildResult result = run("architectureArtifacts");

        assertThat(files("build/architecture")).containsExactly("bom.json", "dependency-usage", "manifest.json");
        assertThat(read("build/architecture/manifest.json"))
                .contains("\"name\": \"framework\"")
                .contains("\"name\": \"java\"")
                .contains("\"purl\": \"pkg:maven/com.example.framework/framework-test@2.1.0\"")
                .contains("\"purl\": \"pkg:maven/com.example.framework/framework-web@2.1.0\"")
                .doesNotContain("pkg:maven/com.example.framework/internal");
        String bom = read("build/architecture/bom.json");
        assertThat(bom)
                .contains("\"bom-ref\": \"pkg:maven/com.example.framework/framework@2.1.0\"")
                .contains("pkg:maven/org.apache.commons/commons-lang3@3.17.0")
                .contains("pkg:maven/org.slf4j/slf4j-api@2.0.17")
                .doesNotContain("project_path");
        assertThat(read("build/architecture/dependency-usage/declarations.json"))
                .contains("org.apache.commons:commons-lang3")
                .contains("org.slf4j:slf4j-api")
                .doesNotContain(":framework-web");
        assertThat(result.getOutput()).contains("panorama-graph: gerados manifest.json, bom.json, dependency-usage (raiz de vários módulos");
    }

    @Test
    void entreModulosQueDivergemAStackFicaComAVersaoMaisNova() {
        assertThat(PanoramaGraphPlugin.newest("21", "17")).isEqualTo("21");
        assertThat(PanoramaGraphPlugin.newest("3.4.10", "3.4.9")).isEqualTo("3.4.10");
    }

    @Test
    void porInitScriptSemMexerNoBuildDoProjeto() throws IOException {
        build("""
                plugins { id 'application' }
                repositories { mavenCentral() }
                """);
        source("src/main/java/com/example/Hello.java", "package com.example; public class Hello {}");
        // O plugin chega pelo classpath do init script, e não pelo bloco plugins: o id dos plugins de build não está
        // no registro do projeto.
        Path init = project.resolve("panorama-graph.init.gradle");
        Files.writeString(init, """
                initscript { dependencies { classpath files(%s) } }
                allprojects { apply plugin: panoramagraph.gradle.PanoramaGraphPlugin }
                """.formatted(pluginClasspath()));

        GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("--init-script", init.toString(), "architectureArtifacts")
                .forwardOutput()
                .build();

        assertThat(files("build/architecture")).containsExactly("bom.json", "dependency-usage", "manifest.json");
    }

    /** O classpath do plugin em teste, que o TestKit grava para o {@code withPluginClasspath}, como lista do Groovy. */
    private static String pluginClasspath() throws IOException {
        java.util.Properties metadata = new java.util.Properties();
        try (var in = PanoramaGraphPluginTest.class.getClassLoader().getResourceAsStream("plugin-under-test-metadata.properties")) {
            metadata.load(in);
        }
        return Arrays.stream(metadata.getProperty("implementation-classpath").split(java.io.File.pathSeparator))
                .map(path -> "'" + path.replace("\\", "/") + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    @Test
    void publishEnviaOPacoteAoReceptorEMostraOResumo() throws IOException {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer receiver = receiver(200, "componente example/catalog (serviço catalog): linha principal", received);
        try {
            build("""
                    plugins { id 'java'; id 'panorama-graph' }
                    panoramaGraph { componentKind = 'bff' }
                    """);
            source("build/pacts/web-catalog.json", "{\"consumer\":{\"name\":\"web\"}}");
            String url = "http://localhost:" + receiver.getAddress().getPort();

            BuildResult result = run("architectureArtifacts", "architecturePublish",
                    "-PpanoramaGraph.url=" + url, "-PpanoramaGraph.version=1.4.0");

            assertThat(received.get())
                    .startsWith("{\"format\":\"panorama-graph-build/1\",\"files\":{")
                    .contains("\"manifest.json\":{")
                    .contains("\"pacts/web-catalog.json\":{\"consumer\":{\"name\":\"web\"}}")
                    .contains("\"options\":{\n  \"kind\": \"bff\",\n  \"version\": \"1.4.0\"\n}")
                    .endsWith("\"dryRun\":false}");
            assertThat(result.getOutput()).contains("componente example/catalog (serviço catalog): linha principal");
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void publishEmDryRunAvisaOReceptorParaNaoGravar() throws IOException {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer receiver = receiver(200, "ok", received);
        try {
            build("""
                    plugins { id 'java'; id 'panorama-graph' }
                    """);
            run("architectureArtifacts");

            BuildResult result = runWithEnv(java.util.Map.of("PANORAMA_GRAPH_URL", "http://localhost:" + receiver.getAddress().getPort()),
                    "architecturePublish", "-PpanoramaGraph.dryRun");

            assertThat(received.get()).endsWith("\"dryRun\":true}").contains("\"options\":{}");
            assertThat(result.getOutput()).contains("conferido, sem gravar");
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void publishFalhaQuandoOReceptorRecusa() throws IOException {
        HttpServer receiver = receiver(422, "  sbom: recusado: Carga inválida", new AtomicReference<>());
        try {
            build("""
                    plugins { id 'java'; id 'panorama-graph' }
                    """);
            run("architectureArtifacts");

            BuildResult result = runner("architecturePublish", "-PpanoramaGraph.url=http://localhost:" + receiver.getAddress().getPort())
                    .buildAndFail();

            assertThat(result.getOutput()).contains("recusou o pacote (HTTP 422)").contains("sbom: recusado: Carga inválida");
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void publishSemArtefatosOuSemUrlExplicaOQueFalta() throws IOException {
        build("""
                plugins { id 'java'; id 'panorama-graph' }
                """);

        assertThat(runner("architecturePublish", "-PpanoramaGraph.url=http://localhost:1").buildAndFail().getOutput())
                .contains("rode architectureArtifacts antes");
        run("architectureArtifacts");
        assertThat(runner("architecturePublish").buildAndFail().getOutput())
                .contains("informe a URL do graph-receiver");
    }

    /** Um graph-receiver de mentira: guarda o corpo e responde com o status e o texto dados. */
    private static HttpServer receiver(int status, String reply, AtomicReference<String> received) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/builds", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = (reply + "\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private void build(String script) throws IOException {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'sample'\n");
        Files.writeString(project.resolve("build.gradle"), script);
    }

    private void source(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String read(String path) throws IOException {
        return Files.readString(project.resolve(path));
    }

    private List<String> files(String dir) {
        String[] names = project.resolve(dir).toFile().list();
        return names == null ? List.of() : Arrays.stream(names).sorted().toList();
    }

    private BuildResult run(String... args) {
        return runner(args).build();
    }

    private BuildResult runWithEnv(java.util.Map<String, String> env, String... args) {
        java.util.Map<String, String> all = new java.util.HashMap<>(System.getenv());
        all.putAll(env);
        return runner(args).withEnvironment(all).build();
    }

    private GradleRunner runner(String... args) {
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments(args)
                .forwardOutput();
    }
}
