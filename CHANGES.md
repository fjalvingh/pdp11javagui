# Changes

## Unreleased

### Phase 0 — scaffolding

- Maven multi-module skeleton: `pdp11-core`, `pdp11-ui`, `pdp11-app`, targeting Java 21.
- Maven wrapper pinning Maven 3.9.11; the enforcer refuses anything below 3.9.
- The `pdp11-core` headlessness rule is enforced at compile time with
  `--limit-modules java.base` and again by an ArchUnit test. Both were verified to fail on a
  deliberately planted Swing reference.
- FlatLaf shell: `Pdp11Gui.main()` opens an empty `MainWindow` with a menu bar.
- GitHub Actions build on Linux, Windows and macOS.
- `Octal` formatting and parsing in `pdp11-core`, with tests.
- Machine descriptions recovered from the PDP11GUI 1.48.6 MSI installer and committed under
  `pdp11-app/src/main/resources/machines`: `pdp11.ini` and the eight `*.modules` m4 libraries
  it includes. These are not in the Pascal repository. See
  `pdp11-app/src/main/resources/machines/README.md`.
