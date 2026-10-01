package me.calo.islands.command;

import me.calo.islands.content.PointDefenseService;
import me.calo.islands.content.ObjectiveActivityService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Player entry point for an optional configured defense activity. */
public final class PointDefenseCommand implements CommandExecutor {
    private final PointDefenseService defense;
    private final ObjectiveActivityService objectives;
    private final Logger logger;

    public PointDefenseCommand(PointDefenseService defense, Logger logger) {
        this(defense, null, logger);
    }
    public PointDefenseCommand(PointDefenseService defense, ObjectiveActivityService objectives, Logger logger) {
        this.defense = defense;
        this.objectives = objectives;
        this.logger = Objects.requireNonNull(logger);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando requiere un jugador conectado.");
            return true;
        }
        if (!player.hasPermission("caloislands.activity")) {
            player.sendMessage("§cNo tienes permiso para esta actividad.");
            return true;
        }
        if (defense == null && objectives == null) {
            player.sendMessage("§eLa actividad de defensa no está configurada.");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            usage(player);
            return true;
        }
        try {
            switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
                case "activate" -> {
                    if (args.length == 2 && objectives != null) {
                        var run = objectives.activate(player, args[1]);
                        player.sendMessage("§aActividad iniciada: " + objectives.definitions().stream()
                                .filter(d -> d.id().equals(args[1])).findFirst().orElseThrow().name());
                    } else if (defense != null && args.length == 1) {
                        if (!defense.activate(player)) player.sendMessage("§cAcércate al punto de defensa activo.");
                    } else usage(player);
                }
                case "list" -> {
                    if (defense != null) player.sendMessage("§eDefensa de punto §f"
                            + (defense.current(player.getUniqueId()).isPresent() ? "En curso" : "Disponible"));
                    if (objectives == null || objectives.definitions().isEmpty()) {
                        if (defense != null) break;
                        player.sendMessage("§eNo hay actividades configuradas.");
                    } else for (var objective : objectives.definitions()) {
                        var run = objectives.current(objective.id());
                        player.sendMessage("§e" + objective.name() + " §7(" + objective.id() + ") §f"
                                + (run.isPresent() ? "En curso" : "Disponible"));
                    }
                }
                case "join" -> {
                    if (args.length != 2 || objectives == null) { usage(player); break; }
                    objectives.join(player, args[1]);
                    player.sendMessage("§aTe uniste a la actividad.");
                }
                case "leave" -> player.sendMessage(objectives != null && objectives.leave(player)
                        ? "§eSaliste de la actividad." : "§eNo participas en una actividad.");
                case "status" -> {
                    if (args.length == 2 && objectives != null) {
                        var objective = objectives.definitions().stream()
                                .filter(d -> d.id().equals(args[1])).findFirst().orElseThrow();
                        var run = objectives.current(args[1]);
                        if (run.isEmpty()) { player.sendMessage("§e" + objective.name() + ": disponible."); break; }
                        var members = objectives.members(run.get().runId());
                        player.sendMessage("§e" + objective.name() + " §7" + run.get().progress()
                                + "/" + objective.goal() + " · " + members.stream().filter(m -> m.active()).count()
                                + " participantes");
                        for (var member : members) player.sendMessage("§7" +
                                (org.bukkit.Bukkit.getOfflinePlayer(member.playerId()).getName() == null
                                        ? "Participante" : org.bukkit.Bukkit.getOfflinePlayer(member.playerId()).getName())
                                + ": " + member.contribution());
                    } else if (defense != null) player.sendMessage(defense.current(player.getUniqueId())
                            .map(run -> "§eDefensa " + run.state() + ": " + run.progress() + " s")
                            .orElse("§eNo tienes una defensa activa."));
                    else usage(player);
                }
                case "cancel" -> {
                    if (args.length == 2 && objectives != null) player.sendMessage(objectives.cancel(player, args[1])
                            ? "§eActividad cancelada." : "§eNo hay una actividad en curso.");
                    else if (defense != null) player.sendMessage(defense.cancel(player)
                            ? "§eDefensa cancelada." : "§eNo tienes una defensa activa.");
                    else usage(player);
                }
                default -> usage(player);
            }
        } catch (SQLException failure) {
            logger.log(Level.WARNING, "Defense command storage result unconfirmed", failure);
            player.sendMessage("§cMariaDB no confirmó la operación; vuelve a consultar el estado.");
        } catch (IllegalArgumentException | IllegalStateException failure) {
            player.sendMessage("§cNo se pudo iniciar la actividad: " + failure.getMessage());
        }
        return true;
    }
    private static void usage(Player player) {
        player.sendMessage("§eUso: /caloactivity <list|activate [id]|join id|status [id]|leave|cancel [id]>");
    }
}
