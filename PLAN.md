# SiriusCloud — what's left

Status as of the player layer landing. Milestones 1–3 are in and the happy path
is verified end to end: node → wrapper → Paper 26.3 on Java 25 → Velocity 4.2.0
proxy, with dynamic registration, modern forwarding and an attachable console.

This document is the honest remainder. The first section is defects in code
that is already committed — those come before any new feature.

---

## P0 — Defects in shipped code

### 1. Player operations from plugins do nothing

`RemotePlayerProvider` sends `PlayerConnectRequestPacket` (0x44),
`PlayerMessagePacket` (0x45) and `PlayerKickPacket` (0x46) to the node, and
`NodePacketHandler` has no branch for any of them. They are registered in the
protocol, they serialise fine, and they are silently dropped on arrival.

So `CloudDriver.players().connect(...)`, `.sendMessage(...)`, `.broadcast(...)`
and `.kick(...)` are inert from a plugin. The node's own `player` and
`broadcast` commands work, because those go through `LocalCloudDriver` and never
touch the network — which is exactly why this was not noticed.

This breaks the central design rule: the same API is supposed to behave
identically on either side of the wire.

**Fix:** handle the three packets in `NodePacketHandler`, routing them into
`PlayerManager`. Reply with `AcknowledgePacket` so the caller's future resolves
on the real outcome instead of completing optimistically.
*Where:* `cloud-node/.../network/NodePacketHandler.java`. Small.

### 2. A wrapper reconnect orphans its services

When a wrapper disconnects, the node marks every service it owned `CRASHED` and
drops it from the registry. The wrapper does not stop those processes — and
deliberately so, since losing the control connection should not disconnect
players. But nothing re-announces them when the wrapper comes back.

Result after any node restart or network blip:

- Minecraft servers keep running, unmanaged and unreachable by any command.
- The node believes the group is empty and provisions replacements.
- Released ports get handed to the new services, which then fail to bind.

**Fix:** mirror what the proxy already does for players. The wrapper sends a
snapshot of its running services right after handshaking; the node adopts them,
re-reserving their names and ports, rather than starting duplicates. Services
whose processes really did die are reconciled from the wrapper's own view.
*Where:* new packet, `CloudWrapper`, `ServiceProcessManager`,
`NodePacketHandler`. Medium — this is the largest correctness gap.

### 3. A service that never reports ready hangs forever

`STARTING` has no timeout. A process that spawns but never sends
`ServiceReadyPacket` — a plugin deadlock, a world that will not load, a jar
waiting on stdin — holds its name, port and memory budget indefinitely.
`GroupBackoff` does not help: nothing ever fails, so nothing backs off.

**Fix:** a per-group start timeout (default ~3 minutes), checked by the
provisioning tick. On expiry, stop the service and record a failure so the
backoff applies.
*Where:* `ProvisioningTask`, `ServiceManager`, `ServiceGroup`. Small.

### 4. Port allocation never checks the port is free

`ServiceRegistry.allocatePort` tracks what the cloud handed out. It has no idea
whether something outside the cloud holds that port, so a service started on an
occupied port fails to bind and crash-loops until the backoff caps.

**Fix:** have the wrapper test-bind the port before spawning and report a clear
failure if it is taken, so the message names the real cause.
*Where:* `ServiceProcessManager`. Small.

### 5. The README describes a module loader that does not exist

> "the event bus and the module loader are the extension points"

The event bus is real. There is no module loader. Either build it (see P2) or
correct the sentence — but not neither.
*Where:* `README.md:490`. Trivial either way.

---

## P1 — No safety net

### 6. Zero automated tests

`./gradlew test` compiles nothing. Every bug found so far was found by running
the whole system by hand, and the two worst were in pure functions that a unit
test would have caught in seconds:

- `PaperVersionCatalog` returned a list that was not ordered, so `latest`
  resolved to the oldest release of the newest family.
- `PaperMcJarProvider` picked the *oldest* Paper build; servers shipped 29
  builds behind and only Paper's own warning revealed it.

Highest-value targets, all pure and fast:

| Target | Why |
|---|---|
| `PaperVersionCatalog` ordering, `latest`, `from()` | already wrong once |
| `PaperMcJarProvider` build selection | already wrong once |
| `NettyDataBuf` varint/string/nullable round-trips | silent corruption if wrong |
| `PacketRegistry` — every packet round-trips, no duplicate ids | cheap, catches a whole class of bug |
| `ServiceRegistry` ordinal and port allocation | concurrency and reuse |
| `GroupBackoff` escalation and reset | timing logic nobody will test by hand |
| `JavaRuntimeResolver.featureOf` | `1.8.0_402` vs `25.0.4` |

*Where:* new `src/test/java` per module, JUnit 5 is already configured. Medium,
and the best value per hour on this list.

### 7. Never actually run on Windows

Every cross-platform decision — stdin shutdown, retrying deletes, UTF-8 streams,
`.exe` resolution, CRLF launchers — was made deliberately and none has been
executed on Windows. The retry-delete path in particular only *does* anything
there.

**Fix:** run the walking skeleton on a Windows machine once. Expect to find
something in file locking or path handling.

### 8. Nothing is logged to disk

Node and wrapper log to the console only. When a node dies, the reason scrolls
away with it, and service console backlogs are in-memory ring buffers.

**Fix:** rolling file appender per process, plus persisting a crashed service's
backlog to `local/crashes/<service>-<timestamp>.log`.
*Where:* `CloudLogger` sinks. Small.

---

## P2 — Foundations the roadmap depends on

### 9. Node-side templates

Templates live only on the wrapper, so a second machine means copying template
directories there by hand and keeping them in sync. Mis-filed under milestone 3;
it is really its own piece.

**Design:** node stores the canonical copy under `node/local/templates/`, and
pushes what a group needs to a wrapper before its first service of that group
starts, with a content hash so unchanged templates are not resent. A
`template deploy` command for pushing edits without a restart.
*Where:* new packets, node template store, wrapper receive side. Medium–large.

### 10. Module system

The whole of milestone 4 is meant to be modules rather than core changes, and
there is currently nothing to load them with.

**Design:** `modules/` directory, one `URLClassLoader` per module, `module.json`
manifest (id, version, main class, api version), lifecycle hooks, and
`EventManager.unsubscribeAll(ClassLoader)` on unload — which the event bus
already supports, unused. Modules get `CloudDriver` and nothing else.
*Where:* new `cloud-node/.../module/`. Medium.

---

## P3 — Features

### Milestone 4 — modules (needs #10 first)

- **REST API** — service and player state over HTTP, token auth. The thing
  every panel and bot needs.
- **Web panel** — read-only first: services, players, consoles. Writes later.
- **Sign walls** — join signs on a lobby wall, updating live from
  `ServiceStateChangedEvent`.
- **NPCs** — same idea, player-shaped.
- **Permissions** — cloud-wide groups and prefixes, synced to backends.

### Milestone 5 — scale

- **Node clustering** — several nodes sharing state, leader election, failover.
  The protocol was designed multi-node so this needs no rewrite, but consensus
  is genuinely hard and should come last.
- **Smarter scheduling** — CPU and memory-aware placement instead of
  least-committed-memory, group affinity, anti-affinity across machines.

---

## P4 — Operational polish

Small, individually cheap, each removes a "why can't I just…" moment.

| Gap | Note |
|---|---|
| No `reload` command | group JSON edits need a node restart |
| No `maintenance <group> on/off` | the flag exists and is respected; nothing toggles it |
| No `restart <service>` | stop-then-start by hand |
| No group editing | `setup` creates; changes mean editing JSON |
| Proxy does not enforce its slot count | capacity is advertised, not applied; Velocity never enforces by default |
| No `/server` command on the proxy | only `/hub` |
| Single permission level | the shared secret grants everything; an `API` client can do anything a wrapper can |
| Balancing uses 10s-old player counts | `connectToGroup` can pile onto one server in a burst |
| Static services never exercised | implemented, untested |
| Node start does not verify the port | `BindException` stack trace instead of "already running" |

---

## Suggested order

1. **P0 #1 and #2** — the API is half-inert and a reconnect orphans servers.
   Both are in shipped code and both bite in normal operation.
2. **P1 #6** — tests, starting with the two functions that already broke.
   Everything after this is safer for it.
3. **P0 #3, #4, #5** — quick, and each removes a confusing failure.
4. **P2 #10** — the module system, because milestone 4 is meaningless without
   it.
5. **P2 #9** — node-side templates, the last thing blocking a genuine
   multi-machine deployment.
6. **P3** — features, in whatever order is most useful to you.

P1 #7 (Windows) can happen whenever a Windows machine is available; it is
independent of everything else.
