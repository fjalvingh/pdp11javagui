# Execution control

[← Memory](04-memory.md) · [Manual index](README.md) · [Next: Disassembler →](06-disassembler.md)

---

**Windows → Execution control.** Reset, start, continue, halt, single step, and where the PC is.

## The two PC fields

| Field | Meaning |
|---|---|
| **Start PC** | Where a program starts. This is not the machine's PC — it is where you intend to start it. Pressing Enter publishes it to the rest of the program, so the assembler and the disassembler know where the program is. **Reset and start** and **New program** use it |
| **Current PC** | The machine's PC. It follows the machine as it stops. Pressing Enter writes what you typed into R7 — the same as pressing **Set PC** |

Loading a program that says where it starts — a [paper tape image](10-load-and-dump.md), or a
MACRO-11 [listing](09-assembler.md) — fills **Start PC** in for you. Neither field is overwritten
while you are typing in it.

## The buttons

| Button | What it does |
|---|---|
| **Reset and set PC** | Reset the machine, then put **Start PC** into R7. The machine does not run |
| **Reset and start** | Reset the machine and start it running at **Start PC** |
| **Continue** | Carry on from where the machine stopped, without resetting it |
| **Halt** | Stop a running program — or, on a console that cannot, say which switch to move |
| **Single step** | Execute one instruction. The new PC arrives as a stop report and moves the display on |
| **Set PC** | Write **Current PC** into R7 |
| **New program** | Assemble the program in [the Assembler window](09-assembler.md), load it into the machine, reset, and set the PC to where it starts |

**New program** is the whole edit-assemble-load-run cycle on one button. It needs a machine to load
into, a source open in the assembler, and `macro11` on your `PATH`; it opens the assembler window
first, because that is where an error will be shown, and stops if the assembly fails.

A status line at the bottom says what the machine is doing: *stopped at 001000*, *running*, *state
unknown*, or *Not connected*. It is also where a mistyped address is reported.

## Why a button is greyed out

This is the substance of the window, not decoration. Which operations are offered depends on two
things at once: **what the console can do**, and **what the machine is doing**.

* **Not connected** — everything is off.
* **The machine is running** — Reset, Reset and start, Continue, Single step, Set PC and New
  program are all off. SimH in particular refuses to deposit into a live PC, and refuses it
  *silently*, so a Set PC against a running machine would produce a confusing nothing.
* **The console cannot do it.** Consoles differ, and the differences are real: SimH cannot reset
  without also starting; an ODT console cannot single-step unless the machine has a HALT switch
  and it is set; an 11/44 in RUN reports a different set of abilities than the same console in
  HALT.

**Halt is always available while connected**, even on a console that has no halt command. A
console that cannot halt still knows how *you* can — by moving a switch — and it tells you which
one and which way. That is more use than a dead button.

## The physical RUN/HALT switch

Some consoles have a real switch on the front of the machine, and for those a **Physical RUN/HALT
switch** group appears with two radio buttons, **RUN/ENABLE** and **HALT**. It is not a control —
it cannot move a switch on a machine across the room. It is you telling the program where the
switch is.

That matters because the console's abilities depend on it: a machine whose switch is at RUN cannot
be single-stepped. Until you say where the switch is, the explanatory line beside it is **red**,
and the buttons that depend on it will not act — the console does not know what it is allowed to
do, and neither does the program.

The group is hidden entirely for consoles that have no such switch.

## What else moves when the machine stops

Nothing in this window tells any other window anything. When the machine stops, that fact and the
new PC are published once, and every interested window picks it up on its own:

* [the disassembler](06-disassembler.md) re-reads around the PC and marks the line;
* [the assembler's listing tab](09-assembler.md) marks the source line the PC is on, if the machine
  stopped inside the program you assembled.

Both work whether or not this window is open, and this window does not know either of them exists.

---

[← Memory](04-memory.md) · [Manual index](README.md) · [Next: Disassembler →](06-disassembler.md)
