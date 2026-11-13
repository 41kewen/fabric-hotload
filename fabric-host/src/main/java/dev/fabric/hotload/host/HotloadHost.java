package dev.fabric.hotload.host;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.fabric.hotload.core.ModuleManager;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HotloadHost implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("fabric-hotload");
    private final String session = UUID.randomUUID().toString();
    private ModuleManager modules;
    private LoaderAdapter adapter;
    private long lastTick;
    private long maxTickGap;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                Path directory = FabricLoader.getInstance().getGameDir().resolve("hotmods");
                modules = new ModuleManager(directory, getClass().getClassLoader(), LOGGER::info);
                adapter = new LoaderAdapter(directory);
                LOGGER.info("HOTLOAD_READY session={} pid={} probe={} mixinTouches={}",
                        session, ProcessHandle.current().pid(), ProbeTarget.value(), ProbeTarget.mixinTouches);
            } catch (Exception exception) { throw new IllegalStateException("Hotload initialization failed", exception); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (modules != null) { modules.tick(); }
            long now = System.nanoTime();
            if (lastTick != 0) { maxTickGap = Math.max(maxTickGap, now - lastTick); }
            lastTick = now;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (modules != null) {
                try { modules.close(); } catch (Exception exception) { LOGGER.error("Hotload cleanup failed", exception); }
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                literal("hotmods").requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER))
                        .then(literal("status").executes(context -> run(context, () -> "STATUS session=" + session
                                + " pid=" + ProcessHandle.current().pid() + " tick=" + context.getSource().getServer().getTickCount()
                                + " resources=" + modules.resourceCount() + " dirty=" + (modules.dirty() || adapter.dirty())
                                + " modules=" + modules.status()
                                + " avgTickMs=" + context.getSource().getServer().getAverageTickTimeNanos() / 1_000_000.0
                                + " maxTickGapMs=" + maxTickGap / 1_000_000.0)))
                        .then(literal("inspect").then(argument("jar", StringArgumentType.word()).executes(context ->
                                run(context, () -> inspect(word(context, "jar"))))))
                        .then(literal("load").then(argument("jar", StringArgumentType.word()).executes(context ->
                                run(context, () -> { requireClean(); return modules.load(word(context, "jar")); }))))
                        .then(literal("reload").then(argument("jar", StringArgumentType.word()).executes(context ->
                                run(context, () -> { requireClean(); return modules.reload(word(context, "jar")); }))))
                        .then(literal("disable").then(argument("id", StringArgumentType.word()).executes(context ->
                                run(context, () -> modules.disable(word(context, "id"))))))
                        .then(literal("loadmod").then(argument("jar", StringArgumentType.word()).executes(context ->
                                run(context, () -> { requireClean(); return adapter.load(modules.inspect(word(context, "jar"))); }))))
                        .then(literal("ordinary").executes(context -> run(context, HotloadHost::ordinaryStatus)))
                        .then(literal("patch").then(argument("mode", StringArgumentType.word()).executes(context ->
                                run(context, () -> patch(word(context, "mode"))))))
                        .then(literal("exec").then(argument("id", StringArgumentType.word())
                                .then(argument("command", StringArgumentType.word())
                                        .executes(context -> execute(context, List.of()))
                                        .then(argument("args", StringArgumentType.greedyString()).executes(context ->
                                                execute(context, Arrays.asList(word(context, "args").split("\\s+"))))))))));
    }

    private void requireClean() {
        if (modules.dirty() || adapter.dirty()) { throw new IllegalStateException("DIRTY_RESTART_REQUIRED"); }
    }

    private String inspect(String filename) throws Exception {
        var info = modules.inspect(filename);
        return "CANDIDATE kind=" + info.kind() + " id=" + info.id() + " version=" + info.version()
                + " sha256=" + info.sha256() + " classes=" + info.classes().size()
                + " blockers=" + info.blockers().stream().limit(5).toList()
                + " compatibility=UNKNOWN_EXPERIMENTAL";
    }

    private int execute(CommandContext<CommandSourceStack> context, List<String> arguments) {
        return run(context, () -> modules.execute(word(context, "id"), word(context, "command"), arguments));
    }

    private static String word(CommandContext<CommandSourceStack> context, String name) {
        return StringArgumentType.getString(context, name);
    }

    private int run(CommandContext<CommandSourceStack> context, CheckedSupplier action) {
        if (modules == null) {
            context.getSource().sendFailure(Component.literal("Hotload server lifecycle is not ready"));
            return 0;
        }
        try {
            String result = action.get();
            context.getSource().sendSuccess(() -> Component.literal(result), false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception | LinkageError failure) {
            LOGGER.error("HOTLOAD_OPERATION_FAILED", failure);
            context.getSource().sendFailure(Component.literal("HOTLOAD_REJECTED " + failure.getMessage()));
            return 0;
        }
    }

    private static String ordinaryStatus() {
        FabricLoader loader = FabricLoader.getInstance();
        var share = loader.getObjectShare();
        return "ORDINARY loaded=" + loader.isModLoaded("hotload_probe")
                + " main=" + share.get("hotload_probe:main") + " server=" + share.get("hotload_probe:server")
                + " ticks=" + share.get("hotload_probe:ticks") + " resource=" + share.get("hotload_probe:resource")
                + " container=" + loader.getModContainer("hotload_probe").isPresent();
    }

    private static String patch(String mode) throws Exception {
        int count = ProbeTarget.mixinTouches;
        String before = ProbeTarget.value();
        Class<?> agent = Class.forName("dev.fabric.hotload.agent.HotloadAgent", true, ClassLoader.getSystemClassLoader());
        String result = (String) agent.getMethod("probe", String.class).invoke(null, mode);
        String after = ProbeTarget.value();
        return result + " before=" + before + " after=" + after + " mixinDelta=" + (ProbeTarget.mixinTouches - count);
    }

    @FunctionalInterface
    private interface CheckedSupplier {
        String get() throws Exception;
    }
}
