# Memory test

[← Loading and dumping](10-load-and-dump.md) · [Manual index](README.md) · [Next: I/O page scanner →](12-io-page-scanner.md)

---

**Windows → Memory test.** Four tests that say whether this machine's memory works, and — which is
the point — **which part of it does not**.

> **These tests write to memory and do not put it back.** They are for a machine that is not
> running anything. The range is capped at the I/O page: writing test patterns into device
> registers would *do* something rather than nothing.

## Why not just write every word and read it back

Because that takes hours. A console examine is a command, an answer and a round trip; at 9600 baud,
testing 128 KB one word at a time is most of a day. Each of these tests instead writes a pattern
chosen so that a small number of accesses says something definite about a whole class of fault.

That is also why the tests have to be told **how big a memory chip is**.

## Setting up

| Control | What it does |
|---|---|
| **From** / **to** | The range to test, octal. Defaults to all of memory below the I/O page |
| **Memory chip size** | How much address space one chip covers: Single word, 1K, 2K, 4K (the default), 16K, 64K, 256K |
| **Set range** | Apply it. The four test buttons stay dead until you have |

Both addresses are forced even and a range the wrong way round is swapped. Editing either field
invalidates the range — a range you have half-retyped is not one you meant to test.

Chip size matters: the tests read a few words *per chip* rather than every word, so this decides
what they can see. **Guessing smaller than the truth costs time; guessing larger misses whole
chips.** *Single word* treats every address as its own chip — the slowest and most thorough
setting.

Changing to a machine with a different address width clears the range, because it is no longer the
same range.

## The four tests

### Test data lines

Moves a single one, and then a single zero, through the sixteen data bits and reports which bits
never move. Finds a data line stuck high or stuck low.

It is cleverer than that sounds, and has to be: a chip that reads back all zeros and a chip that
reads back all ones both look exactly like a stuck data line if you only test one address. So the
test repeats at the first word of every chip and combines the readings — a bit that is zero in
every reading is a line that was never high; a bit that is one in every reading is a line that was
never low. A dead chip pollutes one reading; it cannot make a working line look dead in all of
them. The moving zero is skipped if the moving one already failed.

### Test address lines

Writes **each address into itself** at 0, 2, 4, 8, 16 … and checks that they all come back. A
moving one through the address bits touches one address per address line, so an address line that
is stuck makes two of those addresses the same cell — and the second write lands on top of the
first. Writing the address *as* the data is what makes that visible: the cell says which address
actually reached the memory. The report names the line.

If that passes, a second phase runs the same pattern with the high address bits set. **Phase 1
failing means a dead address line; phase 1 passing and phase 2 failing means a short between
address lines** — a fault the first phase cannot see at all.

### Test data bits

Writes a moving-one pattern into sixteen words of every chip and reads it back. One chip usually
supplies one or two data bits across a whole address range, so a dead chip shows up as the same
bit wrong in every word of that range. Sixteen words per chip — one per data bit — is enough to
see it, and is two orders of magnitude fewer accesses than testing the range.

Phase 1 writes at the start of each chip; phase 2 writes the inverted pattern at the end, so
between them every bit of every chip is seen both ways.

### Random test

Random values at random addresses, read back afterwards. The other three look for a specific class
of fault with a pattern designed to expose it; this one looks for whatever they missed. Writing in
random order and checking in address order is deliberate — it is the ordering that catches a memory
which remembers only the most recent access.

## Reading the result

The window is mostly a log, and lines reach it as the test runs, so a long test can be watched
rather than only reported on. Each line is numbered and time-stamped. **Clear** empties it.

The status line says one of:

* `Test of data lines: passed`
* `Test of address lines: 14 bad words` — with the log saying which, and for address-line failures
  which line is suspected
* `… 14 bad words, and a data line looks dead`
* `Test of data bits: stopped early` — you cancelled it
* `Test of random could not run` — the console refused, or there is no connection

A test longer than a second puts up a progress dialog you can cancel.

Cells are updated as the tests run, so [a memory window](04-memory.md) looking at the same range
shows the damage as it happens.

---

[← Loading and dumping](10-load-and-dump.md) · [Manual index](README.md) · [Next: I/O page scanner →](12-io-page-scanner.md)
