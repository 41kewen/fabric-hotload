package dev.fabric.hotload.host;

import com.mojang.brigadier.CommandDispatcher;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;

/** 1.18.2 仍使用 Fabric 命令注册 API v1。 */
final class MinecraftPlatform {
    private MinecraftPlatform() {
    }

    static void register(Consumer<CommandDispatcher<CommandSourceStack>> registration) {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> registration.accept(dispatcher));
    }

    static boolean authorized(CommandSourceStack source) { return source.hasPermission(4); }

    static Component text(String message) { return new TextComponent(message); }

    static double averageTickMs(MinecraftServer server) { return server.getAverageTickTime(); }

    static void sendSuccess(CommandSourceStack source, Component message) { source.sendSuccess(message, false); }
}
