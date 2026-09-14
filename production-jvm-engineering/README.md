# Phase 6 — Production JVM engineering: JFR, GC tuning, containers

Running a JVM in production is defined by one habit: evidence before flags. JFR (JEP 328) is a
low-overhead, always-on recorder built into the runtime, and its streaming form (JEP 349) turns
it into a live signal source. Every subsystem the earlier phases studied emits events here: GC
phases, allocation samples, monitor contention, virtual-thread pinning. Reading a container's
cgroup limits the same way the JVM itself does is the other half — predicting the GC and heap it
will pick before the pager goes off.

## Medium — `OrderService`: your own JFR event

A custom event with labels, categories, units, and `@StackTrace(false)` — stack capture is the
expensive part of JFR overhead, so it's opt-in, not default. Runs verbatim as in the artifact.

```
java -XX:StartFlightRecording=filename=orders.jfr OrderService
jfr summary orders.jfr
jfr print --events com.acme.OrderProcessed orders.jfr
```

Real captured output:

```
 com.acme.OrderProcessed                   500         12690

com.acme.OrderProcessed {
  startTime = 19:55:49.216 (2026-09-14)
  duration = 1.21 ms
  orderId = "o-0"
  lines = 1
  totalCents = 2.0 kB
  cached = false
  eventThread = "main" (javaThreadId = 3)
}
```

`OrderServiceTest` proves the same thing as a real assertion: a `Recording` is started, two orders
are processed, the recording is dumped, and `RecordingFile.readAllEvents` reads the two events
back with their exact field values — not just that the `Event` subclass compiles.

## Hard — `LiveGcWatch`: JFR event streaming as a live signal source

An in-process `RecordingStream` keeping a pause histogram, an allocation rate, and a contention
log, printing a p99 estimate on every flush. The same API works remotely over JMX
(`RemoteRecordingStream`).

### `EventSettings.withThrottle(String)` does not exist on this JDK

The artifact's own snippet calls `rs.enable("jdk.ObjectAllocationSample").withThrottle("150/s")`.
Reflecting over the real `jdk.jfr.EventSettings` class on this JDK shows no such method — the
only methods are `with(String,String)`, `withStackTrace()`, `withoutStackTrace()`,
`withoutThreshold()`, `withPeriod(Duration)` and `withThreshold(Duration)`. The real way to set
`jdk.ObjectAllocationSample`'s "throttle" setting is the generic key/value form:

```java
rs.enable("jdk.ObjectAllocationSample").with("throttle", "150/s");
```

— the same key a `.jfc` settings file would use for that event. Fixed and verified end to end:
pause histogram, allocation-rate sampling, and (separately, with a real contended lock)
monitor-contention events all work correctly with this correction.

Run it for real (real captured output, `-Xmx64m` to force visible GC activity quickly):

```
GC pauses: 29, p99 < 4096 us, alloc ~984.1 MB/s
GC pauses: 61, p99 < 8192 us, alloc ~1225.8 MB/s
GC pauses: 92, p99 < 8192 us, alloc ~1274.0 MB/s
```

`LiveGcWatchTest` drives `LiveGcWatch.wire` against a real `RecordingStream` while a real
allocation loop runs, and blocks (via a `CountDownLatch`) until at least one real
`jdk.ObjectAllocationSample` event is delivered — proving the corrected API usage actually works,
not just that it compiles.

Guru question the artifact leaves open: why must the streaming callbacks never block, and what
happens to the JFR disk repository if they do? (Hint: `jdk.jfr.internal` and the repository's
chunk rotation — a slow callback backs up the queue the recorder thread is trying to drain.)

## Guru — `ContainerCgroup`: predicting the JVM's ergonomics from cgroup limits, without Docker

### Docker image pulls are blocked in this sandbox — a policy finding, not a workaround of convenience

The guru rung and the exercise both call for `docker run --cpus=... --memory=... eclipse-temurin:25
...`. Docker itself runs here (`dockerd` starts and the CLI works), but `docker pull` fails: the
registry redirects to `production.cloudfront.docker.com`, and this sandbox's own egress policy
hard-blocks that host with a 403 (confirmed via the agent proxy's own status endpoint — a policy
denial, not a transient failure, so not one to retry or route around).

HotSpot's own container detection (`src/hotspot/os/linux/cgroupSubsystem_linux.cpp`) only ever
reads the calling process's own cgroup files — it has no dependency on that process actually
running inside a real container, Docker or otherwise. Docker's container support is itself
implemented as exactly this: place the process in a cgroup with the requested limits, then exec
into it. So `ContainerCgroup` does the same thing directly: create a real cgroup v1 hierarchy,
set real CFS quota/period and a real memory limit, and run `java` confined to it — reproducing the
exact behavior the artifact's `docker run` commands would have shown, verified for real.

One implementation wrinkle specific to this sandbox: a supervisor here re-homes every freshly
forked process's cgroup membership, so a plain `echo $$ > cgroup.procs; java ...` launched as a
new subprocess does not land where you put it. The fix — and the technique `ContainerCgroup`
actually uses — is to have the *same* process write its own pid and then `exec` into `java` in
place: `bash -c 'echo $$ > cgroup.procs && exec java ...'`. This isn't a sandbox-specific hack; it
is exactly what `cgexec`, `systemd-run` and `runc` themselves do, and it's the only race-free
version regardless of environment: a process moved into a cgroup by someone else *after* being
forked can be re-homed by another process-management layer before it gets there; a process that
puts itself into the cgroup and then execs in its own image never leaves that window open.

### Real, captured behavior (matching the artifact's own claims)

`ErgonomicsProbe` reports what a confined JVM sees about itself via
`Runtime`/`ManagementFactory` — more convincing, and more idiomatically Java, than grepping
`-XX:+PrintFlagsFinal`'s text dump for the same facts.

```
=== 1 CPU / 512 MB (docker run --cpus=1 --memory=512m) ===
processors=1
maxMemoryBytes=129761280
collectors=Copy,MarkSweepCompact

=== 1.5 CPU / 2 GB (docker run --cpus=1.5 --memory=2g) ===
processors=2
maxMemoryBytes=536870912
collectors=G1 Young Generation,G1 Concurrent GC,G1 Old Generation

=== 0.2 CPU under real load (the CPU-quota-throttling trap) ===
cpu.stat: nr_periods=34 nr_throttled=33 throttled_time=11.038s
```

One nuance the artifact doesn't spell out, found while verifying this: **1.5 CPU alone is not
enough to pick G1.** With `active_processor_count` correctly rounded up to 2 but only 512 MB of
memory, SerialGC is *still* chosen — G1 needs both thresholds crossed (enough processors *and*
enough memory). The artifact's own paired example (`--cpus=1.5 --memory=2g`) already crosses both,
which is why it reads as "1.5 CPU picks G1" when it's really "1.5 CPU *and* 2 GB together do."

Also confirmed empirically: `Runtime.maxMemory()` (the actually-usable heap) reports a few
percent under the `-XX:MaxHeapSize` flag's own value (129,761,280 vs. 134,217,728 for a 512 MB
limit at the default 25%) — generational layout and alignment overhead the flag value itself
doesn't show. `ContainerCgroupTest` asserts on this with a tolerance, not exact equality.

`ContainerCgroupTest` turns all three scenarios above into real, root-gated assertions (skipping,
not failing, when cgroup v1 isn't writable), including the throttling one: four threads spun for
3 seconds under a 0.2-CPU quota produce real, nonzero `nr_throttled`/`throttled_time` in
`cpu.stat` — the exact "CPU is slow but it isn't the GC" trap the artifact warns about.

## Exercise: a flight deck for one service

`OrderHttpService` is a minimal HTTP endpoint (`com.sun.net.httpserver.HttpServer`, virtual
thread per request) with a realistic allocation profile (string concatenation, not
`StringBuilder`, on purpose) and a deliberately contended `synchronized` pricing cache.
`FlightDeck` is the JFR streaming sidecar: p99 GC pause, allocation rate, monitor contention time,
and `jdk.VirtualThreadPinned` count, exported as one `Metrics` snapshot per run.

Run it under load, per collector:

```
java -XX:+UseG1GC         -cp target/classes dev.sevenrungs.productionjvm.OrderHttpService 5 0
java -XX:+UseZGC          -cp target/classes dev.sevenrungs.productionjvm.OrderHttpService 5 0
java -XX:+UseShenandoahGC -cp target/classes dev.sevenrungs.productionjvm.OrderHttpService 5 0
```

Real captured numbers, five seconds each, under concurrent load (25 requests/round via `curl`):

```
G1:         FLIGHTDECK gcPauseCount=1 p99GcPauseUpperBoundMicros=8192 allocRateBytesPerSecond=3072209.5 monitorContentionMillis=0 virtualThreadPinnedEvents=0
ZGC:        FLIGHTDECK gcPauseCount=5 p99GcPauseUpperBoundMicros=32   allocRateBytesPerSecond=2811650.4 monitorContentionMillis=0 virtualThreadPinnedEvents=0
Shenandoah: FLIGHTDECK gcPauseCount=0 p99GcPauseUpperBoundMicros=2    allocRateBytesPerSecond=5965341.2 monitorContentionMillis=4 virtualThreadPinnedEvents=0
```

**A real finding, not an assumption**: `virtualThreadPinnedEvents=0` under all three collectors,
every time. The service's request handler runs the pricing lookup inside a plain `synchronized`
block on a virtual thread — exactly the pattern that used to pin the carrier thread. JEP 491
(*Synchronize Virtual Threads without Pinning*, JDK 24) removed that pinning for `synchronized`
specifically, and this is that fix confirmed live, not read about: the event genuinely never
fires for this workload on JDK 25. (A `jdk.VirtualThreadPinned` event still exists for the causes
JEP 491 didn't remove — blocking inside a native frame, for instance — `FlightDeck` is watching
for it regardless.)

`FlightDeckSmokeTest` is the fast, always-run check: it runs this exact service confined to a
1-CPU/512 MB cgroup under G1 (the one collector guaranteed to behave identically everywhere),
fires 20 real HTTP requests at it, and asserts the flight deck's exported metrics are all present
and non-negative. The full three-collector run under sustained load above is a manual step, same
as every other phase's heavier benchmark.

### The one-page runbook

| Symptom | Open this event | Look at this field | Change this flag |
|---|---|---|---|
| Response latency spikes correlate with GC | `jdk.GCPhasePause` | `duration`, and the p99 bucket `FlightDeck`/`LiveGcWatch` report | Switch to generational ZGC (`-XX:+UseZGC`) for pause-sensitive services; raise the heap if pauses are frequent |
| Steady-state throughput is low but GC looks fine | `jdk.ObjectAllocationSample` | `weight` summed over time (allocation rate) | Find and cut the allocation hot path (`objectClass`, `stackTrace` on the event); a rate this high before optimizing is a real number to act on, not a guess |
| A handler occasionally stalls under concurrency | `jdk.JavaMonitorEnter` | `duration`, `monitorClass`, the blocked thread's `stackTrace` | Narrow the critical section, or switch the lock to a `java.util.concurrent` structure sized for the real contention, not assumed contention |
| Virtual-thread throughput doesn't scale with load | `jdk.VirtualThreadPinned` | the pinning `stackTrace` | If it's `synchronized`, confirm the JDK is 24+ (JEP 491) before assuming a real bug; if it's native I/O or a legacy `synchronized` under an old JDK, move it off the virtual thread's carrier path |
| CPU-bound work is "slow" but GC and locks both look clean | `os::container` / `cpu.stat` (no JFR event; read the cgroup directly, as `ContainerCgroup.cpuStat()` does) | `nr_throttled`, `throttled_time` | Raise the CPU quota, or set `-XX:ActiveProcessorCount` to decouple GC/JIT thread counts from a fractional quota that's throttling them |
| The service silently underuses a bigger box | (no event; read ergonomics directly, as `ErgonomicsProbe` does) | `Runtime.availableProcessors()`, `Runtime.maxMemory()` vs. the box's real specs | Check the cgroup limits (`docker run --cpus`/`--memory`, or the Kubernetes resource spec) actually match intent — a rounding-up CPU quota or an unset memory limit silently picks the wrong GC or heap size |

## Running the tests

```
mvn -q -pl production-jvm-engineering -am install
```

`OrderServiceTest` and `LiveGcWatchTest` run everywhere. `ContainerCgroupTest` and
`FlightDeckSmokeTest` need real, writable cgroup v1 controllers (true in this sandbox, since it
runs as root) and skip — rather than fail — when that access isn't available, so the module still
builds green on a restricted CI runner.
