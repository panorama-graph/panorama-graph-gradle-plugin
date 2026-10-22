package panoramagraph.gradle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;

/**
 * Quem é o componente: o artefato do build, o repositório e o commit. O repositório e o commit vêm das variáveis do
 * CI (GitLab, GitHub) e, fora dele, do git do projeto.
 */
final class Identity {

    private Identity() {}

    static Provider<Map<String, String>> of(Project project) {
        ProviderFactory providers = project.getProviders();
        Provider<String> repository = providers.environmentVariable("CI_PROJECT_URL")
                .orElse(providers.environmentVariable("GITHUB_SERVER_URL")
                        .zip(providers.environmentVariable("GITHUB_REPOSITORY"), (server, repo) -> server + "/" + repo))
                .orElse(git(project, "remote", "get-url", "origin").map(Identity::withoutCredentials));
        Provider<String> commit = providers.environmentVariable("CI_COMMIT_SHA")
                .orElse(providers.environmentVariable("GITHUB_SHA"))
                .orElse(git(project, "rev-parse", "HEAD"));
        String group = project.getGroup().toString();
        String name = project.getName();
        String version = project.getVersion().toString();
        return repository.map(r -> r).orElse("").zip(commit.orElse(""), (r, c) -> {
            Map<String, String> identity = new LinkedHashMap<>();
            put(identity, "group", group);
            put(identity, "name", name);
            put(identity, "version", "unspecified".equals(version) ? "" : version);
            put(identity, "repository", r);
            put(identity, "commit", c);
            return identity;
        });
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /** URL sem usuário e senha: no CI, o remoto costuma levar o token do job (https://ci-token:TOKEN@host/...). */
    static String withoutCredentials(String url) {
        return url.replaceFirst("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]*:[^/@]*@", "$1");
    }

    /** Saída de um comando git no diretório do projeto; sem valor fora de um repositório ou sem git. */
    private static Provider<String> git(Project project, String... args) {
        return project.getProviders().provider(() -> {
            try {
                var result = project.getProviders().exec(spec -> {
                    spec.commandLine(concat("git", args));
                    spec.workingDir(project.getProjectDir());
                    spec.setIgnoreExitValue(true);
                });
                if (result.getResult().get().getExitValue() != 0) {
                    return null;
                }
                String out = result.getStandardOutput().getAsText().get().trim();
                return out.isEmpty() ? null : out;
            } catch (RuntimeException e) {
                return null;
            }
        });
    }

    private static List<String> concat(String first, String... rest) {
        List<String> all = new java.util.ArrayList<>(List.of(first));
        all.addAll(List.of(rest));
        return all;
    }
}
