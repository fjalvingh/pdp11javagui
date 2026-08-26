# Glossary

[← Troubleshooting](18-troubleshooting.md) · [Manual index](README.md)

---

**Absolute paper tape** — DEC Standard Absolute Paper Tape Format: blocks of data with addresses
and checksums, ending with a zero-length block that says where to start. What a real PDP-11's own
absolute loader reads. One of the four [memory file formats](10-load-and-dump.md#the-four-formats).

**Console** — the interface through which a PDP-11 can be examined and controlled without running
a program on it. Which dialect it speaks — ODT, an 11/44 console processor, SimH's `sim>`
— is one half of [a connection](03-connecting.md).

**Deposit** — write a value into a memory location through the console. The opposite of
examine. In PDP11GUI, typing a value does not deposit it; a button does.

**Edit value** — what a memory cell *should* contain, as opposed to what the machine last said it
does. See [what a cell's two values mean](04-memory.md#what-a-cells-two-values-mean).

**Examine** — read a memory location through the console.

**Glass teletype** — a terminal with no cursor addressing and no escape sequences: text arrives and
scrolls. What [the main window's terminal](02-main-window.md#the-terminal) is, because that is all
any console protocol here needs.

**I/O page** — the top 4 K words of the address space, where every device register lives. Reading
all of it is what [the I/O page scanner](12-io-page-scanner.md) does.

**KD11-B** — the processor board set of the PDP-11/05, on module M7261. Its microcode ships with
PDP11GUI in [both board revisions](13-microcode.md#which-revision-and-why-the-title-says-so).

**KM11** — a maintenance module which, plugged into a PDP-11/05, puts the microprogram counter on
lights. It is what makes [the microcode window](13-microcode.md#on-the-pdp-1105-this-is-a-debugger-not-a-reference)
a debugger on that machine.

**MACRO-11** — DEC's assembler for the PDP-11. PDP11GUI drives the external `macro11` program; see
[the assembler](09-assembler.md).

**Machine value** — what the PDP-11 last said a location contains. See
[what a cell's two values mean](04-memory.md#what-a-cells-two-values-mean).

**Machine description** — an `.ini` file saying which devices this machine has and where. See
[machine descriptions](16-machine-descriptions.md).

**Microword** — one word of a processor's control store: the bits that drive the datapath for one
microcycle. The PDP-11/05's are 40 bits wide, the PDP-11/44's 104. See
[Microcode](13-microcode.md).

**MMU** — memory management unit: the page registers that turn the 16-bit addresses a program uses
into the 18 or 22-bit addresses the memory sees. See [the MMU window](08-mmu.md).

**Module** — in a machine description, a macro that expands to one device's register definitions at
a given base address. See [modules](16-machine-descriptions.md#modules).

**ODT** — Online Debugging Technique: the console built into the microcode of an LSI-11-family
processor, presenting an `@` prompt when the machine halts. The usual way to talk to an 11/23,
11/73 or 11/93.

**Octal** — base 8, and the base every PDP-11 address and value is written in, here and
everywhere. `177560` is a console register address. [The number converter](15-number-converter.md)
translates.

**PC** — the program counter, register R7.

**Physical address** — the address the memory system sees, 16, 18 or 22 bits wide depending on the
machine. Contrast virtual address.

**Profile** — a saved, named [connection](03-connecting.md#the-connection-dialog): a console
protocol plus a transport plus its settings.

**Propagation** — how a value read or written at an address reaches every window showing that
address, without any window knowing about any other.

**SimH** — the historical-computer simulator. PDP11GUI can launch its `pdp11` binary and drive the
simulated machine through SimH's own `sim>` remote console; see
[the SimH console window](14-terminal-log-simh.md#the-simh-console-window).

**Simulated machine** — a PDP-11 simulated inside PDP11GUI itself, needing nothing installed. One
exists for every console protocol, and it is [the first thing to try](18-troubleshooting.md) when
something is not working.

**Single step** — execute exactly one instruction. Not all consoles can; see
[why a button is greyed out](05-execution.md#why-a-button-is-greyed-out).

**Transport** — how the console is reached: a serial line, telnet, a SimH process, or the
simulated machine. The other half of a connection.

**Verify** — read a range back off the machine *without* disturbing what you have loaded or typed,
so that disagreements colour themselves. Distinct from Examine, which replaces both values.

**Virtual address** — the 16-bit address a program uses, before the MMU translates it. The
[disassembler](06-disassembler.md) works in these.

**Word** — 16 bits, at an even address. All PDP-11 memory in PDP11GUI is shown by the word.

---

[← Troubleshooting](18-troubleshooting.md) · [Manual index](README.md)
