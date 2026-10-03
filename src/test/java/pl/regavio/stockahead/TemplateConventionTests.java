package pl.regavio.stockahead;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shared-template convention: every view pulls its {@code <head>}
 * from {@code fragments :: head(...)}, authenticated views also render
 * {@code fragments :: topbar}, and no template carries inline styling — all
 * styling lives in {@code static/css/app.css}.
 */
class TemplateConventionTests {

	private static final Path TEMPLATES_DIR = Path.of("src/main/resources/templates");

	private static final Set<String> UNAUTHENTICATED_VIEWS = Set.of("login.html", "setup.html");

	private static List<Path> templates() throws IOException {
		try (Stream<Path> files = Files.list(TEMPLATES_DIR)) {
			return files
				.filter(path -> path.getFileName().toString().endsWith(".html"))
				.filter(path -> !path.getFileName().toString().equals("fragments.html"))
				.sorted()
				.toList();
		}
	}

	@Test
	void templatesFollowSharedLayoutConvention() throws IOException {
		List<Path> templates = templates();
		assertThat(templates).as("templates found in %s", TEMPLATES_DIR.toAbsolutePath()).isNotEmpty();

		List<String> violations = new ArrayList<>();
		for (Path template : templates) {
			String name = template.getFileName().toString();
			String content = Files.readString(template, StandardCharsets.UTF_8);
			if (!content.contains("fragments :: head(")) {
				violations.add(name + ": missing `fragments :: head(`");
			}
			if (!UNAUTHENTICATED_VIEWS.contains(name) && !content.contains("fragments :: topbar")) {
				violations.add(name + ": missing `fragments :: topbar`");
			}
			if (content.contains("<style")) {
				violations.add(name + ": contains inline `<style` block");
			}
			if (content.contains("style=\"")) {
				violations.add(name + ": contains inline `style=\"` attribute");
			}
		}

		assertThat(violations).as("template convention violations").isEmpty();
	}

}
