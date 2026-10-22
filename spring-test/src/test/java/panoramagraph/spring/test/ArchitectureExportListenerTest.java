package panoramagraph.spring.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.registry.ImportHttpServices;

/** Cada artefato só sai quando a aplicação tem de onde tirá-lo. */
class ArchitectureExportListenerTest {

    @TempDir
    Path dir;

    @BeforeEach
    void reset() {
        ArchitectureExportListener.resetForTests();
    }

    interface RatesApi {
        @GetExchange("/rates")
        String rates();
    }

    @Configuration
    @ImportHttpServices(group = "pricing-api", types = RatesApi.class)
    static class WithClients {}

    @Configuration
    static class Empty {}

    @Test
    void exportaONomeEOsClientesDeUmaAplicacaoSemWeb() throws IOException {
        try (var context = context(WithClients.class, Map.of("spring.application.name", "checkout-api"))) {
            ArchitectureExportListener.export(context, dir);
        }

        assertThat(files()).containsExactly("application.json", "http-clients.json");
        assertThat(Files.readString(dir.resolve("application.json")))
                .contains("\"format\": \"panorama-graph-application/1\"")
                .contains("\"name\": \"checkout-api\"");
        assertThat(Files.readString(dir.resolve("http-clients.json"))).contains("\"service\": \"pricing-api\"");
    }

    @Test
    void semNomeNaoHaApplicationJson() throws IOException {
        try (var context = context(Empty.class, Map.of())) {
            ArchitectureExportListener.export(context, dir);
        }

        // Os clientes declarativos do Spring 7 estão no classpath: a lista vazia é o que a aplicação declara.
        assertThat(files()).containsExactly("http-clients.json");
    }

    @Test
    void oPadraoDaVersaoNoHostEConfiguravel() {
        try (var context = context(WithClients.class, Map.of())) {
            @SuppressWarnings("unchecked")
            var clients = (List<Map<String, Object>>) HttpClientsDescriber.describe(
                            context,
                            new HttpClientsDescriber.SourceFiles(List.of(), Path.of("")),
                            java.util.regex.Pattern.compile("^(pricing)-(api)$"))
                    .get("clients");
            assertThat(clients.get(0)).containsEntry("service", "pricing").containsEntry("version", "api");
        }
    }

    private static AnnotationConfigApplicationContext context(Class<?> config, Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(config);
        context.refresh();
        return context;
    }

    private List<String> files() {
        String[] names = dir.toFile().list();
        return names == null ? List.of() : Arrays.stream(names).sorted().toList();
    }
}
