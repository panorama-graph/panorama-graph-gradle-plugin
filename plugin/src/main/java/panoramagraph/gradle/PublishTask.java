package panoramagraph.gradle;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Envia os artefatos de {@code build/architecture} ao graph-receiver, que os traduz e publica no grafo. É o mesmo
 * pacote que o {@code graph-publish} leria do diretório: cada arquivo JSON pelo caminho relativo (formato
 * {@code panorama-graph-build/1}). Não gera nada: roda depois de {@code architectureArtifacts}, no mesmo build ou
 * num job à parte que recebeu o diretório.
 */
@UntrackedTask(because = "publica no grafo a cada execução")
public abstract class PublishTask extends DefaultTask {

    static final String FORMAT = "panorama-graph-build/1";

    @Internal
    public abstract DirectoryProperty getArtifactsDir();

    /** URL do graph-receiver (ex.: {@code https://panorama-graph.example.com/receiver}). */
    @Input
    @Optional
    public abstract Property<String> getServerUrl();

    /** {@code kind} do componente: service, bff, frontend, job, library (padrão do receptor: service). */
    @Input
    @Optional
    public abstract Property<String> getComponentKind();

    /** Versão do artefato, quando difere da do build que gerou o manifesto (ex.: a calculada pelo pipeline). */
    @Input
    @Optional
    public abstract Property<String> getArtifactVersion();

    /** Só confere: o receptor monta os retratos e não grava. */
    @Input
    public abstract Property<Boolean> getDryRun();

    @Input
    public abstract Property<String> getPluginVersion();

    @TaskAction
    void publish() throws IOException, InterruptedException {
        if (!getServerUrl().isPresent() || getServerUrl().get().isBlank()) {
            throw new GradleException("panorama-graph: informe a URL do graph-receiver (panoramaGraph.serverUrl, "
                    + "-PpanoramaGraph.url ou PANORAMA_GRAPH_URL).");
        }
        File dir = getArtifactsDir().get().getAsFile();
        if (!new File(dir, "manifest.json").isFile()) {
            throw new GradleException("panorama-graph: " + dir + " não tem manifest.json; rode architectureArtifacts antes.");
        }

        String body = payload(dir.toPath());
        URI uri = URI.create(getServerUrl().get().replaceAll("/+$", "") + "/v1/builds");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("content-type", "application/json")
                .header("accept", "text/plain")
                .header("user-agent", "panorama-graph-gradle-plugin/" + getPluginVersion().get())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GradleException("panorama-graph: o graph-receiver em " + uri + " não respondeu (" + e.getMessage() + ")", e);
        }
        String report = response.body().stripTrailing();
        if (response.statusCode() / 100 != 2) {
            throw new GradleException("panorama-graph: o graph-receiver recusou o pacote (HTTP " + response.statusCode() + ")\n" + report);
        }
        getLogger().lifecycle("panorama-graph: {} em {}", getDryRun().get() ? "conferido, sem gravar," : "publicado", uri);
        report.lines().forEach(line -> getLogger().lifecycle(line));
    }

    /** O corpo do {@code POST /v1/builds}; o conteúdo de cada arquivo vai como está, porque já é JSON. */
    String payload(Path dir) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json")).sorted().toList()) {
                files.put(dir.relativize(path).toString().replace(File.separatorChar, '/'), Files.readString(path).strip());
            }
        }
        Map<String, Object> options = new LinkedHashMap<>();
        if (getComponentKind().isPresent()) {
            options.put("kind", getComponentKind().get());
        }
        if (getArtifactVersion().isPresent() && !getArtifactVersion().get().isBlank()) {
            options.put("version", getArtifactVersion().get());
        }

        StringBuilder out = new StringBuilder("{\"format\":\"").append(FORMAT).append("\",\"files\":{");
        int i = 0;
        for (Map.Entry<String, String> file : files.entrySet()) {
            if (i++ > 0) {
                out.append(',');
            }
            out.append(Json.write(file.getKey()).strip()).append(':').append(file.getValue());
        }
        out.append("},\"options\":").append(Json.write(options).strip());
        out.append(",\"dryRun\":").append(getDryRun().get()).append('}');
        return out.toString();
    }
}
