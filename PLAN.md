# SiriusCloud — what's left

Status as of the player layer landing. Milestones 1–3 are in and the happy path
is verified end to end: node → wrapper → Paper 26.3 on Java 25 → Velocity 4.2.0
proxy, with dynamic registration, modern forwarding and an attachable console.

This document is the honest remainder. The first section is defects in code
that is already committed — those come before any new feature.

---

## P0 — Defects in shipped code

#1, #2 and #5 are fixed; kept below with what was done, since they describe
behaviour worth knowing about. #3 and #4 are outstanding.

### ~~1. Player operations from plugins do nothing~~ — fixed

`NodePacketHandler` now handles `PlayerConnectRequestPacket` (0x44),
`PlayerMessagePacket` (0x45) and `PlayerKickPacket` (0x46), routing them into
`PlayerManager`, and replies with `AcknowledgePacket`. `RemotePlayerProvider`
sends them as queries rather than fire-and-forget, so a plugin's future now
fails with the real reason — "that player is not online", "their proxy is not
connected" — instead of completing successfully whatever happened.

A connect target that names a service goes there exactly; anything else is
treated as a group and balanced, matching what the `player <name> send` command
has always done.

### ~~2. A wrapper reconnect orphans its services~~ — fixed

New `ServiceSnapshotPacket` (0x26), wrapper to node, sent immediately after each
handshake — the same shape as the proxy's `PlayerSnapshotPacket` and for the
same reason.

Two halves:

- The node no longer forgets a wrapper's services when it disconnects. They are
  still running, so releasing their names and ports was the thing that let
  replacements be provisioned onto occupied ports.
- On reconnect the snapshot reconciles: services the wrapper no longer reports
  really did die and are dropped; services the node has never heard of (a node
  that restarted under a live machine) are adopted, re-reserving their names and
  ports rather than starting duplicates.

For a service the node already knows, the node's own state wins — the wrapper
can never observe `RUNNING`, since that comes from the in-service plugin.

A wrapper is not schedulable until its snapshot arrives, which closes the window
between handshake and snapshot where the machine looks idle. Two latent bugs
surfaced on the way and are fixed with it: `WrapperRegistry.unregister` removed
by name alone, so a dead channel's late close could unregister the live
reconnection; and a snapshot's claimed wrapper name is now ignored in favour of
the authenticated one, since reconciliation deletes records.

### 3. A service that never reports ready hangs forever

`STARTING` has no timeout. A process that spawns but never sends
`ServiceReadyPacket` — a plugin deadlock, a world that will not load, a jar
waiting on stdin — holds its name, port and memory budget indefinitely.
`GroupBackoff` does not help: nothing ever fails, so nothing backs off.

**Fix:** a per-group start timeout (default ~3 minutes), checked by the
provisioning tick. On expiry, stop the service and record a failure so the
backoff applies.
*Where:* `ProvisioningTask`, `ServiceManager`, `ServiceGroup`. Small.

### ~~4b. Service tokens were single-use, so nothing could ever reconnect~~ — fixed

Found by running the reconnect test rather than by reading: a service's token
was consumed on first use, so the in-service plugin — which re-reads the same
`cloud-connection.json` on every reconnect — was refused forever afterwards.
Any dropped connection locked that service out, and a node restart locked out
every service in the cloud at once.

Invisible until now because the node used to forget a wrapper's services on
disconnect, so the rejections looked like noise from processes nobody was
tracking. With adoption in place they became services stuck in `STARTING`.

**Fixed:** a token is valid for as long as its service is registered, the
wrapper re-announces it in the service snapshot so a restarted node can re-arm
it, and a service proving its identity now supersedes the channel already
registered under its id — which is what single-use was really protecting.

### 4. Port allocation never checks the port is free

`ServiceRegistry.allocatePort` tracks what the cloud handed out. It has no idea
whether something outside the cloud holds that port, so a service started on an
occupied port fails to bind and crash-loops until the backoff caps.

**Fix:** have the wrapper test-bind the port before spawning and report a clear
failure if it is taken, so the message names the real cause.
*Where:* `ServiceProcessManager`. Small.

### ~~5. The README describes a module loader that does not exist~~ — fixed

Resolved the substantive way rather than by editing the sentence: the module
loader now exists (P2 #10), so the claim is true.

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

### ~~10. Module system~~ — done

Built as designed: `node/modules/`, one class loader per module, a
`module.json` manifest, lifecycle hooks, and `unsubscribeAll(ClassLoader)` on
disable — the event bus hook that had been sitting unused since the first
commit. A `modules` console command lists and toggles them.

The loader is **parent-first**, which is the opposite of most plugin systems and
deliberate: a module has to see the same `CloudDriver` and event classes the
node does, or `CloudDriver.instance()` returns something it cannot cast and
every subscription matches nothing. The cost is that a module cannot bring its
own version of a library the node already has.

A module that throws is reported and skipped; the node starts regardless.

---

## P3 — Features

### Milestone 4 — modules

- ~~**REST API**~~ — done. `cloud-modules/rest`, bearer token, loopback by
  default. Reaches the cloud only through `CloudDriver`, which is what makes it
  a real test of the module contract rather than a privileged back door.
- ~~**Web panel**~~ — done, and with writes rather than read-only first: it
  turned out the interesting risk was in the control paths, not the tables.
  Served by the node out of the module jar.
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

1. ~~**P0 #1 and #2**~~ — done.
2. **P1 #6** — tests, starting with the two functions that already broke, and
   now also with the reconnect reconciliation, which has more branches than
   anything else in here and no way to exercise them by hand.
   Everything after this is safer for it.
3. **P0 #3 and #4** — quick, and each removes a confusing failure.
4. ~~**P2 #10**~~ — done, along with the REST API and panel that needed it.
5. **P2 #9** — node-side templates, the last thing blocking a genuine
   multi-machine deployment.
6. **P3** — the remaining milestone-4 modules: sign walls, NPCs, permissions.

P1 #7 (Windows) can happen whenever a Windows machine is available; it is
independent of everything else.
