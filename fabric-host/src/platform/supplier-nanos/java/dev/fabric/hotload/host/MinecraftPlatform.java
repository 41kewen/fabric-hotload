package dev.fabric.hotload.host;

import com.mojang.brigadier.CommandDispatcher;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** 1.20.4 至已列入矩阵的 1.21.4，tick 平均值接口的单位为纳秒。 */
final class MinecraftPlatform {
    private MinecraftPlatform() {
    }

    static void register(Consumer<CommandDispatcher<CommandSourceStack>> registration) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> registration.accept(dispatcher));
    }

    static boolean authorized(CommandSourceStack source) { return source.hasPermission(4); }

    static Component text(String message) { return Component.literal(message); }

    static double averageTickMs(MinecraftServer server) { return server.getAverageTickTimeNanos() / 1_000_000.0; }

    static void sendSuccess(CommandSourceStack source, Component message) { source.sendSuccess(() -> message, false); }
}
