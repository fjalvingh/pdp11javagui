# Pdp11 Java GUI

An IDE for real and simulated PDP-11 computers: connect to a machine's console, look at and change
its memory, write MACRO-11 and load it, run and single-step it, disassemble what is there, and find
out what is wrong with a machine that is not working.

This is a Java rewrite of [Joerg Hoppe's PDP11GUI](http://www.retrocmp.com/tools/pdp11gui), and it
would not exist without his great work: the console protocols, the machine descriptions, the
memory tests and the knowledge of how real PDP-11 consoles behave all come from his program. It
runs on Linux, Windows and macOS.

**[The user manual](manual/README.md)** describes every window, how to connect to a machine and how
to get a program into one.

## Highlights

- **Works without a machine.** `File → Connect to simulated` connects to a PDP-11 simulated inside
  the program, for every console type it supports - no hardware, no SimH, no serial port needed.
- **Many consoles, many connections.** SimH's console (with SimH launched by the program), microcode
  **ODT** (16/18/22 bit, DEC and Robotron K1630), the **PDP-11/44** console processor, and the
  **M9312/M9301** boot ROM consoles; over a serial line, telnet or SimH. Connections are saved as
  named profiles.
- **One memory, every window.** A value typed, loaded from a file or produced by the assembler shows
  in every window at once, before it is deposited; changes waiting to be deposited are always
  visible and can be deposited or discarded together.
- **A built-in MACRO-11 assembler.** Assemble, load and run in one step, with macro libraries, every
  error reported in one pass and warnings for code that assembles but is probably wrong.
- **A disassembler that follows the PC**, names well-known addresses (`TPS: Console transmitter
  status`) and lets you mark words, bytes and strings as data.
- **Hardware diagnosis**: memory tests that say which data line, address line or chip is broken, an
  I/O page scanner that finds what is plugged in, and a library of DEC's XXDP and MAINDEC
  diagnostics that can be collected from the online archives and run straight from the program.
- **Free-floating windows** that remember where they were, in a dark theme.

## Notable changes from the original

- **Free-floating windows instead of one big MDI window**, so tool windows can be spread over
  several monitors. Each one opens where it was last time.
- **The program never freezes while it talks to the machine.** All console work runs in the
  background, so windows stay responsive during a long load, scan or memory test, and a long
  operation shows its progress and can be cancelled.
- **Fast deposits.** On an M9312 boot ROM console, a large deposit first puts a small loader into
  the machine and then sends the rest as checksummed binary blocks. The median XXDP diagnostic
  loads in about 40 seconds at 2400 baud instead of 4 minutes, and garbled characters are recovered
  from. Over SimH, deposits are batched a hundred at a time: a 6,000-word diagnostic takes three
  seconds instead of five minutes.
- **An embedded MACRO-11 assembler.** The original ran the external `macro11`; this assembler is
  part of the program and checks far more. It reports every error in one pass, including mistakes
  `macro11` let through silently: a digit outside the radix, a value too big for its field (`EMT
  400` used to assemble as `TRAP 0`), junk after the operands, undefined symbols, branches out of
  range. It also warns about 25 kinds of code that assembles but is probably wrong: unreachable
  code, `CLR #5`, a word access at an odd address, unused labels. It reads RT-11 and RSX macro
  libraries, and the program starts where `.END` says rather than at its lowest address.
- **A better disassembler.** It has been checked against SimH on all 65536 instruction words.
  Doing that turned up 183 words the original decoded wrongly, which are correct here. It
  comments references to well-known addresses (vectors, CPU and MMU registers, the standard
  devices' registers), lets you mark ranges as words, bytes or strings, and can show a program
  that was loaded or assembled but not yet deposited.
- **Memory is shared between windows**, and reading the machine never throws away a change that has
  not been deposited yet. In the original, every window had its own copy, and an examine could
  overwrite what you had just typed.
- **Simulated machines for every console type** are built into the program, so you can try
  everything without hardware or SimH.
- **The M9312/M9301 boot ROM consoles** and the **PDP-11/05's microcode** are new, as is the
  **diagnostics library**.
- **Fixed file formats.** The split low/high byte format, used for programming ROMs, wrote an
  all-zero high byte file and read back only half the words. Both bugs are fixed. A word that was
  never read is reported rather than silently written as `177777`.
- **Runs on Linux, Windows and macOS**, and is a single jar.

## What is there

The desktop program:

- **Memory** windows (as many as you like): an editable octal grid, examine and deposit by cell or
  range, fill, verify, and export as a SimH `DO` script.
- **Execution control**: reset, start, continue, halt, single step and the PC, as far as the
  connected console supports them.
- **Disassembler**, **Assembler**, and example programs to start from (`File → Load examples`).
- **Device registers** from the machine description, and **Bitfields**, which breaks a register into
  its named bits and edits it from either side.
- **MMU** window, showing any mode's mapping.
- **Memory loader and dumper** in five formats: binary, split low/high byte files for programming
  ROMs, a readable listing, bare octal words, and DEC Absolute Paper Tape.
- **Memory test** and **I/O page scanner**.
- **Diagnostics library**: collects XXDP and MAINDEC programs from bitsavers and other archives,
  reads them off the disc, tape and floppy images they come on, files them by what they test using
  DEC's own diagnostic index, and loads and starts the standalone ones.
- **Microcode** of the PDP-11/44 and of both revisions of the PDP-11/05, a microword at a time,
  decoded into named fields. On an 11/05 with a KM11 it follows the machine's microcode as it runs.
- **Terminal**, **Log**, **SimH console** and **Number converter**.

## Still to do

- The **Blinkenlight execution** window, which produces instructions for keying a program in on a
  real front panel.
- **Disc image transfer**: reading and writing a disc on a real machine through its console. The
  diagnostics library can already read the image formats; moving them to and from a machine is
  not there yet.
- **Installers** per platform; for now the program is a single jar.

The PDP-11/70 front panel of the original is deliberately not carried over.

## Running

Download `pdp11gui-<version>.jar` from the
[releases page](https://github.com/fjalvingh/pdp11javagui/releases) and run it with Java 21 or later:

```
java -jar pdp11gui-<version>.jar
```

Then **File → Connect to simulated → …** for a machine that needs nothing installed, or
**File → Connection settings** to describe a real one. **Windows → …** opens the tool windows.

To use a real SimH, its `pdp11` has to be on your `PATH`. Nothing else needs to be installed.

To build it from a checkout, which needs the `pdp11-common` submodule:

```
git clone --recurse-submodules https://github.com/fjalvingh/pdp11javagui.git
cd pdp11javagui
./mvnw verify
java -jar pdp11-gui/pdp11-app/target/pdp11gui.jar
```

`pdp11-common` - the disassembler, the assembler, the memory file formats and the microcode,
everything that needs no machine - is shared with
[toolsapp](https://github.com/fjalvingh/toolsapp), a web application of PDP-11 tools that has
the microcode browsers with every microword reachable by its own link.

## Licence

GPL-2.0, matching the original.
