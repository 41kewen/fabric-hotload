package dev.fabric.hotload.host;

import dev.fabric.hotload.core.JarInspection;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

final class LoaderAdapters {
    private LoaderAdapters() {
    }

    static OrdinaryModAdapter create(Path directory) throws Exception {
        String version = FabricLoader.getInstance().getModContainer("fabricloader").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
        if (version.equals("0.20.1")) { return new Loader0201Adapter(directory); }
        // 未验证的内部 ABI 不参与类加载；仍允许公共 API 支持的受管模块与 inspect。
        return new OrdinaryModAdapter() {
            @Override
            public boolean dirty() { return false; }

            @Override
            public String description() { return "unsupported-loader-" + version; }

            @Override
            public String load(JarInspection inspection) {
                throw new IllegalStateException("UNSUPPORTED_LOADER " + version + ": ordinary hot loading is disabled");
            }
        };
    }
}
