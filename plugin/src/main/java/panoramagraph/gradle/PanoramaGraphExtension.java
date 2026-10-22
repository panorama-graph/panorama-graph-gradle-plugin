package panoramagraph.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * O bloco {@code panoramaGraph { }} do build. Tudo tem padrão: o plugin funciona sem configuração, e gera só o
 * que o projeto tem como gerar.
 */
public abstract class PanoramaGraphExtension {

    /** Onde ficam os artefatos (padrão {@code build/architecture}). */
    public abstract DirectoryProperty getOutputDir();

    /**
     * Tecnologias do build além das que o plugin detecta, por nome e versão (ex.: a biblioteca interna da organização).
     * Vão para o {@code manifest.json} e viram {@code BUILT_WITH} no grafo.
     */
    public abstract MapProperty<String, String> getTechnologies();

    /**
     * SBOM CycloneDX (JSON) gerado por outra ferramenta (ex.: syft). Sem ele, o {@code bom.json} é o do plugin
     * CycloneDX do Gradle ({@code org.cyclonedx.bom}), que este plugin aplica.
     */
    public abstract RegularFileProperty getSbomFile();

    /** Onde os testes de contrato gravam os pacts (padrão {@code build/pacts}). Vazia ou ausente, não há pacts. */
    public abstract DirectoryProperty getPactsDir();

    /** Lê o acesso a dados do bytecode (padrão {@code true}). */
    public abstract Property<Boolean> getDataAccess();

    /**
     * Põe a biblioteca {@code panorama-graph-spring-test} no classpath de teste, para exportar OpenAPI, AsyncAPI,
     * clientes HTTP e o nome da aplicação. Sem valor, o plugin a põe quando o projeto é Spring (aplica o plugin do
     * Spring Boot ou declara uma dependência {@code org.springframework*}).
     */
    public abstract Property<Boolean> getSpringTest();

    /**
     * Como separar a versão do nome do serviço num grupo de clientes HTTP, quando a versão está no host
     * ({@code catalog-api-v1}): o grupo 1 é o serviço, o 2 a versão. Padrão {@code ^(.+)-v(\d+)$}.
     */
    public abstract Property<String> getServiceVersionPattern();

    /**
     * URL do graph-receiver, para onde {@code architecturePublish} envia os artefatos. Padrão: a propriedade
     * {@code -PpanoramaGraph.url} ou a variável {@code PANORAMA_GRAPH_URL}.
     */
    public abstract Property<String> getServerUrl();

    /** {@code kind} do componente no grafo: service, bff, frontend, job, library (padrão: service). */
    public abstract Property<String> getComponentKind();
}
