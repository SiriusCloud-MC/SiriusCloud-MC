package dev.sirius.cloud.node;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.event.EventManager;
import dev.sirius.cloud.api.event.events.ServiceRemovedEvent;
import dev.sirius.cloud.api.event.events.ServiceStateChangedEvent;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.Platform;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.driver.event.DefaultEventManager;
import dev.sirius.cloud.driver.paper.PaperVersionCatalog;
import dev.sirius.cloud.node.command.CommandManager;
import dev.sirius.cloud.node.command.commands.AttachCommand;
import dev.sirius.cloud.node.command.commands.BroadcastCommand;
import dev.sirius.cloud.node.command.commands.ExecuteCommand;
import dev.sirius.cloud.node.command.commands.GroupsCommand;
import dev.sirius.cloud.node.command.commands.HelpCommand;
import dev.sirius.cloud.node.command.commands.InfoCommand;
import dev.sirius.cloud.node.command.commands.ModulesCommand;
import dev.sirius.cloud.node.command.commands.PlayerCommand;
import dev.sirius.cloud.node.command.commands.PlayersCommand;
import dev.sirius.cloud.node.command.commands.ServicesCommand;
import dev.sirius.cloud.node.command.commands.SetupCommand;
import dev.sirius.cloud.node.command.commands.ShutdownCommand;
import dev.sirius.cloud.node.command.commands.StartCommand;
import dev.sirius.cloud.node.command.commands.StopCommand;
import dev.sirius.cloud.node.command.commands.VersionsCommand;
import dev.sirius.cloud.driver.config.DirectoryLock;
import dev.sirius.cloud.driver.config.JsonConfig;
import dev.sirius.cloud.node.config.NodeConfig;
import dev.sirius.cloud.node.console.NodeConsole;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.module.ModuleManager;
import dev.sirius.cloud.node.network.NodePacketHandler;
import dev.sirius.cloud.node.player.PlayerManager;
import dev.sirius.cloud.node.player.PlayerRegistry;
import dev.sirius.cloud.node.provisioning.GroupBackoff;
import dev.sirius.cloud.node.provisioning.ProvisioningTask;
import dev.sirius.cloud.node.service.ServiceManager;
import dev.sirius.cloud.node.service.ServiceChannelRegistry;
import dev.sirius.cloud.node.service.ServiceRegistry;
import dev.sirius.cloud.node.setup.FirstRunSetup;
import dev.sirius.cloud.node.wrapper.WrapperRegistry;
import dev.sirius.cloud.protocol.connection.NetworkServer;
import dev.sirius.cloud.protocol.packet.PacketRegistry;
import dev.sirius.cloud.protocol.packet.impl.ServiceAvailabilityPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wires everything together and owns the node's lifecycle. */
public final class CloudNode {

    private static final CloudLogger LOGGER = CloudLogger.of("CloudNode");

    /** How long a graceful shutdown waits for services to save and exit. */
    private static final int SHUTDOWN_GRACE_SECONDS = 30;

    private static volatile CloudNode instance;

    private final Path workingDirectory;
    private final NodeConfig config;

    private final EventManager events = new DefaultEventManager();
    private final ServiceRegistry services = new ServiceRegistry();
    private final ServiceChannelRegistry serviceChannels = new ServiceChannelRegistry();
    private final PlayerRegistry players = new PlayerRegistry();
    private final WrapperRegistry wrappers = new WrapperRegistry();
    private final GroupRegistry groups;
    private final ServiceManager serviceManager;
    private final PlayerManager playerManager;
    private final CommandManager commands = new CommandManager();
    private final NetworkServer server = new NetworkServer(PacketRegistry.standard());
    private final PaperVersionCatalog paperVersions = new PaperVersionCatalog("paper");
    private final PaperVersionCatalog velocityVersions = new PaperVersionCatalog("velocity");
    private final GroupBackoff backoff = new GroupBackoff();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sirius-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    private NodeConsole console;
    private DirectoryLock directoryLock;
    private ModuleManager modules;

    public CloudNode(Path workingDirectory) throws IOException {
        this.workingDirectory = workingDirectory;

        Files.createDirectories(workingDirectory.resolve("local"));

        this.config = JsonConfig.loadOrCreate(
                workingDirectory.resolve("config.json"), NodeConfig.class, NodeConfig::new);
        CloudLogger.debugEnabled(config.debug());

        this.groups = new GroupRegistry(workingDirectory.resolve("groups"));

        this.serviceManager = new ServiceManager(config, groups, services, wrappers, events);
        this.playerManager = new PlayerManager(players, services, serviceChannels);

        instance = this;
    }

    public static CloudNode instance() {
        return instance;
    }

    public void start() throws Exception {
        // Two nodes sharing one directory would fight over config.json and
        // groups/, and the second would fail to bind the port anyway — with a
        // stack trace instead of an explanation.
        directoryLock = DirectoryLock.acquire(workingDirectory.resolve(".lock"), "node");

        console = new NodeConsole(commands, workingDirectory.resolve("local").resolve("console_history"));
        printBanner();

        groups.load();

        Path configFile = workingDirectory.resolve("config.json");
        FirstRunSetup setup = new FirstRunSetup(console, groups, config, configFile);
        registerCommands(setup);

        // Offered once, then remembered either way. Tracked in the config
        // rather than inferred from "are there groups", so declining is not
        // re-asked on every single start.
        if (!config.setupCompleted()) {
            setup.runFirstRun();
            config.setupCompleted(true);
            JsonConfig.save(configFile, config);
        }

        // Held as well as bound: the packet handler answers node queries through
        // it, so the API and the console describe the node from one place.
        LocalCloudDriver driver = new LocalCloudDriver(
                serviceManager, services, groups, events, players, playerManager, wrappers, config, serviceChannels);
        CloudDriver.bind(driver);

        // An attached console must not outlive the service it is attached to.
        // Doing this through the event bus rather than a call inside
        // ServiceManager is the point of having the bus: the console is a
        // consumer of lifecycle events, not something the scheduler knows about.
        events.subscribe(ServiceRemovedEvent.class,
                event -> console.detachIfAttachedTo(event.service().uniqueId()));

        // Provisioning health is derived from lifecycle events rather than
        // wired into the scheduler, so a group that cannot start backs off
        // instead of being restarted once a second forever.
        events.subscribe(ServiceStateChangedEvent.class, event -> {
            switch (event.current()) {
                case RUNNING -> backoff.recordSuccess(event.service().groupName());
                case CRASHED -> backoff.recordFailure(event.service().groupName());
                default -> {
                }
            }

            // Proxies learn about backend servers as they become reachable.
            if (event.current() == dev.sirius.cloud.api.service.ServiceState.RUNNING
                    && event.service().type() == ServiceType.SERVER) {
                serviceChannels.broadcastToProxies(services,
                        new ServiceAvailabilityPacket(event.service(), true));
            }
        });

        // A server that has gone must be dropped from every proxy, or players
        // keep being routed to a port with nothing behind it.
        events.subscribe(ServiceRemovedEvent.class, event -> {
            if (event.service().type() == ServiceType.SERVER) {
                serviceChannels.broadcastToProxies(services,
                        new ServiceAvailabilityPacket(event.service(), false));
            }
        });

        server.start(config.bindAddress(), config.port(), new NodePacketHandler(
                config, serviceManager, services, groups, wrappers, events, console,
                serviceChannels, players, playerManager, driver));

        LOGGER.info("Services connect back to {}:{}", config.connectAddress(), config.port());
        LOGGER.info("Wrapper secret: {}", config.secret());
        LOGGER.info("Waiting for a wrapper to connect. Type 'help' for commands.");
        LOGGER.info("Service consoles are hidden until you 'attach <service>'.");

        // After the driver is bound and the listener is up: a module's first
        // act is typically to query the cloud or open a port of its own, and
        // neither works before this point.
        modules = new ModuleManager(workingDirectory.resolve("modules"), driver, events);
        commands.register(new ModulesCommand(modules));
        modules.loadAll();

        scheduler.scheduleWithFixedDelay(
                new ProvisioningTask(groups, services, serviceManager, wrappers, backoff),
                2, 1, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "sirius-shutdown"));

        // Blocks this thread until the console exits.
        console.run(this::shutdown);
    }

    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        LOGGER.info("Shutting down...");
        scheduler.shutdownNow();

        // Before the services stop: a module watching lifecycle events should
        // not receive a burst of shutdown traffic it is half torn down for.
        if (modules != null) {
            modules.disableAll();
        }

        Collection<ServiceInfo> running = services.all();
        if (!running.isEmpty()) {
            LOGGER.info("Stopping {} service(s), waiting up to {}s", running.size(), SHUTDOWN_GRACE_SECONDS);
            running.forEach(service -> serviceManager.stop(service.uniqueId(), false));

            long deadline = System.currentTimeMillis() + SHUTDOWN_GRACE_SECONDS * 1000L;
            while (services.size() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (services.size() > 0) {
                LOGGER.warn("{} service(s) did not stop in time", services.size());
            }
        }

        server.close();
        if (console != null) {
            console.close();
        }
        if (directoryLock != null) {
            directoryLock.close();
        }
        LOGGER.info("Goodbye.");
    }

    private void registerCommands(FirstRunSetup setup) {
        commands.register(new HelpCommand(commands));
        commands.register(new SetupCommand(setup));
        commands.register(new ServicesCommand(services));
        commands.register(new PlayersCommand(players));
        commands.register(new PlayerCommand(players, playerManager, services, groups));
        commands.register(new BroadcastCommand(playerManager));
        commands.register(new GroupsCommand(groups, services));
        commands.register(new StartCommand(groups, serviceManager));
        commands.register(new StopCommand(services, serviceManager));
        commands.register(new ExecuteCommand(services, serviceManager));
        commands.register(new AttachCommand(services, serviceManager, console));
        commands.register(new VersionsCommand(paperVersions, velocityVersions, config.minimumPaperVersion()));
        commands.register(new InfoCommand(config, services, wrappers));
        commands.register(new ShutdownCommand(this));
    }

    private void printBanner() {
        CloudLogger.raw("");
        CloudLogger.raw("   ____  _      _           ____ _                 _ ");
        CloudLogger.raw("  / ___|(_)_ __(_)_   _ ___ / ___| | ___  _   _  __| |");
        CloudLogger.raw("  \\___ \\| | '__| | | | / __| |   | |/ _ \\| | | |/ _` |");
        CloudLogger.raw("   ___) | | |  | | |_| \\__ \\ |___| | (_) | |_| | (_| |");
        CloudLogger.raw("  |____/|_|_|  |_|\\__,_|___/\\____|_|\\___/ \\__,_|\\__,_|");
        CloudLogger.raw("");
        CloudLogger.raw("  node '" + config.nodeName() + "'  |  " + Platform.describe());
        CloudLogger.raw("");
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public NodeConfig config() {
        return config;
    }

    public EventManager events() {
        return events;
    }

    public ServiceRegistry services() {
        return services;
    }

    public WrapperRegistry wrappers() {
        return wrappers;
    }

    public GroupRegistry groups() {
        return groups;
    }

    public ServiceManager serviceManager() {
        return serviceManager;
    }

    public PlayerRegistry players() {
        return players;
    }

    public PlayerManager playerManager() {
        return playerManager;
    }

    public CommandManager commands() {
        return commands;
    }
}
