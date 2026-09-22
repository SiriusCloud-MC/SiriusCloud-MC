# SiriusCloud

A Minecraft server cloud: a control plane that decides what runs where, machine
agents that run it, and an API that behaves identically whether you call it from
the node or from inside a running server.

Runs on **Linux and Windows**.

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
├── node/cloud-node.jar
└── wrapper/cloud-wrapper.jar
    └── plugins/cloud-plugin-paper.jar   ← injected into every service
```

> **First build:** `gradle/wrapper/gradle-wrapper.jar` is a binary and is not
> committed. Fetch it once; `gradlew` prints the exact command if it is missing.

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

**4. Watch it work.** The node's provisioning loop sees `Lobby` below its
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
| `players [service]` | Everyone online, across every proxy |
| `player <name> …` | Info, or `send`/`msg`/`kick` |
| `broadcast <message>` | Message every player on every proxy |
| `exec <service> <command>` | Runs a single command inside a service |
| `attach <service>` | Opens that service's console (see below) |
| `versions [paper\|velocity] [--all]` | Versions available to groups |
| `info` | Node status and connected wrappers |
| `shutdown` | Stops everything, then the node |

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

**Authentication:** wrappers and API clients present the node's shared secret.
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
| **4: Modules** | Module loader ✅, REST API ✅, web panel ✅ · sign walls, NPCs, permissions |
| **5: Scale** | Node clustering, leader election, state replication |

None of milestones 2–4 need core changes: the event bus and the module loader
are the extension points, and the protocol was designed multi-node from the
start so milestone 5 does not require rewriting it.

The REST API is the first module and deliberately so: it uses nothing but
`CloudDriver`, which makes it a standing check that the module contract is
enough to build against.
