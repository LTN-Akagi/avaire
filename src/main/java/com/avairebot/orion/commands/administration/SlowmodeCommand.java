package com.avairebot.orion.commands.administration;

import com.avairebot.orion.Constants;
import com.avairebot.orion.Orion;
import com.avairebot.orion.contracts.commands.Command;
import com.avairebot.orion.database.controllers.GuildController;
import com.avairebot.orion.database.transformers.ChannelTransformer;
import com.avairebot.orion.database.transformers.GuildTransformer;
import com.avairebot.orion.factories.MessageFactory;
import com.avairebot.orion.permissions.Permissions;
import com.avairebot.orion.utilities.ComparatorUtil;
import com.avairebot.orion.utilities.NumberUtil;
import net.dv8tion.jda.core.entities.Message;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class SlowmodeCommand extends Command {

    public SlowmodeCommand(Orion orion) {
        super(orion, false);
    }

    @Override
    public String getName() {
        return "Slowmode Command";
    }

    @Override
    public String getDescription() {
        return "Disables the slowmode module or enables it with the given settings, users with the **" + Permissions.MESSAGE_MANAGE.getPermission().getName() + "** permission are exempt from slowmode limits.";
    }

    @Override
    public List<String> getUsageInstructions() {
        return Arrays.asList(
            "`:command <off>` - Disables slowmode for the current channel.",
            "`:command <limit> <decay>` - Enables slowmode with the given settings."
        );
    }

    @Override
    public List<String> getExampleUsage() {
        return Arrays.asList(
            "`:command off` - Disables slowmode",
            "`:command 1 5` - Enables slowmode, allowing one message every five seconds."
        );
    }

    @Override
    public List<String> getTriggers() {
        return Collections.singletonList("slowmode");
    }

    @Override
    public List<String> getMiddleware() {
        return Arrays.asList(
            "require:user,general.manage_server",
            "throttle:guild,1,5"
        );
    }

    @Override
    public boolean onCommand(Message message, String[] args) {
        if (args.length == 0) {
            return sendErrorMessage(message, "Missing argument, you must either pass in the `limit` and `decay` values to enable slowmode for this channel, or `off` to disable it for this channel.");
        }

        GuildTransformer guildTransformer = GuildController.fetchGuild(orion, message);
        if (guildTransformer == null) {
            return endWithFailureToFindTransformer(message);
        }

        ChannelTransformer channelTransformer = guildTransformer.getChannel(message.getChannel().getId());
        if (channelTransformer == null) {
            return endWithFailureToFindTransformer(message);
        }

        if (args.length == 1 && ComparatorUtil.isFuzzyFalse(args[0])) {
            return disableSlowmode(message, guildTransformer, channelTransformer);
        }

        if (args.length == 2 && NumberUtil.isNumeric(args[0]) && NumberUtil.isNumeric(args[1])) {
            return enableSlowmode(message, args, guildTransformer, channelTransformer);
        }

        return sendErrorMessage(message, "Invalid argument given, the `limit` and `decay` must be valid numbers.");
    }

    private boolean enableSlowmode(Message message, String[] args, GuildTransformer guildTransformer, ChannelTransformer channelTransformer) {
        int limit = NumberUtil.getBetween(NumberUtil.parseInt(args[0]), 1, 30);
        int decay = NumberUtil.getBetween(NumberUtil.parseInt(args[1]), 1, 300);

        channelTransformer.getSlowmode().setEnabled(true);
        channelTransformer.getSlowmode().setLimit(limit);
        channelTransformer.getSlowmode().setDecay(decay);

        return updateDatabase(message, guildTransformer, v -> {
            MessageFactory.makeSuccess(message, "Messages are now limited to :limit message(s) every :decay seconds.")
                .set("limit", limit)
                .set("decay", decay)
                .queue();
        });
    }

    private boolean disableSlowmode(Message message, GuildTransformer guildTransformer, ChannelTransformer transformer) {
        transformer.getSlowmode().setEnabled(false);

        return updateDatabase(message, guildTransformer, v -> {
            MessageFactory.makeSuccess(message, "The `Slowmode` module has been **disabled** for the :channel channel.").queue();
        });
    }

    private boolean endWithFailureToFindTransformer(Message message) {
        return sendErrorMessage(message, "Something went wrong while trying to get the channel transformer object, please contact one of my developers to look into this issue.");
    }

    private boolean updateDatabase(Message message, GuildTransformer guildTransformer, Consumer<Void> consumer) {
        try {
            orion.getDatabase().newQueryBuilder(Constants.GUILD_TABLE_NAME)
                .andWhere("id", message.getGuild().getId())
                .update(statement -> statement.set("channels", guildTransformer.channelsToJson(), true));

            consumer.accept(null);
            return true;
        } catch (SQLException ex) {
            Orion.getLogger().error(ex.getMessage(), ex);

            MessageFactory.makeError(message, "Failed to save the guild settings: " + ex.getMessage()).queue();
        }
        return false;
    }
}