**CTRMap-F5 1.0.3 — a file the editor could not read stops being mistaken for an empty one.**

A world editor for Pokémon Omega Ruby, Alpha Sapphire, X and Y. Build towns that were never in
the game, then play them.

### Download

| | |
|---|---|
| **`CTRMap-F5-1.0.3-windows-x64.zip`** | Unzip, double-click `CTRMap-F5.exe`. **No Java needed.** Start here if you're not sure. |
| **`CTRMap-F5-1.0.3-portable.zip`** | 5 MB instead of 27, but needs Java 8+. `run.bat` on Windows, `run.sh` on macOS/Linux. |

Already have 1.0.0, 1.0.1 or 1.0.2? Help > Check for updates will offer this one and replace the
copy you have.

This release has no new features. It is six fixes, and every one of them is a case where the
editor carried on quietly with the wrong bytes.

### Fixed

**Your workspace.**

- **A failed extraction left an empty file behind, and every later read handed that file back as
  the entry.** The output file was created — which truncates it — before the archive was asked for
  a single byte, and the guard that decides whether to extract is "does this file already exist".
  So one failure poisoned that entry for the life of the workspace, and 103 places read the result
  as real data. Forking a region copies such a file into a new zone and packs it into your
  FieldData: what you see is **a zone with no ground and no message anywhere.** The bytes go to a
  `.part` file now and are moved into place only once whole; a failure deletes it and says so,
  because "could not extract" and "this entry is empty" had to stop being the same answer.
- **A pack that stopped part way lost the compression setting for the slot it was appending.**
  `packArchives` stops at the first archive it cannot rewrite — an emulator or a virus scanner
  holding the file open — and the overrides for that append had already been consumed. You clear
  the cause, pack again, and the appended slot falls back to guessing from the previous entry: a
  region stored raw in a slot the game inflates, or compressed in one it reads raw. **A map the
  game cannot load, out of a pack that reported success.** The settings are put back when a pack
  throws.

**Archives, and what "I could not read that" means.**

- **A file that is not an archive at all opened as an archive with nothing in it.** A missing or
  half-copied game file produced an empty table and no complaint — and an empty archive is a real
  thing a dump can contain, so "I could not read this" and "this holds nothing" were the same
  answer. It refuses now and names the file.
- **Deploy treated two archives it could not fully read as identical.** Identical there means
  *already shipped, skip it* — so an archive that had been cut short was precisely the one Deploy
  decided not to ship. It refuses to call them the same when either one was not read whole.
- **Partly-successful reads were treated as complete ones**, at twelve places across the two files
  every write goes through, including the entry copy in a repack and the whole-container rebuild
  buffer. `read` and `skip` report how much they actually managed, and those answers were being
  dropped.
- **An archive that runs out mid-table stays readable up to the cut.** The entries before it are
  whole and are handed over; the ones past it refuse instead of returning zeros, and the archive
  refuses to be packed back at all — writing 155 of 436 entries back destroys the other 281.

### How this was checked

Verified against a real dump by 136 headless test suites, 135 of which pass. The one that does
not is an internal gate on the mutation baseline: it refuses a baseline that was measured while
other work was still queued, which is the honest state of this repository today. The sweep behind
it is clean — it broke 202 lines on purpose and every one of them was caught by a suite, with no
survivors.

Every fix above also ships with a recorded defect that puts it back: 204 of them, re-applied one
at a time, each required to make its own suite fail. If a fix here is ever undone, something goes
red.

What the suites cannot check is the game: load a map you have edited in an emulator before you
build on top of it.
