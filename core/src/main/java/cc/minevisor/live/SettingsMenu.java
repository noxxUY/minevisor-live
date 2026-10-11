package cc.minevisor.live;

import java.util.ArrayList;
import java.util.List;

public final class SettingsMenu {

    private SettingsMenu() {
    }

    public static final int ROWS = 4;
    public static final String TITLE = "minevisor · settings";

    public enum Action {
        TOGGLE,
        CYCLE_BANNER,
        SUGGEST,
        LINK,
        CLOSE,
        NONE
    }

    public record Slot(int index, String item, boolean glow, Line name, List<Line> lore, Action action, String setting) {
    }

    public record Line(String text, String color, boolean bold) {
        static Line of(String text, String color) {
            return new Line(text, color, false);
        }
    }

    private static final String[] TOGGLES = {"joins", "deaths", "death_location", "motd"};
    private static final String[] TOGGLE_ITEMS = {"minecraft:oak_door", "minecraft:skeleton_skull", "minecraft:compass", "minecraft:oak_sign"};
    private static final String[][] TOGGLE_HELP = {
            {"Posts when someone joins or leaves,", "with their head and how many are online."},
            {"Posts when someone dies,", "with the game's death message."},
            {"Death alerts also show the", "dimension and coordinates."},
            {"Shows the server list MOTD", "under the name on the cards."},
    };

    public static List<Slot> build(DiscordConfig c) {
        List<Slot> out = new ArrayList<>();
        for (int i = 0; i < ROWS * 9; i++) {
            boolean edge = i < 9 || i >= (ROWS - 1) * 9 || i % 9 == 0 || i % 9 == 8;
            if (edge) out.add(new Slot(i, "minecraft:black_stained_glass_pane", false, Line.of(" ", "gray"), List.of(), Action.NONE, null));
        }

        for (int t = 0; t < TOGGLES.length; t++) {
            String key = TOGGLES[t];
            boolean on = DiscordAdmin.isOn(c, key);
            List<Line> lore = new ArrayList<>();
            for (String l : TOGGLE_HELP[t]) lore.add(Line.of(l, "gray"));
            lore.add(Line.of("", "gray"));
            lore.add(Line.of(on ? "● On" : "● Off", on ? "green" : "red"));
            lore.add(Line.of("Click to turn " + (on ? "off" : "on"), "dark_gray"));
            int slot = 10 + t;
            out.add(new Slot(slot, TOGGLE_ITEMS[t], on, new Line(DiscordAdmin.label(key), on ? "green" : "white", true), lore, Action.TOGGLE, key));
            out.add(new Slot(slot + 9, on ? "minecraft:lime_stained_glass_pane" : "minecraft:red_stained_glass_pane", false,
                    new Line(on ? "ON" : "OFF", on ? "green" : "red", true), List.of(Line.of("Click to turn " + (on ? "off" : "on"), "dark_gray")),
                    Action.TOGGLE, key));
        }

        String banner = c.bannerUrl() == null || c.bannerUrl().isBlank() ? "auto" : c.bannerUrl();
        boolean bannerOn = !banner.equalsIgnoreCase("off");
        String bannerState = banner.equalsIgnoreCase("auto") ? "Live map of where players are" : banner.equalsIgnoreCase("off") ? "Off" : "Custom image";
        out.add(new Slot(15, "minecraft:filled_map", bannerOn, new Line("Banner", bannerOn ? "green" : "white", true), List.of(
                Line.of("Image on top of /status, /players", "gray"),
                Line.of("and the status message.", "gray"),
                Line.of("", "gray"),
                Line.of("● " + bannerState, bannerOn ? "green" : "red"),
                Line.of("Click: " + (banner.equalsIgnoreCase("auto") ? "turn off" : "use the live map"), "dark_gray"),
                Line.of("Own image: /minevisor banner <url>", "dark_gray")), Action.CYCLE_BANNER, "banner"));
        out.add(new Slot(16, "minecraft:name_tag", c.hasName(), new Line("Server name", c.hasName() ? "green" : "white", true), List.of(
                Line.of("Shown on the Discord cards and banner.", "gray"),
                Line.of("", "gray"),
                Line.of(c.hasName() ? "● " + c.serverName() : "● Not set (hidden)", c.hasName() ? "green" : "red"),
                Line.of("Click to type a name", "dark_gray")), Action.SUGGEST, "/minevisor name "));

        out.add(new Slot(28, "minecraft:bell", c.webhookEnabled(), new Line("Webhook", c.webhookEnabled() ? "green" : "white", true), List.of(
                Line.of("Channel for the alerts above.", "gray"),
                Line.of(c.webhookEnabled() ? "● Connected" : "● Not set", c.webhookEnabled() ? "green" : "red"),
                Line.of("Click to paste a webhook URL", "dark_gray")), Action.SUGGEST, "/minevisor webhook "));
        out.add(new Slot(29, "minecraft:observer", c.botEnabled(), new Line("Bot", c.botEnabled() ? "green" : "white", true), List.of(
                Line.of("/status and /players in Discord.", "gray"),
                Line.of(c.botEnabled() ? "● Token set (" + DiscordAdmin.mask(c.botToken()) + ")" : "● Not set", c.botEnabled() ? "green" : "red"),
                Line.of("Click to paste the bot token", "dark_gray")), Action.SUGGEST, "/minevisor token "));
        boolean channel = c.statusChannelId() != null && !c.statusChannelId().isBlank();
        out.add(new Slot(30, "minecraft:lectern", channel, new Line("Status channel", channel ? "green" : "white", true), List.of(
                Line.of("One message the bot keeps updated.", "gray"),
                Line.of(channel ? "● " + c.statusChannelId() : "● Not set", channel ? "green" : "red"),
                Line.of("Updates every " + c.statusIntervalMinutes() + " min (/minevisor status_interval)", "gray"),
                Line.of("Click to paste the channel ID", "dark_gray")), Action.SUGGEST, "/minevisor status_channel_id "));
        out.add(new Slot(32, "minecraft:ender_eye", false, new Line("Open the live workspace", "aqua", true), List.of(
                Line.of("Your server in 3D, live, in the browser.", "gray"),
                Line.of("Click for your private link", "dark_gray")), Action.LINK, null));
        out.add(new Slot(34, "minecraft:barrier", false, new Line("Close", "red", true), List.of(), Action.CLOSE, null));
        return out;
    }

    public static Slot at(List<Slot> slots, int index) {
        Slot found = null;
        for (Slot s : slots) if (s.index() == index) found = s;
        return found;
    }
}
