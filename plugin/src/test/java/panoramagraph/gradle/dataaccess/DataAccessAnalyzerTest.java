package panoramagraph.gradle.dataaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import panoramagraph.gradle.dataaccess.sample.CatalogService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DataAccessAnalyzerTest {

    static Map<String, Object> report;

    @BeforeAll
    static void analyze() throws URISyntaxException {
        File classes = new File(CatalogService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<File> classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(File::new)
                .filter(f -> !f.equals(classes))
                .toList();
        File projectDir = new File(System.getProperty("user.dir"));
        report = DataAccessAnalyzer.analyze(
                        List.of(classes), classpath, List.of(new File(projectDir, "src/test/java")), projectDir)
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(String key) {
        return (List<Map<String, Object>>) report.get(key);
    }

    /** "caller -> operation dataset", para comparar sem depender de linha. */
    static List<String> accesses() {
        return list("accesses").stream()
                .map(a -> simple((String) a.get("caller")) + " -> " + a.get("operation") + " " + a.get("dataset"))
                .sorted()
                .toList();
    }

    static String simple(String caller) {
        return caller.substring(caller.lastIndexOf('.') + 1);
    }

    @Test
    void datasetsPeloMapeamento() {
        Map<String, String> datasets = list("datasets").stream()
                .collect(Collectors.toMap(d -> (String) d.get("name"), d -> d.get("store") + "/" + d.get("declaredIn")));
        assertEquals(
                Map.of(
                        "products", "document/project",
                        "sequence", "document/project",
                        "invoices", "relational/project",
                        "line_item", "relational/project",
                        "audit_log", "document/none"),
                datasets);
        Map<String, Object> products = list("datasets").stream()
                .filter(d -> d.get("name").equals("products"))
                .findFirst()
                .orElseThrow();
        assertEquals("src/test/java/panoramagraph/gradle/dataaccess/sample/Product.java", products.get("source"));
    }

    @Test
    void acessosPelosPontosDeChamada() {
        assertEquals(
                List.of(
                        "CatalogService#audits -> read audit_log",
                        "CatalogService#byName -> read products",
                        "CatalogService#get -> read products",
                        "CatalogService#insert -> write products",
                        "CatalogService#nextCode -> read-write sequence",
                        "CatalogService#removeItem -> write line_item",
                        "CatalogService#save -> write products",
                        "CatalogService#view -> write products",
                        "ProductRepositoryImpl#findSlice -> read products"),
                accesses());
        Map<String, Object> save = list("accesses").stream()
                .filter(a -> a.get("caller").toString().endsWith("#save"))
                .findFirst()
                .orElseThrow();
        assertEquals("src/test/java/panoramagraph/gradle/dataaccess/sample/CatalogService.java", save.get("source"));
        assertTrue((Integer) save.get("line") > 0);
        assertEquals("panoramagraph.gradle.dataaccess.sample.ProductRepository#save", save.get("via"));
    }

    @Test
    void oQueNaoResolveFicaComOMotivo() {
        List<String> reasons = list("unresolved").stream()
                .map(u -> u.getOrDefault("caller", u.get("via")) + ": " + u.get("reason"))
                .map(s -> s.replace("panoramagraph.gradle.dataaccess.sample.", ""))
                .sorted()
                .toList();
        assertEquals(
                List.of(
                        "Audit: nome do dataset calculado em tempo de execucao (#{@tenant.prefix}_audit)",
                        "CatalogService#anywhere: nome da colecao em variavel",
                        "CatalogService#archive: metodo de repositorio archiveOld sem leitura ou escrita reconhecida",
                        "CatalogService#generic: classe da entidade em variavel"),
                reasons);
    }

    @Test
    void operacaoPeloNomeDoMetodo() {
        assertEquals("read", DataAccessAnalyzer.operationByName("findAllByStatus"));
        assertEquals("read", DataAccessAnalyzer.operationByName("existsById"));
        assertEquals("write", DataAccessAnalyzer.operationByName("deleteAllById"));
        assertEquals("write", DataAccessAnalyzer.operationByName("saveAll"));
        assertEquals(null, DataAccessAnalyzer.operationByName("finder"));
        assertEquals(null, DataAccessAnalyzer.operationByName("archive"));
    }

    @Test
    void semApiDePersistenciaConhecidaNaoHaRelatorio() throws URISyntaxException {
        File classes = new File(CatalogService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File projectDir = new File(System.getProperty("user.dir"));
        // Sem o classpath, as anotações e os repositórios do Spring Data e do JPA não existem para o analisador.
        assertTrue(DataAccessAnalyzer.analyze(List.of(classes), List.of(), List.of(), projectDir).isEmpty());
    }
}
