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

Phase 5 of 8, partly done. **It runs and it works against a machine**, though the windows that
make it useful for real work are not all there yet.

What works today:

- Three console protocols: **SimH**'s remote console, microcode **ODT** (16/18/22 bit, DEC and
  Robotron K1630), and the **PDP-11/44** console processor (both firmwares). Examine, deposit,
  bulk examine, reset, run, halt and single step on all of them.
- **Simulated machines for every one of them**, inside the JVM. `File → Connect to simulated`
  needs no hardware, no SimH and no serial port, and is the quickest way to see what this does.
- Real SimH, launched by the application, over its remote console.
- A terminal showing the whole conversation - the console's own automated commands and their
  replies included, which is how a flaky console gets debugged - and a Log window with one
  column per channel.
- Settings and window geometry remembered between runs.

Not yet: the Settings dialog, the Memory view, Execution Control and the Disassembler; the
M9301/M9312 boot-ROM console; the assembler and disc-image tooling of phases 6 and 7.

`PLAN.md` has the phase table and, under each phase, what that phase actually found.

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

Then **File → Connect to simulated → …** for a machine that needs nothing installed, or
**File → Connect** for SimH, which needs `pdp11` on `PATH`.

Settings live in the platform's configuration directory - `~/.config/pdp11gui/settings.json` on
Linux, `%APPDATA%` on Windows, `~/Library/Application Support` on macOS. Point `XDG_CONFIG_HOME`
somewhere else to run against a throwaway configuration.

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

## Tests

```
./mvnw test
```

Everything runs headless, including the layout tests: the windows are thin frames around
`JPanel`s, and a panel can be laid out and painted into an image with no display. Each build
also writes `pdp11-ui/target/ui-render/*.png`, which is what those layouts actually look like —
assertions catch a layout that is broken, and a picture catches one that is merely wrong.

Two test classes need something the build machine may not have and skip when it is missing:
`SimhProcessTransportIT` and `SimhConsoleIT` want SimH's `pdp11` on `PATH`. The disassembler is
checked against a committed 65536-word corpus rather than against SimH, so it needs nothing.

## External tools

`macro11` (the MACRO-11 assembler) and SimH's `pdp11` are invoked as external processes and
must be on `PATH` **if you want to use them**. Neither is needed to build, to run the tests, or
to drive a simulated machine. `m4` is not needed at all: machine-description preprocessing is
reimplemented in Java.

## Licence

GPL-2.0, matching the original.
