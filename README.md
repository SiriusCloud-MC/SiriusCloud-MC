# SiriusCloud

A Minecraft server cloud: a control plane that decides what runs where, machine
agents that run it, and an API that behaves identically whether you call it from
the node or from inside a running server.

Runs on **Linux and Windows**. Java 21.

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

On first run it writes `node/config.json` with a generated `secret` and creates
a default `Lobby` group. It prints the secret to the console.

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
| `services` / `ls` | Every known service with state, address, uptime |
| `groups` | Configured groups and how many of each are online |
| `start <group> [count]` | Starts services |
| `stop <service\|group\|all> [--force]` | Graceful stop; `--force` kills |
| `exec <service> <command>` | Runs a command inside a service |
| `info` | Node status and connected wrappers |
| `shutdown` | Stops everything, then the node |

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
| **1 — Skeleton** ✅ | Node, wrapper, protocol, Paper plugin, provisioning, console |
| **2 — Proxy** | Velocity plugin, dynamic server registration, `/hub`, routing |
| **3 — Player layer** | Cloud-wide player registry, messaging, transfers, node-side templates |
| **4 — Modules** | Sign walls, NPCs, REST API, web panel, permissions |
| **5 — Scale** | Node clustering, leader election, state replication |

None of milestones 2–4 need core changes: the event bus and the module loader
are the extension points, and the protocol was designed multi-node from the
start so milestone 5 does not require rewriting it.
