# PDP11GUI (Java/Swing)

An IDE for real and simulated PDP-11 computers: write MACRO-11, load it onto the machine, run
and single-step it, disassemble, and inspect memory and registers.

This is a rewrite in Java/Swing of [Joerg Hoppe's PDP11GUI](http://www.retrocmp.com/tools/pdp11gui),
which is Delphi/VCL for Windows and has since been ported to Lazarus/Free Pascal for Linux.
That Pascal tree lives at `../pdp11gui` and is the reference implementation throughout the
port; nothing here builds against it.

The rewrite is not a transliteration. It drops MDI for free-floating top-level windows,
replaces the single-threaded `Application.ProcessMessages` I/O model with real threads, and
rebuilds the UI with layout managers instead of ~69k lines of absolute-positioned `.dfm`.

**`PLAN.md` is the authoritative design and phasing document.** Read it before starting work.

## Status

Phase 0 of 8: scaffolding. The build runs on all three platforms and the main window opens;
there is no functionality yet.

## Building

Requires JDK 21 or later. Use the Maven wrapper, which pins Maven 3.9.x — the build refuses
anything older, and Maven 3.5 is still the default on some machines here.

```
./mvnw verify
```

## Running

```
./mvnw -pl pdp11-app exec:java
```

or, after `./mvnw package`:

```
java -jar pdp11-app/target/pdp11gui.jar
```

## Modules

| Module | Contains |
|---|---|
| `pdp11-core` | Model, transports, console protocol, disassembler, disc images. Headless. |
| `pdp11-ui` | Swing windows, the window manager, settings binding. |
| `pdp11-app` | `main()`, packaging, and the data resources. |

`pdp11-core` must not depend on Swing or AWT — that is what keeps the console protocol layer
testable against the ported fake PDP-11s with no display. The rule is enforced twice: the
module compiles with `--limit-modules java.base`, so an offending import is a compile error,
and an ArchUnit test covers what the flag cannot see. See `CLAUDE.md` for the full set of
rules the code has to hold to.

## External tools

`macro11` (the MACRO-11 assembler) and SimH's `pdp11` are invoked as external processes and
must be on `PATH`. `m4` is *not* needed: machine-description preprocessing is reimplemented
in Java.

## Licence

GPL-2.0, matching the original.
