# Phase 3 — The Foreign Function & Memory API and native interop

JNI made native code a second language with its own build, its own bugs, and no safety. The FFM
API (JEP 454, final in JDK 22) moves the whole binding into Java: a native function is a
`MethodHandle`, native memory is a `MemorySegment`, and lifetime is an `Arena`. This phase is
about trusting that model enough to build real things on it, not just call `strlen`.

## Read in this order

1. [JEP 454](https://openjdk.org/jeps/454) in full. The sections on arenas, restricted methods and
   the "Memory access" ordering guarantees are the ones people skip and then get wrong.
2. The `MemoryLayout` javadoc: layout paths, `withTargetLayout`, sequence layouts and strides. Then
   `Linker.Option`: `captureCallState` (errno) and `firstVariadicArg`.
3. [JEP 472](https://openjdk.org/jeps/472), then find every JNI or `Unsafe` use in a codebase you
   know and decide which FFM construct replaces it.
4. The [jextract](https://github.com/openjdk/jextract) README and one generated binding, to see
   what a production-grade wrapper looks like before you write your own — see the note below on
   why this repository's own exercise doesn't use it.

## The three rungs

| Difficulty | Class | Run it |
|---|---|---|
| medium | `Strlen` | `mvn -q -pl native-interop compile exec:exec -Dexec.mainClass=dev.sevenrungs.nativeinterop.Strlen` |
| hard | `QsortStructs` | `mvn -q -pl native-interop compile exec:exec -Dexec.mainClass=dev.sevenrungs.nativeinterop.QsortStructs` |
| guru | `MappedRing` | see below — needs two terminals |

Every rung calls a restricted method (`Linker.downcallHandle`, `upcallStub`, or mapping a shared
segment), so `native-interop/pom.xml` wires `--enable-native-access=ALL-UNNAMED` into both the
exec arguments and Surefire — on this JDK (25.0.1) that flag silences a warning rather than fixing
an error, but JEP 472 promises the warning becomes an error later, and the rungs are written to
need the flag regardless.

**`MappedRing`** needs two real processes agreeing on a mapped file:

```
mvn -q -pl native-interop compile

java --enable-native-access=ALL-UNNAMED -cp native-interop/target/classes \
    dev.sevenrungs.nativeinterop.MappedRing produce &
java --enable-native-access=ALL-UNNAMED -cp native-interop/target/classes \
    dev.sevenrungs.nativeinterop.MappedRing consume
# consumer prints 500000500000 once both finish
```

(or via Maven: `-Dexec.mainClass=dev.sevenrungs.nativeinterop.MappedRing -Dexec.programArgs=produce`
in one terminal, `...programArgs=consume` in another — both default to `/dev/shm/ring.bin`;
override with `-Dring.path=...` if running two pairs on the same host at once.)
`MappedRingTest` proves both the in-process protocol and the real two-process case (forking two
child JVMs via `ProcessBuilder`, the same pattern `jvm-internals`'s `GcMultiCollectorSmokeTest`
uses) as part of the normal `mvn verify` run.

## The exercise: bind a real library, then try to break it

> Pick zlib or libsodium. Generate bindings with jextract, then hand-write a small idiomatic Java
> API on top with arena-scoped resources and no leaked segments. Benchmark the compress/encrypt
> path against a JNI baseline with JMH... Write tests that attempt use-after-close,
> out-of-bounds writes, and cross-thread access to a confined arena. Every one must fail as a Java
> exception, never a crash. Capture `errno` through `captureCallState` for one failing call and
> surface it as a typed exception.

What's here for it, under `exercise/`:

- **`ZlibCodec`** — an arena-scoped wrapper over zlib's `compress2()`/`uncompress()`. One `Arena`
  per codec instance, closed once, frees every `MemorySegment` it ever allocated in one step; a
  bad zlib return code becomes `ZlibCodec.ZlibException` instead of a raw integer.
- **`NativeErrno`** — `captureCallState("errno")` around libc's `open()`, surfaced as
  `NativeErrno.NativeIoException` carrying the real `errno` (e.g. `2` = `ENOENT`). Standalone from
  `ZlibCodec` on purpose: zlib's own functions return a `Z_*` code, not `errno`.
- The JMH-vs-JNI benchmark half of this exercise lives in the sibling module,
  [`native-interop-bench`](../native-interop-bench/README.md), for the same reason
  `concurrency-jmm-jcstress` is its own module: it needs an annotation processor, a compiled native
  library, and its own shaded jar.

**Why zlib, not libsodium**: this sandbox has `zlib1g-dev` (so `zlib.h` is there for reference)
but only `libsodium23`'s runtime `.so`, no `-dev` headers — zlib was the one the artifact's own
"pick zlib or libsodium" choice actually fit here.

**Why hand-written bindings, not jextract**: `jextract` is a separate LLVM-based release artifact,
not a Maven dependency, and isn't installed in this environment. `Strlen` and `QsortStructs` above
already hand-write their `FunctionDescriptor`/`MethodHandle` bindings directly rather than through
generated code, so `ZlibCodec` does the same — a deliberate, documented substitution rather than
silently skipping the step or claiming a tool ran that didn't.

**Safety tests, proven, not asserted**: `ZlibCodecTest` closes a codec and then touches a segment
it handed out (`IllegalStateException`), writes one byte past a scratch segment's end
(`IndexOutOfBoundsException`), and touches a confined arena's segment from a different thread
(`java.lang.WrongThreadException` — it lives in `java.lang`, not `java.lang.foreign`, easy to
guess wrong; confirmed empirically while writing this). Every one is a real Java exception a real
test provokes, never a crash.
