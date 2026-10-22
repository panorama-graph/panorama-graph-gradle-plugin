package panoramagraph.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Na raiz de um repositório de vários módulos, o SBOM e o uso de dependências do repositório inteiro, a partir dos de
 * cada módulo: o grafo guarda um retrato por repositório. Um módulo do próprio repositório não é biblioteca do
 * repositório: sai do SBOM e do uso, e o que ele traz conta pelas dependências que o trazem de fora. Módulo sem SBOM ou
 * sem relatório de uso fica de fora daquele artefato; sem nenhum, o artefato não sai.
 */
@CacheableTask
public abstract class RepositoryArtifactsTask extends DefaultTask {

    /** O componente da raiz no SBOM juntado: group, name e version do repositório. */
    @Input
    public abstract MapProperty<String, String> getComponent();

    /** {@code grupo:nome} de todos os módulos do repositório. */
    @Input
    public abstract ListProperty<String> getModules();

    /** O SBOM (CycloneDX) de cada módulo. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getBoms();

    /** A pasta de relatórios do dependency-analysis de cada módulo ({@code declarations.json} e {@code usages-dependencies.json}). */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getUsageReports();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    void write() throws IOException {
        File dir = getOutputDir().get().getAsFile();
        if (dir.exists()) {
            try (var paths = Files.walk(dir.toPath())) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(dir.toPath());
        Set<String> modules = new LinkedHashSet<>(getModules().get());

        List<Map<String, Object>> boms = new ArrayList<>();
        for (File file : getBoms().getFiles()) {
            if (file.isFile()) {
                boms.add(object(Json.read(Files.readString(file.toPath()))));
            }
        }
        if (!boms.isEmpty()) {
            write(new File(dir, "bom.json"), mergeBoms(boms, modules, getComponent().get()));
        }

        // Dois módulos que declaram a mesma dependência na mesma configuração são uma declaração do repositório.
        Map<String, Object> declarations = new LinkedHashMap<>();
        List<Object> usages = new ArrayList<>();
        for (File reports : getUsageReports().getFiles()) {
            File declared = new File(reports, "declarations.json");
            File used = new File(reports, "usages-dependencies.json");
            if (!declared.isFile() || !used.isFile()) {
                continue;
            }
            for (Object d : list(Json.read(Files.readString(declared.toPath())))) {
                String identifier = String.valueOf(object(d).get("identifier"));
                if (external(identifier, modules)) {
                    declarations.putIfAbsent(identifier + " " + object(d).get("configurationName"), d);
                }
            }
            for (Object u : list(object(Json.read(Files.readString(used.toPath()))).get("usages"))) {
                Object coordinates = object(u).get("coordinates");
                if (coordinates != null && external(String.valueOf(object(coordinates).get("identifier")), modules)) {
                    usages.add(u);
                }
            }
        }
        if (!declarations.isEmpty() || !usages.isEmpty()) {
            File usage = new File(dir, "dependency-usage");
            Files.createDirectories(usage.toPath());
            write(new File(usage, "declarations.json"), List.copyOf(declarations.values()));
            write(new File(usage, "usages-dependencies.json"), Map.of("usages", usages));
        }
    }

    /**
     * Um SBOM com os componentes e as dependências de todos, sem os módulos do repositório. As diretas do repositório
     * são as diretas de cada módulo: o que um módulo traz de outro módulo já é direta deste.
     */
    static Map<String, Object> mergeBoms(List<Map<String, Object>> boms, Set<String> modules, Map<String, String> component) {
        Set<String> internal = new LinkedHashSet<>();
        Map<String, Object> components = new LinkedHashMap<>();
        for (Map<String, Object> bom : boms) {
            Object root = object(bom.getOrDefault("metadata", Map.of())).get("component");
            if (root != null) {
                internal.add(String.valueOf(object(root).get("bom-ref")));
            }
            for (Object c : list(bom.get("components"))) {
                Map<String, Object> comp = object(c);
                String ref = String.valueOf(comp.get("bom-ref"));
                if (modules.contains(comp.get("group") + ":" + comp.get("name")) || ref.contains("project_path=")) {
                    internal.add(ref);
                } else {
                    components.putIfAbsent(ref, comp);
                }
            }
        }
        Set<String> direct = new LinkedHashSet<>();
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (Map<String, Object> bom : boms) {
            for (Object d : list(bom.get("dependencies"))) {
                Map<String, Object> dependency = object(d);
                String ref = String.valueOf(dependency.get("ref"));
                List<String> on = list(dependency.get("dependsOn")).stream().map(String::valueOf).filter(r -> !internal.contains(r)).toList();
                if (internal.contains(ref)) {
                    direct.addAll(on);
                } else {
                    dependencies.computeIfAbsent(ref, k -> new LinkedHashSet<>()).addAll(on);
                }
            }
        }
        String rootRef = "pkg:maven/" + component.get("group") + "/" + component.get("name") + "@" + component.get("version");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "library");
        root.put("bom-ref", rootRef);
        root.putAll(component);
        List<Object> deps = new ArrayList<>();
        deps.add(Map.of("ref", rootRef, "dependsOn", List.copyOf(direct)));
        dependencies.forEach((ref, on) -> deps.add(Map.of("ref", ref, "dependsOn", List.copyOf(on))));

        Map<String, Object> first = boms.get(0);
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("bomFormat", first.getOrDefault("bomFormat", "CycloneDX"));
        merged.put("specVersion", first.getOrDefault("specVersion", "1.6"));
        merged.put("version", 1);
        merged.put("metadata", Map.of("component", root));
        merged.put("components", List.copyOf(components.values()));
        merged.put("dependencies", deps);
        return merged;
    }

    /** Dependência de fora do repositório: nem outro módulo ({@code :modulo}) nem um pacote dele. */
    static boolean external(String identifier, Set<String> modules) {
        return !identifier.startsWith(":") && !modules.contains(identifier);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static void write(File file, Object value) throws IOException {
        Files.writeString(file.toPath(), Json.write(value), StandardCharsets.UTF_8);
    }
}
