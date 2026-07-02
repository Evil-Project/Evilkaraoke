package org.evilproject.evilkaraoke.paper.command;

import java.util.List;
import java.util.Locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.evilproject.evilkaraoke.paper.permission.PermissionService;
import org.evilproject.evilkaraoke.paper.stats.SongStats;
import org.evilproject.evilkaraoke.paper.stats.StatsService;
import org.evilproject.evilkaraoke.paper.stats.UserStats;

public final class StatsCommand {
    private final StatsService statsService;
    private final PermissionService permissionService;

    public StatsCommand(StatsService statsService, PermissionService permissionService) {
        this.statsService = statsService;
        this.permissionService = permissionService;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("evilkaraoke.command.stats")) {
            sender.sendMessage(Component.text("You do not have permission to view Evilkaraoke stats.", NamedTextColor.RED));
            return true;
        }
        String scope = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "me";
        return switch (scope) {
            case "me" -> statsMe(sender);
            case "user" -> statsUser(sender, args);
            case "server" -> statsServer(sender);
            case "top" -> statsTop(sender, args);
            default -> {
                sender.sendMessage(Component.text("Usage: /ek stats <me|user|server|top>", NamedTextColor.YELLOW));
                yield true;
            }
        };
    }

    private boolean statsMe(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players have personal Evilkaraoke stats.", NamedTextColor.RED));
            return true;
        }
        UserStats stats = statsService.user(player.getUniqueId(), player.getName());
        sender.sendMessage(Component.text("Your Evilkaraoke stats", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Listen time: " + stats.listenSeconds() + "s", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Songs listened: " + stats.songsListened(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Songs requested: " + stats.songsRequested(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Permission group: " + permissionService.getGroup(player), NamedTextColor.GRAY));
        return true;
    }

    private boolean statsUser(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /ek stats user <player>", NamedTextColor.YELLOW));
            return true;
        }
        var target = org.bukkit.Bukkit.getOfflinePlayerIfCached(args[2]);
        if (target == null) {
            sender.sendMessage(Component.text("Unknown player: " + args[2], NamedTextColor.RED));
            return true;
        }
        UserStats stats = statsService.user(target.getUniqueId(), args[2]);
        sender.sendMessage(Component.text("Evilkaraoke stats for " + args[2], NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Listen time: " + stats.listenSeconds() + "s", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Songs listened: " + stats.songsListened(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Songs requested: " + stats.songsRequested(), NamedTextColor.GRAY));
        return true;
    }

    private boolean statsServer(CommandSender sender) {
        StatsService.ServerStats stats = statsService.serverStats();
        sender.sendMessage(Component.text("Evilkaraoke server stats", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Total listen time: " + stats.totalListenSeconds() + "s", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Songs played: " + stats.totalSongsPlayed(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Songs requested: " + stats.totalSongsRequested(), NamedTextColor.GRAY));
        return true;
    }

    private boolean statsTop(CommandSender sender, String[] args) {
        String category = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "users";
        int limit = parseLimit(args, 4, 5);
        if ("songs".equals(category)) {
            String by = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "played";
            List<SongStats> top = by.equals("requested") ? statsService.topSongsByRequests(limit) : statsService.topSongsByPlays(limit);
            sender.sendMessage(Component.text("Top songs by " + by, NamedTextColor.GOLD));
            int rank = 1;
            for (SongStats song : top) {
                long value = by.equals("requested") ? song.timesRequested() : song.timesPlayed();
                sender.sendMessage(Component.text(rank++ + ". " + song.title() + " (" + value + ")", NamedTextColor.GRAY));
            }
            return true;
        }
        String by = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "time";
        List<UserStats> top = switch (by) {
            case "song-count" -> statsService.topUsersBySongs(limit);
            case "request-count" -> statsService.topUsersByRequests(limit);
            default -> statsService.topUsersByTime(limit);
        };
        sender.sendMessage(Component.text("Top users by " + by, NamedTextColor.GOLD));
        int rank = 1;
        for (UserStats user : top) {
            long value = switch (by) {
                case "song-count" -> user.songsListened();
                case "request-count" -> user.songsRequested();
                default -> user.listenSeconds();
            };
            sender.sendMessage(Component.text(rank++ + ". " + user.playerName() + " (" + value + ")", NamedTextColor.GRAY));
        }
        return true;
    }

    private int parseLimit(String[] args, int... indices) {
        for (int index : indices) {
            if (args.length > index) {
                try {
                    return Math.min(20, Math.max(1, Integer.parseInt(args[index])));
                } catch (NumberFormatException ignored) {
                    // fall through to default
                }
            }
        }
        return 5;
    }
}
