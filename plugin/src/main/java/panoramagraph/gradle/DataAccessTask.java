package panoramagraph.gradle;

import panoramagraph.gradle.dataaccess.DataAccessAnalyzer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * {@code data-access.json} (formato {@code panorama-graph-data-access/2}): os datasets mapeados e onde o código
 * lê ou escreve em cada um, lidos do bytecode. Não lê configuração nem conecta em banco.
 *
 * <p>Só gera o arquivo quando o projeto usa uma API de persistência que o analisador conhece. Sem nenhuma, um arquivo
 * vazio diria ao grafo que o componente não acessa dados, quando o certo é dizer que esta fonte não sabe.
 */
@CacheableTask
public abstract class DataAccessTask extends DefaultTask {

    /** Classes compiladas do código principal. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassesDirs();

    /** Classpath de execução: a hierarquia dos repositórios e as entidades de bibliotecas. */
    @Classpath
    public abstract ConfigurableFileCollection getRuntimeClasspath();

    /** Diretórios de código-fonte, só para apontar o arquivo de cada evidência. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceDirs();

    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    void analyze() throws IOException {
        File dir = getOutputDir().get().getAsFile();
        File out = new File(dir, "data-access.json");
        Files.deleteIfExists(out.toPath());

        Optional<Map<String, Object>> report = DataAccessAnalyzer.analyze(
                new ArrayList<>(getClassesDirs().getFiles()),
                new ArrayList<>(getRuntimeClasspath().getFiles()),
                new ArrayList<>(getSourceDirs().getFiles()),
                getProjectDirectory().get().getAsFile());
        if (report.isEmpty()) {
            getLogger().info("panorama-graph: nenhuma API de persistência conhecida no classpath; sem data-access.json.");
            return;
        }
        Files.createDirectories(dir.toPath());
        Files.writeString(out.toPath(), Json.write(report.get()), StandardCharsets.UTF_8);
        List<?> unresolved = (List<?>) report.get().get("unresolved");
        if (!unresolved.isEmpty()) {
            getLogger().warn("panorama-graph: {} acesso(s) a dados sem dataset identificado; veja {}.",
                    unresolved.size(), out.getName());
        }
    }
}
