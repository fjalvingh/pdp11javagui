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

Phases 0-5 are done and phase 6 is under way. **It runs, it works against a machine, and it is
useful**: connect, look at memory, change it, load a program into it and run it, single-step,
watch the disassembly follow the program counter, and find out what is wrong with a machine whose
memory is failing.

What works today:

- Three console protocols: **SimH**'s remote console, microcode **ODT** (16/18/22 bit, DEC and
  Robotron K1630), and the **PDP-11/44** console processor (both firmwares). Examine, deposit,
  bulk examine, reset, run, halt and single step on all of them.
- **Simulated machines for every one of them**, inside the JVM. `File → Connect to simulated`
  needs no hardware, no SimH and no serial port, and is the quickest way to see what this does.
- Real SimH, launched by the application, and anything reachable over telnet or a serial line.
  A connection is `{console protocol} × {transport}` with saved named profiles, under
  `File → Connection settings`.
- **Memory view** (as many as you like): an editable octal grid, examine and deposit by cell or
  by range, fill-with-address, verify, and export as a SimH `DO` script.
- **Execution control**: reset, reset and start, continue, halt, single step, and the PC. Which
  buttons are live depends on what the connected console can actually do.
- **Disassembler**, which follows the program counter as the machine stops.
- **Device registers** from the machine description - 17 windows' worth from the shipped
  `pdp11.ini` - and **Bitfields**, which breaks the selected register into its named bits and
  edits it from either side.
- **I/O page scanner**: read all 4096 words of the I/O page, see what answers, and get an `.ini`
  section to paste into a description of your own machine.
- **Memory test**: data lines, address lines, data bits chip by chip, and random - the tests that
  say *which part* of a failing memory is broken.
- **Memory loader and dumper**: read a program out of a file into the machine, or a range of the
  machine's memory out to a file. Four formats - a binary byte stream, separate low-byte and
  high-byte files for programming a 16-bit memory built from 8-bit chips, a readable text listing,
  and DEC Standard Absolute Paper Tape, which is what a real PDP-11's own absolute loader reads.
- A terminal showing the whole conversation - the console's own automated commands and their
  replies included, which is how a flaky console gets debugged - and a Log window with one
  column per channel.
- Settings, saved connection profiles and window geometry remembered between runs.

Not yet: the assembler and the remaining tools of phase 6 (MMU, microcode, number converter,
blinkenlight execution, the SimH console and remote log windows), the M9301/M9312 boot-ROM
console, and the disc-image tooling of phase 7.

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
**File → Connection settings** to describe a real one. **Windows → …** opens the tool windows;
they are created when first asked for and remember where they were.

Settings live in the platform's configuration directory - `~/.config/pdp11gui/settings.json` on
Linux, `%APPDATA%` on Windows, `~/Library/Application Support` on macOS. Point `XDG_CONFIG_HOME`
somewhere else to run against a throwaway configuration.

Machine descriptions are installed on first run into the data directory -
`~/.local/state/pdp11gui/machines` on Linux - and `pdp11.ini` is loaded from there. They are
ordinary files: edit one to describe your own machine, and it will not be overwritten by a later
version. The I/O page scanner writes the sections for hardware the description does not know
about.

## Modules

| Module | Contains |
|---|---|
| `pdp11-core` | Model, transports, console protocols, simulated machines, disassembler, machine descriptions, memory tests, memory file formats. Headless. |
| `pdp11-ui` | Swing windows, the window manager, settings binding. |
| `pdp11-app` | `main()`, packaging, and the data resources. |

**Anything that is an algorithm rather than a layout belongs in `pdp11-core`**, even when only
one window uses it: the disassembly listing, the I/O page scan, the four memory tests and the
memory file formats all live there, and every one of them is tested with no display in sight.
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

The simulated machines can be **broken on purpose** — `FakePdp11.setStuckDataLines` and
`setDeadAddressLine` — which is how the memory tests are checked: tie a data line high and the
test that looks for a stuck data line has to say so. A diagnostic nobody has shown a real fault
to is a guess.

Anything with a reader and a writer is tested by **round trip**, in one test that does both. That
is how two bugs in the original's split-byte format were found: its writer produces an all-zero
high byte file and its reader takes half the words, and each of those hides the other.

## External tools

`macro11` (the MACRO-11 assembler) and SimH's `pdp11` are invoked as external processes and
must be on `PATH` **if you want to use them**. Neither is needed to build, to run the tests, or
to drive a simulated machine. `m4` is not needed at all: machine-description preprocessing is
reimplemented in Java.

## Licence

GPL-2.0, matching the original.
