package cc.minevisor.live.bukkit;

import cc.minevisor.live.DiscordAdmin;
import cc.minevisor.live.DiscordConfig;
import cc.minevisor.live.LiveHelp;
import cc.minevisor.live.LiveJson;
import cc.minevisor.live.UpdateChecker;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

final class MinevisorCommand implements CommandExecutor, TabCompleter {

    private final MinevisorLivePlugin plugin;

    MinevisorCommand(MinevisorLivePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "help", "?" -> help(sender);
            case "link" -> link(sender);
            case "revoke" -> revoke(sender);
            case "status" -> status(sender);
            case "discord", "settings", "webhook", "token", "status_channel_id", "status_interval", "banner", "name", "motd",
                 "joins", "deaths", "death_location" -> discord(sender, sub, args);
            default -> {
                error(sender, "Unknown option \"" + args[0] + "\".");
                help(sender);
            }
        }
        return true;
    }

    private static TextComponent text(String s, ChatColor color) {
        TextComponent t = new TextComponent(s);
        t.setColor(color);
        return t;
    }

    private static BaseComponent[] prefixed(BaseComponent... parts) {
        ComponentBuilder b = new ComponentBuilder();
        b.append(text("minevisor", ChatColor.GREEN), ComponentBuilder.FormatRetention.NONE);
        b.append(text(" » ", ChatColor.DARK_GRAY), ComponentBuilder.FormatRetention.NONE);
        for (BaseComponent p : parts) b.append(p, ComponentBuilder.FormatRetention.NONE);
        return b.create();
    }

    private static void send(CommandSender sender, BaseComponent... parts) {
        sender.spigot().sendMessage(prefixed(parts));
    }

    static void ok(CommandSender sender, String message) {
        send(sender, text("✔ ", ChatColor.GREEN), text(message, ChatColor.GRAY));
    }

    /** Tells an admin a newer release is out, with a link to it. */
    static void updateNotice(CommandSender sender, UpdateChecker.Update update, String current) {
        TextComponent link = text("[Download]", ChatColor.AQUA);
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, update.url()));
        hover(link, "Open the " + update.version() + " release on GitHub");
        send(sender, text("⬆ ", ChatColor.YELLOW),
                text("Update available: ", ChatColor.GRAY),
                text(update.version(), ChatColor.YELLOW),
                text(" (you have " + current + ") ", ChatColor.DARK_GRAY),
                link);
    }

    static void error(CommandSender sender, String message) {
        send(sender, text("✘ ", ChatColor.RED), text(message, ChatColor.RED));
    }

    private static TextComponent rule() {
        TextComponent t = new TextComponent("                                                    ");
        t.setColor(ChatColor.DARK_GRAY);
        t.setStrikethrough(true);
        return t;
    }

    private static TextComponent hover(TextComponent c, String tooltip) {
        c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(tooltip)));
        return c;
    }

    private void help(CommandSender sender) {
        boolean admin = sender.hasPermission("minevisor.admin");
        sender.spigot().sendMessage(rule());
        TextComponent title = text("minevisor", ChatColor.GREEN);
        title.setBold(true);
        sender.spigot().sendMessage(new ComponentBuilder()
                .append(text(" ", ChatColor.WHITE)).append(title)
                .append(text(" live", ChatColor.WHITE), ComponentBuilder.FormatRetention.NONE)
                .append(text("  v" + LiveHelp.VERSION, ChatColor.DARK_GRAY), ComponentBuilder.FormatRetention.NONE)
                .append(text("  ·  your server in 3D, live", ChatColor.GRAY), ComponentBuilder.FormatRetention.NONE)
                .create());
        section(sender, LiveHelp.SECTION_LIVE, LiveHelp.live());
        if (admin) section(sender, LiveHelp.SECTION_DISCORD + "  (admins)", LiveHelp.discord());
        sender.spigot().sendMessage(text(" Hover a command for details, click to use it.", ChatColor.DARK_GRAY));
        sender.spigot().sendMessage(rule());
    }

    private static void section(CommandSender sender, String name, List<LiveHelp.Entry> entries) {
        TextComponent header = text(" " + name, ChatColor.GOLD);
        header.setBold(true);
        sender.spigot().sendMessage(header);
        for (LiveHelp.Entry e : entries) {
            TextComponent cmd = text("  " + e.command(), ChatColor.AQUA);
            cmd.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, e.suggestion()));
            hover(cmd, e.command() + (e.args().isEmpty() ? "" : " " + e.args()) + "\n\n" + e.detail() + "\n\nClick to use it.");
            ComponentBuilder line = new ComponentBuilder().append(cmd);
            if (!e.args().isEmpty()) line.append(text(" " + e.args(), ChatColor.DARK_AQUA), ComponentBuilder.FormatRetention.NONE);
            line.append(text("  " + e.summary(), ChatColor.GRAY), ComponentBuilder.FormatRetention.NONE);
            sender.spigot().sendMessage(line.create());
        }
    }

    void link(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            sender.sendMessage("minevisor live workspace (only share it with people you trust): " + linkUrl());
            return;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Run this in game or from the console.");
            return;
        }
        if (!player.hasPermission("minevisor.link")) {
            error(player, "You don't have permission (minevisor.link).");
            return;
        }
        var config = plugin.liveConfig();
        String url = linkUrl();

        TextComponent button = text("[ Open the live workspace ]", ChatColor.AQUA);
        button.setBold(true);
        button.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        hover(button, "Opens your server in 3D in the browser.\nView only: nothing can be changed from there.");
        send(player, button);
        player.spigot().sendMessage(text("   The server's link · same every time · only share it with people you trust",
                ChatColor.DARK_GRAY));
    }

    private String linkUrl() {
        var relay = plugin.relay();
        String token = plugin.tokens().token();
        return relay != null && relay.connected()
                ? LiveJson.relayLink(plugin.liveConfig(), relay.serverId(), token)
                : LiveJson.link(plugin.liveConfig(), token);
    }

    private void revoke(CommandSender sender) {
        if (!sender.hasPermission("minevisor.admin")) {
            error(sender, "Only admins can replace the server's link (minevisor.admin).");
            return;
        }
        plugin.tokens().rotate();
        if (plugin.relay() != null) plugin.relay().reauth();
        plugin.getLogger().info(sender.getName() + " replaced the live link");
        ok(sender, "New link made. The old one stopped working for everyone; get the new one with /minevisor link.");
    }

    private void status(CommandSender sender) {
        String url = plugin.liveConfig().publicUrl();
        TextComponent address = text(url, ChatColor.AQUA);
        address.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        int watching = plugin.watchers();
        send(sender, text("Viewers connect to ", ChatColor.GRAY), address,
                text(" · " + (watching == 0 ? "nobody watching" : watching + " watching now"), ChatColor.GRAY));
    }

    private void discord(CommandSender sender, String sub, String[] args) {
        if (!sender.hasPermission("minevisor.admin")) {
            error(sender, "Discord settings need minevisor.admin (ops have it).");
            return;
        }
        DiscordConfig current = plugin.discordConfig();
        if (sub.equals("discord")) {
            showDiscord(sender, current);
            return;
        }
        if (sub.equals("settings")) {
            if (sender instanceof Player player) SettingsGui.open(plugin, player);
            else error(sender, "The settings menu is in game; use /minevisor <setting> <value> here.");
            return;
        }
        if (args.length < 2) {
            LiveHelp.Entry entry = LiveHelp.discord().stream().filter(e -> e.command().endsWith(" " + sub)).findFirst().orElseThrow();
            TextComponent usage = text(entry.command() + " " + entry.args(), ChatColor.AQUA);
            usage.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, entry.suggestion()));
            send(sender, text("Usage: ", ChatColor.GRAY), usage);
            sender.spigot().sendMessage(text("   " + entry.detail().replace("\n", " "), ChatColor.DARK_GRAY));
            return;
        }
        String value = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        change(plugin, sender, sub, value);
    }

    static void change(MinevisorLivePlugin plugin, CommandSender sender, String sub, String value) {
        DiscordAdmin.Change change = DiscordAdmin.apply(plugin.discordConfig(), sub, value);
        if (change.error() != null) {
            error(sender, change.error());
            return;
        }
        DiscordConfig next = change.next();
        plugin.reconfigureDiscord(next);
        plugin.getLogger().info(sender.getName() + " changed the Discord " + sub.replace('_', ' '));
        ok(sender, change.message());
        if (DiscordAdmin.isOff(value.trim())) return;
        CompletableFuture<DiscordAdmin.Check> check = switch (sub) {
            case "webhook" -> DiscordAdmin.testWebhook(next);
            case "token" -> DiscordAdmin.testToken(next);
            case "status_channel_id" -> CompletableFuture.completedFuture(next.botEnabled()
                    ? new DiscordAdmin.Check(true, "The status message will show up in that channel in a few seconds.")
                    : new DiscordAdmin.Check(false, "Set a bot token too: the bot posts the status message."));
            default -> null;
        };
        if (check == null) return;
        check.thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (result.ok()) ok(sender, result.message());
            else error(sender, result.message());
        }));
    }

    private void showDiscord(CommandSender sender, DiscordConfig c) {
        sender.spigot().sendMessage(rule());
        TextComponent title = text(" Discord", ChatColor.GOLD);
        title.setBold(true);
        sender.spigot().sendMessage(title);
        List<LiveHelp.Entry> entries = LiveHelp.discord().subList(1, 6);
        List<LiveHelp.Setting> settings = LiveHelp.discordSettings(c);
        for (int i = 0; i < settings.size(); i++) {
            LiveHelp.Setting s = settings.get(i);
            LiveHelp.Entry entry = entries.get(i);
            TextComponent name = text(String.format("%-15s", s.name()), ChatColor.WHITE);
            name.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, entry.suggestion()));
            hover(name, entry.detail() + "\n\nClick to change it.");
            sender.spigot().sendMessage(new ComponentBuilder()
                    .append(text(s.set() ? "  ✔ " : "  ✘ ", s.set() ? ChatColor.GREEN : ChatColor.RED))
                    .append(name, ComponentBuilder.FormatRetention.NONE)
                    .append(text(s.value(), s.set() ? ChatColor.GRAY : ChatColor.DARK_GRAY), ComponentBuilder.FormatRetention.NONE)
                    .create());
        }
        sender.spigot().sendMessage(text(" Click a setting to change it.", ChatColor.DARK_GRAY));
        sender.spigot().sendMessage(rule());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 2 && sender.hasPermission("minevisor.admin")) {
            String key = args[0].toLowerCase(Locale.ROOT);
            List<String> options = DiscordAdmin.isToggle(key) ? List.of("on", "off")
                    : key.equals("banner") ? List.of("auto", "off")
                    : key.equals("status_interval") ? List.of("5", "10", "30", "60")
                    : DiscordAdmin.SETTINGS.contains(key) ? List.of("off") : List.of();
            String typed = args[1].toLowerCase(Locale.ROOT);
            return options.stream().filter(o -> o.startsWith(typed)).toList();
        }
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> subs = sender.hasPermission("minevisor.admin")
                ? List.of("help", "link", "revoke", "status", "settings", "discord", "webhook", "token", "status_channel_id",
                        "status_interval", "banner", "name", "motd", "joins", "deaths", "death_location")
                : List.of("help", "link", "revoke", "status");
        return subs.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
