package me.calo.islands;
import me.calo.islands.command.RegionCommand;
import me.calo.islands.core.*;
import me.calo.islands.domain.*;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
final class AdminMenuCommandTest {
    @Test void menuPreservesAliasPermissionsAndExistingCompletion() {
        var command = new RegionCommand(mock(RegionService.class), new RegionSelectionService(), mock(SelectionTool.class), new Messages(new File("src/main/resources/messages.yml")));
        @SuppressWarnings("unchecked") Consumer<Player> menu = mock(Consumer.class); command.setMenu(menu);
        Player admin = mock(Player.class); Command bukkitCommand = mock(Command.class);
        command.onCommand(admin, bukkitCommand, "calo", new String[]{"menu"}); verifyNoInteractions(menu);
        assertTrue(command.onTabComplete(admin, bukkitCommand, "calo", new String[]{""}).isEmpty());
        when(admin.hasPermission("caloislands.admin")).thenReturn(true);
        command.onCommand(admin, bukkitCommand, "calo", new String[]{"menu"}); verify(menu).accept(admin);
        assertEquals(List.of("region", "city", "here", "help", "menu"), command.onTabComplete(admin, bukkitCommand, "calo", new String[]{""}));
        assertEquals(List.of("exact"), command.onTabComplete(admin, bukkitCommand, "calo", new String[]{"region", "mode", "ex"}));
    }
}
