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
}
