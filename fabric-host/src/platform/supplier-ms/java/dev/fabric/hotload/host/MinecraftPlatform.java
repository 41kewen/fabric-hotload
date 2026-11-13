package dev.fabric.hotload.host;

import com.mojang.brigadier.CommandDispatcher;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** 1.20.1 的平均 tick 接口已经返回毫秒。 */
final class MinecraftPlatform {
    private MinecraftPlatform() {
    }

    static void register(Consumer<CommandDispatcher<CommandSourceStack>> registration) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> registration.accept(dispatcher));
    }

    static boolean authorized(CommandSourceStack source) { return source.hasPermission(4); }

    static Component text(String message) { return Component.literal(message); }

    static double averageTickMs(MinecraftServer server) { return server.getAverageTickTime(); }

    static void sendSuccess(CommandSourceStack source, Component message) { source.sendSuccess(() -> message, false); }
}
