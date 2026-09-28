# SiriusCloud

A Minecraft server cloud: a control plane that decides what runs where, machine
agents that run it, and an API that behaves identically whether you call it from
the node or from inside a running server.

Runs on **Linux and Windows**.

| Jump to | |
|---|---|
| [Architecture](#architecture) | what the pieces are and why |
| [Building](#building) and [Running](#running) | standing one up |
| [Updating](#updating) | new versions, and migrating your files |
| [Clustering](#clustering) | several nodes, one leader, failover |
| [Service consoles](#service-consoles) | `attach`, and why crashes are special |
| [The proxy](#the-proxy) | dynamic registration, slots, forwarding |
| [Players](#players) | the cloud-wide player layer |
| [Scaling and restarts](#scaling-and-restarts) | autoscaling, rolling restarts, rollouts |
| [Server software](#server-software), [Isolation](#isolation), [Backups](#backups) | Purpur, Folia, Fabric; Docker; world backups |
| [Persistence](#persistence) | databases, player profiles, key-value store |
| [The API and the panel](#the-api-and-the-panel) | HTTP and the web panel |
| [In game](#in-game) | `/cloud` and notifications |
| [Network features](#network-features) | maintenance, parties, friends, bans, queues, metrics, Discord |
| [LuckPerms](#luckperms) and [Permissions](#permissions) | pick one |

| | Java |
|---|---|
| Node and wrapper | **21+** |
| Minecraft services | **25+** (Minecraft 26.x refuses to start on anything lower) |

These are genuinely different requirements, so the wrapper does **not** run
services on its own JVM. It locates a qualifying JDK on the machine at startup
and refuses to start services with install instructions if there isn't one,
rather than spawning servers that exit instantly.

```bash
sudo pacman -S jdk25-openjdk        # Arch / CachyOS
sudo apt install openjdk-25-jdk     # Debian / Ubuntu
```

Windows and macOS: [Temurin 25](https://adoptium.net/temurin/releases/?version=25),
or `brew install openjdk@25`. Pin `javaExecutable` in `wrapper/config.json` to
skip detection, or set it per group to run different Minecraft versions on
different JDKs.

---

## Architecture

```
                     ┌──────────────┐
                     │     NODE     │  owns all state: groups, services,
                     │  (control)   │  ports, scheduling, the console
                     └───────┬──────┘
                    :1420    │  Netty, length-prefixed binary frames
          ┌─────────────────┼─────────────────┐
          │                 │                 │
   ┌──────┴──────┐   ┌──────┴──────┐   ┌──────┴───────┐
   │   WRAPPER   │   │   WRAPPER   │   │   SERVICE    │
   │  machine A  │   │  machine B  │   │ (Paper + our │
   │             │   │             │   │   plugin)    │
   └──────┬──────┘   └─────────────┘   └──────────────┘
          │ spawns
   ┌──────┴──────────────────┐
   │ Paper  Paper  Paper ... │
   └─────────────────────────┘
```

| Module | Responsibility |
|---|---|
| `cloud-api` | Public contract. **Zero dependencies**, because this is what third parties compile against. |
| `cloud-protocol` | Packet definitions, codec, Netty transport. |
| `cloud-driver` | The `CloudDriver` implementation over the network, plus the event bus. |
| `cloud-node` | State, scheduling, provisioning loop, JLine console. |
| `cloud-wrapper` | Process spawning, templates, jar downloads, console piping. |
| `cloud-plugins/paper` | In-service bridge. Reports readiness so `RUNNING` means something. |
| `cloud-plugins/velocity` | Proxy bridge. Registers backends as they appear, routes players. |
| `cloud-modules/rest` | HTTP API and web panel, loaded as a module rather than built in. |
| `cloud-modules/notify` | Announces cloud events to staff in game. Also a module. |
| `cloud-modules/permissions` | Owns the cloud's permission groups and ranks. |
| `cloud-plugins/permissions` | Separate, opt-in plugin that applies them on a server. |

The design rule everything follows: **all feature code goes through
`CloudDriver`.** The node binds a local implementation backed by its own
registries; wrappers and plugins bind a remote one backed by packets. Same
interface, so a feature is written once and runs on either side.

### Losing the control connection changes nothing

A wrapper that loses the node **does not stop its services**. A network blip
must not disconnect players, so the processes keep running and the wrapper
reconnects underneath them.

That leaves the node holding records it cannot act on, which is fine, and it
must not treat them as gone: releasing their names and ports would have the
provisioning loop start replacements onto ports that are still bound. So the
node keeps them, and the wrapper sends a **snapshot of everything it is
actually running** immediately after each handshake:

- Services the wrapper no longer reports really did die while nobody could see
  them, and are dropped.
- Services the node has never heard of are **adopted**, their names and ports
  re-reserved, rather than duplicated. That is a node that restarted under a
  machine which kept running, and starting a second `Lobby-1` beside the live
  one is exactly what this prevents.

A wrapper is not schedulable until that snapshot arrives; until then the node
cannot tell an idle machine from one already running what it is about to start.
Same idea as the proxy's player snapshot, one layer down.

---

## Building

```bash
./gradlew dist
```

The build needs a **Java 25 JDK available to Gradle** as well as Java 21:
Velocity 4's API is compiled for 25, so the proxy plugin is built against it.
Gradle finds the toolchain itself. You need Java 25 to run servers anyway.

Output lands in `build/dist/`:

```
build/dist/
├── start-node.sh / start-node.bat
├── start-wrapper.sh / start-wrapper.bat
├── node/
│   ├── cloud-node.jar
│   └── modules/                             loaded at node startup
│       ├── cloud-module-rest.jar
│       ├── cloud-module-notify.jar
│       └── cloud-module-permissions.jar
└── wrapper/
    ├── cloud-wrapper.jar
    ├── plugins/                             injected into every service
    │   ├── cloud-plugin-paper.jar
    │   └── cloud-plugin-velocity.jar
    └── optional-plugins/                    opt in per group, see Permissions
        └── cloud-plugin-permissions.jar
```

Modules are shipped enabled and the node loads whatever is in `node/modules/`.
Delete a jar from there to turn that feature off.

---

## Running

**1. Start the node.**

```bash
cd build/dist && ./start-node.sh
```

First run asks you everything it needs:

```
── Node ──
  Name for this node [node-1]: test-node

  This machine has 31194MB (30.5GB) of RAM.
  The node will refuse to start services beyond this budget.
  How much RAM may this node use for services, in MB [28672]: 3072

  Port wrappers and services connect to [1420]: 1420
  Address to listen on (0.0.0.0 accepts remote wrappers) [0.0.0.0]:
  Address wrappers should dial back (this machine's IP if remote) [127.0.0.1]:
  Oldest Paper version to list in 'versions' [1.21.1]:

── First group ──
  Create a Lobby group now? [Y/n]: y
  Group name [Lobby]: Lobby
  Maximum RAM per server, in MB [1024]: 2048
  Starting RAM per server, in MB (same as maximum is usual) [2048]:
  Servers to keep online at all times [1]: 2
  Maximum servers of this group [6]: 8
  Max players per server [50]: 80
  Minecraft version ('latest' = newest stable) [latest]:
  First port for these servers [41000]:
  Keep each server's files between restarts (static)? [y/N]: n
```

Memory defaults are read from the machine rather than guessed, and the port
suggestion avoids colliding with groups you already have. It warns if the
servers you asked for would exceed the budget you just set. Otherwise that is
discovered later as a group that accepts its configuration and then refuses to
start.

Everything is re-runnable: `setup` creates another group, `setup node` revisits
the node settings. Declining is remembered rather than re-asked on every start.
With no terminal to ask on (systemd, a container without `-t`, piped stdin) it
uses defaults instead of hanging on a prompt that can never be answered.

**2. Start the wrapper.** It asks its own questions, including the secret the
node just printed. No hand-editing JSON.

```bash
cd build/dist && ./start-wrapper.sh
```

```
── Wrapper setup ──
  Name for this wrapper [wrapper-1]: wrapper-a

  The node prints its address and secret when it starts.
  Node address [127.0.0.1]:
  Node port [1420]:
  Node secret: f61a2e1e-a302-4460-8dd3-b62cc5a35c2d

  This machine has 31194MB (30.5GB) of RAM.
  How much may this machine use for servers, in MB [28672]: 3072

  JVMs found: Java 21, Java 25
  Minecraft 26.x and newer needs Java 25 or above.
  Minimum Java version for servers [25]:
  Servers will run on Java 25 (25.0.4.1) at /usr/lib/jvm/java-25-openjdk/bin/java.
```

It lists the JVMs actually installed, so the Java question is a choice rather
than a guess, and offers to point at one by path if none qualify.

A node and a wrapper each take a lock on their working directory. Two wrappers
sharing one would be genuinely destructive: the second clears `local/running/`
as part of its stale-directory cleanup, deleting the files of servers the first
still has running.

**3. Watch it work.** The node's provisioning loop sees `Lobby` below its
`minServiceCount` and starts one automatically. Or drive it by hand:

```
sirius@node> start Lobby 2
sirius@node> services
sirius@node> exec Lobby-1 say hello
sirius@node> stop Lobby-1
```

| Command | What it does |
|---|---|
| `help` | Lists every command |
| `setup [group\|node]` | Configures a group, or the node itself, by question |
| `services` / `ls` | Every known service with state, address, uptime |
| `groups` | Configured groups and how many of each are online |
| `start <group> [count]` | Starts services |
| `stop <service\|group\|all> [--force]` | Graceful stop; `--force` kills |
| `restart <service> [--force]` | Stops it and starts a replacement of its group |
| `rollout [<group> [cancel]]` | Rolling restart of a group, or the status of running ones |
| `backup <service>` | Backs up a running service now |
| `edit <group> <field> <value>` | Changes a group setting without editing JSON |
| `maintenance <group> [on\|off]` | Stops the node provisioning a group, or resumes |
| `reload` | Re-reads `node/groups/` from disk |
| `modules` | Lists loaded modules; `enable`/`disable` one |
| `players [service]` | Everyone online, across every proxy |
| `player <name> …` | Info, or `send`/`msg`/`kick` |
| `broadcast <message>` | Message every player on every proxy |
| `exec <service> <command>` | Runs a single command inside a service |
| `attach <service>` | Opens that service's console (see below) |
| `versions [paper\|purpur\|folia\|fabric\|velocity] [--all]` | Versions available to groups |
| `info` | Node status and connected wrappers |
| `migrate` | Data version, migration history and backups |
| `update [check\|now]` | Version, and new releases; see [Updating](#updating) |
| `cluster [status\|init\|join\|add\|remove\|promote]` | Runs several nodes as one; see [Clustering](#clustering) |
| `shutdown` | Stops everything, then the node |

---

## Updating

### Automatically

Nodes and wrappers check GitHub for new releases every 6 hours, download them,
check every file against the release's `SHA256SUMS`, and install them by
restarting:

| | Installs by itself | Servers |
|---|---|---|
| **Node** | Straight away. In a cluster, one node at a time: followers first, spread over a few minutes, the leader last, and only while every member is reachable. | Keep running. Wrappers reconnect and are adopted, as after any node restart. |
| **Wrapper** | Only while it runs no servers, since they are its child processes. Otherwise on its next restart. | Stop while the wrapper restarts, so it waits for you. |

The wrapper also updates the plugins it gives servers (`plugins/`,
`optional-plugins/`); servers get them the next time they start. Modules are
updated if they are installed: one you deleted to turn it off stays deleted.

This needs the **start scripts**: they install a downloaded update before
starting, and start the process again when it asks to be restarted. Started
any other way, updates are downloaded but not installed.

```
sirius@node> update
Version    : 1.0.4
Downloaded : 1.0.5
Last check : 1.0.5 downloaded, installs on the next start
Launcher   : start script, so updates can be installed
```

`update check` looks now, `update now` installs straight away. Settings, in
the node's and each wrapper's `config.json`:

```json
"updates": {
  "mode": "auto",
  "repository": "SiriusCloud-MC/SiriusCloud-MC",
  "checkEveryHours": 6,
  "preReleases": false
}
```

`mode` is `auto`, `download` (install on the next manual restart), `notify`
(only say so) or `off`. A build made from source reports `1.0.0-SNAPSHOT`, so
it moves to the first proper release; set `off` to keep a build of your own.

**Publishing a release:** push a version tag, and the release workflow builds,
tests and publishes every jar with `SHA256SUMS` and a full zip:

```bash
git tag v1.0.4
```

```bash
git push origin v1.0.4
```

A release without `SHA256SUMS` is never installed, so releases made by hand
from a zip are left alone.

### By hand

1. Stop the node and the wrappers (`shutdown`).
2. Replace the program files with the new build's, and nothing else:

   | From `build/dist/` | Replaces |
   |---|---|
   | `node/cloud-node.jar` | the node |
   | `node/modules/*.jar` | the modules |
   | `wrapper/cloud-wrapper.jar` | each wrapper |
   | `wrapper/plugins/*.jar` | the plugins injected into every service |
   | `wrapper/optional-plugins/*.jar` | copy these into your templates again, wherever you put them |

   Keep `config.json`, `groups/`, `local/` and each module's folder: that is
   your data.
3. Start the node and the wrappers again.

On startup each one brings its files up to date with the new build before
reading any of them, and says what it changed:

```
[Migration] Updating this install's files: 1 migration(s), from version 0 to 1
[Migration]   1. Give the starter admin group its new 'Admin | Name' prefix
[Migration]        admin: prefix '&c[Admin] ' -> '&cAdmin &8| &c'
[Migration] The original files are in local/migration-backups/2026-09-27_20-40-02-from-v0
```

| | |
|---|---|
| **Backups** | Every file is copied to `local/migration-backups/` before it is changed. |
| **Failures** | A migration that fails has its changes undone, and the node or wrapper stops with the reason. Ones that already succeeded stay applied, so the next start carries on from there. |
| **Your changes win** | Migrations only replace old defaults. Anything you set yourself is left alone. |
| **Going back** | An older build refuses to start on data a newer one has migrated, because it would silently drop what it does not understand. Restore the backup, or start it with `-Dsiriuscloud.allowDowngrade=true` if you are sure. |

`migrate` on the node console shows the install's data version, every
migration applied and when, and where the backups are. The version is kept in
`local/data-version.json`.

**Adding a migration** (for contributors): implement `Migration` with the next
number and add it to the end of `NodeMigrations` or `WrapperMigrations`. Change
files only through the `MigrationContext` you are given, which does the backups
and the rollback. Never renumber or remove one that has shipped.

---

## Clustering

Several nodes can run as one: one leads and runs the control plane, the others
keep a live copy of its data, and if the leader dies one of them takes over.
Services keep running throughout. Their wrappers and plugins find the new
leader on their own, and it adopts them exactly as a restarted node would.

```
 node-a (leader)  ──replicates──►  node-b (follower)
       │          ──replicates──►  node-c (follower)
       │
   wrappers, servers, proxies: connected to the leader, and know all three
```

### Setting one up

**1. On the node you have now**, which keeps its groups and data:

```
sirius@node> cluster init
```

It asks for the address the other nodes reach it on and a port for traffic
between nodes (default: this node's port + 1), prints a cluster secret, and
asks you to restart the node. Services keep running while it restarts.

**2. On each new node**, answer yes to the first question of first-time setup,
*Is this node joining an existing SiriusCloud cluster?*, and enter the secret.
An existing node can join with `cluster join`, which replaces its own groups and
data with the cluster's. Start it, and it prints the one line to run next:

```
Waiting to be added to a cluster. On its leader, run: cluster add node-b 10.0.0.2:1421 10.0.0.2:1420
```

**3. On the leader**, run that line. The new node receives the groups, every
module's data, the database (if it is JSON files) and the shared settings, and
from then on every change as it happens.

```
sirius@node> cluster status
This node : node-a (leader)
Leader    : node-a   term 1
Data      : revision 42
Majority  : 2 of 3
Members   :
  node-a   10.0.0.1:1421   clients 10.0.0.1:1420   this node
  node-b   10.0.0.2:1421   clients 10.0.0.2:1420   connected, heard 120ms ago, revision 42
  node-c   10.0.0.3:1421   clients 10.0.0.3:1420   connected, heard 120ms ago, revision 42
```

| Command | What it does |
|---|---|
| `cluster` | Status: roles, term, who is reachable, how up to date each node is |
| `cluster init` | Starts a cluster with this node as its first member |
| `cluster join` | Makes this node join a cluster when it next starts |
| `cluster add <name> <cluster addr> <client addr>` | On the leader: adds a member (it must be running) |
| `cluster remove <name>` | On the leader: removes a member |
| `cluster promote` | Makes this node leader without a majority, see below |

### How many nodes

A leader needs a **majority** of the members behind it: 2 of 3, 3 of 5. That is
what makes two leaders impossible, since two majorities always share a node.
It also means:

| Members | Survives losing |
|---|---|
| 1 | nothing (a normal node) |
| 2 | nothing automatically: each needs the other |
| 3 | 1 node |
| 5 | 2 nodes |

**Use three for automatic failover.** With two, if one dies the other waits;
`cluster promote` on the survivor makes it lead anyway. Only do that when the
other really is down: if it is only cut off, both would be running services.

### What it takes care of

| | |
|---|---|
| **Failover** | A dead leader is replaced within about 4–6 seconds, and wrappers reconnect a few seconds after that. Tested by killing the leader outright. |
| **No split brain** | A leader that loses contact with the majority stops within 3 seconds, before the others can have elected a replacement, and restarts as a follower. |
| **Stale leaders are ignored** | Every client remembers the newest term it has seen and refuses a node with an older one, so a leader that was replaced cannot give orders. |
| **A flaky node cannot disrupt** | A node that is merely cut off cannot win an election, so it cannot unseat a healthy leader when it comes back. |
| **Only up-to-date nodes lead** | A node only votes for a candidate whose data is at least as new as its own, and whose build can read it. |
| **Clean hand-over** | `shutdown` on the leader hands over to the most up-to-date follower at once, rather than waiting out a timeout. |

### What is replicated

`groups/`, every module's folder in `modules/`, `local/database/`,
`local/store.json` and the data version. Also the settings that describe the
network rather than one machine: the secrets, the memory budget, the database
settings and the member list. A change reaches the followers about a second
after it is saved; the key-value store saves every 5 seconds.

Not replicated: jars (each node's program, updated per node), and each node's
name, addresses and ports. Writes are not held until followers confirm them,
so a leader that crashes can take its last second or so of changes with it.
With a real database (MySQL, PostgreSQL, MongoDB) every node uses the same
one and nothing there is lost; that is the recommended setup for a cluster.

### Good to know

- **In a cluster, `shutdown` leaves the cluster, not the network.** Services
  keep running and another node takes over. To take everything down, stop the
  wrappers.
- **Updating a cluster:** update and restart the followers one at a time, then
  the leader, which hands over as it goes. Nodes only vote for a candidate on a
  build at least as new as their own, so once the followers are updated the
  next leader is always an updated one. The new leader migrates the files as
  it takes over.
- **Use the start scripts.** A leader that has to step down exits with code
  75, and `start-node.sh` / `start-node.bat` start it again as a follower.
  Started any other way, it stays stopped until you start it.
- **`connectAddress` must be reachable** from every wrapper, since wrappers are
  handed every node's address. `127.0.0.1` only works if everything runs on
  one machine.
- **Modules, and with them the REST API and panel, run on the leader.** Their
  address moves with the leader.
- **Servers started before clustering** only know the one node they were
  started with, and reconnect to it alone. Anything started afterwards knows
  every node.
- Each node keeps its own term, vote and data revision in
  `local/cluster/state.json`; each wrapper keeps the nodes and the newest term
  it has seen in `local/cluster.json`. Deleting a wrapper's file is safe.

---

## Service consoles

**Service output never appears in the node or wrapper console.** With more than
a couple of servers running, interleaved output from all of them buries the
node's own logs and is unreadable anyway. So the wrapper keeps a bounded ring
buffer per service locally and sends *nothing* over the network until somebody
asks: not "sends it and the node discards it", genuinely nothing.

To open one:

```
sirius@node> attach Lobby-1

── attached to Lobby-1 ── type #detach (or press Ctrl+C) to return ──
[12:04:11 INFO]: Starting minecraft server version 1.21.4
[12:04:19 INFO]: Done (8.102s)! For help, type "help"
── end of 47 buffered line(s), now live ──
Lobby-1> say hello everyone
[12:05:02 INFO]: [Server] hello everyone
Lobby-1> #detach
── detached from Lobby-1 ──
```

While attached the console *is* that server's terminal: every line you type is
forwarded to it, the prompt shows its name, and the recent backlog is replayed
first so you see why it is in the state it is in.

- `#detach`, `#exit`, `#quit` or `#back` return to the node. The `#` prefix
  cannot collide with a Minecraft command.
- **Ctrl+C detaches** rather than shutting the node down, because while
  attached it reads as "leave this server", and killing the cloud instead
  would be a nasty surprise. It still shuts down when you are not attached.
- If the service stops while you are attached, the console detaches itself.
  That happens over the event bus, not by a special case in the scheduler.

`exec <service> <command>` remains for firing a single command without attaching.

**Crashes are the exception.** A service that dies unasked pushes the tail of
its console to the node unprompted, and it is printed whether or not anyone is
attached:

```
[ERROR] [Network] Lobby-1 exited unexpectedly with code 1. Last output:
[ERROR] [Network]   | Minecraft 26.1 and newer requires running the server with Java 25 or above.
```

A service that fails during startup is dead before anyone could attach to it, so
without this the operator sees `exited with code 1` and the actual reason sits
in a buffer nobody will ever read.

Groups that keep failing are retried on a growing delay (5s, 15s, 30s, 60s,
120s) rather than once a second, and say so after three consecutive failures.
The delay caps instead of giving up, so a transient cause still recovers on its
own. Reaching `RUNNING` clears the penalty.

---

## The proxy

Players connect to a Velocity proxy; the proxy connects them onward to a
server. `velocity.toml` lists **no servers at all**. The node tells the proxy
about each backend as it becomes reachable, and tells it to forget one the
moment it goes away:

```
[siriuscloud]: Registered Lobby-1 at 127.0.0.1:41000 (lobby)
[siriuscloud]: Unregistered Lobby-1
[siriuscloud]: Registered Lobby-1 at 127.0.0.1:41000 (lobby)
```

That is the whole reason to run a cloud rather than a fixed set of servers: a
static server list would be wrong within seconds of being written. A proxy that
starts late or restarts is sent every server already running, so it converges on
the same state as one that was there from the beginning.

- `/server` lists what is reachable and moves you. Velocity's own only knows
  servers from `velocity.toml`, and this cloud deliberately lists none there.
  A group name resolves to its least loaded member, so `/server Lobby` works
  without knowing whether you want `Lobby-2` or `Lobby-5`.
- Groups marked **`fallback`** are lobbies. Joining players land on the
  least-loaded one, and `/hub` (aliases `/lobby`, `/l`) sends them back.
  Least-loaded rather than random, so a newly started lobby actually takes
  load instead of being ignored until chance finds it.
- A server that disappears takes its players with it: they are moved to a
  lobby with a message, or disconnected with a reason if none is available,
  rather than left on a dead connection until they time out.

### Slots follow the servers

`show-max-players` in `velocity.toml` is a fixed number, which is wrong the
moment the cloud scales: start a second lobby and the proxy still advertises
the old figure. The proxy answers each server-list ping with the sum of what
its registered servers actually hold, so the slot count tracks capacity with
nothing to edit:

```
1 lobby  (50 slots each)  ->  max=50
2 lobbies                 ->  max=100
back to 1 lobby           ->  max=50
```

The count is also **enforced**, not just advertised: Velocity never applies
`show-max-players` on its own, so a list reading 50/50 would happily take the
fifty-first player and the figure would be decoration. Once capacity is reached
logins are refused, and `siriuscloud.joinfull` bypasses that so staff can still
get in to deal with whatever filled the cloud up.

The total is recomputed per ping rather than cached, because it is derived from
a map that changes underneath, and a stale cached total is the exact bug this
replaces. With nothing registered it falls back to the configured figure,
since a server list reading `0/0` looks broken rather than empty.

Services report their occupancy and capacity back to the node too, so
`services` shows real numbers instead of `0/N` for everything.

### Player forwarding is secured

Backends run `online-mode=false` so the proxy can authenticate on their behalf.
On its own that would let anyone who reaches a backend port connect as any
player, so **Velocity modern forwarding is configured on both ends
automatically**: the node generates a forwarding secret, the wrapper writes it
to the proxy's `forwarding.secret` and into each backend's
`config/paper-global.yml`. A connection without a valid signature is refused.

That secret is separate from the cloud secret wrappers authenticate with:
it reaches every service directory on every machine, while the cloud secret
never leaves the wrappers.

Defence in depth is still worth one config line. Backends listen on every
interface by default, because narrowing that silently would break a proxy on
another machine. If the proxy is local, set `serviceBindAddress` to
`127.0.0.1` in `wrapper/config.json`; the wrapper reminds you at every start
until you do.

---

## Players

The node keeps a cloud-wide registry of who is online and where, fed by the
proxies. Every operation resolves to "which proxy holds this player" and sends
it one packet, so callers never need to know which proxy that is, and that is
what makes one cloud out of several of them.

```
sirius@node> players
NAME               SERVER             PROXY          ONLINE     ADDRESS
Sirius             Lobby-1            Proxy-1        4m12s      127.0.0.1
1 player(s).

sirius@node> player Sirius send Survival
sirius@node> player Sirius msg Welcome back
sirius@node> player Sirius kick Testing
sirius@node> broadcast Server restarting in 5 minutes
```

`player <name> send <target>` takes either a service name or a group name. A
name matching a service goes there exactly; anything else is treated as a group
and the node picks the least-loaded running instance. **Balancing lives on the
node**, not the proxy, so every caller gets the same behaviour and a proxy needs
no notion of what a group is.

Player counts arrive on a heartbeat and are therefore up to ten seconds stale,
so transfers already in flight are counted against a service's load until the
proxy reports the player landed. Without that, a burst of joins all sees the
same "emptiest" server and piles onto it, which is the exact failure
least-loaded balancing exists to prevent.

The same reach is available to plugins through `CloudDriver.players()`: a
plugin on one lobby can move, message or kick a player who is on a different
server behind a different proxy, without knowing any of that is true.

### Surviving restarts

A proxy sends a **snapshot** of everyone connected right after it handshakes,
and that snapshot *replaces* what the node believed about that proxy rather
than adding to it. Both halves matter:

- Restart the node under a busy cloud and the players who stayed online would
  otherwise be invisible to it forever, since their join events are long past.
- A proxy that died without saying goodbye leaves ghosts the node would
  keep trying to act on.

A proxy dropping also removes its players, since they reached the cloud through
it and are gone with it.

---

## Templates

Files that should exist in every service of a group go in the wrapper's
template directories:

```
wrapper/local/templates/global/server/     → every SERVER service
wrapper/local/templates/global/proxy/      → every PROXY service
wrapper/local/templates/Lobby/default/     → every Lobby service
```

They are copied into a fresh working directory on each start. Dynamic services
have that directory deleted on stop; static services keep theirs.

Server jars are downloaded from PaperMC and cached in
`wrapper/local/jars/cache/`. Dropping a `paper.jar` into `wrapper/local/jars/`
overrides that, which is also the offline fallback.

---

## Paper versions

Any version PaperMC publishes works. The list is **fetched from their API**, not
hardcoded, so a new Minecraft release is usable the day it appears with no code
change. `versions` prints what is on offer; a group's `version` field takes any
of them or `latest`.

```json
{ "name": "Lobby", "version": "latest", "build": "latest" }
{ "name": "Survival", "version": "1.21.4", "build": "196" }
```

**Nothing in this code parses or compares version strings.** That is the whole
design of [`PaperVersionCatalog`](cloud-driver/src/main/java/dev/sirius/cloud/driver/paper/PaperVersionCatalog.java):
Minecraft version strings have not kept one shape over time, and a comparator
written against the shape they had today would silently mis-order the moment
that changes, resolving `latest` to the wrong release. So PaperMC's own
ordering is authoritative: newest last, `latest` is the final entry, "supported"
means "in the list", and a version range is an *index* into that list rather
than a numeric comparison. `minimumPaperVersion` in `node/config.json`
(default `1.21.1`) trims the `versions` listing that way; `versions --all`
shows everything, and it never blocks a group from pinning an older release.

`latest` resolves to the newest **stable** release. Release candidates are
published alongside releases (the newest entry today is a `-rc` build), so
taking the literal last one would silently put every default group on a
pre-release. Name one explicitly to opt in.

Groups can also override `javaExecutable`, since a version range spanning
several Minecraft releases may span their minimum Java versions too.

---

## Scaling and restarts

Every group scales between `minServiceCount` and `maxServiceCount` on its own.
The whole policy lives in the group file, and all of it is editable live with
`edit <group> <field> <value>`:

```json
{
  "name": "BedWars",
  "minServiceCount": 1,
  "maxServiceCount": 10,
  "startTimeoutSeconds": 180,
  "autoscale": { "enabled": true, "scaleUpAtPercent": 80, "scaleDownAfterEmptySeconds": 300 },
  "maxUptimeMinutes": 0,
  "rolloutOnTemplateChange": true
}
```

| Behaviour | How it works |
|---|---|
| **Scale up** (`scaleup`) | When the group's running services are, together, at least this full, one more starts. Only one at a time: nothing new starts while another is still coming up, and there is a cooldown between actions, so a burst of joins does not start five servers for a load one would carry. |
| **Scale down** (`scaledown`) | A service above the minimum that has been **empty** this long is stopped. Empty, not quiet: nobody is ever moved to free a server. |
| **Start timeout** (`timeout`) | A service that has not reported ready in time is killed and replaced, instead of holding a slot forever. |
| **Scheduled restarts** (`maxuptime`) | Services older than this are replaced with a rolling restart. `0` is off. Useful for plugins that leak. |
| **Template rollouts** (`rollout`) | The wrapper watches `local/templates/`. Ten seconds after the last change to a group's templates, its services are replaced one by one. `rollout <group>` does it by hand, `rollout <group> cancel` stops one. |
| **Busy ports** | Ports are probed before a start. One that something else holds is skipped and left alone for ten minutes. |

A rolling restart never takes capacity away first: it starts the replacement,
waits for it to be ready, marks the old service **draining** (no new players
are sent there), moves its players across, and only then stops it. Static
services are never replaced automatically, since their files are their state.

Fabric servers cannot run the cloud's plugin, so they report no player counts
and do not autoscale; everything else about them works.

---

## Server software

| `software` | Type | Notes |
|---|---|---|
| `paper` | server | The default. |
| `purpur` | server | A Paper fork with more configuration. Versions from Purpur's API. |
| `folia` | server | Regionised Paper. The cloud's plugin supports it; the optional permissions plugin does not, and Folia refuses to load plugins that do not declare support. |
| `fabric` | server | Vanilla with the Fabric loader. `fabric-api` and `FabricProxy-Lite` are downloaded from Modrinth, checksummed, and configured for Velocity forwarding. Readiness comes from the server log. |
| `velocity` | proxy | |

Set it with `edit <group> software purpur`, or in `setup`. `versions <software>`
lists what each offers. Every catalogue is fetched from its project's API, with
downloads hash-checked and cached per version in `wrapper/local/jars/cache/`.

---

## Isolation

By default services are child processes of the wrapper. To give each its own
container instead, with a hard memory and CPU cap so one runaway server cannot
starve the machine, set this in `wrapper/config.json`:

```json
"isolation": {
  "mode": "docker",
  "image": "eclipse-temurin:25-jre",
  "memoryOverheadMb": 384,
  "defaultCpuLimit": 0
}
```

The service directory is bind-mounted, the container runs as the wrapper's own
user (so files stay yours on Linux), and it reaches the node through
`host.docker.internal`. The memory cap is the heap plus `memoryOverheadMb`,
because a JVM uses more than its heap. Per group, `edit <group> cpus 2` caps
cores. The wrapper verifies that Docker answers before it accepts work, and
removes containers a crashed wrapper left behind when it starts again.

---

## Backups

`backup <service>` backs up a running service now. For a schedule, per group:

```
edit Survival backup 60     # every 60 minutes, 0 is off
edit Survival keep 5        # archives to keep per service
```

A backup is consistent: the wrapper runs `save-off`, then `save-all flush`,
waits for the server to confirm the save, zips the directory, and turns saving
back on however the zip went. Archives land in `wrapper/local/backups/<service>/`.
Jars, caches, libraries and logs are left out; they are downloaded again anyway.
Only static services are scheduled, since a dynamic one is thrown away on stop.

---

## Persistence

The node keeps players, bans, friends and module data in a database, chosen in
`node/config.json`:

```json
"database": {
  "type": "json",
  "host": "127.0.0.1",
  "port": 0,
  "database": "siriuscloud",
  "username": "",
  "password": "",
  "uri": "",
  "poolSize": 4
}
```

| `type` | |
|---|---|
| `json` | Files under `node/local/database/`. The default: nothing to install. |
| `mysql`, `mariadb` | One table per collection, `sirius_<collection>`, created on first use. |
| `postgresql` | The same, with Postgres's upsert. |
| `mongodb` | One collection per collection. `uri` overrides host and port. |

`port: 0` means the backend's default port. If the configured database cannot be
opened the node refuses to start, rather than running on and silently losing
every ban written until somebody notices.

**Player profiles** come for free: first and last seen, total playtime, the last
server and every name a player has used, looked up by UUID or by any past name.

**A key-value store** sits beside it for state that is shared but not precious:
queues, parties, counters, locks. It lives in memory on the node, supports TTLs
and `setIfAbsent`, and is flushed to `node/local/store.json` every few seconds.

From any server, proxy or module:

```java
CloudDriver cloud = CloudDriver.instance();

cloud.database().collection("stats").put(uuid.toString(), json);
cloud.store().set("event:double-xp", "on", Duration.ofHours(2));
cloud.players().profile("Notch").thenAccept(profile -> ...);

// Tell the network what state this server is in: matchmaking and
// the panel read it, and anything else can.
cloud.services().setState(ServiceProperties.STATE_INGAME);
cloud.services().setProperty(ServiceProperties.MAP, "Lighthouse");

// Pub/sub, now only delivered to services that subscribed to the channel.
cloud.messaging().publish("bedwars:stats", payload);
```

---

## The API and the panel

The node loads anything in `node/modules/` at startup. The REST API ships as one
of those rather than as part of the node, which is the whole point of the module
system: it reaches the cloud through `CloudDriver` and nothing else, so it can
expose nothing a plugin could not already do.

```
sirius@node> modules
ID                   VERSION      STATUS     DESCRIPTION
rest                 1.0.0        enabled    HTTP API and web panel for the cloud
1 module(s).
```

On first start it writes `node/modules/rest/config.json` with a generated token
and prints it once:

```
[RestApi] API on http://127.0.0.1:8080/api/v1/
[RestApi] Panel on http://127.0.0.1:8080/
[RestApi] API token: e9c7afe9-42ca-4114-ad9d-63898fffedcf
```

**It binds to loopback, and that default is deliberate.** This endpoint starts
and stops servers, moves players and runs console commands: anything that can
reach it can run the cloud. Widen `bindAddress` only behind a reverse proxy
doing TLS: the token travels in a header, and plain HTTP puts it on the wire in
clear. The node says so loudly if you bind it anywhere else.

The token is separate from the wrapper secret for the same reason the forwarding
secret is: this one ends up in a browser's local storage and in whatever scripts
people write, while the cloud secret never leaves a wrapper.

### Endpoints

Every `/api/` call needs `Authorization: Bearer <token>`.

| | |
|---|---|
| `GET /api/v1/overview` | everything below in one response, which is what the panel polls |
| `GET /api/v1/node`, `/nodes`, `/wrappers` | the control plane and its machines |
| `GET /api/v1/groups`, `/services`, `/players` | what is configured, running and online |
| `POST /api/v1/services` | `{"group":"Lobby","count":1}` |
| `POST /api/v1/services/{name}/stop` | graceful stop |
| `POST /api/v1/services/{name}/command` | `{"command":"say hello"}` |
| `POST /api/v1/players/{uuid\|name}/connect` | `{"target":"Lobby"}`: service exactly, or group balanced |
| `POST /api/v1/players/{uuid\|name}/message` | `{"message":"..."}` |
| `POST /api/v1/players/{uuid\|name}/kick` | `{"reason":"..."}` |
| `POST /api/v1/broadcast` | `{"message":"..."}` |

A refusal from the cloud ("no running service of that group", "already at its
maximum") comes back as `409` with the reason, not a `500`: it is the caller's
problem, not a server fault.

### The panel

`http://127.0.0.1:8080/` serves a single self-contained page out of the module
jar: node and wrapper overview, groups, services and players, with start, stop,
console command, send, message, kick and broadcast. It asks for the token once
and keeps it in local storage.

It is **served by the node**, not hosted anywhere. A page hosted elsewhere could
not reach an API on loopback, and pointing a public site at a control plane
would mean exposing the control plane. Everything it renders goes in as text
rather than markup, because player names and MOTDs are attacker-controlled and
this page can stop servers.

---

## In game

### /cloud

Both plugins register `/cloud` (aliases `/siriuscloud`, `/sc`), and every
subcommand goes through `CloudDriver`, the same public API a third-party plugin
would use. Nothing here is a privileged back door into the node.

```
/cloud list [group]           every service, or one group's
/cloud info <service>         one service in detail
/cloud start <group> [count]  starts services
/cloud stop <service>         stops one gracefully
/cloud players                everyone online, cloud-wide
/cloud find <player>          which server somebody is on
/cloud send <player> <target> moves them to a service or group
/cloud broadcast <message>    message every player everywhere
/cloud wrappers               connected machines
/cloud node                   control plane status
```

Each subcommand has its own permission, `siriuscloud.command.<name>`, so read
access and the ability to stop a server are separable. Results arrive
asynchronously and print as they land: blocking the main thread on a network
round trip is how a permission lookup becomes a server freeze.

### Notifications

The `notify` module announces cloud lifecycle to anyone holding
`siriuscloud.notify`:

```
[Cloud] Lobby-1 is ready (127.0.0.1:41000)
[Cloud] Lobby-1 crashed. Check the node console.
[Cloud] Machine wrapper-2 disconnected. Its servers keep running but cannot
        be controlled until it returns.
```

Which events are announced is configurable in `node/modules/notify/config.json`.
Crashes and machine loss default to on; routine starts and stops default to off,
because a cloud that scales constantly would otherwise be a chat spammer.

**The node publishes, the servers filter.** The node has no idea who holds a
permission, so it announces "anyone with `siriuscloud.notify` should hear this"
and each server answers that for its own players using whatever permission
plugin is installed. That keeps the module a thing that describes events rather
than a thing that knows about permission plugins.

---

## Network features

These are modules in `node/modules/`, built on the public API like any module
you would write. Each writes a `config.json` into `node/modules/<name>/` on
first start where every feature can be switched off; deleting a jar removes the
module entirely. Worth doing where a network already runs its own plugin for,
say, bans or private messages: two authorities for the same thing will
contradict each other.

Commands are registered **on every proxy by the node**, so they work on every
server with nothing installed there, and appear in tab completion only for
players with the permission. They can be run from the node console too.

### Display and maintenance

The server list MOTD, version text and tab list, in MiniMessage, set once for
every proxy. Placeholders: `{online}`, `{max}`, `{proxy}`, `{servers}`, and in
the tab list `{player}`, `{server}`, `{ping}`.

| Command | Permission |
|---|---|
| `/maintenance on\|off\|status` | `siriuscloud.maintenance` |
| `/maintenance add\|remove <player>`, `list` | `siriuscloud.maintenance` |

Network-wide maintenance: its own MOTD and version text, and only whitelisted
players or those with `siriuscloud.maintenance.bypass` may join. Turning it on
does not kick anyone already online. On the node console, `maintenance <group>`
still pauses one group's provisioning; anything else goes to this command.

### Social

| Command | What it does |
|---|---|
| `/party invite\|accept\|deny\|leave\|kick\|promote\|disband\|warp\|chat\|list` (`/p`) | Parties across the network. Members follow the leader between servers. |
| `/pc <message>` | Party chat |
| `/friend add\|accept\|deny\|remove\|list\|requests` (`/f`) | Friends, with join and leave notices. Stored in the database. |
| `/msg <player> <message>` (`/tell`, `/w`, `/m`), `/r` | Private messages to anyone on any server. Muted players cannot send them. |
| `/seen <player>` | When someone was last online, or where they are now |
| `/playtime [player]` (`/pt`) | Total time on the network |

### Moderation

| Command | Permission |
|---|---|
| `/ban <player> [duration] [reason]` | `siriuscloud.ban` |
| `/unban <player>` | `siriuscloud.unban` |
| `/mute <player> [duration] [reason]` | `siriuscloud.mute` |
| `/unmute <player>` | `siriuscloud.unmute` |
| `/kick <player> [reason]` | `siriuscloud.kick` |
| `/staffchat <message>` (`/schat`) | `siriuscloud.staffchat` (also receives moderation notices) |
| `/find <player>` | `siriuscloud.find` |
| `/jump <player>` (`/goto`) | `siriuscloud.jump` |

Durations look like `30m`, `12h`, `7d`, `1w2d`; leave it out or write `perm`
for permanent. Players who are offline are found by any name they have used,
and a ban is stored against the UUID, so changing name does not escape it.

Bans are checked **at login on the proxy**, so a banned player never reaches a
server. Mutes are enforced by the cloud's plugin on each server, because signed
chat cannot be blocked on a proxy without disconnecting the player; they are
re-sent to every server whenever one connects, and restored after a node
restart. If the node does not answer a login check within three seconds the
player is let in rather than locked out with the node.

### Matchmaking

| Command | What it does |
|---|---|
| `/play <game>` (`/queue`) | Queue for a group |
| `/leavequeue` (`/lq`) | Leave it |

Players are sent to the **fullest** joinable server with room, so games fill
and start instead of all waiting half-empty. A server is joinable when it is
running, not draining, and its state is `LOBBY` (the default; a game plugin
sets `INGAME` when a round starts). Slots are reserved for fifteen seconds after
a send, so two queue ticks cannot promise the same slot twice. If nothing has
room and the group allows another server, one is started.

A party leader queues the whole party, which only ever lands together. Games
are every non-fallback server group unless `playableGroups` lists them.

### Metrics

Prometheus metrics at `http://127.0.0.1:9225/metrics`: players, services per
group and state, per-service players, TPS, MSPT, heap and uptime, wrapper and
node memory. Set `host` and a `token` in the config to scrape from elsewhere;
the token is then required as `Authorization: Bearer <token>`.

```yaml
scrape_configs:
  - job_name: siriuscloud
    static_configs: [{ targets: ["127.0.0.1:9225"] }]
```

### Discord

Paste a webhook URL into `node/modules/discord/config.json` and the network
reports crashes (with the last lines of the log), wrappers disconnecting, failed
backups and servers below `lowTps` (one alert per server per ten minutes).
Service starts and stops can be turned on too. With no URL the module does
nothing.

---

## LuckPerms

LuckPerms needs to tell every other server when somebody's permissions change.
Out of the box that means running Redis or RabbitMQ, or falling back to
`pluginmsg`, which cannot reach a server that currently has nobody on it and
is exactly the server that will be wrong when the next player joins it.

The cloud already holds an authenticated connection to every running service,
empty ones included, so it is a strictly better transport and one less daemon
to run. The plugins register the cloud as a LuckPerms **messaging service**:

```yaml
# LuckPerms config.yml, on every server and the proxy
messaging-service: custom
```

That is the only change. Nothing else to install, and no Redis.

Everything about the integration is optional and guarded: on a server without
LuckPerms the plugin loads exactly as before, and a LuckPerms whose messenger
API has moved is reported rather than fatal.

> **On the proxy this is weaker than on backends.** Paper has `loadbefore`, so
> the messenger is registered before LuckPerms enables and is picked up
> cleanly. Velocity has no equivalent: declaring LuckPerms as a dependency
> forces this plugin to load *after* it, and declaring nothing leaves the order
> unspecified. If proxy syncing does not start, run `/lp reloadconfig` once.

---

## Permissions

A permission system of the cloud's own, for people who would rather not run
LuckPerms. The node owns the data; a **separate, opt-in plugin** applies it.

> **Use this or LuckPerms, never both.** Two things attaching permissions to
> the same players contradict each other, and the symptom is a rank that
> applies until it does not. The node says so on startup.

### Why the node owns it

A permissions plugin installed on each server knows about the players on that
server. The node knows about all of them, including servers that are empty
right now and servers that have not started yet. A rank granted on one lobby is
in effect on a Bedwars server that boots ten minutes later, without a sync
step, because the server asks the node for the current state as it starts.

Changes travel as a whole snapshot rather than as deltas. The data is small,
and a full replacement cannot drift the way a missed delta can.

### Installing it

The plugin is **not** injected into services automatically, unlike the core
cloud plugin. Copy it into the groups that should use it:

```bash
cp wrapper/optional-plugins/cloud-plugin-permissions.jar \
   wrapper/local/templates/Lobby/default/plugins/
```

### Commands

```
/perms groups                              every group
/perms info <player|group>                 details of either
/perms check <player> <node>               test one permission
/perms group <name> create|delete
/perms group <name> set priority|prefix|suffix|default <value>
/perms group <name> add|remove <node>
/perms group <name> inherit|uninherit <group>
/perms user <player> addgroup|removegroup <group>
/perms user <player> add|remove <node>
```

`siriuscloud.perms.view` covers the read-only three; `siriuscloud.perms.manage`
covers the rest. Every edit is a request to the node, which republishes to every
server, so running a command on one server and seeing the effect on another is
the normal case rather than a feature.

### How resolution works

Weakest rule first: the default group and what it inherits, then the player's
groups in ascending priority so the highest wins, then the player's own nodes.
A node prefixed with `-` denies, and a later rule beats an earlier one.

Wildcards are checked most specific first, so `siriuscloud.command.*` can grant
a whole family while `-siriuscloud.command.stop` carves one back out. Bukkit
does not expand wildcards itself, so the plugin expands them against the
permissions plugins have actually registered. A plugin that never declares its
nodes cannot be covered by a wildcard, which is a limit of the platform.

### Ranks in chat, nametags and the tab list

Prefixes and suffixes take the single highest-priority group, because a player
cannot wear two at once. They show in chat, above players' heads and in the tab
list, which is also sorted with the highest priority on top.

```
/perms group admin set prefix &cAdmin &8| &c
```

```
Admin | JavaRenamed: Hello, world.
```

`&` colour codes carry on until the next one, so the trailing `&c` is what
makes the name red. How it is shown is set once for the whole network, in
`node/modules/permissions/config.json`:

```json
{
  "chat": true,
  "chatFormat": "{prefix}{name}{suffix}&7: &f{message}",
  "nametags": true,
  "tablist": true
}
```

Set `chat` to `false` if a chat plugin should format chat instead. What players
type is never read for colour codes, so nobody can colour their messages by
typing `&c`. Nametags are scoreboard teams on the main scoreboard; a plugin that
gives players a scoreboard of its own, such as a sidebar plugin, replaces them
unless it copies the main scoreboard's teams.

Data lives in `node/modules/permissions/groups.json` and `users.json`, small
enough to read, diff and keep in version control.

---

## Cross-platform notes

Both platforms are first-class. The places where that took real care:

- **Graceful shutdown is stdin-driven.** `Process.destroy()` sends SIGTERM on
  Linux but maps to `TerminateProcess` on Windows, which cannot be caught. A
  "graceful" stop built on it would corrupt worlds on Windows while looking
  fine on Linux. Writing `stop` to the service's stdin is correct on both, so
  there is one code path. `destroy()` and then `destroyForcibly()` are the
  escalation, not the first move.
- **Directory deletion retries.** Windows refuses to delete files with open
  handles, and Minecraft memory-maps region files. Wiping a service directory
  right after exit fails intermittently on Windows and never on Linux. Deletion
  gets bounded retries with a GC hint, and warns rather than throwing.
- **UTF-8 on every process stream.** The native encoding is not UTF-8 on most
  Windows installs; decoding server output with it mangles non-ASCII text.
- **The JVM is located via `java.home`**, with the `.exe` suffix added on
  Windows. Never assume `java` on `PATH` is the right one.
- **Templates are copied, not linked.** Symlinks on Windows need developer mode
  or elevation.
- **Service names are looked up case-insensitively**, so `Lobby-1` behaves the
  same on a case-sensitive Linux filesystem and a case-insensitive Windows one.
- **NIO transport**, not native epoll: no per-OS classifier artifacts for a
  control plane that moves a few hundred small packets a second.
- **`.gitattributes` pins** `gradlew` to LF and `.bat` files to CRLF.

---

## Protocol

Frame:

```
[4-byte length][varint packetId][bool hasQuery][uuid queryId?][payload]
```

Packet ids are registered explicitly in `PacketRegistry.standard()`, so the
whole protocol is readable in one screen. Ids are grouped by direction; never
renumber an existing one, append.

Requests carry a `queryId` and the reply echoes it, which completes a
`CompletableFuture` on the sender. That one field is the entire
request/response layer.

**Channels** are the other half: a service publishes on a named channel, the
node stamps the sender from the authenticated connection and fans it out to
every other service. One primitive carries both the notification module and
LuckPerms' permission syncing, and a publisher never receives its own message
back.

**Authentication:** wrappers present the node's wrapper secret; external tools
and modules present a **separate** API secret. Two values rather than one
because they grant different things: a wrapper secret lets a machine register
and be handed processes to spawn, while an API client only needs to read state
and ask for services. Sharing one meant a leaked API token could register a
fake wrapper and be given real work. Setting `apiReadOnly` in the node config
makes every external client observation-only without taking their credentials
away.
Services present a **per-service token**, generated for each one and written
into its working directory as `cloud-connection.json` immediately before spawn,
so a service can only ever authenticate as itself and the token dies with it.

It stays valid for as long as the service is registered rather than being
consumed on first use. Single-use sounds stronger and is not: a service reads
the same file on every reconnect, so consuming it meant any dropped connection
locked that service out permanently, including every service in the cloud when
the node restarted. What single-use actually guarded against, a second
connection claiming to be a service that is already here, is handled where it
belongs: a service proving its identity supersedes the channel already
registered under its id.

---

## Roadmap

| Milestone | Contents |
|---|---|
| **1: Skeleton** ✅ | Node, wrapper, protocol, Paper plugin, provisioning, attach console |
| **2: Proxy** ✅ | Velocity plugin, dynamic registration, `/hub`, modern forwarding |
| **3: Player layer** ✅ | Registry, transfers, messaging, kicks, restart re-sync |
| **3b: Node-side templates** | Template storage on the node, pushed to wrappers |
| **4: Modules** | Module loader ✅, REST API ✅, web panel ✅, in-game commands ✅, notifications ✅, LuckPerms ✅, permissions ✅ · sign walls, NPCs |
| **4b: Operations** ✅ | Autoscaling, rolling restarts and rollouts, start timeouts, health metrics, backups, Purpur/Folia/Fabric, Docker isolation |
| **4c: Network** ✅ | Databases, player profiles, key-value store, maintenance and display, parties, friends, messages, bans and mutes, matchmaking, Prometheus, Discord |
| **5: Scale** ✅ | Node clustering, leader election, state replication, failover |

None of milestones 2–4 needed core changes: the event bus and the module loader
are the extension points. Milestone 5 did not need a protocol rewrite either:
it added one message type between nodes and a few fields to the handshake, and
failover reuses what a node restart already did, which is adopt the services
its wrappers report.

The REST API is the first module and deliberately so: it uses nothing but
`CloudDriver`, which makes it a standing check that the module contract is
enough to build against.
