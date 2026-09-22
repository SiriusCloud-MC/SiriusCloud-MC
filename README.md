# SiriusCloud

A Minecraft server cloud: a control plane that decides what runs where, machine
agents that run it, and an API that behaves identically whether you call it from
the node or from inside a running server.

Runs on **Linux and Windows**.

| | Java |
|---|---|
| Node and wrapper | **21+** |
| Minecraft services | **25+** — Minecraft 26.x refuses to start on anything lower |

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
| `cloud-api` | Public contract. **Zero dependencies** — this is what third parties compile against. |
| `cloud-protocol` | Packet definitions, codec, Netty transport. |
| `cloud-driver` | The `CloudDriver` implementation over the network, plus the event bus. |
| `cloud-node` | State, scheduling, provisioning loop, JLine console. |
| `cloud-wrapper` | Process spawning, templates, jar downloads, console piping. |
| `cloud-plugins/paper` | In-service bridge. Reports readiness so `RUNNING` means something. |

The design rule everything follows: **all feature code goes through
`CloudDriver`.** The node binds a local implementation backed by its own
registries; wrappers and plugins bind a remote one backed by packets. Same
interface, so a feature is written once and runs on either side.

---

## Building

```bash
./gradlew dist
```

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
> committed. Fetch it once — `gradlew` prints the exact command if it is missing.

---

## Running

**1. Start the node.**

```bash
cd build/dist && ./start-node.sh
```

On first run it writes `node/config.json` with a generated `secret`, prints it,
and asks whether you want a first group:

```
── First-time setup ──
  This node has no groups yet. A group is a template that
  servers are started from - a 'Lobby' group gives you lobby servers.

  Create a Lobby group now? [Y/n]: y
  Group name [Lobby]: Lobby
  Memory per server in MB [1024]: 2048
  Servers to keep online [1]: 1
  Max players per server [50]: 60
  Minecraft version ('latest' = newest stable) [latest]: latest

[INFO] [Setup] Created group 'Lobby' (2048MB, keeping 1 online, Paper latest)
[INFO] [Setup] Lobby-1 will start as soon as a wrapper connects.
```

Every question has a default, so pressing Enter through it is fine. Declining
is remembered rather than re-asked on every start — run `setup` whenever you
want a group. With no terminal to ask on (systemd, a container without `-t`,
piped stdin) it creates a default `Lobby` instead of hanging on a prompt that
can never be answered.

**2. Give the wrapper that secret.** Start it once to generate its config, then
copy `secret` from `node/config.json` into `wrapper/config.json`.

**3. Start the wrapper.**

```bash
cd build/dist && ./start-wrapper.sh
```

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
| `setup` | Creates a group interactively |
| `services` / `ls` | Every known service with state, address, uptime |
| `groups` | Configured groups and how many of each are online |
| `start <group> [count]` | Starts services |
| `stop <service\|group\|all> [--force]` | Graceful stop; `--force` kills |
| `exec <service> <command>` | Runs a single command inside a service |
| `attach <service>` | Opens that service's console (see below) |
| `versions [--all]` | Paper versions available to groups |
| `info` | Node status and connected wrappers |
| `shutdown` | Stops everything, then the node |

---

## Service consoles

**Service output never appears in the node or wrapper console.** With more than
a couple of servers running, interleaved output from all of them buries the
node's own logs and is unreadable anyway. So the wrapper keeps a bounded ring
buffer per service locally and sends *nothing* over the network until somebody
asks — not "sends it and the node discards it", genuinely nothing.

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
- **Ctrl+C detaches** rather than shutting the node down — while attached it
  reads as "leave this server", and killing the cloud instead would be a nasty
  surprise. It still shuts down when you are not attached.
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
that changes — resolving `latest` to the wrong release. So PaperMC's own
ordering is authoritative: newest last, `latest` is the final entry, "supported"
means "in the list", and a version range is an *index* into that list rather
than a numeric comparison. `minimumPaperVersion` in `node/config.json`
(default `1.21.1`) trims the `versions` listing that way; `versions --all`
shows everything, and it never blocks a group from pinning an older release.

`latest` resolves to the newest **stable** release. Release candidates are
published alongside releases — the newest entry today is a `-rc` build — so
taking the literal last one would silently put every default group on a
pre-release. Name one explicitly to opt in.

Groups can also override `javaExecutable`, since a version range spanning
several Minecraft releases may span their minimum Java versions too.

---

## Cross-platform notes

Both platforms are first-class. The places where that took real care:

- **Graceful shutdown is stdin-driven.** `Process.destroy()` sends SIGTERM on
  Linux but maps to `TerminateProcess` on Windows, which cannot be caught — a
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
  Windows — never assume `java` on `PATH` is the right one.
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

Packet ids are registered explicitly in `PacketRegistry.standard()` — the whole
protocol is readable in one screen. Ids are grouped by direction; never
renumber an existing one, append.

Requests carry a `queryId` and the reply echoes it, which completes a
`CompletableFuture` on the sender. That one field is the entire
request/response layer.

**Authentication:** wrappers and API clients present the node's shared secret.
Services present a **one-time token**, generated per service and written into
its working directory as `cloud-connection.json` immediately before spawn — so
a service can only ever authenticate as itself, and the token dies with it.

---

## Roadmap

| Milestone | Contents |
|---|---|
| **1 — Skeleton** ✅ | Node, wrapper, protocol, Paper plugin, provisioning, attach console |
| **2 — Proxy** | Velocity plugin, dynamic server registration, `/hub`, routing |
| **3 — Player layer** | Cloud-wide player registry, messaging, transfers, node-side templates |
| **4 — Modules** | Sign walls, NPCs, REST API, web panel, permissions |
| **5 — Scale** | Node clustering, leader election, state replication |

None of milestones 2–4 need core changes: the event bus and the module loader
are the extension points, and the protocol was designed multi-node from the
start so milestone 5 does not require rewriting it.
