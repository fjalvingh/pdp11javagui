# Troubleshooting

[← Settings and files](17-settings-and-files.md) · [Manual index](README.md) · [Next: Glossary →](19-glossary.md)

---

## First move, always: try a simulated machine

**File → Connect to simulated → …** needs no hardware, no SimH and no serial port. If a simulated
machine behaves and yours does not, the problem is on the wire or in the machine, and you have
just halved the search. If the simulated machine misbehaves too, the problem is in the program or
in how it is being driven.

## Second move: turn on the Read and Write columns

[The Log window](14-terminal-log-simh.md#the-log-window) has a checkbox per channel. **Write** and
**Read** are one line per byte, and they are what you turn on when a console is answering
nonsense. The **Protocol** column beside them shows what the program made of those bytes, so the
line where the two diverge is the line where it went wrong.

## Connecting

**"Could not connect …"**
Look in the terminal and the log for the underlying reason. For a serial line, check the port name
and that nothing else has it open; for telnet, check host and port; for SimH, check that `pdp11`
is on your `PATH` or give an absolute path in the connection settings.

**Connected, but nothing happens when I press anything**
Some consoles need the machine halted before they will talk. Check the status line at the bottom
of [the execution window](05-execution.md). If the machine has a physical RUN/HALT switch, the
program must also be *told* where that switch is — until then it does not know what it is allowed
to do, and the note beside the radio buttons is red.

**"SimH is a program on this machine, not something at the end of a serial line"**
The protocol and transport you picked cannot go together. See
[Connecting](03-connecting.md#transports).

**The terminal will not let me type**
Either you are not connected, or this connection has no machine console. That happens with a SimH
that PDP11GUI did not launch: the only wire is the `sim>` channel, which is in
[the SimH console window](14-terminal-log-simh.md#the-simh-console-window). The terminal says so
when you connect.

**The connection died on its own**
A `[…]` line appears in the terminal saying so, and the windows grey out. Reconnect with **File →
Connect**.

## Buttons that do nothing

**A greyed-out button is a statement, not a fault.** See
[why a button is greyed out](05-execution.md#why-a-button-is-greyed-out). The three usual reasons
are: nothing is connected, the machine is running, or this console genuinely cannot do that.

**Halt is enabled but says "Halt the CPU by moving the physical RUN/HALT switch to HALT"**
That console cannot stop the machine. It has told you what will.

## Memory

**I typed a value and nothing changed in the machine**
Typing sets what *should* be there. Press **Deposit changed**. See
[what a cell's two values mean](04-memory.md#what-a-cells-two-values-mean).

**I deposited and the value came back different**
Press **Verify** to see exactly which words disagree, then run
[the memory tests](11-memory-test.md) over that range. That is what they are for.

**Cells are blank rather than showing `000000`**
Blank means *never read*, which is not zero. Press **Examine all**.

**A window is showing stale values**
Turn off **Use cached values** ([disassembler](06-disassembler.md)) or press **Examine all**.
Disconnecting throws away everything read from the old machine.

## The assembler

**Compile is dead**
`macro11` is not on your `PATH`. The button's tooltip says so. Get it from
<https://github.com/rhefner1/macro11>.

**"Cannot write to the directory …, and MACRO-11 has to put its listing there"**
MACRO-11 writes `<name>.lst` beside the source and has no option not to. Copy the source somewhere
writable — this usually bites when assembling straight off a mounted disc image.

**An error appeared but no dialog**
By design. The line is marked in the Source tab and the status bar is coloured; you are left on
the tab where you can fix it.

**I have a `.lst` but no `macro11`**
**Listing → Open listing …** reads it. Everything downstream — the code grid, depositing,
verifying, the PC marker — works from the listing alone.

## Device registers and bitfields

**Windows → Device registers says "No machine description loaded"**
The description could not be read. The log says why. The program runs without one; you lose the
register windows and the names.

**"No bit field definitions for 177560 in the loaded machine description"**
The register is real; the *names* for its bits are missing. Add a `Bits.` section — see
[machine descriptions](16-machine-descriptions.md#the-format).

## Microcode

**"No microword at 377: it is one of the 42 control store locations this document does not print"**
Not a typo and not a bad reading. Some control store locations simply are not printed in the
listing.

**The fields look right but two rows are coloured**
Those are the fields the *other* board revision disagrees on. If you are not certain which M7261
is in the machine, read the part numbers off the two control store PROMs — they are exactly the
two that change. See [Microcode](13-microcode.md#which-revision-and-why-the-title-says-so).

## Windows

**I closed a memory window and "New memory window" made a different one**
Closing hides. Your window is in the **Windows** menu marked `(closed)`; choosing it brings it
back with its range and edits intact.

**A window came back off-screen after I unplugged a monitor**
It does not: a window whose saved position is no longer on any screen is moved back onto one.

**Everything is in the wrong place and I want to start over**
Delete `settings.json` from [the configuration directory](17-settings-and-files.md#configuration).
The program starts with defaults; nothing else is lost but your saved connection profiles.

## Speed

Over a 9600-baud serial line, every examine is a command, an answer and a round trip. If something
feels slow, it probably *is* doing what you asked:

* narrow the range in the memory window — the default is 64 words for a reason;
* leave **Use cached values** on in the disassembler;
* set a realistic **Memory chip size** before running the memory tests;
* remember that [the I/O page scan](12-io-page-scanner.md) is 4096 individual examines.

Anything over a second is cancellable from its progress dialog, or with Escape.

---

[← Settings and files](17-settings-and-files.md) · [Manual index](README.md) · [Next: Glossary →](19-glossary.md)
