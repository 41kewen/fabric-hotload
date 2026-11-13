package dev.fabric.hotload.host;

import com.mojang.brigadier.CommandDispatcher;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;

/** 1.21.11 和 26.x 使用权限对象，不再使用数字权限级别。 */
final class MinecraftPlatform {
    private MinecraftPlatform() {
    }

    static void register(Consumer<CommandDispatcher<CommandSourceStack>> registration) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> registration.accept(dispatcher));
    }

    static boolean authorized(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    static Component text(String message) { return Component.literal(message); }

    static double averageTickMs(MinecraftServer server) { return server.getAverageTickTimeNanos() / 1_000_000.0; }

    static void sendSuccess(CommandSourceStack source, Component message) { source.sendSuccess(() -> message, false); }
}
