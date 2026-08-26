# Memory

[← Connecting](03-connecting.md) · [Manual index](README.md) · [Next: Execution control →](05-execution.md)

---

**Windows → New memory window.** You can have as many as you like; looking at two parts of memory
at once is the ordinary way to work.

A memory window is a range and a grid: addresses down the left, `+0 +2 +4 …` across the top, one
editable octal word per square.

## What a cell's two values mean

Every window in PDP11GUI that shows memory shows the same kind of cell, and each cell carries
**two** values:

* the **machine value** — what the PDP-11 last said was there;
* the **edit value** — what should be there instead.

When they differ the cell is coloured. That difference is the vocabulary of the whole program:

| Doing this | Sets | So |
|---|---|---|
| **Examine** | both | nothing shows as changed — you are looking at the machine |
| **Typing** a value | the edit value only | it shows as changed until you deposit it |
| **[Loading a file](10-load-and-dump.md)** | the edit value only | every word of the file shows as changed, before anything is written to a PDP-11 |
| **Deposit** | writes the edit value, then both agree | the colour goes away |
| **Verify** | the machine value only | your values stay put, and everything the machine disagrees with colours itself |

That last row is the one worth understanding. **Verify** is how you check that what you loaded is
what is actually in the machine: it reads the same addresses back without touching what you
typed, so the disagreements — and only the disagreements — light up.

A cell that has never been read is **unknown**, and unknown is not zero. Windows show it as blank
rather than as `000000`.

## The range

| Control | Meaning |
|---|---|
| **Start** | The first address, octal. Enter is the same as pressing **Show** |
| **Words** | How many words to show, up to 256. 64 by default |
| **Show** | Apply the range |
| **<** and **>** | Move the range one row earlier or later |

There is no scrollbar over the whole of memory, and that is deliberate: memory is up to 4 MB and
the machine is at the end of a serial line. What you have is a window onto it that you move.
Moving it keeps the values of every address that stays in range, so stepping a row at a time reads
one row from the machine rather than all of it again.

## The buttons

| Button | What it does |
|---|---|
| **Examine all** | Read every word in the range off the machine |
| **Examine cell** | Read just the selected one |
| **Deposit changed** | Write only the words you have edited |
| **Deposit all** | Write every word in the range |
| **Verify** | Read it all back without touching the edits; anything the machine disagrees with shows as changed |

**Deposit changed** is almost always what you want. **Deposit all** writes words you never touched
— which over a serial line is slow, and into an I/O page is a way to poke a device you did not
mean to.

## The right-click menu

Right-click anywhere in the grid:

| Item | What it does |
|---|---|
| **Clear data** | Set every edit value in the range to zero. Nothing is written to the machine |
| **Fill data with address** | Set every word's edit value to its own *word* address (the byte address divided by two). Deposit it, examine it back, and a memory with a broken address line shows up immediately |
| **Export as SimH DO script …** | Write the range out as a SimH command file, which `do` will replay into a simulator |

## Editing

One click starts editing — a memory editor that wants a double-click per word is tiring within
about a minute. Only octal digits reach the field; Ctrl+C and Ctrl+V work as they do anywhere
else. Anything that is not a value is ignored rather than silently turned into one, and clearing
the field makes the cell *unknown* again. Enter commits the edit to the cell; it does **not**
write to the machine.

## Two windows on the same address

That is normal and supported. When a value arrives from the machine, every window showing that
address is offered it — but **a grid you have been typing into does not take it**. Your
uncommitted edits are exactly what you were about to write; silently replacing them with what is
already in the machine would undo the work.

---

[← Connecting](03-connecting.md) · [Manual index](README.md) · [Next: Execution control →](05-execution.md)
