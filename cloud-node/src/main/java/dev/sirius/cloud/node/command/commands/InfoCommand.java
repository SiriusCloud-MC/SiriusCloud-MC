package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.Platform;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.config.NodeConfig;
import dev.sirius.cloud.node.service.ServiceRegistry;
import dev.sirius.cloud.node.wrapper.WrapperRegistry;

public final class InfoCommand implements Command {

    private final NodeConfig config;
    private final ServiceRegistry services;
    private final WrapperRegistry wrappers;

    public InfoCommand(NodeConfig config, ServiceRegistry services, WrapperRegistry wrappers) {
        this.config = config;
        this.services = services;
        this.wrappers = wrappers;
    }

    @Override
    public String name() {
        return "info";
    }

    @Override
    public String description() {
        return "Shows node status and connected wrappers";
    }

    @Override
    public void execute(String[] args) {
        CloudLogger.raw("Node      : " + config.nodeName() + " on " + Platform.describe());
        CloudLogger.raw("Listening : " + config.bindAddress() + ":" + config.port()
                + "  (services connect to " + config.connectAddress() + ")");
        CloudLogger.raw("Memory    : " + services.committedMemory() + "MB / " + config.maxMemory() + "MB committed");
        CloudLogger.raw("Services  : " + services.size());
        CloudLogger.raw("Wrappers  : " + wrappers.all().size());

        wrappers.all().forEach(wrapper -> CloudLogger.raw(
                "  - " + wrapper.name() + " @ " + wrapper.info().host()
                        + "  [" + wrapper.info().platform() + "]"
                        + "  " + wrapper.info().usedMemory() + "/" + wrapper.info().maxMemory() + "MB"));
    }
}
