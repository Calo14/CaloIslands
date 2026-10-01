package me.calo.islands;

import me.calo.islands.core.Messages;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

final class MessagesTest {
    @Test
    void bundledMessagesContainRequiredKeysAndRenderValues() {
        Messages messages = new Messages(new File("src/main/resources/messages.yml"));
        String rendered = messages.text("region-created", "id", "test_region");
        assertTrue(rendered.contains("test_region"));
        assertFalse(rendered.contains("{id}"));
    }

    @Test
    void oldCustomizedMessagesKeepOverridesAndGainNewWandDefaults() throws Exception {
        File old = Files.createTempFile("calo-messages", ".yml").toFile();
        try {
            Files.writeString(old.toPath(), "region-created: '&bRegistro propio {id}'\n");
            Messages messages = new Messages(old);
            assertTrue(messages.text("region-created", "id", "test_region").contains("Registro propio"));
            assertFalse(messages.lines("wand-lore").isEmpty());
            assertTrue(messages.text("wand-name").contains("Selector"));
        } finally {
            Files.deleteIfExists(old.toPath());
        }
    }

    @Test
    void auditReportsMissingUnknownAndBrokenPlaceholdersTogether() throws Exception {
        File custom = Files.createTempFile("calo-translations", ".yml").toFile();
        try {
            Files.writeString(custom.toPath(), "region-created: '&a{other}'\nunknown-entry: 'extra'\n");
            var issues = Messages.audit(custom);
            assertTrue(issues.stream().anyMatch(line -> line.contains("region-created")
                    && line.contains("placeholders")));
            assertTrue(issues.stream().anyMatch(line -> line.contains("unknown-entry")
                    && line.contains("desconocida")));
            assertTrue(issues.stream().anyMatch(line -> line.contains("no-permission")
                    && line.contains("falta")));
        } finally {
            Files.deleteIfExists(custom.toPath());
        }
    }

    @Test
    void auditChecksListPlaceholdersAndMalformedBraces() throws Exception {
        File custom = Files.createTempFile("calo-translation-list", ".yml").toFile();
        try {
            Files.writeString(custom.toPath(), Files.readString(new File("src/main/resources/messages.yml").toPath())
                    .replace("region-created: '&aRegión registrada: {id}'", "region-created: '&aRegión registrada: {id'")
                    .replace("  - '&7Izquierdo: Posición 1'", "  - '&7Izquierdo: {missing}'"));
            var issues = Messages.audit(custom);
            assertTrue(issues.stream().anyMatch(line -> line.contains("region-created") && line.contains("placeholders")));
            assertTrue(issues.stream().anyMatch(line -> line.contains("wand-lore") && line.contains("placeholders")));
        } finally {
            Files.deleteIfExists(custom.toPath());
        }
    }
}
