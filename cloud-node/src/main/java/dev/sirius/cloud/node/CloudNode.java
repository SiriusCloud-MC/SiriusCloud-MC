package dev.sirius.cloud.node;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.event.EventManager;
import dev.sirius.cloud.api.event.events.PlayerConnectEvent;
import dev.sirius.cloud.api.event.events.PlayerDisconnectEvent;
import dev.sirius.cloud.api.event.events.ServiceCreatedEvent;
import dev.sirius.cloud.api.event.events.ServiceRemovedEvent;
import dev.sirius.cloud.api.event.events.ServiceUpdatedEvent;
import dev.sirius.cloud.api.event.events.ServiceStateChangedEvent;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.Platform;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.driver.event.DefaultEventManager;
import dev.sirius.cloud.driver.paper.PaperVersionCatalog;
import dev.sirius.cloud.node.command.CommandManager;
import dev.sirius.cloud.node.command.commands.AttachCommand;
import dev.sirius.cloud.node.command.commands.BackupCommand;
import dev.sirius.cloud.node.command.commands.BroadcastCommand;
import dev.sirius.cloud.node.command.commands.ClusterCommand;
import dev.sirius.cloud.node.command.commands.EditCommand;
import dev.sirius.cloud.node.command.commands.ExecuteCommand;
import dev.sirius.cloud.node.command.commands.MaintenanceCommand;
import dev.sirius.cloud.node.command.commands.MigrateCommand;
import dev.sirius.cloud.node.command.commands.ReloadCommand;
import dev.sirius.cloud.node.command.commands.RestartCommand;
import dev.sirius.cloud.node.command.commands.RolloutCommand;
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
import dev.sirius.cloud.driver.migration.Migrator;
import dev.sirius.cloud.node.migration.NodeMigrations;
import dev.sirius.cloud.driver.config.JsonConfig;
import dev.sirius.cloud.node.config.NodeConfig;
import dev.sirius.cloud.node.gateway.ProxyGateway;
import dev.sirius.cloud.node.database.NodeDatabase;
import dev.sirius.cloud.node.player.PlayerProfiles;
import dev.sirius.cloud.node.store.NodeKeyValueStore;
import dev.sirius.cloud.node.console.NodeConsole;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.module.ModuleManager;
import dev.sirius.cloud.node.network.NodePacketHandler;
import dev.sirius.cloud.node.player.PlayerManager;
import dev.sirius.cloud.node.player.PlayerRegistry;
import dev.sirius.cloud.node.provisioning.AutoScaler;
import dev.sirius.cloud.node.provisioning.BackupScheduler;
import dev.sirius.cloud.node.provisioning.GroupBackoff;
import dev.sirius.cloud.node.provisioning.Rollouts;
import dev.sirius.cloud.node.provisioning.ProvisioningTask;
import dev.sirius.cloud.node.service.ServiceManager;
import dev.sirius.cloud.node.service.ServiceChannelRegistry;
import dev.sirius.cloud.node.service.ServiceRegistry;
import dev.sirius.cloud.node.setup.FirstRunSetup;
import dev.sirius.cloud.node.wrapper.WrapperRegistry;
import dev.sirius.cloud.protocol.connection.NetworkServer;
import dev.sirius.cloud.protocol.packet.PacketRegistry;
import dev.sirius.cloud.protocol.packet.impl.HandshakeResponsePacket;
import dev.sirius.cloud.node.cluster.ClusterMember;
import dev.sirius.cloud.node.command.commands.UpdateCommand;
import dev.sirius.cloud.driver.update.UpdateService;
import dev.sirius.cloud.driver.update.UpdateSettings;
import dev.sirius.cloud.driver.update.Updater;
import dev.sirius.cloud.driver.update.Version;
import java.util.Optional;
import dev.sirius.cloud.protocol.packet.impl.ServiceAvailabilityPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceUpdatePacket;
import dev.sirius.cloud.protocol.packet.impl.TemplateChangedPacket;

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
    private final java.util.Map<dev.sirius.cloud.api.service.ServerSoftware, dev.sirius.cloud.driver.paper.VersionCatalog>
            versionCatalogs = java.util.Map.of(
                    dev.sirius.cloud.api.service.ServerSoftware.PAPER, new PaperVersionCatalog("paper"),
                    dev.sirius.cloud.api.service.ServerSoftware.FOLIA, new PaperVersionCatalog("folia"),
                    dev.sirius.cloud.api.service.ServerSoftware.VELOCITY, new PaperVersionCatalog("velocity"),
                    dev.sirius.cloud.api.service.ServerSoftware.PURPUR,
                    new dev.sirius.cloud.driver.paper.PurpurVersionCatalog(),
                    dev.sirius.cloud.api.service.ServerSoftware.FABRIC,
                    new dev.sirius.cloud.driver.paper.FabricVersionCatalog());
    private final GroupBackoff backoff = new GroupBackoff();
    private final AutoScaler autoScaler = new AutoScaler();
    private final Rollouts rollouts;
    private final BackupScheduler backups;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sirius-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    /** Exit code asking the start scripts to start the node again: as a follower, or on a new version. */
    public static final int RESTART_AS_FOLLOWER = UpdateService.RESTART_EXIT_CODE;

    private volatile ClusterMember cluster;
    private NetworkServer standby;
    private volatile boolean leading;
    private volatile int exitCode;
    private FirstRunSetup setup;

    private NodeConsole console;
    private final DirectoryLock directoryLock;
    private final Migrator migrator;
    private NodeDatabase database;
    private NodeKeyValueStore store;
    private PlayerProfiles profiles;
    private ModuleManager modules;

    public CloudNode(Path workingDirectory) throws IOException {
        this.workingDirectory = workingDirectory;

        Files.createDirectories(workingDirectory.resolve("local"));

        // Two nodes sharing one directory would fight over config.json and
        // groups/, and the second would fail to bind the port anyway — with a
        // stack trace instead of an explanation. Taken before anything is read,
        // because migrations below rewrite files.
        directoryLock = DirectoryLock.acquire(workingDirectory.resolve(".lock"), "node");

        // Before anything reads a file, so every part of the node only ever
        // sees files in this build's shape. No config.json means a first start.
        this.migrator = new Migrator(workingDirectory, NodeMigrations.all());
        migrator.run(Files.notExists(workingDirectory.resolve("config.json")));

        this.config = JsonConfig.loadOrCreate(
                workingDirectory.resolve("config.json"), NodeConfig.class, NodeConfig::new);
        CloudLogger.debugEnabled(config.debug());

        this.groups = new GroupRegistry(workingDirectory.resolve("groups"));

        this.serviceManager = new ServiceManager(config, groups, services, wrappers, events);
        this.playerManager = new PlayerManager(players, services, serviceChannels);
        this.rollouts = new Rollouts(groups, services, serviceManager, players, playerManager);
        this.backups = new BackupScheduler(groups, services, wrappers);

        instance = this;
    }

    public static CloudNode instance() {
        return instance;
    }

    public void start() throws Exception {
        // Checked here rather than left to Netty. A port already in use is the
        // single most common startup failure, and a BindException stack trace
        // buries the one sentence that would tell the operator what to do.
        requirePortAvailable(config.bindAddress(), config.port());

        console = new NodeConsole(commands, workingDirectory.resolve("local").resolve("console_history"));
        printBanner();

        groups.load();

        Path configFile = workingDirectory.resolve("config.json");
        setup = new FirstRunSetup(console, groups, config, configFile);
        registerBaseCommands();

        // Offered once, then remembered either way. Tracked in the config
        // rather than inferred from "are there groups", so declining is not
        // re-asked on every single start.
        if (!config.setupCompleted()) {
            setup.runFirstRun();
            config.setupCompleted(true);
            JsonConfig.save(configFile, config);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "sirius-shutdown"));
        startUpdates();

        if (config.cluster().enabled()) {
            // A follower runs no control plane: it keeps a copy of the data,
            // votes, and points clients at the leader. It becomes a full node
            // only once elected, from the files the leader replicated to it.
            cluster = new ClusterMember(config, workingDirectory, migrator.latest(), new ClusterMember.Listener() {
                @Override
                public void promoted(long term) {
                    try {
                        becomeLeader();
                    } catch (Exception exception) {
                        LOGGER.error("Could not start the control plane as leader", exception);
                        stepDown("the control plane failed to start");
                    }
                }

                @Override
                public void demoted(String reason) {
                    stepDown(reason);
                }
            });
            cluster.build(BUILD);
            startStandbyGateway();
            cluster.start();
        } else {
            becomeLeader();
        }

        // Blocks this thread until the console exits.
        console.run(this::shutdown);
    }

    /**
     * Runs the control plane: the database, the driver, modules, the listener
     * wrappers and services connect to, and the scheduling loops.
     *
     * <p>Straight away on a standalone node. In a cluster, when this node is
     * elected - from files the previous leader replicated here, which is why
     * migrations and groups are looked at again first. Running services are
     * not started again: their wrappers reconnect and report them, and they
     * are adopted exactly as after a node restart.
     */
    private synchronized void becomeLeader() throws Exception {
        if (cluster != null) {
            stopStandbyGateway();
            migrator.run(false);
            groups.load();
            requirePortAvailable(config.bindAddress(), config.port());
            LOGGER.info("Taking over as the cluster's control plane");
        }

        // After setup, which is where the database is chosen, and before the
        // driver, which hands it out. Opening fails loudly: running on without
        // the database this cloud was configured for would quietly drop every
        // ban and profile written until somebody noticed.
        database = NodeDatabase.open(config.database(), workingDirectory.resolve("local").resolve("database"));
        store = new NodeKeyValueStore(workingDirectory.resolve("local").resolve("store.json"));
        store.load();
        profiles = new PlayerProfiles(database);
        profiles.attach(events);

        ProxyGateway gateway = new ProxyGateway(services, serviceChannels, players, playerManager);

        // Held as well as bound: the packet handler answers node queries through
        // it, so the API and the console describe the node from one place.
        LocalCloudDriver driver = new LocalCloudDriver(
                serviceManager, services, groups, events, players, playerManager, wrappers, config, serviceChannels,
                store, database, profiles, gateway);

        // Network commands run from here too, as the console, so an operator is
        // never unable to use a command a module added just because they are
        // at the node rather than in game.
        commands.fallback(new CommandManager.Fallback() {
            @Override
            public boolean dispatch(String name, String[] args) {
                return gateway.executeConsole(name, args);
            }

            @Override
            public java.util.Map<String, String> describe() {
                return gateway.describeCommands();
            }

            @Override
            public java.util.List<String> complete(String name, String[] args) {
                return gateway.suggestConsole(name, args);
            }
        });

        // Proxies show the whole cloud's online count, not just their own.
        events.subscribe(PlayerConnectEvent.class, event -> gateway.onlineChanged());
        events.subscribe(PlayerDisconnectEvent.class, event -> gateway.onlineChanged());
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

        // Every service is told about every other one as it changes, so a
        // plugin's view of the cloud is live and its own event bus fires the
        // same lifecycle events this one does. Sign walls and matchmaking are
        // built on exactly this.
        events.subscribe(ServiceCreatedEvent.class, event -> pushServiceUpdate(event.service(), false));
        events.subscribe(ServiceStateChangedEvent.class, event -> pushServiceUpdate(event.service(), false));
        events.subscribe(ServiceUpdatedEvent.class, event -> pushServiceUpdate(event.service(), false));
        events.subscribe(ServiceRemovedEvent.class, event -> pushServiceUpdate(event.service(), true));

        // A server that has gone must be dropped from every proxy, or players
        // keep being routed to a port with nothing behind it.
        events.subscribe(ServiceRemovedEvent.class, event -> {
            if (event.service().type() == ServiceType.SERVER) {
                serviceChannels.broadcastToProxies(services,
                        new ServiceAvailabilityPacket(event.service(), false));
            }
        });

        events.subscribe(ServiceRemovedEvent.class, event -> {
            autoScaler.forget(event.service().uniqueId());
            backups.forget(event.service().uniqueId());
        });

        NodePacketHandler packetHandler = new NodePacketHandler(
                config, serviceManager, services, groups, wrappers, events, console,
                serviceChannels, players, playerManager, driver);
        packetHandler.onTemplateChanged(this::templateChanged);
        driver.cluster(() -> cluster);
        if (cluster != null) {
            // Every client learns every node, so it can find the next leader.
            packetHandler.clusterView(cluster::term, cluster::clientEndpoints);
        }
        server.start(config.bindAddress(), config.port(), packetHandler);

        LOGGER.info("Services connect back to {}:{}", config.connectAddress(), config.port());
        LOGGER.info("Wrapper secret: {}", config.secret());
        LOGGER.info("API secret: {}{}", config.apiSecret(),
                config.apiReadOnly() ? " (read-only)" : "");
        LOGGER.info("Waiting for a wrapper to connect. Type 'help' for commands.");
        LOGGER.info("Service consoles are hidden until you 'attach <service>'.");

        // After the driver is bound and the listener is up: a module's first
        // act is typically to query the cloud or open a port of its own, and
        // neither works before this point.
        modules = new ModuleManager(workingDirectory.resolve("modules"), driver, events);
        commands.register(new ModulesCommand(modules));
        modules.loadAll();

        scheduler.scheduleWithFixedDelay(
                new ProvisioningTask(groups, services, serviceManager, wrappers, backoff, autoScaler, rollouts),
                2, 1, TimeUnit.SECONDS);

        // Written behind rather than on every change: the store holds state
        // that tolerates losing a few seconds, and a write per increment would
        // turn a busy counter into a busy disk.
        scheduler.scheduleWithFixedDelay(store::flush, 5, 5, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(gateway::tick, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(backups::tick, 30, 30, TimeUnit.SECONDS);

        registerLeaderCommands();
        leading = true;
    }

    // ------------------------------------------------------------- cluster

    /**
     * The client port on a follower: anyone connecting is pointed at the
     * leader, or asked to retry while an election is under way. Wrappers and
     * services follow that on their own.
     */
    private void startStandbyGateway() throws InterruptedException {
        standby = new NetworkServer(PacketRegistry.standard());
        standby.start(config.bindAddress(), config.port(), new dev.sirius.cloud.protocol.connection.PacketHandler() {
            @Override
            public void onPacket(dev.sirius.cloud.protocol.connection.NetworkChannel channel,
                                 dev.sirius.cloud.protocol.packet.Packet packet) {
                if (!(packet instanceof dev.sirius.cloud.protocol.packet.impl.HandshakePacket)) {
                    return;
                }
                java.util.List<String> endpoints = cluster.clientEndpoints();
                channel.send(cluster.leaderClientEndpoint()
                        .map(leader -> HandshakeResponsePacket.redirect(leader, endpoints))
                        .orElseGet(() -> HandshakeResponsePacket.retryLater(
                                "the cluster is electing a leader", endpoints)));
                channel.close();
            }
        });
    }

    private synchronized void stopStandbyGateway() {
        if (standby != null) {
            standby.close();
            standby = null;
        }
    }

    /**
     * Leadership is gone - the majority is out of reach, or another node won
     * a newer term. The control plane stops at once, without touching a
     * single service: they keep running, and their wrappers reconnect to
     * whichever node leads next.
     *
     * <p>The process then exits with {@link #RESTART_AS_FOLLOWER}, and the
     * start scripts start it again as a follower. Starting afresh, rather
     * than resetting every registry in place, is what guarantees nothing of
     * the old leadership lingers.
     */
    private void stepDown(String reason) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        LOGGER.warn("Stepping down as leader: {}", reason);
        LOGGER.warn("Services keep running. This node restarts as a follower.");
        haltControlPlane();
        if (cluster != null) {
            cluster.close();
        }
        exitForRestart();
    }

    /** What the process should exit with once {@link #start()} returns. */
    public int exitCode() {
        return exitCode;
    }

    // ------------------------------------------------------------- updates

    /** This build's version, from the jar manifest; null when run outside a built jar. */
    private static final String BUILD = CloudNode.class.getPackage().getImplementationVersion();

    private UpdateService updates;
    private volatile Version pendingUpdate;
    private volatile long updateNotBefore;
    private volatile String updateWaitingFor = "";
    private final ScheduledExecutorService updateTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sirius-update-install");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Checks for new releases and installs them by restarting - which a node
     * can do at any time, because services keep running while it is away and
     * their wrappers reconnect to it. In a cluster, one node at a time and
     * followers before the leader.
     */
    private void startUpdates() {
        Optional<Version> current = Version.parse(BUILD);
        if (current.isEmpty()) {
            LOGGER.debug("Not a released build; automatic updates are off");
            return;
        }
        Updater updater = new Updater(workingDirectory, current.get(), config.updates(), new Updater.Layout() {
            @Override
            public String mainAsset() {
                return "cloud-node.jar";
            }

            @Override
            public Optional<String> target(String asset) {
                if (asset.equals("cloud-node.jar")) {
                    return Optional.of(asset);
                }
                // Only modules that are installed: deleting one is how it is
                // turned off, and an update must not quietly turn it back on.
                if (asset.startsWith("cloud-module-") && asset.endsWith(".jar")
                        && Files.exists(workingDirectory.resolve("modules").resolve(asset))) {
                    return Optional.of("modules/" + asset);
                }
                return Optional.empty();
            }
        });
        updates = new UpdateService(updater, config.updates(), version -> {
            if (config.updates().mode() == UpdateSettings.Mode.AUTO && UpdateService.hasLauncher()) {
                pendingUpdate = version;
            }
        });
        commands.register(new UpdateCommand(updates, this::installUpdateNow));
        updates.start();
        updateTimer.scheduleWithFixedDelay(this::tryInstallUpdate, 30, 30, TimeUnit.SECONDS);
    }

    /** Restarts into a staged update once doing so cannot cost the network anything. */
    private void tryInstallUpdate() {
        Version version = pendingUpdate;
        if (version == null || shuttingDown.get()) {
            return;
        }
        ClusterMember member = cluster;
        if (member == null) {
            restartForUpdate(version);
            return;
        }
        ClusterMember.Status status = member.status();
        if (!status.members().stream().allMatch(ClusterMember.MemberStatus::connected)) {
            waitingForUpdate("every cluster member to be reachable");
            return;
        }
        if (member.isLeader()) {
            // Followers first: once they run the new version, one of them
            // takes over, and the next leader is always an updated node.
            boolean followersUpdated = member.peerBuilds().size() >= status.members().size() - 1
                    && member.peerBuilds().values().stream()
                    .allMatch(build -> Version.parse(build).map(peer -> peer.compareTo(version) >= 0).orElse(false));
            if (!followersUpdated) {
                waitingForUpdate("the followers to update first");
                return;
            }
        } else {
            // Spread out, so two followers do not restart at the same moment.
            if (updateNotBefore == 0) {
                updateNotBefore = System.currentTimeMillis() + java.util.concurrent.ThreadLocalRandom.current()
                        .nextLong(0, 180_000);
            }
            if (System.currentTimeMillis() < updateNotBefore) {
                return;
            }
        }
        restartForUpdate(version);
    }

    private void waitingForUpdate(String what) {
        if (!what.equals(updateWaitingFor)) {
            updateWaitingFor = what;
            LOGGER.info("SiriusCloud {} is ready to install; waiting for {}", pendingUpdate, what);
        }
    }

    /** For 'update now': download if needed, then restart straight away. */
    private String installUpdateNow() {
        if (!UpdateService.hasLauncher()) {
            return "This node was not started with the start scripts, which are what install updates.";
        }
        String result = updates.check();
        Optional<Version> staged = updates.staged();
        if (staged.isEmpty()) {
            return result;
        }
        Thread.ofPlatform().name("sirius-update-now").start(() -> restartForUpdate(staged.get()));
        return "Restarting to install " + staged.get() + ". Services keep running.";
    }

    private void restartForUpdate(Version version) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("Restarting to install SiriusCloud {}. Services keep running.", version);
        haltControlPlane();
        if (cluster != null) {
            cluster.handOver();
            cluster.close();
        }
        exitForRestart();
    }

    /** Closes what is left and exits with the code that makes the start scripts start this node again. */
    private void exitForRestart() {
        if (updates != null) {
            updates.close();
        }
        updateTimer.shutdownNow();
        if (console != null) {
            console.close();
        }
        directoryLock.close();
        exitCode = RESTART_AS_FOLLOWER;
        Thread.ofPlatform().name("sirius-restart").start(() -> System.exit(RESTART_AS_FOLLOWER));
    }

    /** Stops acting as the control plane, leaving every service running. */
    private void haltControlPlane() {
        leading = false;
        scheduler.shutdownNow();
        stopStandbyGateway();
        if (modules != null) {
            modules.disableAll();
        }
        // Wrappers and services lose this connection and go looking for the
        // next leader; nothing on their side stops.
        server.close();
        if (profiles != null) {
            profiles.flushAll();
        }
        if (store != null) {
            store.flush();
        }
        if (database != null) {
            database.close();
        }
    }

    public ClusterMember cluster() {
        return cluster;
    }

    /**
     * A wrapper saw a template file change.
     *
     * <p>Rolled out where the group asks for it, reported where it does not -
     * otherwise an edit reaches only services started from now on, and a
     * long-lived group runs on the old files for days without anybody noticing.
     */
    private void templateChanged(String groupName) {
        if (TemplateChangedPacket.GLOBAL.equalsIgnoreCase(groupName)) {
            groups.all().stream()
                    .filter(group -> group.type() == ServiceType.SERVER)
                    .filter(dev.sirius.cloud.api.group.ServiceGroup::rolloutOnTemplateChange)
                    .forEach(group -> rollouts.start(group.name(), "the global template changed"));
            return;
        }
        groups.byName(groupName).ifPresent(group -> {
            if (!group.rolloutOnTemplateChange()) {
                LOGGER.info("The template of {} changed. 'rollout {}' applies it to running services.",
                        group.name(), group.name());
                return;
            }
            rollouts.start(group.name(), "its template changed")
                    .ifPresent(reason -> LOGGER.debug("Template of {} changed, not rolling: {}",
                            group.name(), reason));
        });
    }

    private void pushServiceUpdate(ServiceInfo service, boolean removed) {
        serviceChannels.broadcastToServices(new ServiceUpdatePacket(service, removed), null);
    }

    /**
     * Fails early and readably if the listen port is taken.
     *
     * <p>The check is advisory: something could claim the port between here and
     * the real bind. It exists to turn the overwhelmingly common case into a
     * sentence rather than a stack trace, not to make the race impossible.
     */
    private void requirePortAvailable(String host, int port) throws IOException {
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new java.net.InetSocketAddress(host, port));
        } catch (IOException exception) {
            throw new IOException("Port " + port + " on " + host + " is already in use. "
                    + "Another node is probably running, or something else has taken the port. "
                    + "Stop it, or change 'port' in node/config.json.");
        }
    }

    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        // A clustered node leaving is not the network going down: services
        // keep running and another node takes over. That holds from the moment
        // clustering is configured, so restarting into cluster mode after
        // 'cluster init' does not take the network down either.
        if (cluster != null || config.cluster().enabled()) {
            LOGGER.info("Leaving the cluster. Services keep running; another node takes over.");
            haltControlPlane();
            if (cluster != null) {
                cluster.handOver();
                cluster.close();
            }
            if (console != null) {
                console.close();
            }
            directoryLock.close();
            LOGGER.info("Goodbye.");
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

        // After the services have stopped, so every player's disconnect has
        // been counted; then whatever sessions remain are closed out here.
        if (profiles != null) {
            profiles.flushAll();
        }
        if (store != null) {
            store.flush();
        }
        if (database != null) {
            database.close();
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

    /** Commands that make sense on any node, follower or leader. */
    private void registerBaseCommands() {
        commands.register(new HelpCommand(commands));
        commands.register(new MigrateCommand(migrator));
        commands.register(new ClusterCommand(config, workingDirectory, console, () -> cluster));
        commands.register(new ShutdownCommand(this));
    }

    /** Commands that act on the network, registered once this node runs the control plane. */
    private void registerLeaderCommands() {
        commands.register(new SetupCommand(setup));
        commands.register(new ServicesCommand(services));
        commands.register(new PlayersCommand(players));
        commands.register(new PlayerCommand(players, playerManager, services, groups));
        commands.register(new BroadcastCommand(playerManager));
        commands.register(new GroupsCommand(groups, services));
        commands.register(new StartCommand(groups, serviceManager));
        commands.register(new StopCommand(services, serviceManager));
        commands.register(new RestartCommand(services, serviceManager));
        commands.register(new RolloutCommand(rollouts, groups));
        commands.register(new BackupCommand(services, backups));
        commands.register(new EditCommand(groups));
        commands.register(new MaintenanceCommand(groups, commands));
        commands.register(new ReloadCommand(groups));
        commands.register(new ExecuteCommand(services, serviceManager));
        commands.register(new AttachCommand(services, serviceManager, console));
        commands.register(new VersionsCommand(versionCatalogs, config.minimumPaperVersion()));
        commands.register(new InfoCommand(config, services, wrappers));
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
