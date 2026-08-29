# I/O page scanner

[← Memory test](11-memory-test.md) · [Manual index](README.md) · [Next: Microcode →](13-microcode.md)

---

**Windows → I/O page scanner.** Find out what is actually plugged into this machine.

The top 4 K words of a PDP-11's address space is the **I/O page**: every device register lives
there, and an address with nothing behind it does not answer. So reading all 4096 of them and
noting which reply is a complete inventory of the machine's hardware — obtained without opening
the cabinet.

## Running a scan

**Scan the I/O page** reads all 4096 words, one at a time. It reads them singly on purpose: most
of the I/O page is empty, a bulk read of mostly-nonexistent addresses is where the console
dialects behave least predictably, and a scan you want to be able to stop wants one answer per
question. It is not fast over a serial line.

**You watch it happen.** Addresses appear in the list on the left as they answer, already named
where the description knows them, and the status line counts them. A progress bar and a **Cancel**
sit at the bottom right for as long as the scan runs, and nothing else about the window is blocked
— it is your own window, not a dialog standing in front of it.

**Cancel keeps what it found.** That is the point of stopping one: the device you were looking for
has answered and the remaining three thousand addresses are not going to say anything. **Closing
the window also stops the scan** — a scan is minutes of console traffic on the one thread every
other window queues behind, and leaving it running for a window nobody is looking at would make
every other window wait for it.

## What you get

The window splits in two.

**On the left**, the addresses that answered, as a list with a name, an address, a value and a
description — [an ordinary cell list](07-registers-and-bitfields.md#device-register-windows).
Anything [the loaded machine description](16-machine-descriptions.md) knows about is named from
it. The three buttons beside the scan work on those results:

| Button | What it does |
|---|---|
| **Examine all** | Re-read everything that answered |
| **Examine cell** | Re-read the selected one |
| **Deposit changed** | Write back the ones you have edited |

Selecting a row also points [the Bitfields window](07-registers-and-bitfields.md#the-bitfields-window)
at it, if you have one open.

**On the right**, an `.ini` section for everything the description does *not* know about.
Consecutive runs of unnamed addresses are almost certainly one device apiece, so the scanner
groups them into blocks and invents names — `device_177560.reg_0` and so on. The text area is
editable, because you are going to trim it and give things real names before pasting it into
[a machine description of your own](16-machine-descriptions.md).

**That is how you write a description for hardware nobody has documented.** Scan the machine,
paste the section, rename what you recognise, and the next time you load that description the
device gets its own registers window.

## The status line

`23 of 4096 addresses answered; 18 named by the machine description, 2 register blocks found`

— and `- stopped early` if you cancelled. If every address that answered was already named, the
right-hand pane says so rather than being mysteriously empty.

---

[← Memory test](11-memory-test.md) · [Manual index](README.md) · [Next: Microcode →](13-microcode.md)
