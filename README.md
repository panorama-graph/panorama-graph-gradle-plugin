# gradle-plugin

Plugin Gradle `panorama-graph`: a tarefa `architectureArtifacts` reúne em `build/architecture` o que o build sabe do componente, e a `architecturePublish` envia esse diretório ao `graph-receiver` do grafo, que o traduz e publica (como o `./gradlew sonar` envia a análise ao servidor). Funciona sem configuração em qualquer projeto Java, e gera só o que o projeto tem como gerar. Os plugins de build de onde tira o SBOM e o uso de dependências (CycloneDX e dependency-analysis) vêm com ele: o projeto não precisa declará-los.

Dois artefatos publicados, sempre na mesma versão:

| Artefato | O que é |
|---|---|
| `panorama-graph:panorama-graph-gradle-plugin` | O plugin (id `panorama-graph`) |
| `panorama-graph:panorama-graph-spring-test` | A biblioteca que exporta, dos testes, o que só a aplicação Spring de pé sabe. O plugin a põe no classpath de teste sozinho, quando o projeto é Spring |

## Usar

```groovy
// settings.gradle: o repositório Maven onde o plugin está publicado
pluginManagement {
    repositories { maven { url = uri('https://repo.example.com/maven') }; gradlePluginPortal() }
}

// build.gradle
plugins {
    id 'java'
    id 'panorama-graph' version '0.5.1'
}
```

```sh
./gradlew architectureArtifacts architecturePublish -PpanoramaGraph.url=https://graph.example.com/receiver
```

No fim, o build diz o que gerou e, do que não gerou, o motivo:

```
panorama-graph: gerados manifest.json, bom.json, dependency-usage, data-access.json, application.json, openapi.json
panorama-graph: não gerados pacts (nenhum pact em panoramaGraph.pactsDir); ...
```

## O que sai, e de onde

O plugin não pressupõe biblioteca nenhuma do projeto. Os plugins de build de onde saem o SBOM e o uso de dependências, o CycloneDX (`org.cyclonedx.bom`) e o dependency-analysis (`com.autonomousapps.dependency-analysis`), ele aplica e configura sozinho; se o projeto também os declara, nada muda. O dependency-analysis baixa uma biblioteca de apoio (`org.jetbrains.kotlin:kotlin-metadata-jvm`) dos repositórios do projeto. Cada artefato tem uma condição; sem ela, o arquivo não existe, e o grafo mantém o que tinha daquela fonte. Um arquivo vazio diria ao grafo que o componente não tem aquilo, quando o certo é dizer que esta fonte não sabe.

| Arquivo | Sai quando | Como |
|---|---|---|
| `manifest.json` | sempre | Grupo, nome e versão do projeto; repositório e commit das variáveis do CI (GitLab, GitHub) ou do git; a stack: Java (toolchain), Gradle, Kotlin e Groovy quando há código-fonte deles em `src/main`, Spring Boot, Quarkus ou Micronaut quando estão no classpath de execução, e o que o build declarar em `technologies`; os pacotes que o build gera (`packages`), pelas publicações Maven do projeto e, na raiz, de todos os módulos |
| `bom.json` | sempre (ou o arquivo de `sbomFile`, quando informado) | Pelo `cyclonedxDirectBom` do plugin CycloneDX, configurado para só o classpath de execução, sem testes: é o que vai para produção |
| `dependency-usage/` | o projeto é de um tipo que o dependency-analysis analisa: `java-library`, `application`, Spring Boot ou Kotlin (o plugin `java` sozinho não basta) | Os relatórios brutos dele (`declarations.json`, `usages-dependencies.json`) |
| `data-access.json` | há no classpath uma API de persistência que o analisador conhece: JPA (`jakarta.persistence` ou `javax.persistence`), Spring Data (repositórios, MongoDB, JDBC) | Lido do bytecode: as entidades e coleções mapeadas e cada ponto do código que lê ou escreve nelas. SQL em texto, `EntityManager` e driver direto saem em `unresolved`, com o motivo |
| `pacts/` | os testes de contrato gravaram pacts em `pactsDir` (padrão `build/pacts`) | Copiados como estão. O framework de contrato é do projeto |
| `application.json` | Spring, com `spring.application.name` | Pela biblioteca de teste, no primeiro `@SpringBootTest` da aplicação |
| `http-clients.json` | Spring Framework 7, com os clientes HTTP declarativos (`@HttpExchange` em grupos de `@ImportHttpServices`) | Idem. O nome do grupo é o serviço de destino. Outros clientes (RestTemplate, WebClient montado à mão, Feign) não aparecem |
| `openapi.json` | Spring MVC com springdoc | Idem, pelo MockMvc, sem subir a aplicação nem abrir porta |
| `asyncapi.json` | Spring MVC com Springwolf | Idem |

Os formatos próprios (manifesto, aplicação, clientes HTTP, acesso a dados) estão especificados em JSON Schema em [../adapters/schemas/](../adapters/schemas/). Os demais são padrões de fora (CycloneDX, OpenAPI, AsyncAPI, Pact).

## Configurar

Tudo é opcional:

```groovy
panoramaGraph {
    outputDir = layout.buildDirectory.dir('architecture')   // padrão
    technologies.put('plataforma-interna', '2.3.0')             // entra no manifesto, vira BUILT_WITH
    sbomFile = file('build/sbom/cyclonedx.json')            // SBOM de outra ferramenta (ex.: syft)
    pactsDir = layout.buildDirectory.dir('pacts')           // padrão
    dataAccess = false                                      // desliga a leitura do bytecode
    springTest = false                                      // não põe a biblioteca de teste (sem valor: põe se o projeto for Spring)
    serviceVersionPattern = '^(.+)-v(\\d+)$'                // padrão: catalog-api-v1 é o serviço catalog-api, versão 1
    serverUrl = 'https://graph.example.com/receiver'       // padrão: -PpanoramaGraph.url ou PANORAMA_GRAPH_URL
    componentKind = 'bff'                                   // padrão: service (ou -PpanoramaGraph.kind)
}
```

**APIs versionadas.** O grafo guarda uma API por versão (`api:catalog-api/v1/http`). Versão no código, pelo primeiro segmento do caminho (`/v1/products`), sai do OpenAPI e dos clientes sem configuração. Versão na infraestrutura (o cliente chama `catalog-api-v1`) sai dos clientes pelo `serviceVersionPattern`; do lado do provedor, o build não a sabe (o `spring.application.name` é sem versão), e quem a afirma é o `graph-deployments`, pelo Service com a versão no nome.

**Projeto Spring sem `@SpringBootTest`?** OpenAPI, AsyncAPI, clientes HTTP e o nome saem do primeiro teste que carrega a aplicação inteira (o contexto contém a classe `@SpringBootConfiguration`). Sem esse teste, esses arquivos não saem. Um teste de fumaça basta:

```java
@SpringBootTest
class ApplicationTest {
    @Test
    void sobe() {}
}
```

## Publicar

`architecturePublish` envia cada arquivo JSON de `build/architecture` ao `graph-receiver` (`POST /v1/builds`, formato `panorama-graph-build/1`) e mostra no build o resumo que ele devolve: um retrato por artefato e o que a API fez com cada um. Não gera nada nem depende do `architectureArtifacts`: roda depois dele no mesmo build, ou num job à parte que recebeu o diretório. Sem `manifest.json` no diretório, ou sem a URL, falha dizendo o que falta; quando o receptor recusa o pacote, o build falha com os erros.

| Propriedade (`-P`) | Para quê |
|---|---|
| `panoramaGraph.url` | URL do `graph-receiver` (ou a variável `PANORAMA_GRAPH_URL`, ou `serverUrl` no build) |
| `panoramaGraph.dryRun` | Só confere: o receptor monta os retratos e não grava. Para o pipeline de MR |
| `panoramaGraph.version` | Versão do artefato, quando o pipeline a calcula depois do build que gerou o manifesto |
| `panoramaGraph.kind` | `kind` do componente no grafo (padrão: service) |

A identidade do componente (repositório e commit) vem do `manifest.json`, que o build gera com as variáveis do CI (GitLab, GitHub) ou com o git.

**Biblioteca de vários módulos.** Aplicado na raiz de um projeto de vários módulos sem código próprio (sem o plugin `java`), o plugin descreve o repositório inteiro, num retrato só: o manifesto, com os pacotes que todos os módulos publicam e a stack dos módulos Java (na versão mais nova, se divergem), e o SBOM e o uso de dependências juntados dos módulos Java (o plugin aplica neles o CycloneDX e o dependency-analysis). Um módulo do próprio repositório não entra como biblioteca: o que ele traz conta pelas dependências de fora que o trazem. `architecturePublish` envia o retrato. Com `componentKind = 'library'`, o grafo liga o repositório aos componentes que dependem dos pacotes dele (`library_usage` pelo componente). Aplique-o só na raiz: cada módulo publicaria um retrato do mesmo componente.

## Sem mexer no build do projeto

Para experimentar o grafo num projeto que não aplica o plugin, um init script o aplica só naquela execução, sem tocar no `build.gradle` nem no `settings.gradle`:

```groovy
// panorama-graph.init.gradle, fora do projeto
initscript {
    repositories { maven { url = uri('https://repo.example.com/maven') }; gradlePluginPortal() }
    dependencies { classpath 'panorama-graph:panorama-graph-gradle-plugin:0.5.1' }
}
allprojects {
    repositories { maven { url = uri('https://repo.example.com/maven') } }   // a biblioteca de teste, nos projetos Spring
    apply plugin: panoramagraph.gradle.PanoramaGraphPlugin           // num init script, pela classe
}
```

```sh
./gradlew --init-script ~/panorama-graph.init.gradle test architectureArtifacts
./gradlew --init-script ~/panorama-graph.init.gradle architectureArtifacts -x test   # se os testes não rodam aqui
```

Sem os testes saem o manifesto, o SBOM, o uso de dependências e o acesso a dados; o que vem da aplicação Spring (nome, OpenAPI, AsyncAPI, clientes HTTP) precisa do `@SpringBootTest`. A saída fica em `build/architecture/`, como no uso normal.

## No CI

O job de testes roda `./gradlew architectureArtifacts` e guarda `build/architecture/` como artefato do pipeline; um job seguinte roda `./gradlew architecturePublish` com esse diretório (em MR, com `-PpanoramaGraph.dryRun`). O pipeline não precisa de imagem do grafo. Para builds que não são Gradle, o [graph-publish](../adapters/README.md#no-pipeline) faz o mesmo de uma imagem, e o pacote também pode ir por `curl` ([graph-receiver](../adapters/README.md#graph-receiver)).

## Desenvolvimento

```sh
./gradlew build                                   # testes, inclusive os funcionais (TestKit) com projetos de exemplo
./gradlew -PprojectVersion=0.5.1 publishToMavenLocal
PUBLISH_REPOSITORY_URL=... PUBLISH_USERNAME=... PUBLISH_PASSWORD=... ./gradlew -PprojectVersion=0.5.1 publish
```

O plugin e a biblioteca compilam para Java 17, o mínimo do Gradle 9 e do Spring Framework 7.
