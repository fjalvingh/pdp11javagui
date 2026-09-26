# Microcode

[← I/O page scanner](12-io-page-scanner.md) · [Manual index](README.md) · [Next: Terminal, log and SimH console →](14-terminal-log-simh.md)

---

**Windows → Microcode.** A processor's own microcode, one microword at a time: what its bits are
set to, what that means, and where in the printed document it was read from.

Three documents ship with the application and the window works the moment it opens — there is
nothing to download and no file to find:

| Entry | What it is |
|---|---|
| **PDP-11/44** | DEC's printed microcode listing, EY-C3012-RB-001, April 1981 |
| **PDP-11/05 (M7261 rev E)** | The KD11-B control store from the October 1973 drawing set |
| **PDP-11/05 (M7261 rev F)** | The same board from the July 1976 drawing set. The default |

## On the PDP-11/05 this is a debugger, not a reference

An 11/44's console cannot tell you which microword it is executing, so for that machine this
window is what it looks like: documentation, beside the processor it belongs to.

**That stops being true on the PDP-11/05.** With a KM11 in slot 8, the KD11-B's microprogram
counter is on the lights — `MPC0` through `MPC7`. Read eight bits off the panel, type them into
the **µInstruction** box with **Search by** set to **µPC**, and the window tells you exactly which
microword the machine is sitting in and what that microword asserts. That is a processor debugged
below the instruction level.

## Which revision, and why the title says so

The two M7261 revisions have **identical** addresses and next-addresses. They differ in 20 bits
across 14 microwords, all of them in the `AUX` or `CKO` fields. So having the wrong revision
selected **does not look wrong**: every address resolves, every chain walks, and the microword on
screen is simply incorrect about two control lines with nothing at all to show for it.

Two things guard against that:

* the selected revision is in the **window title**, not buried in a combo box;
* the fields the *other* revision disagrees on are **coloured**, with a tooltip saying so.

Which board is in a machine can be read off it without powering it up: two of the ten control
store PROMs change part number between revisions, and they are exactly the two holding `AUX` and
`CKO`.

## Getting about

| Control | What it does |
|---|---|
| **Microcode** | Which of the three documents to show. Remembered between runs |
| **Search by** | **µPC**, **Symbolic tag**, or **Listing line** (where the document has line numbers) |
| **µInstruction** | Editable, and its drop-down is the whole index — dropped open at *Symbolic tag* it is the listing's table of contents |
| **Back** | Return to the microword you came from |
| **Next instruction** | Follow this microword's next-address field, which is where it goes when nothing branches |
| **Open listing …** | Read another copy of the selected microcode — a re-transcription, another scan, or a listing split into one file per page (choose them all at once) |

**Back exists because microcode is mostly read backwards** from the state you ended up in. And
each microword also lists what falls through to *it*, under **Jumped to from**.

If what you searched for is not there, the window says so and **leaves what is on screen alone**
rather than jumping somewhere else. For a µPC it says *why*: 42 of the KD11-B's 256 control store
locations are simply not printed in the listing, so an address read off the KM11 can be perfectly
real and absent from the document — and "no microword at 377" on its own reads like a typo when it
is not.

## What the table shows

Above the fields:

* **Symbolic tag** and **Address**;
* **Next microword** — the *decoded* successor. On the KD11-B the next-address bits are burned
  complemented (`MPC-7-L` down to `MPC-0-L`), so the raw field below reads `215` where the
  microword actually goes to `162`. Showing the raw field alone would invite somebody to "correct"
  it.
* on the PDP-11/05, **Part of** and **Does** — see below.

Then one row per field the *machine* has — which fields exist is a property of the processor, not
a fixed table — with its **Bits**, its value in octal, and what the print set says that value
means: `2 = DATO`.

Below them, where the document carries it: the **Source code** of the microword, the 11/05's
**Flow notes**, **Jumped to from**, and which listing file and line it was read from.

Some fields are highlighted: those are the ones this microword is actually *doing* something with,
as opposed to leaving at their resting value.

### What an 11/05 microword is for

The bits say which lines a microword asserts; they do not say that `ET-2` is the second step of
taking an EMT trap. DEC wrote that down, in the *microprogram flow* (drawing `K-MP-KD11-B-1`, 1972)
that is part of the 11/05 engineering drawings, and the window shows it:

* **Part of** — the routine the microword belongs to, with what sort of routine it is: the
  *Instruction fetch*, a *Source operand* or *Destination operand* addressing mode, an
  *Instruction* such as `JSR` or `RTI`, a *Trap*, a *Console* switch (Start, Examine, Deposit,
  Continue, Load), *Power fail*, or *Interrupts and service* (bus grants, the console UART, the line
  clock). It is in the status line too, beside the µPC.
* **Does** — what the flow prints beside the microword, such as `B,BA←R[6]-2; ENABOVER`.
  `R[S]` and `R[D]` are the instruction's source and destination registers, `K[n]` is a constant,
  `BUT` is *branch on micro test*.
* **Flow notes** — the flow's comments on it: how it is reached (`GET TO ET-2 FROM BT-1 VIA
  GOTO`), where a branch can go (the `IF … GOTO` lists), and anything else DEC thought worth saying.

The text is shown as printed, in capitals and with the document's own spelling. The flow leaves 13
microwords out, and says so on its last page; for those the notes say that, and quote where the flow
mentions them. The grouping into sorts of routine is this program's; the rest is DEC's.

The flow was checked against the listing: both print every microword's address, next address and
tag, and they agree for all 201 microwords the flow shows. It describes revision E, and describes
revision F equally well — the two differ in bits, not in addresses or tags.

### A branch base is not a successor

73 of the KD11-B's 214 microwords select a microtest, and there the hardware ORs the test's result
into the next-address bits. What is printed is therefore where the microword goes **only if the
test comes out zero**. The status line says so — *… if BUT is zero (a branch base)* — rather than
stating as fact something that depends on the state of the machine. The 11/44's `BUT ENABLE` is
the same thing.

### A field that is a don't-care says so

The KD11-B's `AUX` line selects whether the ALU control comes from the microword or is decoded
from instruction register bits — which is how one microword executes `MOV`, `CMP`, `BIT`, `BIC`,
`BIS`, `ADD` and `SUB` alike. In those microwords the printed `ALU` field means nothing, and the
window shows it as a don't-care with the reason, rather than as an operation the machine is not
performing.

## A damaged document still opens

If part of a listing cannot be read, what could not be read is a count in the status line and a
note in [the log](14-terminal-log-simh.md#the-log-window), and the rest of the microcode is there
to look at. Hover the status line for the first few problems.

The 11/05's listing loads with no problems, although one microword leaves it: `A145` goes to 377,
one of the control store locations the drawings do not print. That is how the listing is, not a
fault in reading it, so it is shown only when `A145` is - as "next 377 (not in this document)" in
the status line.

---

[← I/O page scanner](12-io-page-scanner.md) · [Manual index](README.md) · [Next: Terminal, log and SimH console →](14-terminal-log-simh.md)
