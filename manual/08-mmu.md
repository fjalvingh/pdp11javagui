# The MMU

[← Registers and bitfields](07-registers-and-bitfields.md) · [Manual index](README.md) · [Next: The assembler →](09-assembler.md)

---

**Windows → MMU.** What the machine's memory management unit is currently doing: which virtual
addresses reach which physical ones.

A PDP-11 program sees 64 KB. A PDP-11 with an MMU may have four megabytes behind that, and the
page registers decide which piece of it each 8 KB slice of the program's address space lands on.
When a program is reading the wrong memory, this window is where you find out why.

## What it shows

Two tabs, **Instruction space** and **Data space**, each a table of the blocks the current page
registers produce:

| Column | Meaning |
|---|---|
| **#** | The block number |
| **Virtual** | The range of virtual addresses |
| **Physical** | Where they actually land |
| **Size** | How big the block is |

Adjacent pages that map contiguously are shown as one block, so a simple map is a handful of rows
rather than sixteen.

If the second tab is titled **Data space (off)**, this mode has data space disabled — data
accesses go through the instruction map, and the table shows what that means.

## The controls

* **CPU mode** — Kernel, Supervisor, Illegal or User. It opens on the mode the machine is actually
  in and says so beside it (`(the mode the machine is in)`, or `(the machine is in Kernel)` when
  you have moved it). **You can look at any mode, not only the current one** — which matters,
  because the moment you want to see the user map is usually the moment the machine is stopped in
  the kernel.
* **Examine all** — read the PSW, MMR0, MMR3 and all four sets of page registers off the machine.

The window reads the registers when you open it and again when a machine arrives, so it opens on
an answer rather than on an empty map waiting for a button. It also follows the registers: examine
them from somewhere else and the map redraws.

## The status line

One line says what the tables mean:

* `Relocation disabled: virtual is physical, apart from the I/O page` — the MMU is off. Everything
  below is the identity map.
* `Kernel, 22-bit mapping` — relocation is on, and this is which mapping mode.
* `… data space disabled, so data goes through the instruction map`.
* `Cannot work out the memory map from these registers: …` — the registers do not describe a
  coherent map. One page register reading back all ones is enough, and that is itself a finding.
* `Not connected to a machine`.

## Why an address does not translate

Translation can fail two different ways, and the window distinguishes them:

* **Not assigned** — no page maps that address at all.
* **A page length error** — the page exists, but the address is past its declared length. This is
  what the unused end of a stack page looks like, and calling it "not assigned" would be wrong and
  misleading.

---

[← Registers and bitfields](07-registers-and-bitfields.md) · [Manual index](README.md) · [Next: The assembler →](09-assembler.md)
