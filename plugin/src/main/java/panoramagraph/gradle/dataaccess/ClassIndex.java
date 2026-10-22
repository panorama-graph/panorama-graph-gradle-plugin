package panoramagraph.gradle.dataaccess;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * As classes do projeto (lidas inteiras, com o codigo) e, sob demanda, as do classpath (so o cabecalho: hierarquia e
 * anotacoes). O classpath so e consultado para resolver a hierarquia dos repositorios e o mapeamento de entidades que
 * vem de bibliotecas.
 */
final class ClassIndex implements AutoCloseable {

    private final Map<String, ClassNode> projectClasses = new LinkedHashMap<>();
    private final List<File> classpath;
    private final Map<String, Optional<ClassNode>> libraryClasses = new HashMap<>();
    private Map<String, Location> libraryIndex;
    private final Map<File, ZipFile> openJars = new HashMap<>();

    private record Location(File root, boolean jar) {}

    ClassIndex(List<File> classDirs, List<File> classpath) {
        this.classpath = classpath;
        for (File dir : classDirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir.toPath())) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                    try (InputStream in = Files.newInputStream(file)) {
                        ClassNode node = new ClassNode();
                        new ClassReader(in).accept(node, 0);
                        projectClasses.putIfAbsent(node.name, node);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Classes do projeto, em ordem estavel. */
    Iterable<ClassNode> projectClasses() {
        return projectClasses.values().stream()
                .sorted((a, b) -> a.name.compareTo(b.name))
                .toList();
    }

    boolean isProjectClass(String internalName) {
        return projectClasses.containsKey(internalName);
    }

    /** A classe pelo nome interno ({@code com/example/Foo}), do projeto ou do classpath. */
    Optional<ClassNode> find(String internalName) {
        ClassNode own = projectClasses.get(internalName);
        if (own != null) {
            return Optional.of(own);
        }
        return libraryClasses.computeIfAbsent(internalName, this::loadFromClasspath);
    }

    private Optional<ClassNode> loadFromClasspath(String internalName) {
        String entry = internalName + ".class";
        Location location = libraryIndex().get(entry);
        if (location == null) {
            return Optional.empty();
        }
        try {
            ClassNode node = new ClassNode();
            if (location.jar()) {
                ZipFile zip = jar(location.root());
                try (InputStream in = zip.getInputStream(zip.getEntry(entry))) {
                    new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                }
            } else {
                try (InputStream in = Files.newInputStream(location.root().toPath().resolve(entry))) {
                    new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                }
            }
            return Optional.of(node);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Nome de cada arquivo .class do classpath para onde ele esta. O primeiro do classpath vence. */
    private Map<String, Location> libraryIndex() {
        if (libraryIndex != null) {
            return libraryIndex;
        }
        libraryIndex = new HashMap<>();
        for (File root : classpath) {
            if (root.isFile() && root.getName().endsWith(".jar")) {
                try {
                    ZipFile zip = jar(root);
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.endsWith(".class")) {
                            libraryIndex.putIfAbsent(name, new Location(root, true));
                        }
                    }
                } catch (IOException ignored) {
                    // jar ilegivel: as classes dele ficam desconhecidas
                }
            } else if (root.isDirectory()) {
                Path base = root.toPath();
                try (Stream<Path> files = Files.walk(base)) {
                    files.filter(p -> p.toString().endsWith(".class"))
                            .forEach(p -> libraryIndex.putIfAbsent(
                                    base.relativize(p).toString().replace(File.separatorChar, '/'),
                                    new Location(root, false)));
                } catch (IOException ignored) {
                    // diretorio ilegivel
                }
            }
        }
        return libraryIndex;
    }

    private ZipFile jar(File file) throws IOException {
        ZipFile zip = openJars.get(file);
        if (zip == null) {
            zip = new ZipFile(file);
            openJars.put(file, zip);
        }
        return zip;
    }

    /** Todas as interfaces e superclasses, em largura, sem repetir. */
    List<String> supertypes(String internalName) {
        List<String> out = new ArrayList<>();
        List<String> queue = new ArrayList<>(List.of(internalName));
        for (int i = 0; i < queue.size(); i++) {
            Optional<ClassNode> node = find(queue.get(i));
            if (node.isEmpty()) {
                continue;
            }
            List<String> parents = new ArrayList<>(node.get().interfaces);
            if (node.get().superName != null) {
                parents.add(node.get().superName);
            }
            for (String parent : parents) {
                if (!queue.contains(parent)) {
                    queue.add(parent);
                    out.add(parent);
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        for (ZipFile zip : openJars.values()) {
            try {
                zip.close();
            } catch (IOException ignored) {
                // nada a fazer
            }
        }
        openJars.clear();
    }
}
