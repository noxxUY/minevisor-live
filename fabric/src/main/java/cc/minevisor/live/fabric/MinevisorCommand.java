package cc.minevisor.live.fabric;

import cc.minevisor.live.DiscordAdmin;
import cc.minevisor.live.DiscordConfig;
import cc.minevisor.live.LiveHelp;
import cc.minevisor.live.LiveJson;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class MinevisorCommand {

    private MinevisorCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, MinevisorLiveMod mod) {
        dispatcher.register(Commands.literal("minevisor")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(c -> help(c.getSource()))
                .then(Commands.literal("help").executes(c -> help(c.getSource())))
                .then(Commands.literal("link").executes(c -> link(c.getSource(), mod)))
                .then(Commands.literal("revoke").executes(c -> revoke(c.getSource(), mod)))
                .then(Commands.literal("status").executes(c -> status(c.getSource(), mod)))
                .then(Commands.literal("discord")
                        .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .executes(c -> showDiscord(c.getSource(), mod.discordConfig())))
                .then(Commands.literal("settings")
                        .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayer();
                            if (p == null) {
                                error(c.getSource(), "The settings menu is in game; use /minevisor <setting> <value> here.");
                                return 0;
                            }
                            SettingsGui.open(p, mod);
                            return 1;
                        })));
        for (String setting : DiscordAdmin.SETTINGS) {
            dispatcher.register(Commands.literal("minevisor")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(discordSetting(setting, mod)));
        }
    }

    private static MutableComponent text(String s, ChatFormatting... style) {
        return Component.literal(s).withStyle(style);
    }

    private static Component prefixed(Component... parts) {
        MutableComponent m = text("minevisor", ChatFormatting.GREEN).append(text(" » ", ChatFormatting.DARK_GRAY));
        for (Component p : parts) m.append(p);
        return m;
    }

    private static void say(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, false);
    }

    static void ok(CommandSourceStack source, String message) {
        say(source, prefixed(text("✔ ", ChatFormatting.GREEN), text(message, ChatFormatting.GRAY)));
    }

    static void error(CommandSourceStack source, String message) {
        source.sendFailure(prefixed(text("✘ ", ChatFormatting.RED), text(message, ChatFormatting.RED)));
    }

    private static Component rule() {
        return Component.literal("                                                    ")
                .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withStrikethrough(true));
    }

    private static MutableComponent suggest(MutableComponent c, String command, String tooltip) {
        return c.withStyle(s -> s.withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(tooltip))));
    }

    private static boolean isAdmin(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    private static int help(CommandSourceStack source) {
        say(source, rule());
        say(source, text(" ").append(text("minevisor", ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(text(" live", ChatFormatting.WHITE))
                .append(text("  v" + LiveHelp.VERSION, ChatFormatting.DARK_GRAY))
                .append(text("  ·  your server in 3D, live", ChatFormatting.GRAY)));
        section(source, LiveHelp.SECTION_LIVE, LiveHelp.live());
        if (isAdmin(source)) section(source, LiveHelp.SECTION_DISCORD + "  (admins)", LiveHelp.discord());
        say(source, text(" Hover a command for details, click to use it.", ChatFormatting.DARK_GRAY));
        say(source, rule());
        return 1;
    }

    private static void section(CommandSourceStack source, String name, List<LiveHelp.Entry> entries) {
        say(source, text(" " + name, ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (LiveHelp.Entry e : entries) {
            MutableComponent line = suggest(text("  " + e.command(), ChatFormatting.AQUA), e.suggestion(),
                    e.command() + (e.args().isEmpty() ? "" : " " + e.args()) + "\n\n" + e.detail() + "\n\nClick to use it.");
            if (!e.args().isEmpty()) line = text("").append(line).append(text(" " + e.args(), ChatFormatting.DARK_AQUA));
            say(source, text("").append(line).append(text("  " + e.summary(), ChatFormatting.GRAY)));
        }
    }

    static int link(CommandSourceStack source, MinevisorLiveMod mod) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            if (source.getEntity() != null) {
                error(source, "Run this in game or from the console.");
                return 0;
            }
            source.sendSuccess(() -> Component.literal("minevisor live workspace (only share it with people you trust): "
                    + linkUrl(mod)), false);
            return 1;
        }
        String url = linkUrl(mod);

        Component button = text("[ Open the live workspace ]", ChatFormatting.AQUA, ChatFormatting.BOLD)
                .withStyle(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                "Opens your server in 3D in the browser.\nView only: nothing can be changed from there."))));
        player.sendSystemMessage(prefixed(button));
        player.sendSystemMessage(text("   The server's link · same every time · only share it with people you trust",
                ChatFormatting.DARK_GRAY));
        return 1;
    }

    private static String linkUrl(MinevisorLiveMod mod) {
        var relay = mod.relay();
        String token = mod.tokens().token();
        return relay != null && relay.connected()
                ? LiveJson.relayLink(mod.config(), relay.serverId(), token)
                : LiveJson.link(mod.config(), token);
    }

    private static int revoke(CommandSourceStack source, MinevisorLiveMod mod) {
        if (!isAdmin(source)) {
            error(source, "Only admins can replace the server's link.");
            return 0;
        }
        mod.tokens().rotate();
        if (mod.relay() != null) mod.relay().reauth();
        MinevisorLiveMod.LOG.info("{} replaced the live link", source.getTextName());
        ok(source, "New link made. The old one stopped working for everyone; get the new one with /minevisor link.");
        return 1;
    }

    private static int status(CommandSourceStack source, MinevisorLiveMod mod) {
        String url = mod.config().publicUrl();
        int watching = mod.watchers();
        say(source, prefixed(text("Viewers connect to ", ChatFormatting.GRAY),
                text(url, ChatFormatting.AQUA).withStyle(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))),
                text(" · " + (watching == 0 ? "nobody watching" : watching + " watching now"), ChatFormatting.GRAY)));
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> discordSetting(String sub, MinevisorLiveMod mod) {
        return Commands.literal(sub)
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .executes(c -> usage(c.getSource(), sub))
                .then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(c -> setDiscord(c.getSource(), mod, sub, StringArgumentType.getString(c, "value").trim())));
    }

    private static int usage(CommandSourceStack source, String sub) {
        LiveHelp.Entry entry = LiveHelp.discord().stream().filter(e -> e.command().endsWith(" " + sub)).findFirst()
                .orElse(new LiveHelp.Entry("/minevisor " + sub, "<on|off>", DiscordAdmin.label(sub), DiscordAdmin.label(sub) + ": on or off."));
        say(source, prefixed(text("Usage: ", ChatFormatting.GRAY),
                suggest(text(entry.command() + " " + entry.args(), ChatFormatting.AQUA), entry.suggestion(), "Click to use it.")));
        say(source, text("   " + entry.detail().replace("\n", " "), ChatFormatting.DARK_GRAY));
        return 1;
    }

    private static int showDiscord(CommandSourceStack source, DiscordConfig c) {
        say(source, rule());
        say(source, text(" Discord", ChatFormatting.GOLD, ChatFormatting.BOLD));
        List<LiveHelp.Entry> entries = LiveHelp.discord().subList(1, 6);
        List<LiveHelp.Setting> settings = LiveHelp.discordSettings(c);
        for (int i = 0; i < settings.size(); i++) {
            LiveHelp.Setting s = settings.get(i);
            LiveHelp.Entry entry = entries.get(i);
            say(source, text(s.set() ? "  ✔ " : "  ✘ ", s.set() ? ChatFormatting.GREEN : ChatFormatting.RED)
                    .append(suggest(text(String.format("%-15s", s.name()), ChatFormatting.WHITE), entry.suggestion(),
                            entry.detail() + "\n\nClick to change it."))
                    .append(text(s.value(), s.set() ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY)));
        }
        say(source, text(" Click a setting to change it.", ChatFormatting.DARK_GRAY));
        say(source, rule());
        return 1;
    }

    private static int setDiscord(CommandSourceStack source, MinevisorLiveMod mod, String sub, String value) {
        return change(source, mod, sub, value) ? 1 : 0;
    }

    static boolean change(CommandSourceStack source, MinevisorLiveMod mod, String sub, String value) {
        DiscordAdmin.Change change = DiscordAdmin.apply(mod.discordConfig(), sub, value);
        if (change.error() != null) {
            error(source, change.error());
            return false;
        }
        DiscordConfig next = change.next();
        mod.reconfigureDiscord(next);
        MinevisorLiveMod.LOG.info("{} changed the Discord {}", source.getTextName(), sub.replace('_', ' '));
        ok(source, change.message());
        if (DiscordAdmin.isOff(value.trim())) return true;
        CompletableFuture<DiscordAdmin.Check> check = switch (sub) {
            case "webhook" -> DiscordAdmin.testWebhook(next);
            case "token" -> DiscordAdmin.testToken(next);
            case "status_channel_id" -> CompletableFuture.completedFuture(next.botEnabled()
                    ? new DiscordAdmin.Check(true, "The status message will show up in that channel in a few seconds.")
                    : new DiscordAdmin.Check(false, "Set a bot token too: the bot posts the status message."));
            default -> null;
        };
        if (check == null) return true;
        var server = mod.server();
        check.thenAccept(result -> {
            if (server == null) return;
            server.execute(() -> {
                if (result.ok()) ok(source, result.message());
                else error(source, result.message());
            });
        });
        return true;
    }
}
