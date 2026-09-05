# Adding Pokemon from later generations — feasibility scope

Measured 2026-09-04 against a real ORAS dump. Claims here are counts and symbols
read out of this game's files, not inferences from what these formats are said
to do. Where something was NOT established, it says so.

## A correction that frames this document

An earlier version of this scope ended by asking whether CTRMap should "become
multi-generation, or stay the best Gen 6 tool". That was wrong twice over: the
owner had already decided it, repeatedly, and the codebase already implements it.

CTRMap-F5 is multigenerational BY DESIGN and the design is enforced:

    src/ctrmap/gamedef/     GameProfile (abstract)  + Xy / Oras / Sm / Usum
    ctrmap.tests.SourceSeamTest   registered in test.ps1, and it enforces:
      no RomFS path, GameText entry index, or other game-detected constant may
      live anywhere in the editor source outside that package.

Three layers, from GameProfile's own Javadoc:
  UNIVERSAL ENGINE     GARC/LZ11, the GF container family, the text cipher,
                       patricia dicts, the GF name hash, UI/workspace machinery
  GEN 6 FORMAT LAYER   BCH ("H3D") models and everything on them - map painter,
                       prefabs, previews, world animations, fog. XY + ORAS.
                       Gen 7 replaced models with GFModel/GFMotion and needs
                       its own layer.
  PER-GAME PROFILES    archive paths, text indices, verified-feature flags,
                       detection.

Profile contract: a value of null/-1/false means "not present or NOT YET
VERIFIED for this game", and callers must treat that as absence, never guess.

Verified-feature declarations today:
    ORAS  28     the reference implementation
    XY    12     substantially wired
    SM     1     stub
    USUM   0     stub - UsumProfile says "Fill from an USUM dump"

So Gen 7 is not blocked on architecture. It is blocked on MEASUREMENTS, which
need SM/USUM dumps. Nothing in this project is allowed to be filled in by
assumption, and the profile contract makes that a rule rather than a habit.

## Measured: the species tables

    a/1/9/5   personal (base stats)     827 subfiles
    a/1/9/1   826      a/1/9/2   826      a/1/9/3   826
    a/1/9/6                             723 subfiles
    a/1/9/7   item data                 776 subfiles
    a/0/0/8   models          8067 subfiles, 1.07 GB
    298 GARC archives in total

These are parallel arrays indexed by species/form id. Grow one without the
others and every index desynchronises.

NOT ESTABLISHED: a hardcoded species bound in code.bin. Searching for 721/722/
723/802/807/826/827 as u16 literals returned 20-68 hits each, which is noise for
a 5.4 MB binary - ARM encodes small constants as instruction immediates, not as
searchable literals. No limit was located. Do not repeat this search; it is the
wrong instrument. Locate it through the personal-table accessor instead.

## The split: replacing is cheap, adding is a code.bin project

Every Gen 6/7 romhack that ships new species REPLACES existing ones. That is not
a workaround for this use case - a custom region needs ITS roster, not 1,025
species - and replacement needs no binary patching at all.

    replace a species (stats, types, moves, name, dex)  data work
    swap a model XY <-> ORAS                            near copy, same BCH/H3D
    import a Gen 7 model into ORAS                      converter needed
    exceed the species cap                              code.bin, same shape as
                                                        the zone-limit work
    import Gen 8/9                                      asset pipeline, not a
                                                        conversion

The last row is the one to be honest about. Switch models are not merely a
different container: poly counts a 3DS cannot render, textures it cannot hold,
skeletons that do not match. Bringing an SV or SwSh Pokemon to a 3DS game means
mesh decimation, texture downscaling, skeleton retargeting and animation
conversion. That is 3D asset production. No editor feature makes it push-button,
and promising otherwise would set users up to fail.

## Do not rebuild

pk3DS (GPLv3, compatible) already edits personal data, moves, evolutions and
text for XY/ORAS with some SM coverage. Species replacement is exactly the place
to integrate rather than duplicate. What CTRMap uniquely adds is what pk3DS does
not do: placing those species into the custom regions, encounters and scripts
built with the map tools.

## Open questions, in the order they gate things

  1. Do SM/USUM dumps exist to measure against? Gates every Gen 7 profile entry.
  2. Where is the species bound in code.bin? Gates "adding" as opposed to
     "replacing". Same RE shape as the zone limit already under investigation.
  3. Which archive holds Pokemon model parts, and is the a/0/0/8 grouping
     uniform per species? Not established - the hand-written FATB parse drifted
     and CTRMap's own GARC reader should be used instead of re-deriving it.
