# Execution control

[← Memory](04-memory.md) · [Manual index](README.md) · [Next: Disassembler →](06-disassembler.md)

---

**Windows → Execution control.** Reset, start, continue, halt, single step, and where the PC is.

## The two PC fields

| Field | Meaning |
|---|---|
| **Start PC** | Where a program starts. This is not the machine's PC — it is where you intend to start it. Pressing Enter publishes it to the rest of the program, so the assembler and the disassembler know where the program is. **Reset and start** uses it |
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

The edit-assemble-load-run cycle on one button is in [the Assembler window](09-assembler.md):
**Compile and start**, and **Compile and set PC**.

A status line at the bottom says what the machine is doing: *stopped at 001000*, *running*, *state
unknown*, or *Not connected*. It is also where a mistyped address is reported.

## Why a button is greyed out

This is the substance of the window, not decoration. Which operations are offered depends on two
things at once: **what the console can do**, and **what the machine is doing**.

* **Not connected** — everything is off.
* **The machine is running** — Reset, Reset and start, Continue, Single step and Set PC are
  all off. SimH in particular refuses to deposit into a live PC, and refuses it
  *silently*, so a Set PC against a running machine would produce a confusing nothing.
  The exception is a console that cannot stop the machine at all - see below.
* **The console cannot do it.** Consoles differ, and the differences are real: SimH cannot reset
  without also starting; an ODT console cannot single-step unless the machine has a HALT switch
  and it is set; an 11/44 in RUN reports a different set of abilities than the same console in
  HALT; and an M9312 or M9301 boot ROM console cannot reach the CPU registers, so **Reset and set
  PC** and **Set PC** are off there - it can set a PC only by starting from it.

**Halt is available on a console that can halt the machine, and on one with a RUN/HALT switch**
even when it has no halt command: such a console still knows how *you* can — by moving the
switch — and it tells you which way.

**The M9312 and M9301 boot ROM consoles can do neither.** Once a program is started, the console
emulator is gone until the program jumps back into it or the machine is rebooted from the front
panel, and the program cannot always see that happen. So on these consoles Halt is off, the status
line says the program was started and must be stopped from the front panel, and **Reset and
start stays on**: once the console emulator is back at its prompt you can start a program again
without reconnecting. If the program is in fact still running, the start fails with no prompt.

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
