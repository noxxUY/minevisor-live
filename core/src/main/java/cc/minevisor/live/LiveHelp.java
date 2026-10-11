package cc.minevisor.live;

import java.util.ArrayList;
import java.util.List;

public final class LiveHelp {

    private LiveHelp() {
    }

    public static final String VERSION = "0.1.0";

    public record Entry(String command, String args, String summary, String detail) {
        public String suggestion() {
            return args.isEmpty() ? command : command + " ";
        }
    }

    public static final String SECTION_LIVE = "Live workspace";
    public static final String SECTION_DISCORD = "Discord";

    public static List<Entry> live() {
        return List.of(
                new Entry("/minevisor link", "",
                        "Open the live 3D workspace",
                        "The server's link to watch it in 3D:\nterrain, players and blocks in real time.\nThe same link every time, even after restarts.\nOnly share it with people you trust."),
                new Entry("/minevisor revoke", "",
                        "Make a new link (admins)",
                        "The old link stops working for everyone\nright away. Use it if the link leaked."),
                new Entry("/minevisor status", "",
                        "Where viewers connect, who's watching",
                        "Shows the address the workspace uses\nand how many people are watching now.")
        );
    }

    public static List<Entry> discord() {
        return List.of(
                new Entry("/minevisor discord", "",
                        "Show the Discord setup",
                        "What is set up, with secrets hidden."),
                new Entry("/minevisor webhook", "<url|off>",
                        "Announcements channel",
                        "Paste a webhook URL to post server start/stop,\njoins, leaves and deaths.\nDiscord: Server Settings > Integrations > Webhooks."),
                new Entry("/minevisor token", "<token|off>",
                        "Bot for /status and /players",
                        "Bot token from discord.com/developers\n(your app > Bot > Reset Token).\nOnly the first and last characters are ever shown."),
                new Entry("/minevisor status_channel_id", "<id|off>",
                        "Live status message",
                        "The bot keeps one message in this channel\nupdated with who's online.\nDeveloper Mode on, right-click the channel, Copy ID."),
                new Entry("/minevisor status_interval", "<minutes>",
                        "How often the status updates",
                        "The status message is edited in place this often\n(10 minutes by default). It's never deleted or reposted."),
                new Entry("/minevisor banner", "<auto|off|url>",
                        "Image on top of the Discord cards",
                        "auto: a live map of where players are\n(or where they spend most time).\nOr an https image, 2000x500 looks best."),
                new Entry("/minevisor settings", "",
                        "Settings menu",
                        "A chest menu to turn alerts, the banner,\nthe MOTD and more on or off."),
                new Entry("/minevisor name", "<text|off>",
                        "Name on the Discord cards",
                        "Shown on /status, the status message\nand the banner. off hides it."),
                new Entry("/minevisor motd", "<on|off>",
                        "Show the MOTD on the cards",
                        "The server list message under the name.")
        );
    }

    public record Setting(String name, boolean set, String value) {
    }

    public static List<Setting> discordSettings(DiscordConfig c) {
        List<Setting> out = new ArrayList<>();
        out.add(new Setting("Webhook", c.webhookEnabled(), DiscordAdmin.maskWebhook(c.webhookUrl())));
        out.add(new Setting("Bot token", c.botEnabled(), DiscordAdmin.mask(c.botToken())));
        out.add(new Setting("Status channel", c.statusChannelId() != null && !c.statusChannelId().isBlank(),
                c.statusChannelId() == null || c.statusChannelId().isBlank() ? "not set" : c.statusChannelId()));
        out.add(new Setting("Status updates", true, "every " + c.statusIntervalMinutes() + " min, edited in place"));
        boolean banner = c.bannerUrl() != null && !c.bannerUrl().isBlank();
        out.add(new Setting("Banner", banner, banner ? shorten(c.bannerUrl()) : "not set (optional)"));
        return out;
    }

    private static String shorten(String url) {
        return url.length() <= 42 ? url : url.substring(0, 39) + "…";
    }
}
