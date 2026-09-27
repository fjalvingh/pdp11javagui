# Golden fixture for the machine-description preprocessor

`pdp11.expected.ini` is the byte-for-byte output of

    m4 --include=. pdp11.ini

run over `pdp11-app/src/main/resources/machines/`, with GNU M4 1.4.21. When the Java
preprocessor lands in phase 2 it must reproduce this file exactly from the same inputs.

Regenerate only if the inputs change, and say why in `CHANGES.md` if you do — a fixture that
gets regenerated whenever it disagrees with the code is not a test.
