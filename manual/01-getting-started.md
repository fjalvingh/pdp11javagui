# Getting started

[← Manual index](README.md) · [Next: The main window →](02-main-window.md)

---

## What you need

A **Java 21 runtime** and nothing else. PDP11GUI ships as a single jar with everything it needs
inside it.

Two external programs are used if you have them, and are not needed otherwise:

| Program | Needed for | Where to get it |
|---|---|---|
| `macro11` | Assembling MACRO-11 source in [the assembler window](09-assembler.md) | <https://github.com/rhefner1/macro11> |
| SimH's `pdp11` | Connecting to a real SimH simulator that PDP11GUI launches | <https://github.com/open-simh/simh> |

Both must be on your `PATH` to be used. Neither is needed to run the program, and neither is
needed for the simulated machines built into it. If one is missing, the button that would have
used it says so rather than failing obscurely.

## Running it

Download `pdp11gui-<version>.jar` from the
[releases page](https://github.com/fjalvingh/pdp11javagui/releases) and run it:

```
java -jar pdp11gui-1.2.0.jar
```

Or, from a source checkout:

```
./mvnw package
java -jar pdp11-app/target/pdp11gui.jar
```

## Your first five minutes, with no PDP-11

You do not need hardware, SimH, or a serial port to see what this program does. It contains
simulated machines for every console protocol it speaks, and they run inside the same JVM.

1. **File → Connect to simulated → PDP-11 ODT, 18 bit (11/23)**.

   The status bar under the terminal turns green and says `Connected`. The title bar names the
   console. Nothing was installed and nothing was configured.

2. **Windows → New memory window**.

   A grid of memory appears, addresses down the left, `+0 +2 +4 …` across the top. Type `1000`
   in **Start**, press Enter. Press **Examine all**: the machine is asked for those words and the
   grid fills in.

3. **Click a cell and type a number**, then press Enter.

   The cell turns a different colour. Nothing has been written to the machine — that value is
   what you say *should* be there. Press **Deposit changed** and it is written, and the colour
   goes away. This distinction runs through the whole program; see
   [what a cell's two values mean](04-memory.md#what-a-cells-two-values-mean).

4. **Windows → Execution control**.

   Set **Start PC** to `1000`, press **Reset and start**, then **Halt**. Watch **Current PC**
   move. Open **Windows → Disassembler** and it follows the PC on its own — nothing had to be
   told about anything.

5. **Windows → Memory test → Test data lines**.

   The simulated machine's memory is healthy, so it passes. The same test run against a machine
   with a stuck data line names the bit.

## Then, with a real machine

Everything above works identically against hardware. What changes is
[the connection](03-connecting.md): a console protocol (which dialect the machine speaks) and a
transport (how you reach it). For a real PDP-11 that is usually **ODT** over a **serial port**;
for a PDP-11/44 it is the **PDP-11/44 console** over a serial port.

The other thing that changes is speed. A console examine is a command, an answer and a round trip;
at 9600 baud, reading a few hundred words is not instant. Operations that take longer than a
second put up a progress dialog with a **Cancel** in it (Escape works too), and the windows are
built around reading as little as they can get away with.

## A note on windows

Tool windows are free-floating top-level windows, not panes inside a frame. They are created the
first time you ask for one and remember where they were between runs. **Closing one hides it
rather than destroying it** — its contents are still there when you bring it back from the
**Windows** menu, where it is listed as `(closed)`.

---

[← Manual index](README.md) · [Next: The main window →](02-main-window.md)
