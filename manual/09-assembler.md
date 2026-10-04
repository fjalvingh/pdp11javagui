# The assembler

[← The MMU](08-mmu.md) · [Manual index](README.md) · [Next: Loading and dumping →](10-load-and-dump.md)

---

**Windows → Assembler.** Write a MACRO-11 program, assemble it, load it into the machine, and
watch it run.

## Before you start

Assembling needs **`macro11` on your `PATH`**. It is not bundled and not required for anything
else; if it is missing, the **Compile** button says so in its tooltip and stays dead. Get it from
<https://github.com/rhefner1/macro11>.

You can still use the window without it: the **Listing** tab reads a `.lst` somebody else
produced, and everything downstream of that — the code grid, depositing, verifying, the PC
marker — works from the listing alone.

## One window, three tabs

| Tab | What it holds |
|---|---|
| **Source** | The editor, with MACRO-11 syntax highlighting, line numbers, and the assembler's error line marked |
| **Listing** | What MACRO-11 printed, with the same error marked — and, while the machine is stopped inside this program, the line the PC is on |
| **Code** | The assembled words as an editable memory grid. This is what is actually deposited |

The three are always about the same program, which is why they are tabs rather than three windows.

## Source tab

| Button | What it does |
|---|---|
| **New** | Start an empty program (asking first if the current one is unsaved) |
| **Open …** | Open a `.mac` source |
| **Save** / **Save as …** | Write it out |
| **Compile** | Save the source and run MACRO-11 over it |

MACRO-11 writes its listing beside the source, as `<name>.lst`, and has no option not to — so the
source has to live in a directory you can write to. Assembling straight off a mounted disc image
fails for that reason, and the message says which directory is the problem.

**An assembler error does not raise a dialog.** It marks the line, colours the status bar, and
leaves you on the tab where you can fix it. A syntax error is an ordinary event in writing a
program.

**Your edits survive closing the window.** Closing hides it; the text is still there when you
bring it back. Quitting with unsaved changes asks first.

## Example programs

**File → Load examples** lists the example programs that come with PDP11GUI. Choosing one opens
it in this window, ready for **Compile**. The first is a game of tic-tac-toe on the console
terminal. It needs no operating system and runs on anything from an 11/05 up; start it at `1000`.

The second, the memory tester, finds where memory is installed, lists each block by address and
size, and then tests all of it in an endless loop of passes until you halt the machine. Every pass
writes and reads back five patterns: each word's own address, its complement, all zeros, all ones
and a checkerboard. A bad word is reported with its address, what was written, what was read and
which bits differ. Only the first ten in each pass are shown, but every one is counted. It also
starts at `1000`, and needs nothing but the console terminal. Without memory management it sees
the 28K words below `160000`. With memory management it sees everything below the I/O page,
18-bit or 22-bit, and first checks that the high address lines are really connected, so that a
22-bit processor in an 18-bit backplane does not report one board many times over. The memory
below the end of the program, which holds the vectors and the program itself, is not tested.

The example is copied into `examples/` in the [data directory](17-settings-and-files.md) and
opened from there, because MACRO-11 needs a file it can write a listing beside. That copy is
yours to change and save. Load the same example again later and your changed copy is not
overwritten without asking: **Yes** puts the original back, **No** opens your copy.

## Listing tab

| Button | What it does |
|---|---|
| **Open listing …** | Read a `.lst` produced elsewhere. Neither the source nor MACRO-11 is needed |
| **Show code** | Jump to the Code tab |

The listing carries the relation between source lines, listing lines and memory words — one source
line can produce several listing lines, and one listing line several words. That relation is what
lets an address be turned back into a line, which is how the PC marker works.

## Code tab

| Control | What it does |
|---|---|
| **Start address** | Where the program begins, from the listing. Read-only |
| **Verify** | Read the same addresses back off the machine and compare |
| **Deposit changed** | Write only the words that differ |
| **Deposit all** | Write every word of the program into the machine |

The grid is [an ordinary memory grid](04-memory.md): you can edit a word before depositing it, and
the colouring means what it means everywhere else.

**Verify after loading** is worth the habit, especially over a serial line. It reads the program
back without disturbing what you loaded, so anything the machine disagrees with lights up.

## The whole cycle on one button

[The execution window](05-execution.md)'s **New program** does: assemble → deposit → reset → set
the PC to the program's start address. It works with the assembler window closed, and opens it if
the assembly fails so you can see where.

After that, **Reset and start** runs it, **Halt** stops it, and **Single step** walks it — with
[the disassembler](06-disassembler.md) following the PC and this window's Listing tab marking the
source line it is on.

## While it is assembling

MACRO-11 is an external process and is given five seconds. A second **Compile** is refused while
one is in flight. What the editor holds is what gets assembled — the text is written out first —
so assembling from the execution window cannot quietly assemble yesterday's file.

---

[← The MMU](08-mmu.md) · [Manual index](README.md) · [Next: Loading and dumping →](10-load-and-dump.md)
