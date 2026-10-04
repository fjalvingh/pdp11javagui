# Collecting diagnostics

[← Glossary](19-glossary.md) · [Manual index](README.md)

---

**File → Collect diagnostics …** Finds DEC's XXDP and MAINDEC diagnostics on the Internet,
downloads them, takes every program off every disk, tape and floppy image, and keeps them in a
library on this machine, sorted by what each one is for.

PDP11GUI does not come with any diagnostics. They are DEC's, and they are not this project's to
hand out. But people have been preserving them for decades, and the window knows where they put
them: bitsavers' copies of the XXDP distributions and diagnostic floppies and tapes, and Don
North's (AK6DN) bootable TU58 sets and tests he wrote himself. Nothing is fetched until you
press **Collect**.

## Collecting

The **Collect** tab lists the places to look. Each has a tick box; click a source to see what it
holds and why you might want it. If you take only one, take **XXDP V2.2 and V2.5 distributions** —
the two complete XXDP+ releases, with about 700 diagnostics each.

1. **Find diagnostics** reads the ticked sources' web pages and lists every image they offer,
   marking each **new** or **in the library**. Nothing is downloaded yet.
2. **Collect *N* new** downloads the new ones, one after another. The status line says which file
   and how far; **Cancel** stops between two reads. Everything collected before you cancelled is
   kept, and the next Collect carries on with what is left.

With the sources that are ticked to begin with, that is around 60 MB to download and 150 MB on
disk, because the disk images are kept uncompressed, ready to attach to SimH. It took under half
a minute on a fast line when this was written.

Collecting again later only fetches what is new. A source that adds files is picked up by the next
**Find**: the program reads the source's pages each time instead of using a fixed list of files.

Your ticks are remembered.

## What it can read

| Medium | Forms |
|---|---|
| RL01, RL02, RK06, RK07, RM, MSCP disks | XXDP+ file system |
| RK05, RP disks, TU58, RX01, RX02 | DOS-11 file system |
| RX01 and RX02 floppies | Physical images (with the interleave) and logical ones, either way |
| DECtape | The 1972 DOS-11 DECtape layout |
| Magnetic tape | SimH `.tap` images in DOS-11 format — the MMDP and MSDP tapes |
| ImageDisk | `.imd` floppy images, including runs that stopped before the last track |
| Anything | Gzipped (`.gz`), and zip archives holding any of the above |
| Single programs | `.BIN`, `.BIC` and the like, taken as they are |

A medium that cannot be read whole still gives what it can. A file whose blocks could not all be
read is kept, but marked **damaged**, and the problem is listed under **Problems**. If the same
program turns up whole on another medium, the whole copy replaces it.

## The library

The **Library** tab lists every program collected, one row per program:

| Column | What it is |
|---|---|
| **Program** | The XXDP file name: `ZRLGE0.BIC` is revision E0 of diagnostic ZRLG |
| **Title** | DEC's title for it, abbreviations and all, from DEC's own index |
| **Family** | What it is for — see below |
| **Date** | The date on the medium |
| **Size** | In bytes |
| **Media** | On how many of the collected media it was found |
| **Status** | **damaged**, or **other copy** when a different program has the same name |

**Family** narrows the list to one kind; **Find** narrows it to the rows that contain every word
you type, in the name, the diagnostic number, the title or the family. `11/34 mem` finds the
PDP-11/34 memory management tests; `rl02` finds the RL02 tests and the RL02 packs they shipped on.

Select a program to see where its file is, its DEC part number and every revision DEC's index
lists, which kits DEC shipped it on, and every medium it was found on here.

### Families

DEC named every diagnostic with four letters, and the first says what it is for. The titles come
from DEC's *PDP-11 Diagnostic Index* of 1990 (AH-FG66P-MC), which is built into PDP11GUI: it
lists about 1,150 diagnostics, so most of what you collect gets a title.

| First letter | Family |
|---|---|
| H | XXDP monitors, drivers and utilities |
| G | PDP-11/04 |
| F | PDP-11/34 |
| B | PDP-11/35, 11/40 |
| K | PDP-11/44 |
| C | PDP-11/45, 11/50, 11/55 |
| Q | PDP-11/60 |
| E | PDP-11/70 |
| V | LSI-11 and Q-bus |
| J | PDP-11/23, 11/24 (KDF11) |
| O | PDP-11/73, 11/83, 11/84 (KDJ11) |
| N | Q-bus options |
| Z | Peripherals and options — runs on any processor |
| X | DEC/X11 system exerciser modules |
| U | Boot ROMs and firmware |
| P | System configurations and chain files |
| D, R, T, I | GT40, laboratory, the maintenance program generator, manufacturing |

XXDP V2.5's monitors and drivers have plain names (`XXDPXM.SYS`, `DD.SYS`) and are recognised by
those too. Files whose names say nothing — chain files, AK6DN's tests — are under **Other**.

## Running a diagnostic

A program in the library can be put into the machine and started from the **Library** tab, with
nothing on the machine but its console: every word is deposited, one after another, and the
machine is started at the program's start address. No boot device, no XXDP, no loader.

That only works for **standalone** programs, and the **Runs** column says which those are:

| Runs | What it means |
|---|---|
| **standalone** | A classic MAINDEC diagnostic, with its own vectors and console code. It can be run |
| **needs DRS** | Built for the XXDP+ Diagnostic Runtime Services supervisor, which has to be loaded with it. It has a program header at 002000 instead of vectors |
| **needs XXDP** | Part of XXDP itself: a monitor, a driver, or a utility that works through the monitor |
| (empty) | Not a program — a chain file, a help file, a listing — or a damaged copy |

Tick **Standalone only** to see just the ones that can run. Select one and the bar under the list
offers:

* **Start at** — filled in from the program: its transfer address, or **200** when it has none,
  which is almost always. That is DEC's rule as well.
* **Switches (176)** — standalone diagnostics read their options from the switch register, or,
  on a machine without one, from location **176**. Leave it empty to keep what the program sets
  (usually zero: all tests, stop on nothing); type an octal value to set it after loading —
  `100000`, for instance, is "halt on error" in most of them. The program's documentation lists
  its switches.
* **Load** deposits the program and stops there, so you can change memory before starting it.
  The Execution window's start PC is set to the program's start address.
* **Load and start** deposits it, resets the machine and starts it. What the program prints
  appears on the console terminal in the main window.

The machine has to be stopped first; a program cannot be deposited under a running one. On a
console with a RUN/HALT switch, say where the switch is first, as for any start.

Selecting a program shows, under **Running**, the addresses it loads into. A machine with less
memory than that cannot run it: an 11/05 with 8K words has nothing above 037777.

**How long it takes** depends on the console. Over SimH a 6,000-word diagnostic goes in in a few
seconds — deposits are sent a hundred at a time. Over a serial ODT console every word is typed,
so the same program takes several minutes at 9600 baud; the progress dialog shows how far it is and
can cancel. A cancelled load is never started.

The program, its family and its switches are DEC's; PDP11GUI only puts it in memory. Tested: the
PDP-11/34 basic instruction test `FKAAC0`, loaded this way into SimH set to an 11/34, announces
itself and prints `END PASS`.

## Where it is kept

**Open folder** shows the library in your file manager. It is laid out to be used without
PDP11GUI:

```
diagnostics/
  library.tsv                         what is here, and where each thing came from
  files/ZRLGE0.BIC                    every program, under its own name
  files/variants/3f9a0c21b4de/...     a different program under a name already taken
  media/bitsavers-rl02/xxdp25.rl02    the media, uncompressed, ready to attach to SimH
```

| Platform | Directory |
|---|---|
| Linux | `$XDG_DATA_HOME/pdp11gui/diagnostics`, or `~/.local/share/pdp11gui/diagnostics` |
| macOS | `~/Library/Application Support/pdp11gui/library/diagnostics` |
| Windows | `%APPDATA%\pdp11gui\library\diagnostics` |

It is not in the data directory, which is for things that may be thrown away. Deleting the
library is safe: the next collection downloads it all again.

To boot XXDP in SimH from the library, attach a medium from `media/` — for example
`attach rl0 …/media/bitsavers-rl02/xxdp25.rl02` and `boot rl0`.

---

[← Glossary](19-glossary.md) · [Manual index](README.md)
