package dev.fabric.hotload.api;

import java.util.List;

@FunctionalInterface
public interface ModuleCommand {
    String execute(List<String> arguments) throws Exception;
}
