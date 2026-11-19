package dev.tkachuk.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LayeringTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java", "dev", "tkachuk", "bank");
    private static final String BASE_PACKAGE = "dev.tkachuk.bank.";
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);

    private static final Map<String, Set<String>> MAY_DEPEND_ON = Map.of(
            "common", Set.of(),
            "ledger", Set.of("common"),
            "account", Set.of("common", "ledger"),
            "transfer", Set.of("common", "ledger", "account"),
            "paymentfile", Set.of("common", "transfer"),
            "interbank", Set.of("common", "transfer"));

    private static final List<String> WEB_PACKAGES = List.of("org.springframework.web", "org.springframework.http",
            "jakarta.servlet");

    private static final List<String> MESSAGING_PACKAGES = List.of("org.apache.kafka", "org.springframework.kafka",
            "org.springframework.modulith");

    private static List<SourceFile> sources;

    @BeforeAll
    static void readSources() throws IOException {
        assertThat(MAIN_SOURCES).as("LayeringTest reads the sources: run the tests from the project root")
                .isDirectory();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).map(SourceFile::read).toList();
        }
        assertThat(sources).as("Java sources under " + MAIN_SOURCES).isNotEmpty();
    }

    @Test
    void shouldHaveDependencyRuleForEveryPackage() {
        List<String> withoutRule = sources.stream()
                .map(SourceFile::module)
                .filter(module -> !module.isEmpty() && !MAY_DEPEND_ON.containsKey(module))
                .distinct()
                .toList();

        assertThat(withoutRule).as("new packages need a line in MAY_DEPEND_ON and in ARCHITECTURE.md").isEmpty();
    }

    @Test
    void shouldDependOnlyInAllowedDirection() {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : sources) {
            Set<String> allowed = MAY_DEPEND_ON.getOrDefault(source.module(), Set.of());
            for (String imported : source.imports()) {
                String target = moduleOf(imported);
                if (target == null || target.equals(source.module()) || target.equals("api")) {
                    continue;
                }
                if (!source.module().isEmpty() && !allowed.contains(target)) {
                    violations.add(source.relativePath() + " imports " + imported);
                }
            }
        }

        assertThat(violations).as("imports against the dependency direction").isEmpty();
    }

    @Test
    void shouldKeepGeneratedApiInControllersAndMappers() {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : sources) {
            boolean allowed = source.name().endsWith("Controller") || source.name().endsWith("Mapper");
            for (String imported : source.imports()) {
                if (imported.startsWith(BASE_PACKAGE + "api.") && !allowed) {
                    violations.add(source.relativePath() + " imports " + imported);
                }
            }
        }

        assertThat(violations).as("generated API types outside controllers and mappers").isEmpty();
    }

    @Test
    void shouldKeepHttpInWebLayer() {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : sources) {
            boolean allowed = source.name().endsWith("Controller") || source.relativePath().startsWith("common/web/");
            for (String imported : source.imports()) {
                boolean web = WEB_PACKAGES.stream().anyMatch(prefix -> imported.startsWith(prefix + "."));
                if (web && !allowed) {
                    violations.add(source.relativePath() + " imports " + imported);
                }
            }
        }

        assertThat(violations).as("HTTP and servlet types outside controllers and common/web").isEmpty();
    }

    @Test
    void shouldKeepMessagingInInterbankPackage() {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : sources) {
            for (String imported : source.imports()) {
                boolean messaging = MESSAGING_PACKAGES.stream().anyMatch(prefix -> imported.startsWith(prefix + "."));
                if (messaging && !source.module().equals("interbank")) {
                    violations.add(source.relativePath() + " imports " + imported);
                }
            }
        }

        assertThat(violations).as("Kafka and Spring Modulith types outside the interbank package").isEmpty();
    }

    private static String moduleOf(String imported) {
        if (!imported.startsWith(BASE_PACKAGE)) {
            return null;
        }
        String rest = imported.substring(BASE_PACKAGE.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? "" : rest.substring(0, dot);
    }

    private record SourceFile(String relativePath, String module, String name, List<String> imports) {

        static SourceFile read(Path path) {
            String relativePath = MAIN_SOURCES.relativize(path).toString().replace('\\', '/');
            int slash = relativePath.indexOf('/');
            String module = slash < 0 ? "" : relativePath.substring(0, slash);
            String fileName = path.getFileName().toString();
            String name = fileName.substring(0, fileName.length() - ".java".length());
            try {
                Matcher matcher = IMPORT.matcher(Files.readString(path));
                List<String> imports = new ArrayList<>();
                while (matcher.find()) {
                    imports.add(matcher.group(1));
                }
                return new SourceFile(relativePath, module, name, List.copyOf(imports));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + path, e);
            }
        }
    }
}
