package panoramagraph.spring.test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.registry.HttpServiceProxyRegistry;

/**
 * Descreve os clientes HTTP declarativos ({@code @HttpExchange}) registrados em grupos de serviço do Spring Framework 7
 * ({@code @ImportHttpServices(group = "catalog-api", ...)}). A convenção é que o nome do grupo é o serviço de destino,
 * o mesmo nome com que ele se apresenta (o {@code spring.application.name} dele).
 *
 * <p>Quando o destino versiona a API no host, a versão faz parte do nome do serviço na infraestrutura e, portanto, do
 * grupo ({@code catalog-api-v1}). O descritor separa {@code service} e {@code version} pelo padrão
 * {@code panoramaGraph.serviceVersionPattern}, para que o cliente seja ligado ao componente, e não a um nome por
 * versão.
 *
 * <p>{@code source} e o arquivo de codigo do cliente, relativo a raiz do projeto, procurado nos diretorios de codigo
 * que o plugin informa; fora do build (sem essa informacao, ou sem o arquivo), fica de fora.
 */
final class HttpClientsDescriber {

    static final String DEFAULT_SERVICE_VERSION = "^(.+)-v(\\d+)$";

    private HttpClientsDescriber() {}

    static final String SOURCE_DIRS_PROPERTY = "panoramaGraph.sourceDirs";
    static final String PROJECT_DIR_PROPERTY = "panoramaGraph.projectDir";
    static final String SERVICE_VERSION_PATTERN_PROPERTY = "panoramaGraph.serviceVersionPattern";

    static Map<String, Object> describe(ApplicationContext context) {
        return describe(context, SourceFiles.fromSystemProperties(),
                Pattern.compile(System.getProperty(SERVICE_VERSION_PATTERN_PROPERTY, DEFAULT_SERVICE_VERSION)));
    }

    static Map<String, Object> describe(ApplicationContext context, SourceFiles sources) {
        return describe(context, sources, Pattern.compile(DEFAULT_SERVICE_VERSION));
    }

    static Map<String, Object> describe(ApplicationContext context, SourceFiles sources, Pattern serviceVersion) {
        List<Map<String, Object>> clients = new ArrayList<>();
        for (HttpServiceProxyRegistry registry :
                context.getBeansOfType(HttpServiceProxyRegistry.class).values()) {
            registry.getGroupNames().stream().sorted().forEach(group -> registry.getClientTypesInGroup(group).stream()
                    .sorted(Comparator.comparing(Class::getName))
                    .forEach(type -> clients.add(describeClient(group, type, sources, serviceVersion))));
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "panorama-graph-http-clients/2");
        document.put("clients", clients);
        return document;
    }

    private static Map<String, Object> describeClient(
            String group, Class<?> type, SourceFiles sources, Pattern serviceVersion) {
        HttpExchange typeExchange = AnnotatedElementUtils.findMergedAnnotation(type, HttpExchange.class);
        String typeUrl = typeExchange == null ? "" : typeExchange.url();
        String typeMethod = typeExchange == null ? "" : typeExchange.method();

        List<Map<String, Object>> operations = new ArrayList<>();
        Arrays.stream(type.getMethods())
                .sorted(Comparator.comparing(Method::getName))
                .forEach(method -> {
                    HttpExchange exchange = AnnotatedElementUtils.findMergedAnnotation(method, HttpExchange.class);
                    if (exchange == null) {
                        return;
                    }
                    Map<String, Object> operation = new LinkedHashMap<>();
                    operation.put("method", exchange.method().isEmpty() ? typeMethod : exchange.method());
                    operation.put("path", joinPaths(typeUrl, exchange.url()));
                    operation.put("function", method.getName());
                    operations.add(operation);
                });

        Map<String, Object> client = new LinkedHashMap<>();
        client.put("group", group);
        Matcher hostVersion = serviceVersion.matcher(group);
        if (hostVersion.matches()) {
            client.put("service", hostVersion.group(1));
            client.put("version", hostVersion.group(2));
        } else {
            client.put("service", group);
        }
        client.put("type", type.getName());
        sources.of(type).ifPresent(source -> client.put("source", source));
        client.put("baseUrlProperty", "spring.http.serviceclient." + group + ".base-url");
        client.put("operations", operations);
        return client;
    }

    static String joinPaths(String base, String path) {
        if (base.isEmpty()) {
            return path.isEmpty() ? "/" : path;
        }
        if (path.isEmpty()) {
            return base;
        }
        return base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }

    /** Onde procurar o arquivo de codigo de um tipo: os diretorios de codigo e a raiz do projeto. */
    record SourceFiles(List<Path> dirs, Path projectDir) {

        private static final List<String> EXTENSIONS = List.of(".java", ".kt", ".groovy");

        static SourceFiles fromSystemProperties() {
            String dirs = System.getProperty(SOURCE_DIRS_PROPERTY, "");
            String projectDir = System.getProperty(PROJECT_DIR_PROPERTY, "");
            if (dirs.isBlank() || projectDir.isBlank()) {
                return new SourceFiles(List.of(), Path.of(""));
            }
            return new SourceFiles(
                    Arrays.stream(dirs.split(Pattern.quote(File.pathSeparator))).map(Path::of).toList(),
                    Path.of(projectDir));
        }

        Optional<String> of(Class<?> type) {
            Class<?> outer = type;
            while (outer.getEnclosingClass() != null) {
                outer = outer.getEnclosingClass();
            }
            String base = outer.getName().replace('.', '/');
            for (Path dir : dirs) {
                for (String extension : EXTENSIONS) {
                    Path candidate = dir.resolve(base + extension);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(projectDir.relativize(candidate).toString().replace(File.separatorChar, '/'));
                    }
                }
            }
            return Optional.empty();
        }
    }
}
