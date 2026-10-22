package panoramagraph.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/**
 * {@code manifest.json} (formato {@code panorama-graph-manifest/1}): quem é o componente, com que stack ele é
 * construído e que pacotes o build gera.
 */
@CacheableTask
public abstract class ManifestTask extends DefaultTask {

    public static final String FORMAT = "panorama-graph-manifest/1";

    /** Arquivos que o {@code architectureArtifacts} pode reunir, por artefato. Informativo para quem lê. */
    static final Map<String, String> ARTIFACTS = artifacts();

    /** group, name, version, repository e commit; os ausentes ficam de fora. */
    @Input
    public abstract MapProperty<String, String> getComponent();

    /** Nome e versão de cada tecnologia, na ordem em que vão para o manifesto. */
    @Input
    public abstract MapProperty<String, String> getTechnologies();

    /** Pacotes que o build gera, pelo purl com a versão ({@code pkg:maven/<grupo>/<nome>@<versão>}). */
    @Input
    public abstract ListProperty<String> getPackages();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    void write() throws IOException {
        List<Map<String, Object>> technologies = new ArrayList<>();
        getTechnologies().get().forEach((name, version) -> {
            Map<String, Object> technology = new LinkedHashMap<>();
            technology.put("name", name);
            if (!version.isEmpty()) {
                technology.put("version", version);
            }
            technologies.add(technology);
        });
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("component", getComponent().get());
        manifest.put("technologies", technologies);
        List<Map<String, Object>> packages = new ArrayList<>();
        getPackages().getOrElse(List.of()).forEach(purl -> packages.add(Map.of("purl", purl)));
        if (!packages.isEmpty()) {
            manifest.put("packages", packages);
        }
        manifest.put("artifacts", ARTIFACTS);

        File dir = getOutputDir().get().getAsFile();
        Files.createDirectories(dir.toPath());
        Files.writeString(new File(dir, "manifest.json").toPath(), Json.write(manifest), StandardCharsets.UTF_8);
    }

    private static Map<String, String> artifacts() {
        Map<String, String> artifacts = new LinkedHashMap<>();
        artifacts.put("application", "application.json");
        artifacts.put("sbom", "bom.json");
        artifacts.put("dependencyUsage", "dependency-usage");
        artifacts.put("openapi", "openapi.json");
        artifacts.put("asyncapi", "asyncapi.json");
        artifacts.put("httpClients", "http-clients.json");
        artifacts.put("dataAccess", "data-access.json");
        artifacts.put("pacts", "pacts");
        return artifacts;
    }
}
