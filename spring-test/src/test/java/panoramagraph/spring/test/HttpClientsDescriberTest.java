package panoramagraph.spring.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.registry.ImportHttpServices;

class HttpClientsDescriberTest {

    @HttpExchange("/products")
    interface ProductsApi {
        @GetExchange("/{id}")
        String getProduct(@PathVariable String id);

        @PostExchange
        String createProduct(String body);
    }

    @Configuration
    @ImportHttpServices(group = "product-catalog-api", types = ProductsApi.class)
    static class ClientsConfig {}

    @Test
    @SuppressWarnings("unchecked")
    void shouldDescribeGroupsAndOperations() {
        try (var context = new AnnotationConfigApplicationContext(ClientsConfig.class)) {
            Map<String, Object> document = HttpClientsDescriber.describe(context);

            List<Map<String, Object>> clients = (List<Map<String, Object>>) document.get("clients");
            assertThat(clients).hasSize(1);
            Map<String, Object> client = clients.get(0);
            assertThat(client.get("group")).isEqualTo("product-catalog-api");
            assertThat(client.get("service")).isEqualTo("product-catalog-api");
            assertThat(client).doesNotContainKey("version");
            assertThat(client).doesNotContainKey("source");
            assertThat(client.get("baseUrlProperty"))
                    .isEqualTo("spring.http.serviceclient.product-catalog-api.base-url");
            assertThat((List<Map<String, Object>>) client.get("operations"))
                    .containsExactly(
                            Map.of("method", "POST", "path", "/products", "function", "createProduct"),
                            Map.of("method", "GET", "path", "/products/{id}", "function", "getProduct"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPointToTheSourceFileOfTheClient(@TempDir Path project) throws Exception {
        Path sources = project.resolve("src/main/kotlin");
        Path file = sources.resolve(HttpClientsDescriberTest.class.getName().replace('.', '/') + ".kt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
        var located = new HttpClientsDescriber.SourceFiles(List.of(project.resolve("src/main/java"), sources), project);

        try (var context = new AnnotationConfigApplicationContext(ClientsConfig.class)) {
            List<Map<String, Object>> clients =
                    (List<Map<String, Object>>) HttpClientsDescriber.describe(context, located).get("clients");

            assertThat(clients.get(0).get("source"))
                    .isEqualTo("src/main/kotlin/panoramagraph/spring/test/HttpClientsDescriberTest.kt");
        }
    }

    @Configuration
    @ImportHttpServices(group = "product-catalog-api-v1", types = ProductsApi.class)
    static class HostVersionedClientsConfig {}

    @Test
    @SuppressWarnings("unchecked")
    void shouldSplitTheVersionFromAHostVersionedGroup() {
        try (var context = new AnnotationConfigApplicationContext(HostVersionedClientsConfig.class)) {
            List<Map<String, Object>> clients =
                    (List<Map<String, Object>>) HttpClientsDescriber.describe(context).get("clients");

            Map<String, Object> client = clients.get(0);
            assertThat(client.get("group")).isEqualTo("product-catalog-api-v1");
            assertThat(client.get("service")).isEqualTo("product-catalog-api");
            assertThat(client.get("version")).isEqualTo("1");
            assertThat(client.get("baseUrlProperty"))
                    .isEqualTo("spring.http.serviceclient.product-catalog-api-v1.base-url");
        }
    }

    /** Como uma anotação própria de uma organização: o caminho da interface vem de uma anotação composta. */
    @Retention(RetentionPolicy.RUNTIME)
    @HttpExchange
    @interface ComposedClient {
        @AliasFor(annotation = HttpExchange.class, attribute = "url")
        String basePath();
    }

    @ComposedClient(basePath = "/rates")
    interface RatesApi {
        @GetExchange("/{id}")
        String getRate(@PathVariable String id);
    }

    @Configuration
    @ImportHttpServices(group = "pricing-api", types = RatesApi.class)
    static class ComposedClientsConfig {}

    @Test
    @SuppressWarnings("unchecked")
    void shouldReadTheBasePathFromAComposedAnnotation() {
        try (var context = new AnnotationConfigApplicationContext(ComposedClientsConfig.class)) {
            List<Map<String, Object>> clients =
                    (List<Map<String, Object>>) HttpClientsDescriber.describe(context).get("clients");

            assertThat((List<Map<String, Object>>) clients.get(0).get("operations"))
                    .containsExactly(Map.of("method", "GET", "path", "/rates/{id}", "function", "getRate"));
        }
    }

    @Test
    void shouldDescribeNoClientsWhenThereIsNoRegistry() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            assertThat(HttpClientsDescriber.describe(context).get("clients")).isEqualTo(List.of());
        }
    }

    @Test
    void shouldJoinPaths() {
        assertThat(HttpClientsDescriber.joinPaths("", "")).isEqualTo("/");
        assertThat(HttpClientsDescriber.joinPaths("/products/", "/{id}")).isEqualTo("/products/{id}");
        assertThat(HttpClientsDescriber.joinPaths("/products", "")).isEqualTo("/products");
    }

    @Test
    void shouldWriteJson() {
        assertThat(Json.write(Map.of("a", List.of("x\"y", 1, true))))
                .isEqualTo("{\n  \"a\": [\n    \"x\\\"y\",\n    1,\n    true\n  ]\n}\n");
    }
}
