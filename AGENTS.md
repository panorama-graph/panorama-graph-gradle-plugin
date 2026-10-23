# panorama-graph-gradle-plugin — Guia para agentes

Este arquivo diz **como trabalhar** no projeto. **O que o plugin gera, e quando,** está no [README.md](README.md).

## Regras

- **Nada é pressuposto.** O projeto que aplica o plugin pode não ter Spring, JPA, Pact, springdoc ou qualquer outra biblioteca. Cada artefato tem uma condição, conferida no build (plugin aplicado, classe no classpath, arquivo gravado), e sem ela o arquivo não existe. Nunca gere um artefato vazio por falta da fonte: vazio diz ao grafo que o componente não tem aquilo.
- **Os plugins de build dos artefatos vêm com ele.** CycloneDX e dependency-analysis são dependências do plugin e aplicados por ele: quem adota não declara nem configura nada (a biblioteca interna de uma organização também não). Outros plugins não são aplicados; o plugin reage aos que o projeto aplica (`withPlugin`). As tarefas de terceiros são configuradas por reflexão, porque o projeto pode trazer outra versão do plugin delas.
- **A biblioteca de teste não carrega o que não existe.** Toda classe opcional (Spring MVC, springdoc, Springwolf, os clientes HTTP do Spring 7) é conferida com `ClassUtils.isPresent` antes de ser tocada, e o código que a usa fica numa classe à parte.
- **Detecção pelo que o projeto é, não pelo que chega de carona.** A linguagem vem do código-fonte, não do classpath (a biblioteca padrão do Kotlin chega como dependência de outras).
- **Formato muda junto.** Mudar o que um artefato próprio contém muda o esquema em `schemas/` e o `graph-publish`, no [panorama-graph-adapters](https://github.com/panorama-graph/panorama-graph-adapters), e o README daqui. Mude os dois repositórios juntos: o formato e o tradutor no panorama-graph-adapters, a geração aqui.
- **Mesma versão.** O plugin põe no classpath de teste a biblioteca da própria versão (`plugin.properties`); os dois são publicados juntos.
- **Enviar não é traduzir.** O `architecturePublish` só empacota os arquivos como estão e envia; a tradução para fatos é do `graph-receiver` (a mesma do `graph-publish`). Nada de montar retrato aqui: seria uma segunda tradução para manter igual à primeira.
- **Sem organização no código.** Nada de nome, pacote ou convenção de uma organização: o que é dela entra pela configuração (`technologies`, `serviceVersionPattern`).

## Código

| Caminho | Papel |
|---|---|
| `plugin/.../PanoramaGraphPlugin.java` | Aplica o CycloneDX e o dependency-analysis, registra as tarefas, liga os plugins de terceiros e põe a biblioteca de teste |
| `plugin/.../PanoramaGraphExtension.java` | O bloco `panoramaGraph { }` |
| `plugin/.../ManifestTask.java`, `Identity.java` | `manifest.json`: identidade (CI ou git) e stack |
| `plugin/.../DataAccessTask.java`, `dataaccess/` | `data-access.json`, pela leitura do bytecode (ASM) |
| `plugin/.../PublishTask.java` | `architecturePublish`: envia `build/architecture` ao `graph-receiver` (`POST /v1/builds`) e mostra o resumo |
| `spring-test/.../ArchitectureExportListener.java` | `TestExecutionListener` que exporta do primeiro `@SpringBootTest` da aplicação |
| `spring-test/.../HttpClientsDescriber.java`, `MvcDocuments.java` | Clientes HTTP declarativos; OpenAPI e AsyncAPI pelo MockMvc |

## Testes

- `./gradlew build`.
- `PanoramaGraphPluginTest` aplica o plugin em projetos de exemplo pelo TestKit: um cenário por condição (sem nada, com JPA, SBOM e uso de dependências, projeto que também declara os plugins de build, subprojeto, com SBOM de fora, com pacts, com e sem Spring, linguagem pelo código-fonte), e o envio contra um receptor de mentira (corpo, dry-run, recusa e o que falta). Condição nova pede cenário novo.
- A validação de verdade é num serviço real: `architectureArtifacts` antes e depois da mudança, comparando os arquivos campo a campo, e o `graph-publish --dry-run` sobre o resultado.
