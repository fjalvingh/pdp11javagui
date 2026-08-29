# Disassembler

[← Execution control](05-execution.md) · [Manual index](README.md) · [Next: Registers and bitfields →](07-registers-and-bitfields.md)

---

**Windows → Disassembler.** Memory read back off the machine, shown as PDP-11 instructions, with
the line the PC is on marked.

## It follows the program counter on its own

Every time the machine stops — after a halt, after a single step — this window reads the eleven
words around the new PC, decodes them and marks the line. You do not have to press anything, and
the [execution window](05-execution.md) does not have to be open. Stepping through a program is
therefore: press **Single step**, look here.

Two consequences worth knowing:

* **A window that is not on the screen does not read memory.** Every stop would otherwise cost
  eleven examines over a serial line for a window nobody is looking at. Open it and it catches up
  immediately rather than waiting for the next stop.
* **Listing an address of your own stops it following.** A marker that moves the listing out from
  under you while you are reading is worse than no marker. It starts following again the next
  time the machine stops.

## The controls

| Control | What it does |
|---|---|
| **From** | Where to start, octal. Enter here is the same as **Show** |
| **Show** | List 100 instructions from there |
| **>** | Another 100, added below, carrying on from where the listing left off |
| **<** | Back up 32 bytes and list again from there |
| **Use cached values** | When on (the default), words already read are not read again |

**A page is 100 instructions, not a range of addresses.** How many words that is cannot be known
before they have been decoded — an instruction is one, two or three of them — so the window reads a
page's worth of words, decodes, and asks for as many more as it is still short of. It does not read
three words per line on the off-chance, which over a serial line would double what a page costs.

**>** continues the same listing rather than starting a new one, and this matters: **where an
instruction starts is a guess.** Nothing in memory says which words are opcodes, so a page that
began again at a guessed boundary could decode the same bytes into different instructions across
the page break. The next 100 begin exactly where the last 100 stopped.

**<** is how you correct a listing that started on the wrong word — if you begin decoding on an
operand the whole listing is nonsense, plausible nonsense. It backs the start up 32 bytes and
builds the listing again from there; it is usually obvious within two lines which alignment is
right. It throws the old listing away, because continuing one that was decoded wrongly would keep
the boundaries that were wrong.

**Use cached values** is the difference between a window that keeps up with single-stepping over a
9600-baud line and one that does not. Turn it off when you are looking at memory that something
else is changing underneath you — self-modifying code, or a buffer a device is filling.

## The status line

* `100 instructions from 001000 to 001450 - the next page starts at 001454` — the ordinary case.
  The last address is where **>** will carry on from.
* `PC at 001006` — the PC is in the listing and the line is marked.
* `PC at 001006 - listing realigned to 001004, because the PC is inside an instruction that starts
  earlier` — the PC landed on the second or third word of a multi-word instruction. The listing
  backed up to the instruction that actually contains it rather than pretending an operand word is
  an opcode.
* `Nothing has been read from this range yet`, or `Not connected, so there is nothing to
  disassemble`.

## Addresses here are virtual

The disassembler works in the 64 KB a program can see, whatever the physical machine is — an
instruction stream only means anything through the processor's own eyes. If your machine has an
MMU turned on, [the MMU window](08-mmu.md) is what tells you which physical memory those addresses
are reaching.

Disconnecting throws away everything read from the old machine: nothing read from one PDP-11 can
be trusted about the next one.

---

[← Execution control](05-execution.md) · [Manual index](README.md) · [Next: Registers and bitfields →](07-registers-and-bitfields.md)
