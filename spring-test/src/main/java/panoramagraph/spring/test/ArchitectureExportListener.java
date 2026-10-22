package panoramagraph.spring.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.util.ClassUtils;

/**
 * Exporta, do primeiro teste {@code @SpringBootTest} que carrega a aplicação inteira (o contexto contém a classe
 * {@code @SpringBootConfiguration}), o que só a aplicação de pé sabe: o nome do serviço, os clientes HTTP declarativos,
 * o OpenAPI e o AsyncAPI. Reaproveita o contexto do teste; não sobe a aplicação nem abre porta.
 *
 * <p>Cada artefato depende de uma biblioteca, e só é exportado quando ela está no classpath: um artefato vazio diria ao
 * grafo que o serviço não tem aquilo, quando o certo é dizer que esta fonte não sabe.
 *
 * <ul>
 *   <li>{@code application.json}: com {@code spring.application.name} definido.
 *   <li>{@code http-clients.json}: com os clientes HTTP declarativos do Spring Framework 7 (grupos de
 *       {@code @HttpExchange} no {@code HttpServiceProxyRegistry}). Outros clientes (RestTemplate, WebClient montado à
 *       mão, Feign) não aparecem.
 *   <li>{@code openapi.json}: com springdoc, numa aplicação Spring MVC.
 *   <li>{@code asyncapi.json}: com Springwolf, numa aplicação Spring MVC.
 * </ul>
 *
 * <p>Só atua quando o build define {@code panoramaGraph.exportDir} (o plugin {@code panorama-graph} faz isso
 * na tarefa {@code test}). Rodando pela IDE, não faz nada.
 */
public class ArchitectureExportListener implements TestExecutionListener, Ordered {

    static final String EXPORT_DIR_PROPERTY = "panoramaGraph.exportDir";
    static final String APPLICATION_FORMAT = "panorama-graph-application/1";

    private static final Log LOG = LogFactory.getLog(ArchitectureExportListener.class);
    private static final Set<String> EXPORTED = ConcurrentHashMap.newKeySet();

    static final String HTTP_SERVICE_REGISTRY = "org.springframework.web.service.registry.HttpServiceProxyRegistry";
    static final String WEB_APPLICATION_CONTEXT = "org.springframework.web.context.WebApplicationContext";
    static final String MOCK_MVC = "org.springframework.test.web.servlet.MockMvc";
    static final String SPRINGDOC = "org.springdoc.core.properties.SpringDocConfigProperties";
    static final String SPRINGWOLF = "io.github.springwolf.core.asyncapi.AsyncApiService";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        String exportDir = System.getProperty(EXPORT_DIR_PROPERTY);
        if (exportDir == null || exportDir.isBlank()) {
            return;
        }
        if (!AnnotatedElementUtils.hasAnnotation(testContext.getTestClass(), SpringBootTest.class)
                || !testContext.hasApplicationContext()) {
            return;
        }
        ApplicationContext context = testContext.getApplicationContext();
        if (!isApplicationContext(context)) {
            return;
        }
        export(context, Path.of(exportDir));
    }

    /** O contexto contém a classe {@code @SpringBootConfiguration}: não é um teste com configuração própria. */
    static boolean isApplicationContext(ApplicationContext context) {
        return context.getBeanNamesForAnnotation(SpringBootConfiguration.class).length > 0;
    }

    static void export(ApplicationContext context, Path dir) {
        Environment env = context.getEnvironment();
        String name = env.getProperty("spring.application.name");
        if (name != null && !name.isBlank()) {
            exportOnce("application.json", dir, () -> Json.write(describeApplication(name)));
        }
        if (present(HTTP_SERVICE_REGISTRY)) {
            exportOnce("http-clients.json", dir, () -> Json.write(HttpClientsDescriber.describe(context)));
        }
        // As classes do Spring MVC só são tocadas depois de conferidas: numa aplicação sem web, ou reativa, elas não
        // existem.
        if (present(WEB_APPLICATION_CONTEXT) && present(MOCK_MVC) && MvcDocuments.isServletWeb(context)) {
            if (present(SPRINGDOC)) {
                String path = env.getProperty("springdoc.api-docs.path", "/v3/api-docs");
                exportOnce("openapi.json", dir, () -> MvcDocuments.get(context, path));
            }
            if (present(SPRINGWOLF)) {
                String path = env.getProperty("springwolf.path.base", "/springwolf")
                        + env.getProperty("springwolf.path.docs", "/docs");
                exportOnce("asyncapi.json", dir, () -> MvcDocuments.get(context, path));
            }
        }
    }

    /** O nome com que o serviço se apresenta (o participante nos pacts, o grupo dos clientes HTTP). */
    static Map<String, Object> describeApplication(String name) {
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("format", APPLICATION_FORMAT);
        application.put("name", name);
        return application;
    }

    private static boolean present(String className) {
        return ClassUtils.isPresent(className, ArchitectureExportListener.class.getClassLoader());
    }

    private static void exportOnce(String fileName, Path dir, ThrowingSupplier content) {
        if (!EXPORTED.add(fileName)) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName), content.get(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            EXPORTED.remove(fileName);
            LOG.warn("panorama-graph: não foi possível exportar " + fileName + ": " + exception.getMessage());
        }
    }

    @FunctionalInterface
    interface ThrowingSupplier {
        String get() throws Exception;
    }

    static void resetForTests() {
        EXPORTED.clear();
    }
}
