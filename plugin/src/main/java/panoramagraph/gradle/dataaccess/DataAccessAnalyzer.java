package panoramagraph.gradle.dataaccess;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Le o bytecode do codigo principal e descreve os dados do componente: os datasets mapeados ({@code @Document},
 * {@code @Entity}, {@code @Table}) e cada ponto do codigo que le ou escreve neles, por repositorio Spring Data ou por
 * {@code MongoTemplate}. O que a leitura estatica nao resolve (colecao em variavel, SQL, driver) sai em
 * {@code unresolved}, com o motivo, em vez de sumir.
 *
 * <p>Formato {@code panorama-graph-data-access/2}. O {@code store} e a categoria do banco ({@code document},
 * {@code relational}), e nao o motor: o motor vem da instancia em que o banco de cada ambiente esta. Codigo morto conta
 * como acesso: nao ha analise de alcancabilidade.
 */
public final class DataAccessAnalyzer {

    public static final String FORMAT = "panorama-graph-data-access/2";

    /** Categorias do banco ({@code store}) que o mapeamento do Spring Data revela. */
    static final String DOCUMENT = "document";
    static final String RELATIONAL = "relational";

    static final String REPOSITORY = "org/springframework/data/repository/Repository";
    static final String MONGO_DOCUMENT = "Lorg/springframework/data/mongodb/core/mapping/Document;";
    static final String JPA_ENTITY = "Ljakarta/persistence/Entity;";
    static final String JPA_TABLE = "Ljakarta/persistence/Table;";
    /** O JPA antes do Jakarta EE 9 ({@code javax.persistence}), com as mesmas regras. */
    static final String LEGACY_JPA_ENTITY = "Ljavax/persistence/Entity;";
    static final String LEGACY_JPA_TABLE = "Ljavax/persistence/Table;";

    /**
     * As APIs de persistência que o analisador sabe ler. Sem nenhuma delas no classpath, ele não tem o que dizer, e
     * não gera o relatório: um relatório vazio afirmaria que o componente não acessa dados.
     */
    static final List<String> SUPPORTED_APIS = List.of(
            "org/springframework/data/repository/Repository",
            "org/springframework/data/mongodb/core/MongoOperations",
            "org/springframework/data/relational/core/mapping/Table",
            "jakarta/persistence/Entity",
            "javax/persistence/Entity");
    static final String JDBC_TABLE = "Lorg/springframework/data/relational/core/mapping/Table;";
    static final Set<String> WRITE_ANNOTATIONS = Set.of(
            "Lorg/springframework/data/mongodb/repository/Update;",
            "Lorg/springframework/data/jpa/repository/Modifying;",
            "Lorg/springframework/data/jdbc/repository/query/Modifying;");
    static final String MONGO_QUERY = "Lorg/springframework/data/mongodb/repository/Query;";

    static final Set<String> MONGO_OWNERS = Set.of(
            "org/springframework/data/mongodb/core/MongoTemplate",
            "org/springframework/data/mongodb/core/MongoOperations",
            "org/springframework/data/mongodb/core/ReactiveMongoTemplate",
            "org/springframework/data/mongodb/core/ReactiveMongoOperations");
    static final Set<String> MONGO_READ = Set.of(
            "find", "findOne", "findAll", "findById", "findDistinct", "count", "estimatedCount", "exactCount",
            "exists", "aggregate", "aggregateStream", "stream", "query", "scroll", "geoNear", "mapReduce");
    static final Set<String> MONGO_WRITE = Set.of(
            "insert", "insertAll", "save", "updateFirst", "updateMulti", "upsert", "remove", "bulkOps", "update",
            "replace");
    static final Set<String> MONGO_READ_WRITE =
            Set.of("findAndModify", "findAndRemove", "findAndReplace", "findAllAndRemove");
    static final Set<String> MONGO_DRIVER = Set.of("getCollection", "execute", "executeCommand");

    /** Acesso que a leitura do bytecode nao resolve: a consulta esta em texto (SQL, JPQL) ou vai direto ao driver. */
    static final Map<String, String> RAW_OWNERS = Map.of(
            "org/springframework/jdbc/core/JdbcTemplate", "SQL do JdbcTemplate nao e analisado",
            "org/springframework/jdbc/core/JdbcOperations", "SQL do JdbcTemplate nao e analisado",
            "org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate", "SQL do JdbcTemplate nao e analisado",
            "org/springframework/jdbc/core/namedparam/NamedParameterJdbcOperations", "SQL do JdbcTemplate nao e analisado",
            "org/springframework/jdbc/core/simple/JdbcClient", "SQL do JdbcClient nao e analisado",
            "jakarta/persistence/EntityManager", "acesso pelo EntityManager nao e analisado",
            "javax/persistence/EntityManager", "acesso pelo EntityManager nao e analisado",
            "com/mongodb/client/MongoCollection", "acesso direto pelo driver do MongoDB",
            "com/mongodb/client/MongoDatabase", "acesso direto pelo driver do MongoDB");

    static final List<String> READ_PREFIXES =
            List.of("find", "read", "get", "query", "search", "stream", "count", "exists");
    static final List<String> WRITE_PREFIXES = List.of("save", "insert", "delete", "remove", "update");

    private final ClassIndex index;
    private final List<File> sourceDirs;
    private final File projectDir;

    private final Map<String, Mapping> mappings = new HashMap<>();
    private final Map<String, Map<String, Object>> datasets = new LinkedHashMap<>();
    private final List<Map<String, Object>> accesses = new ArrayList<>();
    private final List<Map<String, Object>> unresolved = new ArrayList<>();
    private final Map<String, Optional<String>> repositoryEntities = new HashMap<>();

    /** Como uma classe e mapeada: nome do dataset (ou o motivo de nao ter), banco e tipo. */
    record Mapping(String name, String store, String kind, String problem) {}

    private DataAccessAnalyzer(ClassIndex index, List<File> sourceDirs, File projectDir) {
        this.index = index;
        this.sourceDirs = sourceDirs;
        this.projectDir = projectDir;
    }

    /**
     * @param classDirs  classes compiladas do codigo principal
     * @param classpath  classpath de execucao, consultado sob demanda
     * @param sourceDirs diretorios de codigo-fonte, para apontar o arquivo de cada evidencia
     * @param projectDir raiz do projeto: os caminhos de evidencia saem relativos a ela
     */
    public static Optional<Map<String, Object>> analyze(
            List<File> classDirs, List<File> classpath, List<File> sourceDirs, File projectDir) {
        try (ClassIndex index = new ClassIndex(classDirs, classpath)) {
            if (SUPPORTED_APIS.stream().noneMatch(api -> index.find(api).isPresent())) {
                return Optional.empty();
            }
            return Optional.of(new DataAccessAnalyzer(index, sourceDirs, projectDir).run());
        }
    }

    private Map<String, Object> run() {
        for (ClassNode node : index.projectClasses()) {
            Mapping mapping = mapping(node);
            if (mapping != null) {
                registerDataset(node, mapping, true);
            }
        }
        for (ClassNode node : index.projectClasses()) {
            for (MethodNode method : node.methods) {
                scan(node, method);
            }
        }
        accesses.sort(Comparator.comparing((Map<String, Object> a) -> String.valueOf(a.get("source")))
                .thenComparing(a -> (Integer) a.get("line"))
                .thenComparing(a -> String.valueOf(a.get("dataset"))));
        unresolved.sort(Comparator.comparing((Map<String, Object> a) -> String.valueOf(a.get("source")))
                .thenComparing(a -> (Integer) a.get("line")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", FORMAT);
        List<Map<String, Object>> sortedDatasets = new ArrayList<>(datasets.values());
        sortedDatasets.sort(Comparator.comparing((Map<String, Object> d) -> String.valueOf(d.get("name"))));
        out.put("datasets", sortedDatasets);
        out.put("accesses", accesses);
        out.put("unresolved", unresolved);
        return out;
    }

    // ---- datasets

    /** O mapeamento da classe, ou null quando ela nao e mapeada para um dataset. */
    Mapping mapping(ClassNode node) {
        return mappings.computeIfAbsent(node.name, n -> readMapping(node));
    }

    private Mapping readMapping(ClassNode node) {
        AnnotationNode document = annotation(node.visibleAnnotations, MONGO_DOCUMENT);
        if (document != null) {
            String name = firstNonBlank(value(document, "value"), value(document, "collection"));
            return named(name != null ? name : uncapitalize(simpleName(node.name)), DOCUMENT, "collection");
        }
        AnnotationNode entity = annotation(node.visibleAnnotations, JPA_ENTITY);
        AnnotationNode table = annotation(node.visibleAnnotations, JPA_TABLE);
        if (entity == null) {
            entity = annotation(node.visibleAnnotations, LEGACY_JPA_ENTITY);
            table = annotation(node.visibleAnnotations, LEGACY_JPA_TABLE);
        }
        if (entity != null) {
            String name = table != null ? value(table, "name") : null;
            if (name == null) {
                String entityName = value(entity, "name");
                name = snakeCase(entityName != null ? entityName : simpleName(node.name));
            }
            return named(name, RELATIONAL, "table");
        }
        AnnotationNode jdbcTable = annotation(node.visibleAnnotations, JDBC_TABLE);
        if (jdbcTable != null) {
            String name = firstNonBlank(value(jdbcTable, "value"), value(jdbcTable, "name"));
            return named(name != null ? name : snakeCase(simpleName(node.name)), RELATIONAL, "table");
        }
        return null;
    }

    private static Mapping named(String name, String store, String kind) {
        if (name.contains("#{") || name.contains("${")) {
            return new Mapping(null, store, kind, "nome do dataset calculado em tempo de execucao (" + name + ")");
        }
        return new Mapping(name, store, kind, null);
    }

    private void registerDataset(ClassNode node, Mapping mapping, boolean project) {
        if (mapping.name() == null) {
            unresolved.add(problem(mapping.problem(), node.name.replace('/', '.'), null, sourcePath(node), 0));
            return;
        }
        String id = mapping.store() + "/" + mapping.name();
        if (datasets.containsKey(id)) {
            return;
        }
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("name", mapping.name());
        dataset.put("store", mapping.store());
        dataset.put("kind", mapping.kind());
        dataset.put("type", node.name.replace('/', '.'));
        dataset.put("declaredIn", project ? "project" : "library");
        if (project) {
            dataset.put("source", sourcePath(node));
        }
        datasets.put(id, dataset);
    }

    /** O mapeamento de uma entidade, registrando o dataset se ela vier de uma biblioteca; sem nome, traz o motivo. */
    private Mapping entityMapping(String internalName) {
        Optional<ClassNode> node = index.find(internalName);
        if (node.isEmpty()) {
            return new Mapping(null, null, null, "classe " + internalName.replace('/', '.') + " nao encontrada");
        }
        Mapping mapping = mapping(node.get());
        if (mapping == null) {
            return new Mapping(null, null, null,
                    "classe " + internalName.replace('/', '.') + " nao e mapeada (@Document, @Entity, @Table)");
        }
        if (mapping.name() != null && !index.isProjectClass(internalName)) {
            registerDataset(node.get(), mapping, false);
        }
        return mapping;
    }

    // ---- acessos

    private void scan(ClassNode owner, MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) {
            return;
        }
        Frame<SourceValue>[] frames = null;
        boolean framesFailed = false;
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call)) {
                continue;
            }
            Site site = new Site(owner, method, call);
            if (MONGO_OWNERS.contains(call.owner)) {
                if (frames == null && !framesFailed) {
                    try {
                        frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
                    } catch (AnalyzerException e) {
                        framesFailed = true;
                    }
                }
                if (framesFailed) {
                    unresolved.add(problemAt(site, "nao foi possivel analisar o metodo"));
                } else {
                    templateCall(site, frames[method.instructions.indexOf(call)]);
                }
            } else if (RAW_OWNERS.containsKey(call.owner)) {
                unresolved.add(problemAt(site, RAW_OWNERS.get(call.owner)));
            } else if (index.isProjectClass(call.owner) && repositoryEntity(call.owner) != null) {
                repositoryCall(site);
            }
        }
    }

    /** Chamada a um repositorio Spring Data do projeto: a entidade pelo tipo generico, a operacao pelo metodo. */
    private void repositoryCall(Site site) {
        MethodInsnNode call = site.call();
        Declared declared = declaration(call.owner, call.name, call.desc);
        if (declared != null && index.isProjectClass(declared.owner().name)) {
            if (repositoryEntity(declared.owner().name) == null) {
                return; // fragmento com implementacao propria: o acesso real esta na implementacao
            }
            if ((declared.method().access & Opcodes.ACC_ABSTRACT) == 0) {
                return; // metodo default: o corpo dele e analisado como qualquer outro
            }
        }
        String operation = declared != null ? annotatedOperation(declared.method()) : null;
        if (operation == null) {
            operation = operationByName(call.name);
        }
        if (operation == null) {
            unresolved.add(problemAt(site, "metodo de repositorio " + call.name + " sem leitura ou escrita reconhecida"));
            return;
        }
        String entity = repositoryEntity(call.owner).orElse(null);
        if (entity == null) {
            unresolved.add(problemAt(site, "entidade do repositorio " + dotted(call.owner) + " nao identificada"));
            return;
        }
        addAccess(site, entityMapping(entity), operation);
    }

    /** Chamada ao MongoTemplate: operacao pela tabela, colecao pela constante passada. */
    private void templateCall(Site site, Frame<SourceValue> frame) {
        MethodInsnNode call = site.call();
        String operation = MONGO_READ_WRITE.contains(call.name)
                ? "read-write"
                : MONGO_WRITE.contains(call.name) ? "write" : MONGO_READ.contains(call.name) ? "read" : null;
        if (operation == null) {
            if (MONGO_DRIVER.contains(call.name)) {
                unresolved.add(problemAt(site, "acesso pelo driver ou por comando (" + call.name + ")"));
            }
            return; // administracao (indices, colecoes), configuracao e getters
        }
        if (frame == null) {
            return; // codigo inalcancavel
        }
        Type[] args = Type.getArgumentTypes(call.desc);
        int base = frame.getStackSize() - args.length;
        boolean skipFirstString = call.name.equals("findDistinct"); // o primeiro String e o campo
        Integer collectionArg = null;
        Integer classArg = null;
        Integer objectArg = null;
        for (int i = 0; i < args.length; i++) {
            String d = args[i].getDescriptor();
            if (d.equals("Ljava/lang/String;")) {
                if (skipFirstString) {
                    skipFirstString = false;
                } else {
                    collectionArg = i;
                }
            } else if (d.equals("Ljava/lang/Class;") && classArg == null) {
                classArg = i;
            } else if (d.equals("Ljava/lang/Object;") && objectArg == null) {
                objectArg = i;
            }
        }
        if (collectionArg != null) {
            Object constant = constant(frame.getStack(base + collectionArg));
            if (constant instanceof String name) {
                addAccess(site, new Mapping(name, DOCUMENT, "collection", null), operation);
            } else {
                unresolved.add(problemAt(site, "nome da colecao em variavel"));
            }
            return;
        }
        if (classArg != null) {
            Object constant = constant(frame.getStack(base + classArg));
            if (constant instanceof Type type && type.getSort() == Type.OBJECT) {
                addAccess(site, entityMapping(type.getInternalName()), operation);
            } else {
                unresolved.add(problemAt(site, "classe da entidade em variavel"));
            }
            return;
        }
        if (objectArg != null) {
            String type = typeOf(frame.getStack(base + objectArg), site.method(), call);
            if (type != null && !type.equals("java/lang/Object")) {
                addAccess(site, entityMapping(type), operation);
            } else {
                unresolved.add(problemAt(site, "tipo do objeto passado a " + call.name + " nao identificado"));
            }
            return;
        }
        unresolved.add(problemAt(site, "colecao de " + call.name + " nao identificada"));
    }

    private void addAccess(Site site, Mapping mapping, String operation) {
        if (mapping.name() == null) {
            unresolved.add(problemAt(site, mapping.problem()));
            return;
        }
        if (mapping.store().equals(DOCUMENT) && !datasets.containsKey(DOCUMENT + "/" + mapping.name())) {
            // colecao citada so pelo nome: existe para o componente, sem classe que a mapeie
            Map<String, Object> dataset = new LinkedHashMap<>();
            dataset.put("name", mapping.name());
            dataset.put("store", DOCUMENT);
            dataset.put("kind", "collection");
            dataset.put("declaredIn", "none");
            datasets.put(DOCUMENT + "/" + mapping.name(), dataset);
        }
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("dataset", mapping.name());
        access.put("store", mapping.store());
        access.put("operation", operation);
        access.put("via", dotted(site.call().owner) + "#" + site.call().name);
        access.put("caller", dotted(site.owner().name) + "#" + site.method().name);
        access.put("source", sourcePath(site.owner()));
        access.put("line", site.line());
        accesses.add(access);
    }

    // ---- repositorios

    /** A entidade de um repositorio Spring Data do projeto, ou null se a interface nao e um repositorio. */
    Optional<String> repositoryEntity(String internalName) {
        return repositoryEntities.computeIfAbsent(internalName, this::readRepositoryEntity);
    }

    private Optional<String> readRepositoryEntity(String internalName) {
        Optional<ClassNode> node = index.find(internalName);
        if (node.isEmpty() || (node.get().access & Opcodes.ACC_INTERFACE) == 0) {
            return null;
        }
        if (!index.supertypes(internalName).contains(REPOSITORY)) {
            return null;
        }
        String signature = node.get().signature;
        if (signature != null) {
            for (String[] parent : Signatures.superTypes(signature)) {
                String raw = parent[0];
                String firstArg = parent[1];
                if (firstArg != null
                        && (raw.equals(REPOSITORY) || index.supertypes(raw).contains(REPOSITORY))) {
                    return Optional.of(firstArg);
                }
            }
        }
        return Optional.empty();
    }

    private record Declared(ClassNode owner, MethodNode method) {}

    /** Onde o metodo chamado esta declarado: na propria interface ou na primeira supertype que o tem. */
    private Declared declaration(String owner, String name, String desc) {
        List<String> chain = new ArrayList<>();
        chain.add(owner);
        chain.addAll(index.supertypes(owner));
        for (boolean exact : new boolean[] {true, false}) {
            for (String type : chain) {
                Optional<ClassNode> node = index.find(type);
                if (node.isEmpty()) {
                    continue;
                }
                for (MethodNode m : node.get().methods) {
                    if (m.name.equals(name) && (!exact || m.desc.equals(desc))) {
                        return new Declared(node.get(), m);
                    }
                }
            }
        }
        return null;
    }

    private static String annotatedOperation(MethodNode method) {
        if (method.visibleAnnotations == null) {
            return null;
        }
        for (AnnotationNode a : method.visibleAnnotations) {
            if (WRITE_ANNOTATIONS.contains(a.desc)) {
                return "write";
            }
            if (a.desc.equals(MONGO_QUERY) && Boolean.TRUE.equals(rawValue(a, "delete"))) {
                return "write";
            }
        }
        return null;
    }

    static String operationByName(String name) {
        for (String prefix : WRITE_PREFIXES) {
            if (startsWithWord(name, prefix)) {
                return "write";
            }
        }
        for (String prefix : READ_PREFIXES) {
            if (startsWithWord(name, prefix)) {
                return "read";
            }
        }
        return null;
    }

    private static boolean startsWithWord(String name, String prefix) {
        return name.startsWith(prefix)
                && (name.length() == prefix.length() || !Character.isLowerCase(name.charAt(prefix.length())));
    }

    // ---- valores na pilha

    /** A constante que produziu o valor (LDC de String ou de classe), ou null quando ele vem de outro lugar. */
    private static Object constant(SourceValue value) {
        if (value.insns.size() != 1) {
            return null;
        }
        AbstractInsnNode insn = value.insns.iterator().next();
        return insn instanceof LdcInsnNode ldc ? ldc.cst : null;
    }

    /** O tipo estatico de um valor: de onde ele saiu (new, cast, retorno, campo) ou a variavel local declarada. */
    private static String typeOf(SourceValue value, MethodNode method, AbstractInsnNode at) {
        if (value.insns.size() != 1) {
            return null;
        }
        AbstractInsnNode insn = value.insns.iterator().next();
        if (insn instanceof TypeInsnNode type
                && (type.getOpcode() == Opcodes.NEW || type.getOpcode() == Opcodes.CHECKCAST)) {
            return type.desc;
        }
        if (insn instanceof MethodInsnNode m) {
            return internalName(Type.getReturnType(m.desc));
        }
        if (insn instanceof FieldInsnNode f) {
            return internalName(Type.getType(f.desc));
        }
        if (insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && method.localVariables != null) {
            int position = method.instructions.indexOf(at);
            for (LocalVariableNode local : method.localVariables) {
                if (local.index == v.var
                        && method.instructions.indexOf(local.start) <= position
                        && position <= method.instructions.indexOf(local.end)) {
                    return internalName(Type.getType(local.desc));
                }
            }
        }
        return null;
    }

    private static String internalName(Type type) {
        return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
    }

    // ---- evidencia

    private record Site(ClassNode owner, MethodNode method, MethodInsnNode call) {
        int line() {
            for (AbstractInsnNode n = call; n != null; n = n.getPrevious()) {
                if (n instanceof LineNumberNode l) {
                    return l.line;
                }
            }
            return 0;
        }
    }

    private Map<String, Object> problemAt(Site site, String reason) {
        return problem(
                reason,
                dotted(site.call().owner) + "#" + site.call().name,
                dotted(site.owner().name) + "#" + site.method().name,
                sourcePath(site.owner()),
                site.line());
    }

    private static Map<String, Object> problem(String reason, String via, String caller, String source, int line) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reason", reason);
        p.put("via", via);
        if (caller != null) {
            p.put("caller", caller);
        }
        p.put("source", source);
        p.put("line", line);
        return p;
    }

    /** Caminho do arquivo-fonte relativo ao projeto, achado nos diretorios de codigo pelo atributo SourceFile. */
    private String sourcePath(ClassNode node) {
        String pkg = node.name.contains("/") ? node.name.substring(0, node.name.lastIndexOf('/') + 1) : "";
        String file = node.sourceFile != null ? node.sourceFile : simpleName(node.name).replaceAll("\\$.*", "") + ".java";
        for (File dir : sourceDirs) {
            File candidate = new File(dir, pkg + file);
            if (candidate.isFile()) {
                return projectDir.toPath().relativize(candidate.toPath()).toString().replace(File.separatorChar, '/');
            }
        }
        return "src/main/java/" + pkg + file;
    }

    // ---- utilitarios

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) {
            return null;
        }
        return annotations.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    private static Object rawValue(AnnotationNode annotation, String name) {
        if (annotation.values == null) {
            return null;
        }
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static String value(AnnotationNode annotation, String name) {
        Object v = rawValue(annotation, name);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }

    static String simpleName(String internalName) {
        String name = internalName.substring(internalName.lastIndexOf('/') + 1);
        return name.substring(name.lastIndexOf('$') + 1);
    }

    static String uncapitalize(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Nome no padrao do Spring Boot para tabelas: {@code ProductCode} vira {@code product_code}. */
    static String snakeCase(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }
}
