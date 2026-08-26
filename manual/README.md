# PDP11GUI user manual

PDP11GUI is an IDE for real and simulated PDP-11 computers. It connects to a machine's console —
over a serial line, over telnet, to a SimH simulator it launches itself, or to a machine simulated
inside the program — and from there lets you look at memory, change it, assemble a MACRO-11
program and load it, run and single-step it, disassemble what is there, and find out which part of
a failing memory is broken.

This manual describes the Java/Swing version. It is written for someone sitting in front of the
program with a machine (or no machine at all) to hand.

## Start here

* **[Getting started](01-getting-started.md)** — install, run, and drive a simulated PDP-11
  without owning one.
* **[The main window](02-main-window.md)** — the terminal, the status bar, the menus, and how
  tool windows behave.
* **[Connecting to a machine](03-connecting.md)** — console protocols, transports, and saved
  connection profiles.

## Looking at the machine

* **[Memory](04-memory.md)** — the memory grid, examine and deposit, and what the two values in a
  cell mean.
* **[Execution control](05-execution.md)** — reset, start, continue, halt, single step, and the PC.
* **[Disassembler](06-disassembler.md)** — memory as instructions, following the program counter.
* **[Device registers and bitfields](07-registers-and-bitfields.md)** — one window per device, and
  a register broken into its named bits.
* **[The MMU](08-mmu.md)** — which virtual addresses reach which physical ones.

## Getting programs in and out

* **[The assembler](09-assembler.md)** — write MACRO-11, assemble it, load it, run it.
* **[Loading and dumping memory files](10-load-and-dump.md)** — four file formats, in both
  directions.

## Diagnosing hardware

* **[Memory test](11-memory-test.md)** — data lines, address lines, chips, and random patterns.
* **[I/O page scanner](12-io-page-scanner.md)** — find out what is actually plugged in.
* **[Microcode](13-microcode.md)** — the PDP-11/44's and the PDP-11/05's microcode, microword by
  microword.

## Everything else

* **[Terminal, log and SimH console](14-terminal-log-simh.md)** — the three windows that show you
  the wire.
* **[Number converter](15-number-converter.md)** — octal, decimal, hex and binary at once.
* **[Machine descriptions](16-machine-descriptions.md)** — the `.ini` files that say what devices
  your machine has, and how to write one.
* **[Settings and files](17-settings-and-files.md)** — where everything is kept.
* **[Troubleshooting](18-troubleshooting.md)** — what to do when it will not connect, will not
  assemble, or answers nonsense.
* **[Glossary](19-glossary.md)** — ODT, deposit, the I/O page, and the rest of the vocabulary.

## Conventions used throughout

**Everything is octal.** Addresses and values in every window, in every file format and in this
manual are octal, as they are in every PDP-11 document ever printed. `177560` is a console
receiver status register, not one hundred and seventy-seven thousand. The
[number converter](15-number-converter.md) is there for when that stops being convenient.

**Nothing you type reaches the machine until you deposit it.** Typing in a memory grid changes
what *should* be there; a separate button writes it. See
[what a cell's two values mean](04-memory.md#what-a-cells-two-values-mean) — it is the single most
important idea in the program.

**A greyed-out button is telling you something.** Which operations are offered depends on what the
console on the other end can actually do; an ODT console cannot single-step without a HALT switch,
and SimH cannot reset without also starting. See
[the enablement rules](05-execution.md#why-a-button-is-greyed-out).
