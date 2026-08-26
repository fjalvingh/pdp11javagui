# Device registers and bitfields

[← Disassembler](06-disassembler.md) · [Manual index](README.md) · [Next: The MMU →](08-mmu.md)

---

Memory is words at addresses. A *device register* is a word at an address that has a name, a
purpose, and half a dozen individually meaningful bits in it. These two windows are for looking at
memory that way.

Both are built from [the loaded machine description](16-machine-descriptions.md) — the `.ini` file
that says what devices this machine has and where they are jumpered. The one shipped with
PDP11GUI describes a generic PDP-11 and declares seventeen device groups.

## Device register windows

**Windows → Device registers ▸** and pick a device. There is one window per device group in the
description, and each is created the first time you ask for it.

Where [a memory window](04-memory.md) lays cells out across a grid by address, this is a **list**:
one register per row, with its name, its address, its value and what the description says it is —
`PSW`, `0177776`, *Processor Status Word*.

| Button | What it does |
|---|---|
| **Examine all** | Read every register of this device |
| **Examine cell** | Read the selected one |
| **Deposit changed** | Write only the registers you have edited |
| **Deposit all** | Write every register |

All four need a connection and are dead without one. A line at the bottom says what the device is,
how many registers it has, and how many you have changed and not deposited.

**Two rows at the same address is normal here.** The RX211's data buffer, for instance, is declared
six times under six names because the controller reinterprets it at each stage of a transfer. All
six rows show, they hold the same word, and they are kept in agreement.

Editing works as it does everywhere: typing sets what *should* be there, and a separate button
writes it. See [what a cell's two values mean](04-memory.md#what-a-cells-two-values-mean).

## The Bitfields window

**Windows → Bitfields.**

A word of a PDP-11 device register is rarely a number — it is half a dozen flags and a two-bit
mode field. This is where you set the mode field to 3 without working out that it means adding
`014000`.

The window shows one register:

* **Address** and **Value** at the top, both typeable. Enter in the address field points the
  window at that address, and reads it if there is a machine to read from.
* A table of the register's named fields, from the machine description: **Name**, **Bits**,
  **Mask**, **Value**, **Max** and **Info**. The **Value** column is editable.
* **Examine cell** and **Deposit cell**.

**The value and the fields are two views of one number.** Type in the value and every field
updates; type in a field and the value updates. Setting a field to more than it can hold is
refused rather than allowed to overflow into its neighbour.

### How the window gets pointed at a register

Two ways, and they are independent:

1. **Select a register in any memory or device window.** The selection is published, and this
   window follows it. Nothing tells it to — it is watching.
2. **Type an address into the Address field** and press Enter. This is how you look at the bits of
   a device whose window you do not have open.

With nothing selected it says so. With an address that the machine description has no bit
definitions for, it says that instead — the register is real, the *names* for its bits are what is
missing, and adding them is [an edit to the description](16-machine-descriptions.md).

### It works on its own copy

The value in this window is this window's copy of the register, at the same address. Experimenting
with the bits is not a change to what the memory window beside it is showing — until you press
**Deposit cell**, at which point the value is written to the machine and every window showing that
address is told.

---

[← Disassembler](06-disassembler.md) · [Manual index](README.md) · [Next: The MMU →](08-mmu.md)
