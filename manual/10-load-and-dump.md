# Loading and dumping memory files

[← The assembler](09-assembler.md) · [Manual index](README.md) · [Next: Memory test →](11-memory-test.md)

---

Two windows, one subject: getting a block of memory out of a file into the machine, and out of the
machine into a file.

* **Windows → Memory loader** — file → machine.
* **Windows → Memory dumper** — machine → file.

## The four formats

| Format | Files | Carries its own addresses | Has an entry address | What it is for |
|---|---|---|---|---|
| **Binary byte stream** | 1 | no | no | Raw words as little-endian byte pairs. What a ROM burner wants |
| **Separate low byte / high byte binary files** | 2 | no | no | Programming a 16-bit memory built out of 8-bit chips — one file per device |
| **Text file, one octal address and its values per line** | 1 | yes | no | `addr: value value value …`, eight to a line. Readable, greppable, diffable |
| **DEC standard absolute paper tape** | 1 | yes | yes | What a real PDP-11's own absolute loader reads. Blocks with checksums, and a final block saying where to start |

The window shows exactly the fields the chosen format needs — one file row or two, a **Load at**
address only for formats that do not carry their own, an **Entry address** only for the format
that records one. A field that would be ignored is not offered.

## Loading a file into the machine

**Windows → Memory loader.**

| Control | What it does |
|---|---|
| **Format** | From the table above |
| The file rows | The file(s), with **Browse …** |
| **Load at** | Where to put the contents, for a format that does not say. Enter here reads the file |
| **Entry address** | Filled in from the file, for a format that records one |
| **Load file** | Read the file into the grid below. **Nothing is written to the machine yet** |
| **Deposit changed** | Write the words that differ from what the machine holds |
| **Deposit all** | Write every word |
| **Verify** | Read the same addresses back off the machine; anything that disagrees shows as changed |

**Loading fills the grid; it does not touch the machine.** That split is the point: what came out
of the file is in front of you, every word showing as changed, before a single word reaches a
PDP-11. Depositing is a separate button and a separate decision.

The usual sequence is **Load file** → look → **Deposit all** → **Verify**. Over a serial line the
verify is not paranoia; it is how you find out that word 200 of your paper tape did not make it.

**Verify** says either *The machine holds exactly what was loaded* or how many words differ.

Loading a paper-tape image also publishes its entry address as the program's start address, so
[the execution window](05-execution.md) is ready to run it — the loader does not know that window
exists, it simply says where the program starts and whoever cares listens.

## Writing memory out to a file

**Windows → Memory dumper.**

| Control | What it does |
|---|---|
| **From** / **to** | The range to dump, octal |
| **Format** | From the table above |
| **Entry address** | What to record as the start address. Blank means the start of the range |
| The file rows | Where to write, with **Browse …** |
| **Examine all** | Read that range off the machine into the grid |
| **Write file** | Write it |

**Examine first, then write.** The grid shows exactly what is about to be written; the file is
made from the grid, not from a fresh read.

### Words that were never read

A cell nobody has examined has no value, and *no value is not zero*. The positional formats — the
byte stream and the byte-split pair — have nowhere to put "unknown": the file is a sequence of
bytes and every word needs one. Those formats write **zero** and the window **tells you how many**
words that happened to. A block about to be burned into a ROM with a hundred silent zeros in it is
worth a sentence.

The text and paper-tape formats carry their own addresses, so they simply leave unknown words out.

## Round-tripping

Everything written by the dumper can be read back by the loader in the same format, and that is
checked. So an easy way to copy a block of memory from one machine to another is: dump it from
one, load it into the other, verify.

---

[← The assembler](09-assembler.md) · [Manual index](README.md) · [Next: Memory test →](11-memory-test.md)
