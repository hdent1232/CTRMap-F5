# Audit 2026-10-06 - findings, high and medium

Full evidence and proposed fix for each finding. Back to the [summary](AUDIT-2026-10-06.md).

<a id="f011"></a>

## F011 - The open-time repair passes rewrite zone headers per resource and write the master table only at the end; any throw in between leaves ZO headers and master rows disagreeing, and it runs automatically on every workspace open

**high** | data-integrity | `src/ctrmap/GeometryForker.java:361`

repairSharedResources: per resource, ZO files written+persisted at 427-430; master written once at 438-441; whole body in catch(Exception) -> refusedBecause (442-445). A throw from the TEXT pass (TextForker.forkAppendedTexts at 418) after the AREA pass wrote its ZO headers skips the master write. repairSharedAppendedZones has the same shape: zone file written+persisted per zone at 580-581, master at 601-604, catch-all at 605-611; forkArchives (578) or blankRegionFiles can throw on zone N after zones before it were repointed. The class javadoc (41-45) says the game reads the header from the master table, so the editor and the game then disagree about which map/area the zone uses, and nothing checks the two copies against each other.

**Fix.** Stage every write of a pass into memory (they are all byte arrays already), write master and ZO files together at the end, and on throw delete what this pass wrote. Add an integrity check that every ZO header's ids equal its master row.

<a id="f025"></a>

## F025 - The zone SCRIPT archive is not managed at all: a created zone can never have its own events, runs its donor's, and appended zones (536+) cannot run field scripts - so the headline 'talking NPCs / signs / facilities' features only work in repurposed retail slots

**high** | unfinished | `src/ctrmap/TextForker.java:24`

TextForker 24-26: 'the zone's SCRIPT cannot be forked - CTRMap does not manage that archive at all - so every created zone runs the donor's events'. CtrmapMainframe 2630-2631: 'Base zones (index &lt; 536) CAN run scripts ... Appended zones (536+) can't run scripts.'; 3027-3033 refuses a battle facility in an appended zone; ZoneRepurposeScanner exists only as the workaround (2596-2599). OUTSTANDING.md files it under Known limitations ('ArchiveType has no script archive'). README advertises talking NPCs 'in all 536 zones' and zone appending side by side without saying the two do not combine.

**Fix.** Add the script archive to ArchiveType/profile, extract/pack it like the others, add SCRIPT to ZoneAppender.MADE_PRIVATE, and find out (RE) why appended zones cannot run scripts.

<a id="f033"></a>

## F033 - Options > Clean workspace deletes every unpacked edit without asking - the other door to the same operation asks and offers to pack first

**high** | data-integrity | `src/ctrmap/CtrmapMainframe.java:1527`

cleanWorkspaceAction (1527-1531): 'unloadEditors(); Workspace.cleanAndReload(); mZonePnl.loadEverything();' - no confirm; the menu item (909) has no tooltip. Chain verified: Workspace.cleanAndReload (259-261) -> cleanAll (243-250) -> WorkspaceSession.cleanAll (690-693) = 'cleanDirectories(workspaceDir, true, persistPaths); persistPaths.clear();' javadoc 'Empties every extraction directory, edited files included, and forgets the edits'; cleanDirectory (707-720) calls files[i].delete() on everything when deletePersistent. Edits live there until Pack. One misclick on a menu item named like housekeeping loses all unpacked work.

**Fix.** Confirm with the count of edited files and offer Pack first (as the settings dialog does), or make Clean keep persisted files and offer 'discard edits' separately.

<a id="f039"></a>

## F039 - Adding zones is a one-shot operation: it refuses unless the game has exactly the stock 536 zones, so the only way to add more later is 'Remove added zones', which deletes every added zone and its content

**high** | architecture | `src/ctrmap/ZoneAppender.java:226`

appendZones: 'if (oldCount != ZoneLimitPatch.BASE_ZONES) throw new IOException("ZoneData already has " + oldCount + " zones; the code patch assumes a stock 536-zone base. Revert first.")' (226-227). The count is rounded to a multiple of 4 (224-225, ZoneLimitPatch.masterIndex). 'Revert' is CtrmapMainframe.removeAddedZonesAction, which removes ALL added zones and deletes their extracted files (2512-2527). A user who added 2 zones and later needs a 5th must destroy the first two. The class is still headed 'EXPERIMENTAL ... (ORAS only in v1)' and 'Whether the GAME accepts a grown ZoneData ... has never been proven by any fan hack' (17-18, 44-46).

**Fix.** Make ZoneLimitPatch build its patch for any target count from the stock base, and let appendZones grow from the current count (shifting master/EN again); record an in-game verification.

<a id="f046"></a>

## F046 - The zone-limit executable patch users get is built blind from Omega Ruby offsets; the verified variant exists but nothing in the program calls it, and Alpha Sapphire is not distinguished

**high** | data-integrity | `src/ctrmap/formats/codepatch/ZoneLimitPatch.java:117`

Class javadoc 11 and 37-42: offsets 'Reverse-engineered from Omega Ruby's decompressed executable'; 'IPS generation is self-contained ... applyToCode additionally verifies the stock bytes to guarantee it is patching a matching ORAS build'. grep: applyToCode has no production caller (tests only); the one producer is ZoneLoadingPanel.java:1877 'ZoneLimitPatch.buildIPS(realZones)', which embeds the five OR offsets and never sees the user's code.bin. TITLE_ID is Omega Ruby (51), and ZoneLoadingPanel.java:1854 tells every ORAS user to install it at 'sdmc:/luma/titles/000400000011C400/code.ips'. OrasProfile.titleId() (OrasProfile 170-177) is Omega Ruby for both editions. An Alpha Sapphire user (whose executable is a different build - ShopData and ItemIconTable each check for that) is handed a 5-word patch for another binary, at a path their game never reads.

**Fix.** Ask for the user's decompressed code.bin when producing the patch and build it with applyToCode-style verification (diff -> IPS); detect the edition (C400/C500) and use its own offsets or refuse.

<a id="f077"></a>

## F077 - The macOS/Linux build cannot initialise OpenGL: only Windows x64 JOGL natives ship, and run.sh puts no native jar on the classpath - measured failing on Linux

**high** | build-debt | `run.sh:16`

lib/ holds jogl-all-natives-windows-amd64.jar and gluegen-rt-natives-windows-amd64.jar only (unzip -l: 'natives/windows-amd64' and nothing else). run.sh (the launcher README advertises for 'macOS/Linux') runs 'java -cp CTRMap-F5.jar:lib/jogl-all.jar:lib/gluegen-rt.jar ctrmap.CtrmapMainframe' (16-18). Measured in this session on Linux amd64 with that classpath: 'gluegen platform init FAILED: java.lang.UnsatisfiedLinkError: Can't load library: .../natives/linux-amd64/libgluegen-rt.so' then 'GLProfile FAILED: NoClassDefFoundError: Could not initialize class com.jogamp.common.os.Platform'. CtrmapMainframe.createAndShowGUI constructs GLPanel and H3DRenderingPanel unconditionally (574-575), so the window build hits that error. Apple Silicon (aarch64) would need its own natives too.

**Fix.** Bundle the linux-amd64/macosx-universal natives (or the fat jogl-all-main jars), or stop advertising the portable zip for macOS/Linux; and make the 3D panels degrade (2D-only) when GL cannot initialise instead of failing the window.

<a id="f083"></a>

## F083 - Saving props with a matrix open silently deletes any prop whose cell has no region (inverted guard makes the fallback dead), and throws for props off the grid

**high** | data-integrity | `src/ctrmap/formats/propdata/GRPropData.java:63`

PropEditForm.store:813 calls props.write(map.mm) on every prop save with a matrix open. GRPropData.write(MapMatrix) (42-83) assigns each prop to cell ((int)x/720, (int)z/720). If that cell is null it runs a 'nearest populated cell' search whose body is guarded by 'if (y &lt; 0 || y >= mm.height)' with no braces and no continue (63-64): the inner loop runs ONLY for out-of-range y, where mm.regions.get(x, y) is ArrayList.get(-1 or height) -> IndexOutOfBoundsException; for every in-range y it does nothing. So a prop in an empty cell is either dropped from the written file (no cell receives it; nothing is reported) or the save throws on the EDT. A prop at x or z &lt;= -720 or past the grid edge throws at line 55 (ResizeableMatrix.get is list.get(x).get(y)). The 'limit' expression Math.abs(mm.width/2 + horizontalRegion) is not a distance. assemblePropData returns null on IOException and the caller passes it to storeFile. Class doc carries 'TODO - injecting textures'.

**Fix.** Replace with: clamp/nearest-cell by real distance over populated cells, refuse the save (in words) when no populated cell exists, never drop a prop; add a test with a prop in an empty cell.

<a id="f096"></a>

## F096 - The map-matrix zone-grid tool throws ClassCastException on cell selection: one field is cast to Short on one path and Integer on another

**high** | correctness | `src/ctrmap/humaninterface/MatrixEditForm.java:1004`

zoneRefNumber is a plain JFormattedTextField (initComponents:448, no formatter). showRegion (186-190) sets it to mm.zones.get(x,y) - a Short - then calls zoneRefDropdown.setSelectedIndex, which fires zoneRefDropdownActionPerformed (843-848): zoneRefNumber.setValue(getSelectedIndex()) - an Integer - then setZoneByNumber (1002-1007) does (Short) zoneRefNumber.getValue(). Measured by replicating the chain headlessly (scratchpad/probe/ZoneRefProbe.java): 'CAST FAILS in setZoneByNumber: class java.lang.Integer cannot be cast to class java.lang.Short'; control shows the stored value is Integer. The opposite cast fails on the other paths: zoneRefNumberActionPerformed (838-839) and btnFillChunkActionPerformed (825, which calls it first) do (Integer) zoneRefNumber.getValue(), which throws whenever the field holds the Short from showRegion or a user-typed value (the field's formatter class is fixed by its first Short value). The guard conditions (MZ tool selected, mm.hasLOD == 1, a cell selected) are exactly the tool's normal use on the 22 retail matrices that carry a zone grid. grep finds no setDefaultUncaughtExceptionHandler in the tree, so the exception goes to stderr on the EDT and the zone edit is silently not applied.

**Fix.** Read the field as ((Number) getValue()).intValue() everywhere (or give it one Integer formatter) and add a headless test that drives showRegion -> dropdown -> setZoneByNumber.

<a id="f173"></a>

## F173 - The GAME DATA IS READ-ONLY guard is bypassed by a line before the write, or by env/sudo/bash -c in front of it, or a writer not on its 37-name list (find -delete, rsync --delete)

**high** | silent-failure | `.claude/hooks/guard_game_data.py:105`

MEASURED (scratchpad/gameprobe.py: the installed guard and its tools/hooks twin, each run as the harness runs hooks with the payload on stdin - nothing executed, nothing under RomFS touched; control: every write below is REFUSED when it is the whole command). With `cd /tmp` on the line before, all four are ALLOWED by both copies: `rm RomFS/000400000011C400/a/0/1/3`, `mv Workspace/zonedata/3 /tmp/x`, `Remove-Item -Recurse RomFS_original_garcs/a`, `cp /tmp/evil RomFS/.../a/0/1/3`. Cause: _targets() (.claude/hooks/guard_game_data.py:105) splits a command with re.split(r"[;|&amp;]+|&amp;&amp;") - no newline - so a multi-line command is one piece whose first word is `cd`, not a writer, and its later writers' arguments are never examined (only the anywhere-in-text redirect and -Destination patterns survive). This is the guard behind CLAUDE.md's GAME DATA IS READ-ONLY - data its own refusal text says "cannot be regenerated without a 3DS and several hours" - and the newline-blind splitter is the exact defect bundle_shell.py:40-43 says was found in five hooks and fixed by one shared splitter; the project stack never adopted it (shellin.py hands each guard the whole command string). Same class as F172. ADDENDUM - ON ONE LINE TOO (scratchpad/gameprobe2.py, the installed .claude/hooks/guard_game_data.py run as the harness runs it, nothing executed; control: `rm RomFS/a/0/1/3` is refused). The same delete is ALLOWED when anything stands in front of the writer or the writer is not on the hand list: `env rm RomFS/a/0/1/3`, `sudo rm RomFS/a/0/1/3`, `bash -c "rm RomFS/a/0/1/3"`, `find RomFS -name '\*.bak' -delete`, and `rsync -a --delete empty/ RomFS/`. _targets (:98-121) takes only the FIRST word of each piece as the writer and compares it with _WRITERS, a list of 37 names - the shape the hook's own docstring rejects for its roots ('A hardcoded list is a guard that is dead in every project but one') and the shape CLAUDE.md names A GUARD'S TRIGGER IS A CLASS, NOT A LIST OF NAMES. The bundle's shell reader (bundle_shell.every_command_is, tests/test_bundle.py AnEscapeIsAskedOfEveryCommand) already reads through env, timeout, bash -c, sh -lc and cmd /c. ADDENDUM - IT ALSO REFUSES WHAT IS NOT A WRITE TO GAME DATA (observed live during this audit, 2026-10-06). A 'sed -i' rewriting a file in the session scratchpad was refused with 'GAME DATA IS READ-ONLY, and this writes 'RomFS', which is under RomFS/ (sed -i rewrites the file in place)'. 'RomFS' was a word inside the sed REPLACEMENT TEXT, not a path. The guard tokenises the command and treats any token naming RomFS as the write target. It is blind to the real bypasses this finding lists and fires on a quoted word, so the guard's errors run both ways: it passes writes it should catch and refuses edits that never touch game data. The same edit went through the Edit tool, which the guard does not inspect by content. ADDENDUM - A SECOND FALSE POSITIVE, SAME DAY: A QUOTED '>' READ AS A REDIRECT. A 'python3 fnd.py add ...' command whose double-quoted argument contained the prose 'Options > Workspace settings' was refused with 'this writes Workspace, which is under Workspace/ (a redirect writes it)'. The guard does not respect shell quoting: a '>' inside a quoted string is taken as a redirect and the next word as its target. Like the 'RomFS' case above, the tokenizer is wrong in both directions. It has no notion of what the shell will actually execute.

**Fix.** split commands with a newline-aware splitter (one shared reader for every project guard, as bundle_shell.split_commands is for the bundle's), and add multi-line cases for every writer verb to the guard's tests and plants.

<a id="f175"></a>

## F175 - A git push is never recognised on any line but the first, or when handed to bash -c / sh -c / powershell / xargs or reached through a git alias - the per-push approval rule is not asked

**high** | silent-failure | `.claude/hooks/guard_push.py:64`

MEASURED (scratchpad/pushprobe.py: .claude/hooks/guard_push.py and its tools/hooks twin, run as the harness runs hooks with cwd at the repository; no .push-approved token present; nothing pushed - the guard only runs read-only git rev-parse; control: `git push origin master` as the whole command is REFUSED by both). `cd &lt;repo>` NEWLINE `git push origin master`, and `echo pushing` NEWLINE `git push -u origin HEAD`, are ALLOWED by both with no approval asked. Cause: _PUSH (:64) is anchored on (?:^|[;|&amp;(]|\$\() and compiled without re.M, so a push that does not begin the whole command string, and is not after ;

&amp; or (, is not recognised as a push at all; repo_of's cd regex (:92) has the same blindness. No second line of defence: .githooks holds commit-msg, gate-before and pre-commit - no pre-push - so this PreToolUse guard is the only enforcement of CLAUDE.md's NEVER PUSH WITHOUT EXPLICIT PER-PUSH APPROVAL. Its own docstring (:33-37) records the previous recognition hole ("A PUSH IT CANNOT RECOGNISE IS A PUSH IT NEVER JUDGES") as fixed. Same newline class as F172/F173. ADDENDUM (read tests/test_guard_push.py, all 165 lines): its spelling test (:109-123) and pass-through test (:146-161) drive 13 spellings of a push - -C with a spaced folder, -c, an env prefix, env, the path to git.exe, &amp; with that path, cd &amp;&amp; push - and none on a second line, so the suite that exists for exactly this class (its docstring: a push spelled so it is not recognised as one) cannot see F175. ADDENDUM - ON ONE LINE TOO (scratchpad/pushprobe2.py, the installed .claude/hooks/guard_push.py run as the harness runs it, no token present; control: `git push origin master` alone is refused). Five one-line spellings that run the same push are ALLOWED with no approval asked: `bash -c "git push origin master"`, `sh -c 'git push origin master'`, `powershell -Command "git push origin master"`, `xargs git push origin master`, and `git -c alias.p=push p origin master`. _PUSH (:64) recognises git only after the start of the line or a ;

&amp; ( or $( and only when the subcommand word is literally `push`, so a push handed to a shell, run by xargs, or reached through an alias is not a push to it. The bundle stack already reads through exactly these wrappers and aliases for `git commit` (guard_command_rules; tests/test_bundle_action.py GitBehindAWrapperIsStillGit drives 14 wrapper spellings, 9 shell hand-offs and both alias forms) and has no push rule at all, so the one rule the owner states as NEVER has the weaker recogniser of the two stacks.

**Fix.** recognise pushes per command through a newline-aware splitter (and through wrappers, as bundle_shell reads them); add a .githooks/pre-push that checks the same token so the rule holds for a push no PreToolUse hook can read; add multi-line cases to tests/test_guard_push.py.

<a id="f176"></a>

## F176 - The project's guard stack has no newline-aware command splitter: 5 of its command guards pass their own refused case once any line precedes it

**high** | architecture | `.claude/hooks/shellin.py:135`

MEASURED, THE CLASS (scratchpad/classprobe.py: every installed guard that judges shell text, from both hook folders, each given one case it refuses as a one-line command - all 12 controls held - then the same case after a `cd .` line; run as the harness runs hooks, nothing executed; the suite-running case in a scratch folder with its own fresh build/.battery-running lock). 5 of 12 pass their own case after a cd line, and all 5 are the project stack's: guard_game_data (F173), guard_push (F175), guard_background_pipe (F172), guard_suite_running, guard_idle_poll. All 7 bundle-side or newline-aware ones held (bundle background_pipe, idle_poll, heredoc, command_rules; project forbidden_command, exit_code, heredoc). New member here: guard_suite_running's written() splits shell text on [;|&amp;]+ (:132) and shellin.writes_something does the same (shellin.py:135), so `cd .` NEWLINE `rm src/Foo.java` during a battery is allowed - CLAUDE.md's NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING has no bundle twin to catch it (redirects are still seen: they are matched anywhere). guard_idle_poll's hole is covered only because its bundle twin also runs. One cause: the project stack has no shared newline-aware splitter - every project guard splits for itself (shellin.py hands each the whole command string) - while bundle_shell.split_commands exists one folder over. ADDENDUM (ran tools/guard/rule_map.py here: exit 0, '32 rules in CLAUDE.md: 32 have a mechanism, 0 do not'). The project's measurement of whether its rules are enforced reports green over exactly these holes: rule_map.json maps NEVER PUSH to guard_push.py, GAME DATA IS READ-ONLY to guard_game_data.py and NEVER EDIT SOURCE WHILE THE SUITE IS RUNNING to guard_suite_running.py - three of the five guards this class shows passing their own case after one harmless line. _exists() (:69-93) asks only that the named file or token is in the tree; nothing asks a mechanism to refuse its rule in more than the one spelling its own test uses.

**Fix.** give shellin one splitter (newline, ;, &amp;&amp;, ||, quotes, heredoc bodies - port bundle_shell.split_commands) and route every project guard and writes_something through it; add a class test that runs each guard's one-line refusal behind a preceding line, so a guard added tomorrow is held to it.

<a id="f177"></a>

## F177 - Project hook stack fails OPEN on any payload byte cp1252 cannot decode: one Japanese character skips every project guard

**high** | security | `.claude/hooks/guard_all.py:304`

MEASURED (scratchpad/cp1252probe.py: .claude/hooks/guard_all.py run as the harness runs it, payload sent as the harness's UTF-8 BYTES, PYTHONIOENCODING=cp1252 forced exactly as tests/test_bundle.py AHookAnswersTheHarnessInUTF8 forces it to judge the Windows pipe the owner's machine has - that file records the cp1252 decoding as measured on 2026-09-26 in CTRMap; nothing executed). Controls: `rm RomFS/a/0/1/3` and `git push origin master` are REFUSED bare, and still REFUSED with a trailing comment holding an e-acute (cp1252 mangles it but decodes it). The same two commands with a trailing comment holding ONE Japanese katakana (U+30DD), o-macron (U+014D) or an emoji (U+1F501) are ALLOWED, exit 0, no deny. Cause: guard_all.main (:302-306) reads `json.load(sys.stdin)` through the text layer, and cp1252 leaves bytes 0x81 0x8D 0x8F 0x90 0x9D undefined, so the strict decode raises UnicodeDecodeError, which `except Exception: sys.exit(0)  # never break a turn on a parse failure` turns into ALLOW for EVERY project guard at once (game data, push, suite-running, generated-code/initComponents, fan-out, heredoc, forbidden-command ...). settings.json runs it as plain `python ...guard_all.py` - no -X utf8, no PYTHONUTF8. The same text read is in 16 project hook files (grep json.load(sys.stdin)|sys.stdin.read() over .claude/hooks/\*.py), guard_unfinished.py (the Stop hook) among them, and in the byte-identical tools/hooks copy. The bundle stack fixed exactly this class (bundle_shell.payload_text decodes the bytes; test_no_entry_point_the_harness_runs_reads_stdin_any_other_way) and asks it only of HOOKS = the bundle folder; nothing asks it of .claude/hooks. Japanese text is ordinary in this project's payloads (Pokemon names, game text), so this is not a contrived bypass: any Write/Edit whose content holds such a character skips every project guard. ADDENDUM: this project already knows the class and fixed it one hook over, on the same date. tools/arm_checkout.py:196-199 (the SessionStart hook) reads its input as UTF-8 BYTES with the comment that sys.stdin.read() decodes a Windows pipe as cp1252, and :306-311 writes its output as UTF-8 bytes for the same reason (measured 2026-09-26, UnicodeEncodeError, exit 1). guard_all.py - the PreToolUse door to every project guard - was not changed.

**Fix.** Read the payload as bytes and decode UTF-8 (sys.stdin.buffer.read().decode('utf-8')) at one shared door in .claude/hooks, as bundle_shell.payload_text does; make a parse failure DENY (or at least loud) rather than exit 0; extend the bundle test's stdin_reads/speaks_the_code_page checks to .claude/hooks and tools/hooks.

<a id="f182"></a>

## F182 - Tracked git hooks are mode 100644, so on every POSIX clone (all cloud sessions) git skips commit-msg and pre-commit while arm_checkout reports them ARMED

**high** | silent-failure | `tools/arm_checkout.py:76`

MEASURED (scratchpad/hookexec.py, git 2.43.0, a SCRATCH repository armed the way this project arms itself - core.hooksPath = .githooks - holding a commit-msg that always exits 1; control: at mode 100755 the commit is REFUSED by the hook). At mode 100644 - the mode all three tracked hooks have (`git ls-files -s .githooks`: commit-msg, gate-before, pre-commit all 100644) - the same commit EXITS 0: git prints "hint: The '.githooks/commit-msg' hook was ignored because it's not set as executable" and commits. In the same 100644 trial tools/arm_checkout.arm_hooks reports armed=True ("git hooks ARMED"), because it asks only os.path.isfile(commit-msg) (:76, :91). So in every POSIX clone - Linux and macOS, i.e. every cloud session, the exact case arm_checkout.py's docstring says it exists for ("a fresh clone ... ran NO commit hook at all ... silently off, with .githooks/ sitting in the repository looking armed") - commit-msg and pre-commit never run: commit_guard.py, commit_gate.py's staged checks (fix touches a guard, new and held plant drives), bundle_install/tiers/timeouts/format_strings/prove_plants --owed, and the gate stamp. This session's own SessionStart printed "OK: git hooks already armed (core.hooksPath = .githooks)". What is left at a commit is the PreToolUse command check (guard_command_rules asks the message rules); and because no stamp is ever written, gate_stamps.ungated would then report every honest cloud commit as made past the gate and refuse every later command, with a remedy (commit through the gate) that cannot run there. The bundle's own tests show it: test_bundle_gates' three gate-stamp tests write their scratch commit-msg with io.open (mode 0644) and FAIL on this machine (scratchpad/pysuite.txt) - green only where the execute bit does not exist. On Windows git ignores the bit, which is why the owner never sees it.

**Fix.** Mark the hook files executable in the index (git update-index --chmod=+x); make arm_hooks require os.access(hook, os.X_OK) on POSIX and read it back; write the test scratch hooks with mode 0755; add a test that git ls-files -s reports 100755 for every hook file.

<a id="f219"></a>

## F219 - In the double-click Windows build no error reaches anyone: no uncaught-exception handler, no log file, and a jpackage launcher with no console - so 114 log/stderr sites and every exception escaping an EDT listener vanish

**high** | silent-failure | `package.ps1:120`

MEASURED (scratchpad/m_errsink.py over all 418 tracked src/\*\*/\*.java, comments blanked and string literals kept. CONTROLS: a planted 'Thread.setDefaultUncaughtExceptionHandler(h);' matches the handler pattern; a comment containing Logger.getLogger is blanked; a 'sun.awt.exception.handler' string survives the blanking; CtrmapMainframe is among the files with a Logger site). RESULT for production source (outside ctrmap/tests): 76 Logger.getLogger sites in 33 files, and 38 printStackTrace / System.err sites in 24 files. There are 0 sites, in production or tests, of setDefaultUncaughtExceptionHandler, sun.awt.exception.handler, addHandler, FileHandler, LogManager or logging.properties, and nothing calls System.setErr. Every one of those 114 sites therefore writes to java.util.logging's default ConsoleHandler or to System.err directly, and so does every exception that escapes an EDT listener: Swing's default is to print the stack to stderr and carry on. Where stderr goes depends on how the app was started. run.bat:18/20 starts 'java -Xmx1024m ...', a console process, so a user who started it that way can see the trace in that window if they look. package.ps1:120-125 builds the one-double-click Windows build ('For the user who does not have Java and should not have to care', :100) with 'jpackage --type app-image' and no --win-console, which makes a GUI-subsystem launcher with no console attached. In that build every Logger line, every printStackTrace and every uncaught EDT exception goes NOWHERE: no window, no file, no status line. That multiplies the reach of the 64 LOG-ONLY catch blocks counted for F211 (log a SEVERE and carry on), which in this build are byte-for-byte the same as empty catches, and of every 'no try/catch, the exception goes to the EDT' path filed in this audit and the census (census H.36, at its line 503, already notes in passing that 'the shipped app has no console', but no entry files the missing sink itself). The only file-backed output in the tree is Updater's apply.log (Updater.java:423-424), for the updater subprocess alone (census M.143). The 330 Ui.error/message/confirm/... calls in 34 files are the app's visible channel, and they are reached only where a catch was written to call one. ADDENDUM - TWO MESSAGES SEND THE USER TO THAT LOG. git grep of production string literals for 'see (the) log/console' and similar finds two, and both point at the stream this finding shows goes nowhere. BuildingPaletteDialog.java:284: 'Could not cut this piece from the dump (see log).' GeoEditForm.java:359: '(skipped: N piece(s), see log)', where the list it refers to is printed with System.out.println on the next line (:362). In the double-click build there is no log to see.

**Fix.** Install one sink at startup (CtrmapMainframe.main, before the first window): Thread.setDefaultUncaughtExceptionHandler plus a java.util.logging FileHandler in the workspace or user folder, and route uncaught EDT exceptions to a Ui.error dialog that names the log file. Guard: a suite that starts the frame headless, throws from a listener, and asserts the log file received the trace; and a source check that refuses a new Logger site in a class with no path to that handler (there is one handler, so this is a startup assertion).

<a id="f220"></a>

## F220 - Opening a zone hands it to all nine editors on a SwingWorker thread: 198 Swing mutations off the EDT, a nested modal progress dialog shown from the worker, and two JOptionPanes - and across the tree 10 of 18 background entries reach 251 Swing mutation sites, 233 of them through calls a body-level grep cannot see

**high** | architecture | `src/ctrmap/humaninterface/ZoneLoadingPanel.java:1272`

MEASURED FROM BYTECODE (scratchpad/m_edt.py over javap of the scratchpad build; summaries m_edt_sum.py, m_edt_depth.py, m_edt_paths.py). ENTRIES: every doInBackground of a SwingWorker subclass, every Thread subclass run(), and every Runnable handed to new Thread(...). The probe found 18, which equals the 13 'doInBackground()' declarations plus the 5 'new Thread'/'extends Thread' sites in production source. From each entry it follows application calls 6 deep: virtual and interface calls go to every application implementation, and a lambda or anonymous class handed straight to SwingUtilities/EventQueue.invokeLater is not followed. It counts every invoke on a Swing/AWT component or model class (repaint/revalidate and SwingWorker's own thread-safe methods excluded). CONTROLS: ZoneLoadingPanel.java:213-214 (JComboBox setSelectedIndex/removeAllItems at depth 0, census M.13); Builder.java:560-561 (getSelectedIndex at depth 0 plus DefaultListModel mutations through loadGARC, census M.15); UpdateUI's worker is an entry; TileDBWriter.main's invokeLater Runnable is not; ZoneEditors.show dispatches to its ZoneView implementations in CtrmapMainframe. That last control FAILED on the first version of the probe, which followed interface calls into the body-less abstract declaration and reported 40 mutations for the zone-open worker where the true count is 198. RESULT: 10 of the 18 background entries mutate Swing, at 251 distinct sites in 15 files. Only 18 of those sites are inside a doInBackground/run body. The other 233 are reached through calls, so the grep-shaped rule census M.15 proposes ('no doInBackground body touches a Swing model') would see 18 of 251. THE WORST ONE IS OPENING A ZONE, read in source: ZoneLoadingPanel.java:1272-1295 doInBackground calls loadZone(z) (39 widget writes) and zoneEditors.show(z), which hands the zone to all nine ZoneViews (CtrmapMainframe.java:301-467) on the worker thread. That is 198 mutations: NPCEditForm 55, CameraEditForm 33, MatrixEditForm 29, TriggerEditForm 17, WarpEditForm 8, ScriptEditor 3 and more. Among them, the map view's show() calls TileMapPanel.loadMatrix (:476), which on the worker thread does TileUndo.clear(), Selector.unfocus, LoadingDialog.makeDialog (a 'new JDialog()'), starts a NESTED SwingWorker and calls progress.showDialog(), which is modal dlg.setVisible(true) (LoadingDialog.java:88), off the EDT while the outer loading dialog is up. CameraEditForm.loadFrom shows Ui.error (CameraEditForm.java:72) and NPCEditForm.loadFromEntities asks 'Renumber them now?' through Ui.confirm (NPCEditForm.java:176), both from that worker thread; Ui.message/confirm do not marshal to the EDT (Ui.java:180-190, 224-247). ZoneLoadingPanel.java:1262-1271's own comment records that a modal dialog off the event thread was removed from this exact worker, and the same worker still reaches three. OTHER ENTRIES: TileMapPanel$3 (load matrix, :497) runs loadRegions, which rebuilds the displayed CollEditPanel's JTree (JTree.setModel, CollEditPanel.java:201/226), remove(placeholder) and the PropEditForm combos. ZonePreviewPane$1 (:143) does the same on the preview's own panel and constructs a GLJPanel off the EDT in mapView() (:266-283); two quick selections can run loadRegions on that one panel concurrently, because seq is checked only before draw. ESPICAControl$3 (:146-157) appends to a JTextArea and enables a button from a raw thread. SetupWizard$14 (:533-541) writes the Workspace.GAMEDIR_PATH/WORKSPACE_PATH statics and can show Ui.error through Workspace.reportSnapshot. Workspace$1 (pack), TileMapPanel$2/$4 and ZoneLoadingPanel$6 drive LoadingDialog.setBarPercent/setDescription from the worker; LoadingDialog's javadoc calls it 'suitable for SwingWorkers', but its setters (:39-44) write JProgressBar/JLabel directly. NOT FOLLOWED: Workspace$PackProgress.at (10 calls) and Redraw.all (2 calls) have no application implementation that bytecode can name, because they are lambdas. The GL panels are all GLJPanel, whose display() runs on the EDT, so they are not entries. Census M.13, M.14 and M.15 and F116 are instances of this class. None of them measures its reach, and nothing in production asserts the thread anywhere: git grep finds 0 calls to SwingUtilities.isEventDispatchThread or EventQueue.isDispatchThread in production source.

**Fix.** Split every worker into read (doInBackground: file I/O and parsing only, returning a value) and show (done()/process() on the EDT): ZoneLoadingPanel's zone-open worker returns the Zone and done() calls loadZone, loadedZone.open and zoneEditors.show; TileMapPanel.loadMatrix splits loadRegions into a parse step and an install step; LoadingDialog's setters marshal with invokeLater (or callers use publish/process). Make it impossible at the point of action: Ui.message/confirm/option/input and LoadingDialog.showDialog throw IllegalStateException off the EDT, and a suite runs m_edt-style reachability over the bytecode (every doInBackground/run entry, interface calls dispatched to implementations) with a ceiling of 0 Swing mutation sites, planted with a worker that calls a helper that calls setText.

<a id="f227"></a>

## F227 - The workspace has no unit of work: 14 user-facing operations (fork, resize, clone, interior wiring, area forks, append, repair, blank canvas, painter Apply, pack, Connect zones, Rename zone) write several files in sequence and leave whatever they reached staged when one throws - while five features each built their own private rollback

**high** | architecture | `src/ctrmap/GeometryForker.java:224`

READ IN SOURCE. The workspace has no unit of work. Every operation that changes several files writes them one after another (Files.write / storeFile, each followed by addPersist so it ships at the next pack) and stops wherever an exception stops it. Whatever was written before that point stays staged, with no rollback and no record of what a half-run left. Read for this finding, each confirmed to write in sequence with no undo on failure: (1) GeometryForker.forkArchives (:224-262) copies each region (:245-257, an IOException at :247 or :251 after earlier regions were written), then the matrix, while the pack-time compression maps (F014) are filled per file. (2) MapResizer.resize (:229-270) writes the new regions, then the matrix (:263), then the ZO header (:269), then the master row (:271). A throw between the matrix and the ZO write leaves a staged matrix that nothing points to, and a throw between the ZO and the master row leaves the header and the runtime-authoritative row disagreeing. (3) TextForker.forkAppendedTexts (:95-112) writes and persists one story text per zone and can throw on the next zone (:100, :108), after the earlier copies are staged and the in-memory master edited. (4) InteriorWirer.cloneAndWire (:74-97) clones the donor into the slot (ZoneCloner.cloneIntoSlot, which persists the ZO and the master row), and only then reads, rewires and writes the exits. Any failure after the clone leaves a cloned interior whose exits lead into the retail exterior. ALREADY FILED INSTANCES OF THE SAME SHAPE: forkArea (F003), forkAppendedAreas (F002), the open-time repair passes (F011, census H.10), the pack across archives (F035), the append's geometry fork before its budget check (F040) and its text fork (census M.20), clone-then-fork in two places (census M.34, and the same shape in setupFacilityAction, CtrmapMainframe.java:3086-3088), Blank canvas half-written regions (F072, census H.59), and the map painter's commit phase (F112). Counted without overlap that is 14 user-facing operations (fork geometry, resize, interior wiring, area fork, batch area fork, open-time repair, pack, zone append, clone zone, facility setup, blank canvas, painter Apply, Connect zones, Rename zone) and 16 write sequences, because the append runs both forkArchives and TextForker. Connect zones: ZoneLinker.link writes zone A (:116) and then zone B (:118-125), so a failure on B leaves a one-way door, the outcome its own class javadoc calls worse than none. Rename zone: ZoneManager.renameZone writes the names file (:157-158) and then the ZO header and master row (repointParentMap, :160-162). MEANWHILE THE TREE HAS BUILT ROLLBACK FIVE TIMES, each private to one feature and each different: TilePainterForm.StagedRegion/StagedArea (:287, :301, 'STAGED into the Apply's area transaction'), GeoEditForm.Snapshot (:61, 'An edit is TRANSACTIONAL across the three data layers'), PaintForm's restoreRealModel 'put back' (:889-912), PropEditForm's 'all-or-nothing: the merged pack bytes are fully built' (:530, :1060), and Updater.applyStaged's copy-back (:322-335; census M.143 found its twin runApply without it). git grep finds 0 production classes named for staging or transactions outside those two TilePainterForm/GeoEditForm inner classes. Each fix so far has added a sixth private answer or none, so every new multi-file operation starts non-atomic by default.

**Fix.** One WorkspaceTransaction in WorkspaceSession: writes go to a scratch overlay keyed by (archive, entry), the master-table and ZO edits included, plus the pending compression overrides (F014); commit() moves them into the extraction folders and the persist list in one step, after every precondition has been checked; abort() deletes the overlay. Port the 14 operations onto it, starting with forkArchives/MapResizer/TextForker/InteriorWirer, and retire the five private mechanisms into it. Guard: a fault-injection suite that throws from the Nth write of each operation in a scratch workspace and asserts the extraction folders and persist list are byte-identical to before; and a source rule that refuses Files.write/storeFile/addPersist on workspace files outside the transaction.

<a id="f231"></a>

## F231 - A damaged or missing game archive leaves the editor with no workspace - Workspace.validate empties it first and a damaged archive's RuntimeException escapes its catch - and closing the window in that state deletes every unpacked edit (census H.13): Workspace settings also leaves the previous zone open over the null workspace

**high** | silent-failure | `src/ctrmap/Workspace.java:191`

READ IN SOURCE. Workspace.validate (Workspace.java:185-200) begins with install(null), which sets current = null (:132-134), and then calls WorkspaceSession.open inside 'try { ... } catch (WorkspaceSession.OpenFailed ex)'. open() checks only that each required archive EXISTS (:278-285). It then runs s.openArchives() (:290), which constructs a GARC per archive (:296-305), and GARC.parse throws RuntimeException('... is not a readable archive: ...') on a damaged one (GARC.java:167; F054 and census M.21 describe what it does not check). That RuntimeException is not an OpenFailed, so it leaves validate with current already null. All three callers then skip CtrmapMainframe.onWorkspaceOpened, the method written for exactly this case. Its null branch exists because 'A WORKSPACE THAT FAILED TO OPEN TAKES THE ZONE THAT WAS OPEN WITH IT ... leaving the PREVIOUS game's zone open at its old index with the entity editors still showing its records' (CtrmapMainframe.java:1791-1803), and it says Options > Workspace settings reaches that state. (1) Workspace settings, WorkspaceSettings.applySettings (:94) 'onWorkspaceOpened(Workspace.validate(this))' with no catch. A new game folder holding one unreadable archive throws on the EDT, so the window's 'game' static still names the PREVIOUS session and the previous zone stays open with its editors live, while Workspace.current is null. That is the split state the null branch was written to prevent, reached by the throw instead of the null. In the shipped build the exception goes nowhere (F219). (2) Startup, CtrmapMainframe.java:837 'onWorkspaceOpened(Workspace.validate(frame))' in the window build, no catch: the app comes up with no workspace and no word about why. (3) The setup wizard catches it and tells the user their dump is incomplete (census M.140). The OpenFailed contract ('the list of problems the user is shown') covers missing files only. A present but damaged archive, the case a user can actually fix, is the one that bypasses it. ADDENDUM - AND CLOSING THE WINDOW THEN DELETES THE UNPACKED EDITS (census H.13, re-checked 2026-10-06 and still true). The window's close handler runs Workspace.cleanUnchanged() whenever openEditors.saveAll answers true (CtrmapMainframe.java:792-795). With a session open that keeps the persisted edits. With NO session (current == null) and a WORKSPACE_PATH set, it calls 'WorkspaceSession.cleanDirectories(new File(WORKSPACE_PATH), false, NO_PERSISTED)' (Workspace.java:263-268), where NO_PERSISTED is an empty list (:157). cleanDirectory protects a file only if that list contains it (WorkspaceSession.java:707-719), so every extracted file is deleted, the user's unpacked edits included. That state is exactly what this finding produces: validate has run install(null) and thrown. A missing game folder produces it too, through the OpenFailed path. A user whose game folder is moved, renamed or holds one damaged archive starts the editor, sees no workspace, closes it, and loses every edit not yet packed.

**Fix.** WorkspaceSession.open wraps openArchives/readPersist and converts any failure into OpenFailed naming the archive and the reason (Ui.reason), so all three callers go through the one reporting path and onWorkspaceOpened(null) always runs on failure; install(null) moves after the open succeeds or fails, not before. Guard: a suite that opens a scratch game folder with one archive truncated to 10 bytes and asserts validate returns null with a problem naming that archive, and that onWorkspaceOpened(null) closed the loaded zone.

<a id="f234"></a>

## F234 - Empty zone, Rename zone and Connect zones write a zone through their own container while the zone table keeps its old one - all three default to the open zone - and the next save of that zone splices with stale offsets: measured, the emptied entities get 48 bytes of script appended, a link's added warp is cut off, and a large shrink truncates the zone file to its header

**high** | data-integrity | `src/ctrmap/formats/containers/AbstractGamefreakContainer.java:313`

MEASURED BY RUNNING IT (scratchpad/jtool/StaleContainerProbe.java against the tree compiled into the scratchpad; FakeGameFiles; a synthetic ZO with subfiles header 56 B, entities 64 B, script). The setup is two ZO instances over one file, as the editor holds them: A is the zone table's instance, B is a second one an action opens. B.storeFile(1, ...) rewrites the entities, then A.storeFile(2, unchanged script bytes) runs, which Zone.store does on EVERY save of the open zone ('file.storeFile(2, s.getScriptBytes())', Zone.java:108, written whatever 'keep' says). CONTROL 1: after B's write a fresh instance reads exactly B's entities and the old script. CONTROL 2: the same store from a FRESH instance leaves the file intact, so the damage is the stale instance. RESULTS. (a) Entities shrink 64->16 (Empty zone), script 400 B: after A's store, subfile 1 is 64 bytes, B's 16 emptied bytes plus 48 bytes of script spliced on. (b) Entities grow 64->96 (Connect zones adds a warp): subfile 1 is cut back to 64 bytes, and the last 32 bytes of what the link wrote are gone. (c) Shrink larger than the script: the container is TRUNCATED TO ITS 20-BYTE HEADER and ArrayIndexOutOfBoundsException escapes storeFile, whose catch is IOException only (AbstractGamefreakContainer.java:291-350; F048's in-place truncation is what destroys the file). WHY: storeFile reads the file's CURRENT bytes but slices them with this instance's own offsets[] (:313-337), and nothing re-reads offsets after another instance rewrites the file. HOW THE EDITOR GETS THERE, read in source. ZoneLoadingPanel.loadEverything builds a ZO and parses a Zone for EVERY slot once, from Workspace.getWorkspaceFile (:243-247), which hands back the existing extracted file (WorkspaceSession.java:516-517). Opening a zone uses that object (loadedZone.at, :1284). Three actions then write a zone through their OWN new ZO, and all three DEFAULT to the open zone. Zone > Empty zone calls ZoneManager.clearZone (:48-75, 'new ZO(zf, ws)'; default 'def = loadedZone.index()', CtrmapMainframe.java:2569) and then tells the user 'Reselect the zone in the dropdown to see it cleared', and that zone switch runs openEditors.saveAll -> ZoneLoadingPanel.store -> Zone.store -> the stale A.storeFile(2). Zone > Rename (default :2317; ZoneManager.renameZone writes the header, so the in-memory header now differs and a later save offers to write the OLD header back). Zone > Connect zones (ZoneLinkDialog starts on 'here = zones.index()', :59; ZoneLinker.link writes both ends through new instances, :104-134). None of them reloads the table (grep: no reload in ZoneLinker/ZoneManager/ZoneLinkDialog; the Empty message asks the user to do it). The OTHER end of a link is stale as well: its table Zone was parsed before the link, so opening it shows the pre-link warps, and its first save cuts the link off as in (b). Census M.99's open-zone hazards and F048's truncation are neighbours of this; neither names the stale-instance write. ADDENDUM - THE HAZARD IS KNOWN, AND GUARDED FOR ONE PAIR OF COPIES. ZoneLinker.link reloads zone B before its second write when A and B are the same zone (ZoneLinker.java:119-122): 'RELOADED, not the copy taken above: A and B are the same zone when somebody links two doors inside one building, and writing the stale copy would throw the first half of the link away.' That is exactly this defect, recognised and closed between the linker's own two copies. The third copy, the zone table's, which the editor saves from, is never consulted.

**Fix.** Make one container per file: ZO instances come from a per-session cache keyed by path (the zone table's), so every writer shares the offsets; or have storeFile re-read the header offsets from disk before slicing and refuse when they differ from its own (the cheap, general guard). Then: after any external zone write, reload that slot in the zone table (and the open zone's editors), and stop Zone.store writing the script unconditionally. Guard: StaleContainerProbe as a suite - two instances, external write, stale store - asserting the file still reads back exactly; plus a suite over clearZone/link/rename on the open zone followed by OpenEditors.saveAll.

<a id="f001"></a>

## F001 - A zone whose area was itself forked can never be forked again: planFork refuses every area id >= 228, not just the table slot 228

**medium** | correctness | `src/ctrmap/AreaForker.java:111`

planFork: 'if (oldArea >= AD_GLOBAL_TABLE) throw ... which is not a real area' (111-112). Forked areas get ids 229..255 (forkArea newArea = ad.length; MAX_AREA_ID 255). The comment at 406-408 says the only id meant to be refused is 228 itself. So a zone cloned from a created/forked zone shares area 229+ and forkArea/forkAppendedAreas refuse it as 'not a real area'.

**Fix.** Refuse oldArea == AD_GLOBAL_TABLE only (and oldArea >= ad.length); add a suite case forking a zone whose area is 229.

<a id="f002"></a>

## F002 - forkAppendedAreas claims to refuse before it writes, but only the id budget is checked up front; a per-zone failure mid-loop leaves earlier zones' AreaData/NPC entries staged with no per-area table rows

**medium** | data-integrity | `src/ctrmap/AreaForker.java:299`

Javadoc 250-254 'IT REFUSES BEFORE IT WRITES'. Only the budget check (279) precedes the loop. Inside the loop, planFork (309) and the getWorkspaceFile null check (303) can throw at zone i>0 after zones 0..i-1 already did Files.write(adOut)+addPersist+registerPendingArea (313-315) and the NPC writes (316-326). The grown table is written only after the loop (337), so the staged area entries have no 44-byte rows in entry 228 - the condition the class javadoc says makes the engine read past the end.

**Fix.** Plan every zone first (pure planFork over all of them), then write; or stage into a temp dir and move only after all plans succeed.

<a id="f003"></a>

## F003 - forkArea stages five files in sequence (area, NPC fillers, NPC, table, zone, master row) with no rollback; a throw part-way leaves a half-fork persisted for the next pack

**medium** | data-integrity | `src/ctrmap/AreaForker.java:396`

Files.write+addPersist at 396-397, 409-414, 416-417, 420-421, 423-424, then repointMasterArea 425 which can throw 'Could not extract the master zone-header table' (446) or 'row out of range' (451) after the ZO header was already rewritten (423) - ZO header and master row then disagree.

**Fix.** Build every byte array first (planFork already does), then write all; on any throw delete the files written in this call and drop their persist entries.

<a id="f004"></a>

## F004 - The main window's shared file picker ignores Cancel: picked() discards showOpenDialog's result and returns getSelectedFile() anyway

**medium** | api-trap | `src/ctrmap/CtrmapMainframe.java:1349`

picked(): 'jfc.showOpenDialog(frame); File f = jfc.getSelectedFile(); if (f != null) {...} return f;' (1348-1355). Swing does not clear the selection on Cancel, so click-a-file-then-Cancel returns that file. Four actions trust it: openGrAction (1361, replaces the loaded map), openMapMatrixAction (1383), openZoneAction (1413), objToCollisionsAction (1483, REPLACES the open collision meshes at 1505-1506).

**Fix.** if (jfc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return null; - and make the helper the only way these actions get a file.

<a id="f007"></a>

## F007 - The main window is an all-static god class: 3,281 lines, 42 mutable static fields (20 public), 87 static methods, no instance state - and its 18 public panel statics exist for the test battery, not the program

**medium** | architecture | `src/ctrmap/CtrmapMainframe.java:95`

Measured with a brace-depth scanner (control: frame/game must be counted): 42 mutable static fields, 3 static finals, 87 static methods. Bytecode scan of all 714 production classes (control: CtrmapMainframe's own getstatic of Workspace.GAMEDIR_PATH is found): ZERO production classes read a CtrmapMainframe field. The battery reads them heavily (66 refs to CtrmapMainframe.mTileMapPanel, 24 mZonePnl, 20 mNPCEditForm in src/ctrmap/tests). Five production classes call UP into window static methods: AreaLightingDialog (refreshSceneFog x2), MaisonEditDialog (showGameData), ZoneLoadingPanel (showWorldEditor, refreshSceneFog), WorkspaceSettings (onWorkspaceOpened, unloadEditors), SetupWizard (onWorkspaceOpened). A second window, or a suite holding two editors, is impossible by construction.

**Fix.** Make the window an instance that the suites build; hand the five callers a narrow interface for the five upward calls; make the panel fields private instance fields once the battery builds its own window.

<a id="f009"></a>

## F009 - ORAS zone indices for the Battle Maison (517) and Battle Institute (448) are hard-coded in the window, outside ctrmap.gamedef, despite the seam rule

**medium** | architecture | `src/ctrmap/CtrmapMainframe.java:2990`

2989-2992: 'private static final int MAISON_LOBBY_ZONE = 517;' 'INSTITUTE_ZONE = 448;' used at 3069 to pick the clone source. ARCHITECTURE.md: 'No RomFS path, GameText entry index, or other game-detected constant may live anywhere outside ctrmap.gamedef.' SourceSeamTest scans string literals, so integer constants pass. The Feature.MAISON gate (3007) stops other games today, but the numbers are ORAS facts living in a game-agnostic class.

**Fix.** Move both to the profile (e.g. profile().facilityLobbyZone(kind)) answering -1 when unmeasured.

<a id="f013"></a>

## F013 - 'Read the master zone-header table' is written out at least six times, with three different answers to where it is and what to do when it is the wrong size

**medium** | duplication | `src/ctrmap/GeometryForker.java:757`

Copies: AreaForker.areaSharers 142-154 (index zoneCount, falls back to the GARC copy, then answers 1), GeometryForker.matrixSharers 757-772 (same fallback), repairSharedResources 373-383 (index zoneCount, refuses), repairSharedAppendedZones 528-539 (index zoneCount, refuses), looksUntouched 642-643 (index zoneCount, NO null or length check - masterFile null is an NPE that the caller's catch turns into refusedBecause = "null"), and repointMasterRow 861-862 / AreaForker.repointMasterArea 443-444 (index ZoneTables.masterIndex(zo)). Plus forkGeometry 709-710 recomputes zoneCount as zo.length - zoneTrailing() instead of ZoneTables.zoneCount. Sharer counting over the master is written four times (AreaForker.zonesUsingArea, matrixSharers, and two users-maps in the repair passes).

**Fix.** One MasterTable class: load (with one fallback policy), rowOf, idOf(resource), sharers(resource, zone), write. Every caller goes through it.

<a id="f014"></a>

## F014 - Pack-time compression overrides travel between the forkers and the pack through private static mutable maps - process-global state outside every global-state count

**medium** | architecture | `src/ctrmap/GeometryForker.java:61`

GeometryForker 61-62 pendingFieldOverrides/pendingMatrixOverrides, AreaForker 65-66 pendingAreaOverrides/pendingNpcRegOverrides, each with register\*/consume\* statics (GeometryForker 830-888, AreaForker 458-484). ARCHITECTURE.md's global-state table counts PUBLIC static mutable fields only (36), so these are invisible to it. A fork that throws after registering leaves overrides that the NEXT unrelated pack consumes.

**Fix.** Make the pending overrides part of the staged state the session owns (e.g. WorkspaceSession.pendingCompression(ArchiveType)), cleared with the staged files.

<a id="f015"></a>

## F015 - Placing a working building clones only the ground floor: a multi-floor interior's stairs still lead to the RETAIL upstairs, which is shared with the original house

**medium** | unfinished | `src/ctrmap/InteriorWirer.java:25`

Class javadoc 25-27: 'Multi-warp interiors (2-floor houses) get their DOOR rewired; any further floors still link to the retail upstairs - callers should surface that'. cloneAndWire (74-97) clones one donor zone and rewires only exitWarps; the return value is the count left 'pointing at retail'. Editing the upstairs of a placed house edits the retail house's upstairs too.

**Fix.** Clone the connected interior set (follow same-area warps transitively), rewire the internal stairs between clones, and refuse when there are not enough free base slots.

<a id="f017"></a>

## F017 - Resize map picks the new cells' ground as the BIGGEST mesh - the rule the blank-canvas picker abandoned because the biggest mesh is usually a wall, fence or the sea

**medium** | correctness | `src/ctrmap/MapResizer.java:236`

MapResizer.resize 236-243: picks gm = the posOk mesh with most triangles and hands it to RegionFactory.blank(template, gm). CtrmapMainframe.blankCanvasAction 2891-2897 explains why that was replaced there: 'on 312 of the first 400 retail regions the biggest mesh is a wall, a fence or the sea', and uses PaintedMaterials.groundFirstMeshOrder(probe) instead. The resizer never got the fix, so grown cells are floored with a wall/sea material in most maps. ADDENDUM - THE ABANDONED RULE ALSO SURVIVES IN THE BLANK-CANVAS DIALOG'S OWN TEXT. CtrmapMainframe.java:2891-2896 orders the materials with PaintedMaterials.groundFirstMeshOrder: this map's ground first, then the rest biggest first. Its comment says why: 'on 312 of the first 400 retail regions the biggest mesh is a wall, a fence or the sea'. Fifty lines later the same dialog tells the user the opposite (:2939-2941): 'These are THIS map's own materials, largest first - the top entry is almost always the ground.' So the size rule this code replaced lives on in two places, MapResizer's choice of ground (this finding) and the words that explain the picker. Nothing ties a dialog's description of an ordering to the ordering.

**Fix.** Use PaintedMaterials.groundFirstMeshOrder(tm)[0] here too, and have both call one 'ground mesh of this region' function.

<a id="f018"></a>

## F018 - The resize action says growing a map makes the zone's geometry private first; it does not - the old cells keep whatever sharing they had

**medium** | doc-drift | `src/ctrmap/CtrmapMainframe.java:3117`

CtrmapMainframe 3117-3118: '//growing a map forks the zone's geometry first'; refusal text 3121-3122: '...after the zone's geometry has been made private'. resizeMapAction calls MapResizer.resize directly (3149); resize() never calls GeometryForker.ensurePrivate, and its own javadoc (MapResizer 21-23) says 'the old grid keeps its cells (top-left, with whatever sharing they had)'. A user who resizes a cloned town and then paints an old cell edits the source town too.

**Fix.** Call GeometryForker.ensurePrivate(zoneIndex) (and pack) before resizing, or correct the text and warn when old cells are shared.

<a id="f019"></a>

## F019 - MapResizer is handed a session and then writes the master table and compression overrides through the global one

**medium** | architecture | `src/ctrmap/MapResizer.java:270`

resize(WorkspaceSession ws, ...) reads/writes through ws (175-269), then calls GeometryForker.repointMasterRow(zo, zoneIndex, newMatrix) (270), which resolves Workspace.getWorkspaceFile/Workspace.addPersist (GeometryForker 862-873), and GeometryForker.registerPendingField/Matrix (258, 263) - process statics. A suite handing its own session gets the region/matrix/ZO writes in its session and the master-row write in whatever session is global, which is the half-migrated seam ARCHITECTURE.md says does not count as migrated. ADDENDUM - A SECOND INSTANCE OF THE SAME SEAM MIX. ZoneManager.renameZone(WorkspaceSession ws, ...) is handed its session, reads the ZoneData archive from it (:91), and then bounds the zone index with ZoneCloner.getMasterIndex() (:95), which reads the GLOBAL Workspace. A caller that hands a session other than the installed one gets its range check, and so its idea of which entry is the master table, from a different game.

**Fix.** Give repointMasterRow and the pending-override registry a WorkspaceSession parameter and pass ws.

<a id="f021"></a>

## F021 - Deploy only ever ADDS to the mod folder: an archive that was deployed once and is now back to retail stays in the mod and keeps loading in game

**medium** | data-integrity | `src/ctrmap/ModDeployer.java:179`

deploy(): for each MODDABLE archive, unchanged-vs-pristine -> 'r.unchanged++; continue;' (179-181); changed -> copyFile into modRoot/romfs (184). Nothing removes modRoot/romfs/&lt;rel> for an archive that is now unchanged. Restore-from-vault, Remove added zones, or undoing an edit by hand all return an archive to retail; the next deploy reports it 'unchanged, skipped' while the previously deployed modified copy is still in the load path. The window then says 'Nothing changed to deploy (no edits since the last deploy)' (CtrmapMainframe 2002) - but the comparison is against pristine, not against the last deploy.

**Fix.** For every MODDABLE/MODDABLE_IN_PLACE archive that compares equal to pristine, delete modRoot/romfs/&lt;rel> if present and report it as 'removed from mod'.

<a id="f024"></a>

## F024 - When the open-time area/text repair refuses half-way, the user is told nothing unless a MAP was also shared, and whatever the half-run staged is left for some later pack

**medium** | silent-failure | `src/ctrmap/PaddingZoneRepair.java:85`

repairOnOpen: rest = repairSharedResources(base) (75); its refusedBecause is copied into r (82-84); the only dialog is guarded by 'if (!r.shared.isEmpty())' (89) - r.shared is filled only by the MAP pass. A workspace whose map is already private but whose AREA/TEXT pass threw part-way (see F011: ZO headers written, master not) gets no message, no pack, and 'then.run()' (97) opens the editor over the half-repaired state. The staged files and compression overrides are packed by whatever pack runs next.

**Fix.** Report any refusal from either pass; on refusal, roll back what the pass staged (F011) before continuing.

<a id="f026"></a>

## F026 - The tileset editor (Extras) writes files it cannot read back and reports nothing: name length in chars vs bytes, per-tile write errors swallowed after the count is written, and every failure is log-only

**medium** | data-integrity | `src/ctrmap/TileDBWriter.java:670`

Tile.write: 'dos.writeByte(name.length()); dos.write(name.getBytes("UTF-8"));' (670-671) - any non-ASCII name writes more bytes than the length byte says, and names over 255 wrap. IOException per tile is caught and logged (676-678) after savefileActionPerformed already wrote 'dos.writeInt(tiles.size())' (551), so a failed tile leaves a count with fewer records. The save's own IOException is log-only (556-558). Reading: openfileActionPerformed ignores showOpenDialog's result (327-328), clears the tiles BEFORE parsing (332) so a magic mismatch (337-341) or IOException (357-359) loses the open set, ignores dis.read(strbuf)'s count (346), leaks the stream on exception (334), and prints 'added i' to stdout (353). New/Save ignore showSaveDialog's result too (487-488, 543-544). btnPrevTile's parseInt is uncaught (505) while btnNext catches it (518-522).

**Fix.** Write the UTF-8 byte length; let write() throw; build the tile list in a local and swap on success; check chooser results; try-with-resources; Ui.error on failure.

<a id="f029"></a>

## F029 - The integrity check and the atmosphere picker still hard-code ORAS's zone-table tail ('length - 2'), the arithmetic the rest of the tree was migrated off because X/Y's master table is at length - 1

**medium** | correctness | `src/ctrmap/WorkspaceIntegrity.java:90`

WorkspaceIntegrity.check(ws, ...) is HANDED a session yet reads the master at zo.getDecompressedEntry(zo.length - 2) (90), bounds rows by zo.length - 2 (92) and sets zoneCount = zo.length - 2 (186). GfEnvPicker.java:86 'int zoneCount = zoG.length - 2;'. XyProfile.java:79 documents that XY reads the master at length - 1. MapResizer 181-183 records the same literal being removed as 'ORAS's tail spelled as a literal in a class that is not allowed to know it'. Also hard-coded in four offline tools (GroundMaterialAudit 70, TerrainDonorHarvester 58, TerrainHarvester 424-425, BuildingHarvester 77, the last two with Math.min(536, ...)). On an XY workspace the integrity check reads the wrong entry as the master table.

**Fix.** Use ZoneTables.zoneCount(zo, ws.profile()) / masterIndex; extend the seam guard to refuse 'length - &lt;int>' on a ZoneData GARC outside ZoneTables.

<a id="f031"></a>

## F031 - The cross-archive integrity check never compares a zone's ZO header with its master-table row - the one divergence the fork/repair paths can actually produce

**medium** | unfinished | `src/ctrmap/WorkspaceIntegrity.java:68`

check() validates area/matrix ids in the MASTER table (185-219), NPC registry reach (104-110), stranded registries (140-179) and matrix->region refs (221-263). Nothing reads ZoneData entry headers and compares them to master rows, yet AreaForker/GeometryForker/repair passes write the two separately and non-atomically (F002, F003, F011), and GeometryForker's javadoc says the game reads only the master copy.

**Fix.** Add check 5: for each zone, header u16@2/@4/@text == master row; report the first mismatches.

<a id="f034"></a>

## F034 - The list of which extracted files are edits is rewritten delete-then-write with a log-only failure, and losing it turns the next window close into deletion of every unpacked edit

**medium** | data-integrity | `src/ctrmap/WorkspaceSession.java:359`

savePersist: 'persistConfig.delete(); persistConfig.createNewFile(); ... new FileWriter(persistConfig)' (359-371); IOException only logged (368-370). The close handler (CtrmapMainframe 794-796) runs cleanUnchanged() - which deletes every extracted file NOT in persistPaths (696-698, 713-718) - and then saveWorkspace()->savePersist(). If the write fails or the process dies between delete and write, the next launch reads an empty list (readPersist 311-345) and the next close deletes all of the user's unpacked edits as 'unchanged'.

**Fix.** Write to a temp file and atomically move it over; surface the failure through Ui; refuse cleanUnchanged when the persist file could not be read.

<a id="f035"></a>

## F035 - A pack rewrites the game's archives one after another with no cross-archive transaction: a failure part-way leaves the GAME FOLDER with some archives grown and their partners not

**medium** | data-integrity | `src/ctrmap/WorkspaceSession.java:977`

packArchives (969-1054) packs FIELD_DATA, AREA_DATA, ZONE_DATA, MAP_MATRIX, BUILDING_MODELS, NPC_REGISTRIES, trainers, Maison, text in sequence, each straight into the game directory; it 'Throws on the first archive that cannot be rewritten' (956-957). An area fork appends to AREA_DATA (980) and to NPC_REGISTRIES (991): if anything between them throws (an emulator holding ZoneData open is the cause the javadoc names), the game folder has an area with no registry at its id - the exact 'zone cannot load' state WorkspaceIntegrity lists as having bitten repeatedly (25-26). Workspace.packWorkspace's dialog then says 'The game archives may be partly written' (CtrmapMainframe-side, Workspace 550-551).

**Fix.** Write every repacked archive to a temp file beside its target first, and only after all succeed move them into place (each move is atomic); on failure delete the temps and leave the game untouched.

<a id="f036"></a>

## F036 - Every pack rewrites and recompresses the six biggest archives even when nothing in them was edited, which is also why Deploy has to decompress and diff every entry

**medium** | efficiency | `src/ctrmap/WorkspaceSession.java:988`

FIELD_DATA, AREA_DATA, ZONE_DATA, MAP_MATRIX, BUILDING_MODELS and NPC_REGISTRIES are packed unconditionally (977-991); the trainer, Maison and text archives are gated on hasPersistedFiles (996-1020), and the comment at 1012 says why: 'packDirectory rewrites the GARC in the game directory even with zero persisted files'. ModDeployer 22-25 then documents the consequence: 'Pack Workspace recompresses every archive ... so the container bytes change even when nothing was edited', forcing garcContentsEqual to decompress every entry of both copies of every moddable archive on each deploy (ModDeployer 343-347).

**Fix.** Gate every archive on hasPersistedFiles or a pending override, as the small ones already are; then Deploy could compare file bytes first.

<a id="f038"></a>

## F038 - Each archive's facts are kept in six parallel lists that must agree by hand

**medium** | duplication | `src/ctrmap/WorkspaceSession.java:112`

SUBDIR map (112-132), WORKSPACE_SUBDIRS (142-148), REQUIRED (89-98), SNIFF_OFF (105-109), RELOAD_ORDER (157-164), the Maison list inlined again in packArchives (1004-1005), plus ModDeployer.MODDABLE / MODDABLE_IN_PLACE (ModDeployer 38-79). The WORKSPACE_SUBDIRS javadoc (134-141) records the cost already paid: 'when the two lists drifted apart, the directories only the second one knew about were never created and the editors that wrote into them failed silently' - and SUBDIR and WORKSPACE_SUBDIRS are still two lists of the same names.

**Fix.** Carry dir name, required, sniff, moddable and pack-gating on ArchiveType (or one table keyed by it) and derive every list.

<a id="f040"></a>

## F040 - An append stages the whole geometry fork before checking the area-id budget, so running out of area ids leaves megabytes of orphan region/matrix copies (and their compression overrides) for the next pack

**medium** | data-integrity | `src/ctrmap/ZoneAppender.java:277`

appendZones calls GeometryForker.forkAppendedZones (277), which writes+persists every region copy and the new matrices and registers pending field/matrix overrides (GeometryForker 238-261), BEFORE AreaForker.forkAppendedAreas (294), whose budget check (AreaForker 279-284) can throw. The comment at 286-289 promises it 'checks it for the WHOLE batch before it writes anything' - true inside forkAppendedAreas, false for the append. TextForker (302) can also throw after both. Nothing removes the staged files; the next pack appends them to FieldData/MapMatrix as entries no zone uses.

**Fix.** Check every budget (area ids, STORYTEXT presence - already done at 192) before the first write; or stage into a temp folder and move only when every fork planned successfully.

<a id="f043"></a>

## F043 - Removing added zones never gives back the area ids their appends consumed, so a few add/remove cycles exhaust the 8-bit area budget and zone appending is refused for good

**medium** | architecture | `src/ctrmap/ZoneRemover.java:37`

removeFromFile rewrites ZoneData only (37-77); the javadoc (21-26) says FieldData/MapMatrix tails are left as 'harmless' unreferenced data and says nothing about AreaData, NPCRegistries or STORYTEXT, which every append also grows (ZoneAppender 294, 302; MADE_PRIVATE = MAP, AREA, TEXT). AreaForker takes new ids from ad.length (AreaForker 278, 359) and caps them at MAX_AREA_ID = 255 with retail using 229 (AreaForker 250-252, 59). Each append consumes a multiple of 4 ids (spares included), so roughly six 4-zone add/remove cycles exhaust the 27 spare ids, after which forkAppendedAreas refuses every append ('only N are left', AreaForker 279-284) on a workspace that has no added zones at all.

**Fix.** Truncate AreaData/NPCRegistries/STORYTEXT tails that only removed zones referenced (they are the newest entries), or reuse unreferenced tail ids in AreaForker.

<a id="f044"></a>

## F044 - Map > OBJ to collisions loses the first half of every quad: it writes a degenerate triangle (v0, v1, v1) - and the parser crashes outright on OBJ files that real tools write

**medium** | correctness | `src/ctrmap/formats/WavefrontOBJ.java:51`

Quad branch: area test on (v0,v1,v2) (50) but 'meshes.get(meshIndex).addTri(v0, v1, v1);' (51); the second half 'addTri(v0, v3, v2)' (54) is right. A Blender export with quads therefore imports half its walkable floor as zero-area triangles - holes the player falls through. Also: a face before any 'o'/'g' line hits meshes.get(0) on an empty list (IndexOutOfBounds, 42/51) which the IOException catch (60) does not cover, so the menu action dies with a stack trace nobody sees; negative (relative) indices and n-gons over 4 are not handled (silently dropped); the reader is never closed (18); the class is self-described 'INCOMPLETE' (12). Reached from CtrmapMainframe.objToCollisionsAction (1498), which then REPLACES the open collision meshes.

**Fix.** addTri(v0, v1, v2); create a default mesh when none was declared; triangulate n-gons as a fan; support relative indices; try-with-resources; throw a named error on malformed input.

<a id="f045"></a>

## F045 - The shop patcher writes 430 bytes into the executable wherever an anchor string ends, with no check that this is the build it was measured on - its two sibling patchers both refuse first

**medium** | data-integrity | `src/ctrmap/formats/codepatch/ShopData.java:99`

write() (99-123): locate() finds '\0rom:/DllStartMenu.cro\0' (or the 'rom2:' variant) anywhere past 0x400000 (70-78) and overwrites COUNTS-many u16s from there. No offset cross-check, no stock-word check, no range check on the existing values. ItemIconTable.verify (120-155) checks the anchor offset against the measured 0x47C644, a neighbouring table's stock words, and every value's range, and says why: 'a patch applied to the wrong executable does not fail, it produces a build that boots and is wrong somewhere else' (116-118); ZoneLimitPatch checks five stock words (per ItemIconTable 116-117). A different build (e.g. Alpha Sapphire, a revision, or an already-relocated code.bin) gets 430 bytes written over whatever follows the string.

**Fix.** Add verify(code): measured offset 0x47AA3E, the 24 recorded retail lists (or a fingerprint of the neighbour), and refuse otherwise.

<a id="f048"></a>

## F048 - Every container save truncates the workspace file in place before writing it, and moves the in-memory offsets before knowing the write worked: one failed write destroys every subfile of that container

**medium** | data-integrity | `src/ctrmap/formats/containers/AbstractGamefreakContainer.java:317`

storeFile: offsets[i] += change for i > num (314-316) BEFORE 'new FileOutputStream(f)' (317), which truncates the extracted container; the rewrite then streams header, the bytes before, the new subfile and the bytes after. An IOException part-way (the javadoc names the cause: 'an emulator or a virus scanner holding the archive open', 273-275; or a full disk) leaves a truncated file holding none of the untouched subfiles (zone header, model, collision), and this object's offsets no longer describe the file on disk or the original. The stream is not closed on that path either (no try-with-resources). getWorkspaceFile already uses the safe shape (temp + move, WorkspaceSession 511-532) - this, the hottest write path in the editor, does not.

**Fix.** Assemble the new container in a byte[]; write to f + '.part'; move over f; only then commit the new offsets and call files.edited.

<a id="f050"></a>

## F050 - The raw archive browser cannot open any container whose magic is not AD/BM/GR/MM/ZO: the fallback class verifies its header before it has stored it

**medium** | correctness | `src/ctrmap/formats/containers/DefaultGamefreakContainer.java:12`

DefaultGamefreakContainer(File f, short magic, ...) { super(f, files); this.magic = magic; } (11-14). super() -> open() -> verify() (AbstractGamefreakContainer 56-58, 88-97, 141-152) calls the overridden getHeader(), which returns this.magic - still 0 - so every real file fails 'its first two bytes are 0x.... and this container is 0x0' and open() throws IllegalStateException. Reached from ContainerIdentifier.makeAGFC's default branch (ContainerIdentifier 39-40), used by Builder.java:74 and :472. javac -Xlint reports 77 [this-escape] warnings in production code - the same 'constructor calls an overridable method' shape, of which this one is a confirmed failure.

**Fix.** Pass the expected magic into the base constructor (or make getHeader() a constructor argument for every subclass); audit the other this-escape sites.

<a id="f052"></a>

## F052 - Two fully decoded formats ship with no way to reach them: the X/Y wardrobe (DressUpIndex/DressUpArchive, 799 lines) and PartyParam's script-selector names

**medium** | unfinished | `src/ctrmap/formats/dressup/DressUpArchive.java:28`

grep: no production class outside their own files references ctrmap.formats.dressup or PartyParam; only DressUpIndexTest/PartyParamTest do. OUTSTANDING.md 'Known limitations' records both as 'MEASURED, NOT SHIPPED' ('the only way to use PartyParam's names is to read the Java source'), and DuplicateWorkTest's UNREACHED table carries them so they are not flagged as dead. The dress-up decoder targets X/Y, a game the editor does not open (XyProfile gates). ADDENDUM - ITS DOCUMENTATION DESCRIBES A GARC DEFECT THAT HAS BEEN FIXED. DressUpArchive's javadoc still explains its part-count logic by a sniff that 'misses' 46 texture subfiles and hands them back compressed (DressUpArchive.java:20-26). docs/customization-assets.md:387-402 calls the 64:1 ratio cap 'A GARC reading defect found on the way' with 697 entries affected. GARC.sniffLZ11 has since dropped the cap; its javadoc records why the cap and a second guess were removed (GARC.java:197-233), and DressUpIndexTest asserts zero late decompressions (:89-108, :427). The two descriptions of the wardrobe archive now explain behaviour the reader no longer has. Since nothing reaches the format (this finding), nobody re-reads them.

**Fix.** Decide per OUTSTANDING: surface PartyParam names in the script/trainer editors (cheap - the data is decoded), and park dress-up until X/Y opens; meanwhile they are compiled into every release.

<a id="f054"></a>

## F054 - The GARC reader checks neither magic nor version and hard-codes version-4 padding and header layout, so any non-GARC file or any GARC v6 (the Sun/Moon/USUM revision ARCHITECTURE.md plans for) is parsed into invented entries and would be written back corrupt

**medium** | correctness | `src/ctrmap/formats/garc/GARC.java:85`

parse(): garcMagic, endian, version, sectionCount, decompressedLength, compressedLength are read and discarded (85-92); 'padding = 4; //version 4 has always nearest pad to 4, ver 6 can specify more' (93); then seek(garcLength) with bytes 4-7 of whatever the file is (95). writeRepacked copies 'first 14 bytes of header' and rewrites fixed v4 offsets (523-531, 640-642) and forces appended entries' flags from entry 0 (571); its own comment says 'We are only implementing simple GARCs without directories' (546). A bad magic only fails if the arithmetic happens to throw; ctrmap.util.Bytes already has checkMagic. ARCHITECTURE.md's Gen 7 plan says 'Gen 7 kept GARC (a newer revision)' and 'Verify the universal layer holds (GARC version...)' - the reader would not refuse it, it would misread it. The class header still says 'ported mostly from the original Ohana3DS and pk3DS, but will be soon rewritten' (19-21). Also dead C#-ism: String.format("folder_{0:D5}/", i) builds a literal and is never used (112-116).

**Fix.** Check 'CRAG' and version in parse; read padding from the v6 header or refuse v6 until it is measured; refuse multi-flag entries rather than flattening them.

<a id="f055"></a>

## F055 - Opening any archive reads every entry's full bytes just to sniff 64 of them, and a pack reopens the archive once per unchanged entry - multiplied by the reload cascade after every pack

**medium** | efficiency | `src/ctrmap/formats/garc/GARC.java:128`

parse(): for each entry 'byte[] buffer = new byte[length]; in.readFully(buffer);' then sniffLZ11 looks at the first SNIFF_PREFIX_BYTES = 64 (128-131, 187). So constructing a GARC reads the whole archive; the javadoc itself measures a/0/0/8 at ~0.7 s (231-233). writeRepacked opens 'new FileInputStream(file)' and skipFully(offset) for EVERY unchanged entry (618-628) - ~860 opens for FieldData - and does a linear indexOfIntArray per entry (584, 611). WorkspaceSession.packArchives then triggers ~96 GARC constructions (F037), each a full read of its archive, plus a full re-parse in replaceWith (665).

**Fix.** Read only min(length, 64) bytes per entry in parse; use one RandomAccessFile in writeRepacked; a HashSet for changed indices; reload once.

<a id="f057"></a>

## F057 - The LZ11 decoder every reader uses never fails: on a truncated or corrupt stream it pads to the declared size and returns garbage as data - the class's own strict check documents this and is used only for sniffing

**medium** | silent-failure | `src/ctrmap/formats/garc/LZ11.java:14`

decompress(): a non-0x11 type only prints 'Not a LZ11 compressed file' to stderr and carries on (19-21); instream.read() returning -1 at end of input becomes flags 0xFF / byte values -1 (34, 40, 78) and the loop runs until currentOutSize reaches the header's size (32); back-references before the start of output read the zero-filled window (68-74). prefixDecodes' javadoc (114-121) says so outright: 'decompress cannot: at the end of its input it reads -1 ... and pads its way to the declared length - so it decodes anything'. GARC.getDecompressedEntry (GARC 765-767) returns this for every compressed entry, so a damaged entry in the game's archives opens in the editor as plausible-length garbage and can be saved back. Also: sizes over 16 MB (the extended 32-bit header) are not handled by either direction (23-24, 226-228).

**Fix.** Make decompress strict (throw on exhausted input, disp > written, wrong type) using the same token walk prefixDecodes already has; handle or refuse the extended-size header.

<a id="f058"></a>

## F058 - A collision mesh that fails its magic, length or terminator check - or throws while parsing - is opened anyway, and its Save writes whatever part was read over the region's real collision

**medium** | data-integrity | `src/ctrmap/formats/gfcollision/GRCollisionFile.java:49`

Constructor: 'if (dis.read4Bytes() != COLL_MAGIC) System.err.println("Coll magic mismatch");' (49-51); 'Coll length exceeds EOF, will be adjusted on write' (53-55); 'Term missing/misplaced/magic error.' (73-75); IOException -> e.printStackTrace() (80-82). An EOF mid-mesh leaves the 16 bucket lists partly filled; write() (150-163) then rebuilds subfile 2 from those triangles via GfColl.build and storeFile. If the throw comes before 'bounds' is assigned, 83 NPEs instead. The shipped build has no console, so the user sees a collision that opened and saves normally. This is the same 'print and carry on' shape the camera, container and GARC readers were fixed out of (OUTSTANDING items A and J).

**Fix.** Throw an IllegalStateException naming the region on magic/length/terminator/IO failure, as CameraDataFile does; the collision panel refuses to open that region.

<a id="f061"></a>

## F061 - Collision has two parsers that disagree: the editor loads through the legacy reader (prints and continues, dedupes every repeat) and saves through the spec-exact one (refuses bad input, keeps genuine repeats)

**medium** | duplication | `src/ctrmap/formats/gfcollision/GfColl.java:57`

GfColl(byte[]) refuses a non-coll subfile (58-60) and reconstructs source multiplicity - 'the source mesh itself may contain genuinely repeated triangles - a tri's source multiplicity is its max occurrence count across buckets' (72-106). GRCollisionFile's constructor parses the same bytes with its own reader, only prints on bad magic/terminator (F058) and dedupe() drops EVERY repeated triangle (114-138). GRCollisionFile.write() hands those deduped triangles to GfColl.build (150-163), so a region opened and saved through the collision editor loses retail's genuinely repeated triangles and is no longer byte-identical to the retail-exact rebuild. GfColl also identifies triangles by a 64-bit hash (triKey 389-395, used as the map key at 88-97), so two distinct triangles that collide are merged silently.

**Fix.** Load the editor's mesh from GfColl.uniqueTris and delete GRCollisionFile's parser; key on the 9 float bit patterns, not a hash of them.

<a id="f064"></a>

## F064 - The shared-area refusal sends users to 'Map > Fork map geometry', which forks the map, not the area - so doing what it says can never clear it

**medium** | ui | `src/ctrmap/formats/h3d/BchTexturePack.java:549`

BchTexturePack.assertNotShared: 'Give this zone its own area first (Map > Fork map geometry).' (547-549); PropEditForm.java:559 says the same. Map > Fork map geometry runs CtrmapMainframe.forkGeometryAction -> GeometryForker.ensurePrivate (2109-2156), which copies FieldData regions and the MapMatrix and leaves areadataID untouched. The only production caller of AreaForker.forkArea is AreaForkPrompt.java:90 (grep), reached from the fog &amp; lighting flow; there is no menu item that forks an area. A user refused a texture carry or prop-registry edit follows the instruction, forks the geometry (appending megabytes), retries, and is refused again with the same sentence.

**Fix.** Name the real door (or add 'Map > Give this zone its own area...' wired to AreaForker.forkIfShared) and offer it from the refusal itself.

<a id="f065"></a>

## F065 - The guard that stops texture carries from changing other zones' maps fails OPEN: any exception inside it answers 'nobody shares this area'

**medium** | data-integrity | `src/ctrmap/formats/h3d/BchTexturePack.java:411`

zonesUsingArea: the whole body is in 'catch (RuntimeException ex) { return null; //never block an edit because the guard itself failed }' (411-413), and null means 'not shared - proceed' to assertNotShared (544-551). An unreadable or misaligned master table, a short archive entry, an index past the end - each turns into permission to grow a shared area. The javadoc of the same method records what the 'nobody' answer cost: 'the answer that put 855 KB of textures into Mauville City's area' (357-358), and assertNotShared's javadoc: 'grew the area behind fifteen other zones' (534-537). It also returns null (proceed) for every game without AREA_FORK (373-376).

ADDENDUM: The battery pins the fail-open answer as intended: HandedGameTest.java:225-227 asserts that a game whose zone archive nothing opened 'yields no sharers rather than a crash' (zonesUsingArea -> null), the same null assertNotShared and carryToArea read as 'private, go ahead'.

**Fix.** Fail closed: on an exception, refuse with the reason ('could not tell whether area N is shared: ...').

<a id="f066"></a>

## F066 - The GameFreak patricia name-tree builder exists three times, and BCH header/relocation parsing at least four times

**medium** | duplication | `src/ctrmap/formats/h3d/BchTexturePack.java:723`

Patricia builders: BchTexturePack.buildTree/traverse/getBit (694-760), BchModelAppender.buildTree/traverse/getBit (BchModelAppender 31-63, same algorithm in one-line style) and WorldAnim.buildTree/insert/bitOf (WorldAnim 330-421, a different but equivalent formulation); each is separately claimed 'byte-exact' against retail. BCH header + relocation readers: BCHFile (relocates in place, BCHFile 31-172), BchMapModel.parseHeader/parseReloc (103-183), WorldAnim constructor (47-65), BchTexturePack.parse/isTexturePack (88-167), BchModelAppender.decode (67-80). Small helpers repeat too: a static align(int,int) in several classes and C-string readers (BchModelAppender.str, BchTexturePack.readCString, WorldAnim.str, BchMapModel name loops 600-619).

**Fix.** One ctrmap.formats.h3d.Patricia (build/lookup/serialize) and one BchSections (header + relocation map) that every BCH reader/writer uses; delete the copies.

<a id="f067"></a>

## F067 - Every building cut opens the pristine FieldData archive from scratch - which reads every entry of it - and leaves a scratch file behind

**medium** | efficiency | `src/ctrmap/formats/h3d/BuildingCatalog.java:243`

pristineRegion: 'byte[] bytes = new GARC(garcFile).getDecompressedEntry(region);' (243). Constructing a GARC reads all entries' bytes to sniff compression (GARC.parse 128-131, F055), so each extract() of one donor region costs a full read of FieldData (~860 regions). The palette previews and stamps from a 3,583-entry catalog (README), and extract is called per placement/preview. Each call also writes 'bcat_region_&lt;n>' into scratch (247-250) and never deletes it. Catalog load failures are stderr-only and a single malformed number aborts the rest of that TSV silently (134-136).

**Fix.** Cache one GARC handle for the snapshot per session (it never changes); read the one entry; delete or reuse the scratch file; report catalog load errors.

<a id="f069"></a>

## F069 - Reading a model's name loops forever (until OutOfMemoryError) on any BCH whose name pointer runs off the end - and it is called on every collision open

**medium** | correctness | `src/ctrmap/formats/h3d/H3DModelNameGet.java:38`

'while ((read = dis.read()) != 0) { sb.append((char)read); }' (38-40): at end of stream read() returns -1, which is not 0, so the loop appends U+FFFF forever. The offsets it follows are unvalidated (skip by contentHeaderOffset - 16, pointer table, model0 + 0x84, string offset - 22-35; skip() results ignored), and the class 'assumes that the file contains at least 1 model' (15). CollEditPanel.java:210 calls it with f.getFile(1) for the region being opened; a region whose subfile 1 is empty, truncated or not a model BCH hangs the editor. It is also a fifth BCH reader (BchMapModel.getModelName does the same job with bounds checks, 596-605).

**Fix.** Delete it and use new BchMapModel(bytes).getModelName() (or a bounds-checked reader that stops at -1 and refuses a non-model BCH).

<a id="f072"></a>

## F072 - Blank map canvas reports success even when regions could not be blanked or were left half-written

**medium** | silent-failure | `src/ctrmap/CtrmapMainframe.java:2964`

blankCanvasAction calls 'RegionFactory.blankRegionFiles(gr, groundMesh);' as a bare statement per region (2964) and then always says 'Zone N now has a private blank canvas (region(s) ...)' (2974-2979). blankRegionFiles answers false both when the model cannot be rebuilt (RegionFactory 71-83) and when a write fails part-way - after subfile 1 may already be written and 2/0/3 not (93-111) - and its own comment says 'half a blanked region is not blanked, and the caller says the zone was kept' (91-92). This caller says nothing of the kind.

**Fix.** Collect the false answers and report which regions kept their old content (or are half-blanked), as GeometryForker.repairSharedAppendedZones does with r.kept.

<a id="f075"></a>

## F075 - No geometry writer updates a mesh's stored bounds: move, duplicate, append, OBJ import and resize change vertex positions while each mesh's bounding data stays the retail value (unverified whether the game culls by it)

**medium** | correctness | `src/ctrmap/formats/h3d/model/H3DModel.java:173`

The reader records objectEntry.boundingBoxOffset (H3DModel 173) and BchModelVerifier checks that each mesh's metadata block sits at header+0x34 (BchModelVerifier 118-122), so per-mesh bounds/metadata exist in the format. grep: nothing outside H3DModel reads boundingBoxOffset, and no writer (BchMapModel.setVertexPositions/appendGeometry/setMeshGeometry/translate, GeoBoxOps, MapModelObjImporter, RegionFactory, MapPrefab.stamp\*) touches mesh metadata - BchMapModel.rebuildRawData explicitly leaves contents verbatim (722-723). If the engine frustum-culls by those bounds, geometry moved or grown outside a mesh's original box vanishes at some camera angles. The project's own Resize and Import-model dialogs already say in-game behaviour is unverified.

**Fix.** Recompute and write each edited mesh's bounds (or confirm by RE that the field is unused and say so in the writer).

<a id="f080"></a>

## F080 - MapMatrix parse swallows every exception into a half-built object; the blank-matrix constructor builds a camera boundary and drops it; resizeMatrix throws on any shrink

**medium** | silent-failure | `src/ctrmap/formats/mapmatrix/MapMatrix.java:97`

Constructor (57-100) wraps the whole parse in catch (Exception) -> Logger SEVERE: a short/corrupt subfile leaves ids/zones/LOD partially filled (or null if getFile(0) threw) and cambounds truncated, and the caller (CtrmapMainframe:2223, TileMapPanel) gets an object that write() will happily serialize back - the truncated grid is then persisted. MatrixCameraBoundaries(dis) (20-30) does the same per entry. The 4-arg constructor (102-118) creates mcb, sets isRepeal/north/south/west/east, and never adds it to cambounds, so Builder.java:498's blank matrix ships with zero camera boundaries. ResizeableMatrix.resizeMatrix (349-368) starts its inner loop at j = l.size() and calls l.remove(j) whenever j >= newHeight, i.e. IndexOutOfBounds on every height shrink; grep: 0 callers in prod or tests (dead). removeRows/removeColumns have no bounds checks. Lines 120-138 are firstRegionId's javadoc orphaned above regionsOwnedBy's, leaving firstRegionId undocumented and regionsOwnedBy with two stacked docs.

**Fix.** Make parse failure throw (callers already catch), add mcb to cambounds or remove it, delete resizeMatrix, move the orphaned javadoc.

<a id="f081"></a>

## F081 - Editing any NPC registry entry rebuilds it from defaults and drops eight unknown fields; Add overwrites an existing UID-0 entry

**medium** | data-integrity | `src/ctrmap/humaninterface/NPCRegistryEditor.java:116`

saveEntry (110-154) builds e2 = new NPCRegistryEntry() and copies only uid/model/render/shadow/isItOb/isDummy/collW/collH/u1/u4/u8/u9/uC/u11; u5, uD, uE, u10, u12, u13, u16, u17 take constructor defaults, and the default sets u12 = 2 (NPCRegistry:305) on a field whose own comment says 'always 0' (NPCRegistry:256) while u11 ('1 or 2, always used') defaults to 0 - an apparent u11/u12 swap that every edited entry now ships. NPCRegistryEntry.equals (338-347) omits exactly those fields and there is no hashCode, so the loss is invisible to the change check. btnAddActionPerformed (377-384) does reg.entries.put(e2.uid, e2) with e2.uid == 0: retail registries carry UID-0 dummies (comments at NPCRegistry:240/246), so Add silently replaces the dummy and dict holds 0 twice; the add also never calls mapModel and hard-codes 31 instead of NPCRegistry.MAX_ENTRIES. NPCRegistry.store (203-220) writes entries in HashMap order (not file order) through a truncating FileOutputStream, and the constructor (52-63) logs IOException and continues, so a short read followed by any edit writes the truncated registry back.

**Fix.** Edit entries in place (copy-on-write from e, not from defaults), include every field in equals + add hashCode, allocate a free UID on Add, keep file order (LinkedHashMap), and make a failed parse refuse to store.

<a id="f082"></a>

## F082 - An item edit is split across two persistence models: the record is poked into the live game folder, its name/description go through the workspace

**medium** | architecture | `src/ctrmap/formats/pokedata/ItemTable.java:279`

ItemTable.writeRecord opens garcFile = WorkspaceSession.archiveFile(ITEM_DATA) = new File(gameDir + rel) (WorkspaceSession:203) with RandomAccessFile 'rw' and writes 36 bytes in place - outside the workspace, outside pack, outside persistPaths; ItemText.setLine (ItemText:105-123) writes the staged GameText copy into the workspace and reports it edited. Consequences: (a) Clean workspace (F033) reverts the name/description but leaves the record edited, so a new item reverts to '???' while its record stays live; (b) the item archive has its own baseline system (original_items/itemdata.garc + edited.txt in GameFiles.durable) separate from the workspace pristine snapshot and the Vault; (c) ModDeployer must special-case it via changedSinceBaseline (410-434), which re-reads both whole files byte by byte on every deploy; (d) ItemEditSession.save writes the record first and text second, so a text failure leaves a half-applied edit that the session cannot roll back. The in-place design is defended in the class doc (avoids repacking), but the user-facing model (one save, one revert) is not what the storage delivers. ADDENDUM - THE SCHEMA'S OWN TIER PROMISES THE PIPELINE THIS BYPASSES. RecordSchema.Tier.DATA is documented as 'Pure data in an archive. Reversible, deploys through the normal pipeline, no executable patch' and 'a writer pokes bytes in place and never repacks the archive' (RecordSchema.java:40-46). The one DATA-tier writer in use, ItemTable, does poke in place, but into the live game folder, outside the workspace, the pack and the pristine snapshot. It deploys through a separate list, ModDeployer.MODDABLE_IN_PLACE, against its own baseline file (ItemTable.baselineArchive), which is the second persistence model this finding describes. 'The normal pipeline' is the one it is exempt from.

**Fix.** Stage the item archive in the workspace like every other archive (in-place poke into the STAGED copy keeps the no-repack property), so clean/revert/deploy treat record and text as one edit.

<a id="f084"></a>

## F084 - ADPropRegistryEntry.equals returns true for null/foreign objects and ignores u2; editing an entry resets its 36 unknown bytes and moves it to the end; write() clears modified before the write

**medium** | correctness | `src/ctrmap/formats/propdata/ADPropRegistry.java:211`

equals (203-212) ends 'return true;' for anything that is not an ADPropRegistryEntry, including null, and never compares u2 (0x24 bytes, 'mostly constant except for the XY aquacorde fountain'); no hashCode. ADPropRegistryEditor.saveEntry (130-170) builds e2 = new ADPropRegistryEntry() and copies everything except u2, so any edit replaces the entry's u2 with the default constant (ADPropRegistry:178) and the equals-based change check cannot see it; it then remove()+put()s into a LinkedHashMap, moving the entry to the end of the written registry. Default constructor fills evtAnimations1[0..2] with -1 and leaves [3] = 0 though the field note says it is always FFFF (136, 172-174). ADPropRegistry.write (109-124) sets modified=false BEFORE writing and only logs IOException, so a failed write is forgotten. Constructor: a duplicate reference id 'will be overwritten' (75) - the next write() then emits one fewer entry; IOException is printed and a partial registry kept. Each entry's read/write calls dis.close()/dos.close() on the SHARED stream (159, 199) - harmless only because both wrap byte-array streams. dis.read(u2) (158) unchecked. The same rebuild-from-defaults pattern as NPCRegistryEditor (F081). ADDENDUM - TWO 'NEW ENTRY' CLICKS KEEP ONE. ADPropRegistryEditor.btnNewEntryActionPerformed puts each new entry under the fixed key -1 (reg.entries.put(-1, e2), :459) of a LinkedHashMap keyed by reference (ADPropRegistry.java:30). It then appends to the list model and to 'dict' unconditionally (:460-462). A second Add before the first is given a UID replaces the first in the registry while the list shows both, so the first new entry exists on screen and nowhere else, and is not written by Save. ADDENDUM - A SECOND SERIALIZER EXISTS BECAUSE THE FIRST CANNOT HAND BACK BYTES. ADPropRegistry.write serialises 'count + entries' and stores it straight into the container, logging an IOException (ADPropRegistry.java:109-124). The map painter needs the bytes staged into its area transaction instead, so TilePainterForm.StagedArea.stageRegistry writes the same body itself: writeInt(entries.size()) then each entry's write(dos) (TilePainterForm.java:335-346, 'its own write() goes straight to disk and cannot report a failure'). The registry layout now has two writers; a change to it (the u2 the equals ignores, the 36 unknown bytes) must be made in both. A 'byte[] serialize()' on ADPropRegistry, which write() and the painter would both call, removes the second.

**Fix.** Copy-on-edit from the existing entry, compare all fields (including u2) in equals with a proper instanceof/false fallback and hashCode, keep order, clear modified only after a successful write, refuse duplicate references instead of overwriting.

<a id="f085"></a>

## F085 - Item-giver template hands the item out on every talk (one-shot flag guard 'not templated yet'); the ORAS-only templates are offered with no game gate

**medium** | unfinished | `src/ctrmap/formats/scripts/NpcTemplates.java:217`

NpcTemplates.addItemGiverScript javadoc (213-218): 'The giver is repeatable: the vanilla one-shot flag guard is not templated yet.' - a placed item giver is an infinite item source. Class doc (29-30): 'Everything here was measured on ORAS. XY is NOT covered by the recon - callers should gate these templates to ORAS workspaces.' grep of NPCEditForm for 'Feature.' finds no feature gate at all (call sites NPCEditForm:1040/1126/1174/1314/1356/1395, TilePainterForm:1243), and GameProfile.Feature has no NPC/script-template member. ORAS constants live as literals in the format layer instead of the profile: TRAINER_ID_MAX = 949 and ITEM_ID_MAX = 775 (90, 107), repeated as 1..949 in GauntletScriptWizard:65, while ItemTable derives the item count from the archive. ADDENDUM - THE BATTLE-POINTS GIVER HAS THE SAME HOLE, AND BOTH FORMS SAY SO. GiveBpForm: 'The NPC adds this many BP each time it is talked to (no one-time flag yet; the game caps total BP at 9999)' (GiveBpForm.java:38). FacilityScriptWizard.addGiveBpScript emits GetBP + amount -> SetBP with no flag check (FacilityScriptWizard.java:21-47), so a BP giver is an unlimited Battle Points source up to the cap. GiverForm repeats the item-giver note (GiverForm.java:42). Both features are shipped with the missing one-time guard written into their own UI as a 'yet'.

**Fix.** Template the one-shot flag (EventWork set/check frame from a vanilla giver), add a Feature gate for script templates, move the id bounds into GameProfile (or derive them from the text/trainer archives).

<a id="f086"></a>

## F086 - A branch whose target is not an instruction boundary is silently rewritten to 'jump to address 0' on the next renumber; unknown opcodes crash the disassembler after a stdout print

**medium** | silent-failure | `src/ctrmap/formats/scripts/PawnInstruction.java:938`

JumpListener.onAddressChange (935-941): src.argumentCells[0] = target.pointer - src.pointer, and on NullPointerException (target was null because lookupInstructionByPtr found no instruction at the jump target, chkAddJumpListener:190) it writes -src.pointer, i.e. a relative jump to absolute 0 (the HALT_P head). Every renumber (callInstructionListeners: ScriptEditor commits, TalkerScriptWizard.installCase, MsgWrapper/SignWrapper stub insert) applies it without a word. PawnClosure.validateStubInsertPreconditions exists precisely to refuse such targets ('would corrupt or NPE later'), but only the two injectors call it; ScriptEditor.setArgsFromString accepts any hex target (231-238). CaseListener does the opposite for the same condition: a null case target keeps its stale offset (966-972) and a null default target throws NPE (974). getDisassembly's default branch (579-580) does System.out.println('Invalid Command ID') then falls into eA, where Commands.values()[cmd] throws ArrayIndexOutOfBounds for any opcode past NUM_OPCODES (cmd is cellValue &amp; 0x7FFF).

**Fix.** Refuse (in words) any branch/case target that is not an instruction boundary at the point it is set, in every editing path; make the listener throw instead of retargeting to 0; render unknown opcodes as a raw cell instead of indexing the enum.

<a id="f087"></a>

## F087 - The two bytecode transplants are still 93% the same code after the 'one engine' extraction

**medium** | duplication | `src/ctrmap/formats/scripts/SignWrapperInjector.java:248`

Measured (dupe_lines.py, controls: self 100%, vs ItemTable 5/278): 259 of 278 substantive lines of SignWrapperInjector also occur verbatim in MsgWrapperInjector. PawnClosure (its class doc) took the closure walk, CRC cell order, stub preconditions and boundary check, but each injector still carries its own copy of: canInject, validateDonor, countInjectedInstructions, pickDonor/tryDonor, the natives dedupe/append step, the whole yield-stub insert + publics/mainEntryPoint/dataStart/heapStart fixup (Sign 324-355 == Msg 465-496), the block append with SYSREQ_N/CALL fixups, the post-injection self-check, and the ~45-line per-instruction self-containment validation in buildDonor; plus the same Refused->InjectionException catch block pasted 9 times (5 + 4, each closing brace at column 0). PawnClosure's own doc gives the cost: 'a fix to the walk landed on one injector and not the other' - in code that rewrites save-bound zone scripts. Each has its own InjectionException type, which is why callers 'catch InjectionException by name' twice.

**Fix.** Move the shared steps into PawnClosure (parameterized by a WrapperSpec: finder, geometry, CRC, optional data step) with one exception type; leave only the msg buffer/rewrite step in MsgWrapperInjector.

<a id="f088"></a>

## F088 - Tile-painter sign wiring is a fifth, non-transactional copy of the script-wizard surgery that ZoneScriptEdit says no longer exists

**medium** | data-integrity | `src/ctrmap/humaninterface/TilePainterForm.java:1152`

ZoneScriptEdit's class doc: 'This used to be written out four times, once per wizard... It is one thing now' - it works on a COPY, stores text first, and commits the script last, dropping the copy on failure. TilePainterForm.wireSigns (1152-1271) does not use it: it injects the sign routine into the live parsed script s (1182), appends cases per sign (1243) and lines to msg, then commits in three separate writes - Files.write(story text) + Workspace.addPersist (1252-1253), zo.storeFile(1, furniture) (1259), zo.storeFile(2, script) (1265). A failure at the script write leaves furniture records pointing at case ids the script does not have and story lines nobody references, already persisted; an exception from addSignScript mid-loop abandons earlier signs' in-memory cases. It also reaches the global facade (Workspace.getStoryTextGARC/getWorkspaceFile/addPersist, 1191-1253) though it is handed a LoadedZone. ZoneScriptEdit itself would accept msg+sign donors in one edit, where the second injector computes its block base from a dataStart the first injection did not move (the ordering hazard GFLPawnScript.updateRaw's doc describes); no production caller sets both today (NPCEditForm:1033/1124/1288). ADDENDUM - AND THE INSTRUCTION EMITTERS UNDER THEM ARE SEPARATE TOO. GauntletScriptWizard owns an Emitter that advances its pointer by each instruction's real encoded size ('4 + (hasCompressedArgument ? 0 : argumentCount \* 4)', GauntletScriptWizard.java:230-260, 'tracking pointers by real encoded sizes'). FacilityScriptWizard.addGiveBpScript writes the same Battle-Points frame as emitBp (:215-221), but tracks the pointer by hand with literal sizes, 'ptr += 4 / += 12 / += 8' (FacilityScriptWizard.java:21-47). git grep counts hand-sized 'ptr += 4|8|12' steps in FacilityScriptWizard 7, TalkerScriptWizard 4, GauntletScriptWizard 3, PawnSubroutine 2 and ScriptEditor 2. The literals are right for today's opcodes; they are a second copy of the size rule the Emitter computes, and the copy that is wrong first when an opcode's encoding changes.

**Fix.** Route wireSigns through ZoneScriptEdit (one copy, one commit), and make ZoneScriptEdit refuse or re-sync (updateRaw) between two injections.

<a id="f092"></a>

## F092 - The terrain painter ships with undocumented JVM switches that delete its hole-sealing geometry, a global diagnostic counter, and admitted open seam defects

**medium** | unfinished | `src/ctrmap/formats/tilemap/PaintedRegionBuilder.java:454`

Production geometry reads System.getProperty in-loop: nobacking (454) drops every backing wall, noposts (490) every corner post, nogusset (PaintedCliffMesh:221) every gusset - the three mechanisms that seal the map; terrain (TerrainCatalog:79, also MapPrefab:393) swaps the whole donor palette from a file; skirtstats (515) and cliffdebug (TerrainCatalog:308) print. None appears in any .md, UI or suite outside the old census. SKIRTS (764) is a static int[3] incremented on every build and never reset, with SKIRTS[1] counting two unrelated things (flat skirts at 423 and donor-side backing skips at 458), in a class whose split-stage docs call it 'pure, it holds no state'; spriteMaterials (915) is another static cache. The code admits the geometry is not closed: 410-416 'Until that is fixed the taller skirt stays, and its cost is a few blades near corridors' (bounding the skirt raised visible sky from 348px to 2338px 'because ... the ordinary wall that should cover the rest is not actually there'); 442-451 the corner seam 'has been patched three times here ... and still left slivers'. The file also carries 9 runs of 3+ blank lines (100 lines; 217-232, 772-786, 868-899...) and an orphaned two-quad cliff javadoc (814-833) left by the split.

**Fix.** Remove the switches or route them through a documented debug option; make SKIRTS per-build output; track the seam defects as owned work items with a geometry test (no sky through the map) rather than comments.

<a id="f095"></a>

## F095 - Editing the 4th 'event script 1 animation' field of a prop registry entry makes every later save of that entry throw ClassCastException

**medium** | correctness | `src/ctrmap/humaninterface/ADPropRegistryEditor.java:41`

The constructor gives Short value class to oa1, oa2, oa3, ea11, ea12, ea13 - but not ea14 (41). saveEntry casts all of them, ea14 included: e2.evtAnimations1[3] = (Short) ea14.getValue() (150). Measured with a headless probe replicating the field setup (scratchpad/probe/FmtProbe.java): control field with setValueClass(Short) commits '7' as java.lang.Short; the ea14 setup holds a Short until edited, then commitEdit yields java.lang.Long, and the (Short) cast throws ClassCastException. saveEntry runs from the list selection listener (88) and closeRequested (55), on the EDT, so after touching that field the user can neither save the entry nor switch entries cleanly. Same file: getH3DModel reads Workspace.getWorkspaceFile/Workspace.session() globals (94-96) and NPEs if the BCH has no model (97); saveEntry indexes models.get(...).name without a null check after getH3DModel can return null (163, 166).

**Fix.** Add ea14 to setShortValueClass (better: read every field via ((Number) getValue()).shortValue()), and guard the null model.

<a id="f098"></a>

## F098 - Every model preview runs a 60 FPS loop from construction and destroys and re-uploads all of its GPU buffers on every frame

**medium** | efficiency | `src/ctrmap/humaninterface/CustomH3DPreview.java:72`

CustomH3DPreview's constructor starts an FPSAnimator(this, 60) (26-27) whether or not the panel is ever shown; display() (63-84) calls model.destroyAllBOs(gl), then makeAllBOs() and uploadVBO(gl) for every mesh, then renders - a full delete-and-upload of the model's vertex buffers 60 times a second. Instances are created by ADPropRegistryEditor (218), NPCRegistryEditor (190), PropEditForm (1185), every ModelPicker (37 - ChallengeForm and the other NPC wizards each build one) and NPCEditForm (1481); only NPCEditForm's wizard previews and BuildingPaletteDialog/ZonePreviewPane's MapPreview3D are ever stop()ped (NPCEditForm:1509, ZonePreviewPane:108), so the registry editors' and the prop form's loops run for the life of the window, hidden or not. display() also renders with a null material when materialId is out of range (80), the uploadVBO NPE path in F078. The camera framing (68) mixes 0.5\*max - min, not (max - min)/2.

ADDENDUM: Corroborated by the battery: DataSafetyGuardsTest.java:744-750 records that building a PropEditForm (its generated initComponents makes a CustomH3DPreview) starts an FPSAnimator on a NON-daemon thread that keeps the JVM alive after main returns, so the suite has to fetch the preview by reflection and stop it by hand.

**Fix.** Upload once per loadModel (on the GL thread via invoke), render with an on-demand animator (start on show, stop on hide) or repaint-on-change.

<a id="f099"></a>

## F099 - The legacy collision viewer ('Very old.') runs a 300 FPS immediate-mode render loop from application start for the life of the process

**medium** | efficiency | `src/ctrmap/humaninterface/GLPanel.java:35`

GLPanel's constructor starts new FPSAnimator(this, 300, true) and never stops it (35-36); CtrmapMainframe builds it unconditionally at startup (573-574, wired to CollInputManager at 700), so whether or not the user ever opens the collision tab, a GL context redraws 300 times a second with glBegin/glEnd immediate-mode triangles (63-69) over parent.coll. Together with the per-preview 60 FPS loops in F098, an idle editor keeps several render threads busy. The panel belongs to the legacy GRCollisionFile path (CollEditPanel), which F061 found duplicated by GfColl. ADDENDUM - AND EVERY TRIANGLE ALLOCATES ON EVERY FRAME. The loop's only content is GRCollisionFile.render -> GRCollisionMesh.render -> Triangle.render (GLPanel.java:66; GRCollisionFile.java:272; GRCollisionMesh.java:47). Triangle.render (Triangle.java:63-65) builds 'new Random((long) averageheight)' for every triangle, every frame, to pick a colour that depends only on the triangle's height. So a loaded region's few thousand collision triangles cost a few thousand Random objects per frame at 300 FPS, in a viewer the window builds at startup. The colour is a pure function of the height and could be computed once at load.

**Fix.** Drop the animator (repaint on change) or start/stop it with the tab's visibility; retire the legacy collision viewer if GfColl replaced it.

<a id="f100"></a>

## F100 - The Extras 'Free camera mod (DANGER)' mass edits are irreversible, ungated by game, and inject X/Y Lumiose collision data into every area

**medium** | ui | `src/ctrmap/humaninterface/ExtrasPanel.java:166`

freecamActionPerformed writes the bundled resource DummyLumioseCollision.bin (Lumiose is an X/Y city) into AreaData subfile 7 of EVERY area (166-170) with no Feature gate and no check that subfile 7 means the same thing on the open game; freecamReg and btn3d rewrite all zone headers (188-206, 222-231) via zone.store(), stopping on the first refusal with every earlier zone already written. All three dialogs say 'It cannot be undone from within CTRMap' (163, 185, 219) - against the owner's 'undo/redo everywhere' rule - and nothing snapshots what they overwrite. The camera-collision loop has no error handling: an area whose workspace file cannot be extracted throws mid-loop with the earlier areas already rewritten.

**Fix.** Gate the mod on a profile Feature, take a pre-edit snapshot (or route through the Vault) so it can be reverted, and make each mass edit all-or-nothing.

<a id="f101"></a>

## F101 - Map-geometry stamp writes the texture carry to the area immediately, so Undo and 'discard' leave it behind - contradicting 'only touches the workspace on Save'

**medium** | data-integrity | `src/ctrmap/humaninterface/GeoEditForm.java:348`

GeoEditForm's class doc: 'Everything is undoable and only touches the workspace on Save.' stampHere calls carryTextures (348) -> BchTexturePack.carryToArea, which does tgt.storeFile(c.subfile, c.pack) and files.edited(tgtFile) at once (BchTexturePack ~462-467). The Snapshot pushed for undo (303-306, 353) holds only model, collision and tiles, so undo() (617-638) restores those and leaves the imported textures in the area pack; answering 'Discard' in setSelection (405-408) or store(false) (681) also leaves them. The undo stack is unbounded and each Snapshot keeps a full copy of the region model byte[] plus every collision layer, so memory grows with every edit of a session. The class doc says the three layers are 'TRANSACTIONAL', but save() (640-665) writes the model, then each collision layer, then the tilemap as separate storeFile calls on the same GR, and only the model write is checked.

**Fix.** Defer the texture carry to save() (or record and revert it in the Snapshot), bound the undo depth, and stage all GR subfile writes into one container write.

<a id="f102"></a>

## F102 - Battle-facility editors replace any record they cannot read with a blank one, then write every record back on Save

**medium** | data-integrity | `src/ctrmap/humaninterface/MaisonEditDialog.java:250`

MaisonEditDialog.SetModel.loadPool (242-257): for each of the pool's 999 entries, an exception reading or parsing the workspace file is caught and the slot becomes new MaisonSet() (species 0 = 'empty'); save() (283-292) then rewrites ALL 999 files through a truncating FileOutputStream and addPersists each, so an unreadable retail set is silently replaced by an empty one on the next Save - and an 'empty' slot is exactly what MaisonPoolGuard then offers as free authoring space. MaisonClassListDialog.loadTable/save (181-219) does the same with new MaisonClassList(): classTag 0, no indices, sourceLength 0, which write() emits as a 4-byte record - a retail class row turned into class 0 with no sets. Even with nothing unreadable, every Save rewrites and marks edited all 999 (or all class-list) files, so the next pack carries the whole archive. The EV-spread/form column is 'read-only-ish (raw), leave for now' (395, 412), and ids are clamped to ORAS literals 721/621/775 (391-394).

**Fix.** Refuse to open (or mark the row unreadable and read-only) when a record fails to parse; write only rows that changed; make EV preset/form editable or say why not.

<a id="f103"></a>

## F103 - Removing a matrix row/column leaves the selected-cell cursor past the new edge, so the next Save/store throws IndexOutOfBounds and writes nothing; Remove also has no lower bound

**medium** | correctness | `src/ctrmap/humaninterface/MatrixEditForm.java:926`

btnRemoveRow/ColActionPerformed (915-935) shrink ids/LOD/zones and width/height but never clamp or drop curRegX/curRegY. saveAll (118-140) then runs mm.ids.set(curRegX, curRegY, id) - ResizeableMatrix.set is list.get(x).set(y) (MapMatrix.java:341-342). saveAll is reached from Save, both chunk fields, the next click and store() (156-170, the flush File>Save, zone switch and close all run). Measured (scratchpad/probe/CursorProbe.java against the compiled tree): control set on column 2 before removal ok; after removeColumn 'IndexOutOfBoundsException: Index 2 out of bounds for length 2'. Second Remove on a 1x1 matrix: 'Index -1 out of bounds for length 0' (no lower bound in either handler). switchTools (283-336) already fixed exactly this class for a scale change by dropping the cursor; the resize handlers are the two call sites the fix did not reach. btnRemoveCam (984-995) has the same shape: enabled whenever the camera tool is selected, it calls boundEntryBox.removeItemAt(getSelectedIndex()) with -1 when no boundary exists.

**Fix.** Drop the cursor (curRegX=curRegY=-1, MatrixSelector.unfocus) in a single resize helper used by all four handlers, refuse removal at width/height 1, and disable Remove camera when cambounds is empty; better, make ResizeableMatrix resize own a listener so a cursor cannot outlive its cell.

<a id="f105"></a>

## F105 - The NPC and prop registry editors' close handler never protects an edit: it answers true even when the save is refused, and the tool column that hosts them never calls it

**medium** | correctness | `src/ctrmap/humaninterface/NPCRegistryEditor.java:46`

NPCRegistryEditor.closeRequested (45-50) is 'if (saveEntry(true)) { } return true;' and ADPropRegistryEditor.closeRequested (53-61) also ends 'return true' - both under a javadoc saying 'It answers false when a save was refused ... dropping it would discard the edit silently'. git show f911ca4 (2026-09-12): the window version was 'if (saveEntry(true)) dispose();' - the dispose was removed and the refusal never became 'return false'. Second half: grep of src (tests excluded) finds exactly ONE caller of closeRequested(), CtrmapMainframe.closeExtrasTool:1192, for the Extras tab (control: the same grep finds all 4 definitions - FormPanel:85, ADPropRegistryEditor:54, NPCRegistryEditor:46, WorkspaceSettings:67). The two registry editors are opened into the World Editor's tool column through toolPane.accept -> CtrmapMainframe.switchToolUI (1296-1311), which replaces jsp's right component with no question asked. So the current entry's unsaved fields (uid, model, shadow, collision, flags) are dropped silently on any tool switch, zone switch or second 'Edit registry data' click; a 'UID not unique' refusal is likewise walked past. No test mentions closeRequested (grep of src/ctrmap/tests: 0 files).

**Fix.** Make switchToolUI (the single host seam) ask the outgoing FormPanel closeRequested() and refuse the switch on false; make both handlers 'return saveEntry(true)' (plus the parent refresh); add a guard test that a refused save keeps the pane.

<a id="f106"></a>

## F106 - 'Discard' means three different things across the 11 keep-changes prompts; only 2 of them revert, so a Discard followed by a Cancel later in the save chain leaves edits on screen that are marked clean

**medium** | architecture | `src/ctrmap/humaninterface/PropEditForm.java:836`

Measured: grep 'askToKeep(' outside tests = 11 call sites (ADPropRegistryEditor:155, NPCRegistryEditor:140, PropEditForm:810,844, CameraEditForm:265, MatrixEditForm:161, NPCEditForm:422, TileMapPanel:391,645, ZoneLoadingPanel:507,519). REVERT the in-memory state: 2 (NPCEditForm:424 reg.discard(), ZoneLoadingPanel:524 zone.discardEntities()). CLEAR THE FLAG ONLY, edits stay in memory: 5 (PropEditForm:836 falls through to props.modified=false at 841, PropEditForm:846 reg.modified=false, CameraEditForm:267 f.modified=false, TileMapPanel:462 and :649 tilemap.modified=false). NOTHING: 4 (both registry editors, MatrixEditForm:162, zone header default arm). Correctness of the flag-only arms rests on every askToKeep(true) caller reloading afterwards - true for the zone switch, close, clone, append and painter-apply paths only when OpenEditors.saveAll returns true. saveAll STOPS at the first refusal (OpenEditors:67-74), so answering Discard for props and then Cancel for the prop registry (same store(), 836 then 849) or any later editor leaves the zone open with the discarded prop edits still drawn and props.modified=false: File > Save (saveAll(false), CtrmapMainframe:1456) then skips them, and the next unrelated prop edit writes them along with it.

ADDENDUM: A twelfth keep-changes prompt sits outside askToKeep: GeoEditForm.store (GeoEditForm.java:668-684) asks its own Ui.confirm and on No (or a CLOSED dialog, since only CANCEL is tested) sets unsaved=false without reverting the live map-view Tilemap it edited through setTileData (:553, :580) - tm.modified stays true, so the next tilemap save writes the discarded tile move - and without refreshing the buttons (Save/Undo stay enabled; GeoEditFormGuardsTest.java:302-304 pins this). ADDENDUM - A TWELFTH PROMPT OUTSIDE THE ELEVEN. TextEditor.store asks its own 'Save text changes?' through Ui.confirm rather than Ui.askToKeep (TextEditor.java:160-170), so it is not among the eleven askToKeep prompts counted here. Its No arm is the flag-only meaning: 'modified = false; return true;', with the typed edits left in the table, shown, and no longer marked unsaved. Its write failure logs and returns false with no message (:199-202). That false stops OpenEditors.saveAll, so the zone switch or File > Save simply does not happen, and nobody says why (census M.101 files the dropped answer).

**Fix.** Give each editor one discard() that restores its last saved state (the NPCRegistry/Zone shape), make askToKeep's DISCARD arm call it, and add a guard that every askToKeep site's DISCARD arm reverts.

<a id="f107"></a>

## F107 - Two implementations of 'register a prop model in this area and import its textures' in one class, already disagreeing on what the entry and the import look like

**medium** | duplication | `src/ctrmap/humaninterface/PropEditForm.java:1055`

placeSelectedPaletteProp+offerTextureImport (435-525, 566-678) vs autoRegisterProp (1055-1142, reached from saveAndRefresh when a typed UID has no entry). Differences read off the two bodies: (1) palette ASKS before modifying the area's texture pack (604-611); auto imports silently. (2) palette verifies the donor pack holds every name (626) and the merged pack yields exactly the imported count (653); auto checks only errorlevel. (3) palette calls db.registerImportedTextures (667); auto does not, so PropDatabase's per-area name sets lag the disk after an automatic import. (4) palette zeroes imported.eventScr1/eventScr2 (488-489); auto keeps the template's (1121-1126) - eventScr1 is 'automated prop animation (doors opening on contact...)' per ADPropRegistry:130, so the same model gets different animation bindings depending on which button registered it. (5) palette resolves a reference collision (483-485) and falls back to a dummy entry on a bad template (475-481); auto uses ref=uid and fails the whole registration on a template parse error. (6) palette registers reg.models (495-505); auto does not. (7) auto calls Workspace.addPersist after storeFile (1108); palette relies on 'persisted by storeFile itself' (656). The shared-area rule holds in both only because BchTexturePack.importIntoArea asserts it (BchTexturePack:625).

**Fix.** One PropRegistration.register(model) used by both buttons, with one decision on eventScr, one verification set, one persist path; the UI difference (ask vs not) as a parameter.

<a id="f109"></a>

## F109 - Script editor: typing '{' for a CASETBL in any subroutine but the first inserts the SUBROUTINE template ('RETN(0)' and a closing brace) - the case-sensitive search never stops at its stop marker; and in the first subroutine near the end of the text the other branch throws

**medium** | correctness | `src/ctrmap/humaninterface/ScriptEditor.java:233`

scanBackwardsFindUntil (228-256): the case-sensitive branch's stop test is 'if (text.length() &lt;= idx + toFind.length() &amp;&amp; text.substring(idx, idx + stopAt.length()).equals(stopAt))' - the outer condition's '||' pasted as '&amp;&amp;', so the stop marker is only honoured within toFind.length() of the END of the text; the case-insensitive branch (244-249) has the intended shape. insertUpdate (276) calls the sensitive branch with ('sub_', stop '\t') to decide sub-vs-CASETBL. Measured by reflection against the compiled class (scratchpad/probe/ScanProbe.java), text laid out as getDisassemblyTextForArea does, CASETBL in the second sub: control (case-insensitive, same args) -1 'stopped at the tab'; case-sensitive 20 'DID NOT STOP - found sub_ at 20 past the tab'. The loop also never examines index 0 (while idx > 0), which is the only reason the FIRST subroutine gets the right template. Second defect, same method: the insensitive branch takes text.substring(idx, idx + toFind.length()) with no bound - a '{' typed at the end of the text throws StringIndexOutOfBoundsException (measured: 'Range [33, 37) out of bounds for length 35') out of a DocumentListener that catches only BadLocationException. Context: the editor keeps the text and script.instructions in sync on every keystroke by re-assembling the whole document and diffing INSTRUCTION COUNTS (insertUpdate 351-394, the filter's remove 152-205); the CASETBL re-parse block is pasted twice (180-197, 403-421); the Commit button (949-965) already re-assembles the whole text, which is the path that is actually right.

**Fix.** Fix the '&amp;&amp;' (and bound the insensitive substring); longer term drop the per-keystroke model sync and let Commit/Run assembler (assemble(), 980-994) be the one path from text to instructions.

<a id="f110"></a>

## F110 - Every resize of the World Editor creates a new offscreen OpenGL drawable and context and never destroys the previous one

**medium** | resource-leak | `src/ctrmap/humaninterface/TileMapPanel.java:188`

componentResized (178-204) assigns CM2DDrawable = factory.createOffscreenAutoDrawable(...) then display() and getContext().makeCurrent(); the drawable it replaces only has its renderables' buffers deleted (181-184) - the drawable itself (its context and offscreen framebuffer/pbuffer) is dropped unreleased. Measured: grep '\.destroy()' over src/ctrmap outside tests = 0 hits; grep 'CM2DDrawable' in the file = 9 lines, none a destroy/release (control: the same grep finds the field, the create at 188 and the second makeCurrent at 896-898). A window drag-resize delivers a componentResized per step, so a long session leaks one native GL context and framebuffer per step; the old context is also left current on the EDT. Related cost on the same path: renderGL (683-697) builds a new AWTGLReadBufferUtil and reads the whole viewport back into a new BufferedImage on every paint that is not short-circuited.

**Fix.** Create the drawable once and resize it (GLOffscreenAutoDrawable.setSurfaceSize), or destroy() the old one first; reuse one AWTGLReadBufferUtil.

<a id="f111"></a>

## F111 - After the matrix editor adds a row or column, zooming the 2D map or resizing the window throws: two of the panel's seven matrix-bounded loops still index arrays the matrix has outgrown

**medium** | correctness | `src/ctrmap/humaninterface/TileMapPanel.java:672`

The panel's arrays (tilemaps/models/tallgrass/colls) are sized from mm at load (529-532); MatrixEditForm's Add row/column raise mm.width/mm.height on the SAME MapMatrix (MatrixEditForm 892-895, 907-910) and the arrays do not follow - the comment at 410-423 says so. Measured by listing every loop bounded by mm.width/mm.height in the file (grep: 289, 385, 408, 449, 534, 672, 720, 805, 821): index-guarded with 'j &lt; arr.length &amp;&amp; i &lt; arr[j].length' or equivalent - saveMatrix scan 387, save worker 424, discard 456, renderCM3D 722/725, uploadBuffers 807/810; allocation 534 and regions-only 289 need none; UNGUARDED - renderTileMap 674 'tilemaps[j][i] != null' (reached from scaleImage, i.e. every zoom) and deleteGLInstanceBuffers 823/826 'models[j][i]' (reached from componentResized, i.e. every resize, where the throw also skips the drawable rebuild below it). getRegionForTile (838) indexes tilemaps[x/40][y/40] with no bound either. The guard was added call site by call site; these are the call sites it did not reach.

**Fix.** Make the arrays follow the matrix (grow them in the same resize helper F103 asks for), or route every read through one bounds-checked accessor so a new loop cannot forget.

<a id="f112"></a>

## F112 - Map painter Apply: a write failure during the commit phase is reported as 'Nothing was written - the map is exactly as it was' after the zone was forked and some regions were already written

**medium** | data-integrity | `src/ctrmap/humaninterface/TilePainterForm.java:525`

applyToZone stages everything first (388-520) and its javadoc says 'All of it or none of it' - but the commit phase is a plain sequence of writes: GeometryForker.ensurePrivate(zoneIndex) (525, forks the zone's map when shared), then per staged region four gr.storeFile calls (526-550, a RuntimeException there becomes IllegalStateException('could not write region N') with earlier regions already stored), then area.commit() (551; StagedArea.commit 348-361 stores subfiles one by one and throws 'could not write area ...' part way). Every one of those throws propagates to PaintForm.applyAction's catch (PaintForm:1047-1049) -> applyFailed (1069-1073), whose text is unconditional: 'Apply failed: ... Nothing was written - the map is exactly as it was.' PaintForm's javadoc (1056-1062) rests that sentence on 'Apply clears every precondition before it writes anything' - true of the preconditions, not of the writes. So a disk-full/permission failure on the second region (non-composite applies write every region) or on the area leaves a forked zone with a half-written map, the user is told it is untouched, and the next Pack Workspace ships it - the exact outcome the staging was written to prevent.

**Fix.** Have applyToZone report which phase failed (a distinct exception type for commit-phase failures), make applyFailed say 'partly written' for those, and make the region/area writes go to temp files renamed into place only after all succeed.

<a id="f114"></a>

## F114 - Workspace settings: cancelling the game-folder change 'interrupts the saving process' after the workspace, ESPICA and tileset settings were already overwritten - they are persisted unvalidated at exit

**medium** | data-integrity | `src/ctrmap/humaninterface/WorkspaceSettings.java:80`

save() (79-90) assigns Workspace.WORKSPACE_PATH, ESPICA_PATH, TILESET_DEFAULT and TILESET_PATH first, then calls repointGameDir, which returns WITHOUT running the rest of the save when the backup question is declined (129-132) or the 'State change warning' is cancelled/closed (147-149, commented 'interrupt the saving process'). applySettings - the only place that validates and re-opens (Workspace.validate / onWorkspaceOpened, 93-102) - never runs, but the four globals stay changed. Workspace.saveWorkspace (Workspace.java:618-636), run by the main window's close handler, writes all of them to preferences, so the next launch opens a workspace folder that was never validated, paired with the OLD game folder. With no session open (current == null), Workspace.cleanUnchanged/cleanAll (Workspace.java:243-270) also act on the new unvalidated path. And closeRequested (66-77) answers true whatever save() did, under the same 'answers false when a save was refused' javadoc as F105. ADDENDUM - THE SETUP WIZARD HAS THE SAME ORDER. SetupWizard.doFinish assigns the global 'Workspace.WORKSPACE_PATH = wsPath' (:510) before settleBackup asks about an existing pristine backup. Declining runs the first Runnable, which goes back to STEP_WORKSPACE (:511-516). If the user then presses Cancel, doCancel stores a preference and disposes, under the comment 'nothing has been written yet unless Finish ran, so cancelling is free' (:618-622). The global already holds the new workspace path beside the OLD game path, a pair nobody chose, and the window's settings persistence writes it out later. The wizard's successful path writes the same two globals from a SwingWorker thread (:536-537; F220). Its catch-all maps any RuntimeException from opening the game to 'found no maps' (census M.140), and its game-path field re-runs DumpCheck's directory walk on the EDT on every keystroke (revalidateGame).

**Fix.** Assign the globals only inside applySettings (after the game-folder step agrees), and have closeRequested return false when the save was interrupted.

<a id="f115"></a>

## F115 - Zone headers do not round-trip: bits the parser does not name are dropped on every write, and the Zone tab rewrites several fields it cannot display (ORAS map type 1 becomes 3, unknown types become 0xFF, unknown XY weathers become 29)

**medium** | data-integrity | `src/ctrmap/formats/zone/ZoneHeader.java:111`

Parser level, MEASURED (scratchpad/probe/HeaderBitsProbe.java, ORAS and XY): control header with no unnamed bits round-trips identical; with bits 14-15 of 0x1E and 5-9 of 0x20 set: '0x1E in C283 out 0283

0x20 in 0BE2 out 0802' - read keeps weather&amp;0x1F, skybox, skates, battleBG (7 bits) and mapChange&amp;0x1F plus bits 10-15 (ZoneHeader 111-125), assembleData (178-192) writes only those. Zone.changed() is 'assembleData != file bytes' (Zone.java:48-52), so a zone carrying any such bit reads as header-modified the moment it is opened: every zone switch asks 'Zone header has been modified' with no edit made, and File > Save (saveAll(false) -> askToKeep SAVE) writes the stripped header. Form level, read off the code: ZoneLoadingPanel.store (366-412) writes EVERY header field back from the widgets; getTypeIndex maps raw 1 -> row 1 for every game but getTypeRaw(1) is 3 on non-XY (672-681), so an ORAS mapType 1 is saved as 3; raws 4, 5 and >=10 map to row -1 and getTypeRaw(-1) is -1, written by dos.write as 0xFF; XY weather raws 24-28/30/31 map to -1 and getWeatherRaw(-1) falls through to 29 (590); OLvalue (6 bits, read as H0x1C>>10) is written as 0/1 from a checkbox (376). Raw values the combo boxes cannot show at all (ORAS type 2, weather >= 10, transition >= 8) throw in loadZone and leave that zone's header permanently unsaveable (store 336-364 refuses, by design). Prevalence of each case in retail data is NOT measured here: no RomFS is present in this environment. ADDENDUM, prevalence as the battery itself records it against the owner's retail dump (not re-measured here, no corpus): ZoneLoadingStateTest:442 asserts wideOL.size()==52 - 52 of the retail zones hold an OLvalue >1 that the 'Is parent map' checkbox cannot represent, and :489-496 pins that opening one and saving moves exactly byte 0x1D and writes OLvalue back as 0 ('PINNED as it behaves, not as it should'). The same suite records 0 retail ORAS zones with mapType 1 (:249) and 0 with an out-of-box dropdown value (:637), so of the cases above only the OLvalue one is live on shipped data - and it is live on 52 zones.

**Fix.** Keep the raw header bytes and write back only the fields the user changed (or carry unnamed bits through the model); make every combo box show an unknown raw value as itself, as NpcMoveCodes/WarpTransitions already do; add a guard that parse+assemble is identity over a retail master table.

<a id="f116"></a>

## F116 - The zone list is rebuilt from a SwingWorker's background thread, mutating four Swing components (and firing their listeners) off the event thread

**medium** | architecture | `src/ctrmap/humaninterface/ZoneLoadingPanel.java:212`

loadEverything's doInBackground (204-320) calls zoneList.setSelectedIndex(-1)/removeAllItems/addItem per zone, tmg.setSelectedIndex/removeAllItems/addItem, type.setModel and weather.setModel - all on the worker thread while the EDT is pumping the modal progress dialog. JComboBox model changes fire ActionEvents/ListDataEvents synchronously, so zoneListActionPerformed and the combo UI delegates run on the worker concurrently with EDT painting. Swing components are not thread-safe; the symptoms of this class are intermittent (stale or half-drawn lists, ArrayIndexOutOfBounds from BasicComboBoxUI during paint). Same shape: the loop also calls System.out.println for every zone whose script has > 3 publics (250-252, debug output left in), and System.gc() is called explicitly on every zone switch (90, 211).

**Fix.** Build the label list and the Zone[] table in doInBackground, return them, and populate the combo boxes in done() on the EDT; delete the debug println and the explicit GC calls.

<a id="f118"></a>

## F118 - Four production sites (and four tools) still hard-code ORAS's 'length - 2' for the ZoneData tail that ZoneTables was written to own - on X/Y they read the last real zone as the master table

**medium** | correctness | `src/ctrmap/WorkspaceIntegrity.java:90`

ZoneTables (10-28) records that 'Nine places in the editor worked out how many by writing archive.length - (isXY() ? 1 : 2)' and replaces it with the profile's zoneDataTrailingEntries(): OrasProfile:118 returns 2, XyProfile:84 returns 1. grep 'length \*- \*2' over src/ctrmap (tests excluded) still finds: WorkspaceIntegrity:90 'zo.getDecompressedEntry(zo.length - 2)' as the master table, :92 and :186 as the zone count; GfEnvPicker:86 'int zoneCount = zoG.length - 2'; tools BuildingHarvester:77, TerrainDonorHarvester:58 (Math.min(536, zo.length - 2)), TerrainHarvester:424-425, GroundMaterialAudit:70. WorkspaceIntegrity.report runs on every pack (WorkspaceSession:1042) for every game, so on X/Y it parses the last zone's container as the 0x38-row master table: either 'the master zone table is unreadable or misaligned' on every pack, or area/matrix checks run against garbage rows. GfEnvPicker on X/Y lists one zone too few. The same files also parse header fields (u16 at +2/+4) and the MapMatrix grid by hand: 'sub0 + 8 + ' appears 14 times in 8 files (GeometryForker 5, TilePainterForm 3, MapResizer 1, BuildingHarvester 1, GroundMaterialAudit 1, TerrainDonorHarvester 1, TerrainHarvester 1, MapMatrix itself 1). ADDENDUM - A FIFTH PRODUCTION SITE THE 'length - 2' GREP COULD NOT SEE. ZoneRemover.removeFromFile spells the same tail three other ways: 'if (count &lt;= BASE_ZONES + 2)' (:40), 'int removed = count - (BASE_ZONES + 2)' (:43), and 'int masterIdx = count - 2, enIdx = count - 1' (:44). Its caller, the Remove added zones action, computes the stock length the measured way, 'ZoneRemover.BASE_ZONES + ZoneTables.zoneTrailing()' (CtrmapMainframe.java:2478). So the action and the remover it calls disagree on the tail for any game whose trailing count is not 2. Today that path is reachable only for ORAS (ZONE_APPEND), so this is latent. The spelling class is therefore wider than one regex: a scan for the tail must look for the trailing-entry count in every form (- 2, + 2, - 1 next to a zone count), not one literal.

**Fix.** Route every ZoneData tail/count through ZoneTables.zoneCount/masterIndex (tools too, with the profile handed in), and the matrix grid through MapMatrix; add a guard that greps for 'length - 2' / 'sub0 + 8' outside those owners, as DuplicateWorkTest does for other capabilities.

<a id="f121"></a>

## F121 - Vault: Omega Ruby and Alpha Sapphire (or two regions of one game) in folders not named by title id share one vault entry; the second is reported 'already vaulted' and a restore would write the first game's files into it

**medium** | data-integrity | `src/ctrmap/vault/Vault.java:371`

entryDir (134-139) keys an entry by the dump folder's name when it looks like a 16-hex title id, else 'game-' + profile.type().name(); its javadoc says that fallback 'cannot separate two regions - so seal records the detected name in the manifest and wouldOverwriteDifferentGame checks it before writing'. wouldOverwriteDifferentGame (371-376) compares only existing.gameType with dc.profile.type().name() - both ORAS for Omega Ruby and Alpha Sapphire - and the recorded displayName would not help either: OrasProfile.displayName() is 'Omega Ruby / Alpha Sapphire' for both (OrasProfile:27; XyProfile:23 'X / Y'). So seal() on the second dump hits isSealed(entry) (210), passes the different-game test, and returns sealed=true with the FIRST dump's manifest (218-220): the user is told the game is backed up when it is not, and restoreOne (540-587) restores the other game's bytes into it (its digest check passes - it compares against the manifest, which is the other game's). A dump extracted to a folder called 'romfs' (the usual tool output) takes this path. Not exercised end to end here (no dumps in this environment); read off the code.

**Fix.** Key the entry on the title id read from the dump itself (ExeFS/ncch header or the profile's detection), and refuse when an existing manifest's titleId differs or is unknown; never answer 'already sealed' for a manifest whose titleId cannot be matched.

<a id="f122"></a>

## F122 - Two NetBeans forms no longer describe their generated code: the NPC editor's whole Dialogue section and the Zone tab's Clone/Add-zones buttons were added by hand inside the GEN-BEGIN:initComponents block, so the next save from the GUI designer deletes them

**medium** | build-debt | `src/ctrmap/humaninterface/NPCEditForm.java:1755`

Measured with scratchpad/formcheck.py (compares every .form's component names and handlers with the Java GEN variables block and GEN-FIRST handlers; controls: LoadingDialog.form vs its own java matches exactly, vs WarpEditForm.java does not). 16 of 18 forms match exactly. NPCEditForm: 8 components in the Java GEN block and absent from the .form - btnAddTalker, btnEditDialogue, dlgLabel, dlgPreview, dlgScrollPane, dlgSep, dlgStatus, scrDropdown (grep counts: btnAddTalker form 0 / java 15, scrDropdown form 0 / java 23; control: btnSave 4 hits in the .form). ZoneLoadingPanel: btnCloneZone (form 0 / java 12) and btnAddZone (form 0 / java 18). Their handlers (NPCEditForm 829 btnEditDialogueActionPerformed, 924 btnAddTalkerActionPerformed, 1535 scrDropdownActionPerformed; ZoneLoadingPanel 1683, 1781) are plain methods without GEN-FIRST markers. Both were introduced by 135caa8 (2026-07-20, 'UI: trigger tool, dialogue wizard, prop palette, zone cloning'). NetBeans regenerates initComponents and the variables block from the .form on every designer save, so opening either form in the designer silently drops the Dialogue/Add-NPC feature and the zone cloning/appending buttons, and leaves code that references fields that no longer exist. CLAUDE.md forbids hand-editing initComponents; this is the state that rule protects against, already present.

**Fix.** Add the 10 components to the two .form files (via the designer) so the forms describe the code again, and add formcheck's comparison as a guard.

<a id="f123"></a>

## F123 - Camera edits smaller than one whole unit are silently dropped, and Apply rounds a stored fractional FOV to an integer whenever another field changes

**medium** | data-integrity | `src/ctrmap/formats/cameradata/CameraCoordinates.java:42`

CameraCoordinates.equals rounds all seven floats with Math.round before comparing; CameraEditForm.saveCamera writes cam2 only if !cam.equals(cam2) (CameraEditForm.java:238). The form offers '#0.00' fields. Probe CamEqProbe (control: pitch 10.0 vs 11.0 differ = true): pitch 10.0->10.4 seen as change = false; distance 150.25->150.49 = false. FOV is shown on an int JSlider (CameraEditForm.java:143 Math.round, :218 (float) getValue()), so any Apply that changes another field writes FOV rounded; FOV 30.4 vs 30 is not itself a change. Slider range -180..180 also offers negative FOVs. Also lead 67 (equals without hashCode).

ADDENDUM: PROPERTIES.md:139 states 'Each edit form writes what it displayed and nothing else' and names four guards (Prop, Matrix, Trigger, Geo). Measured: the only suite that drives CameraEditForm's data path is EditToolGuardsTest:1085, which loads an unreadable table and checks the refusal; no suite saves a camera and compares the bytes, so F097, F123 and F127 all sit under a property the file claims is guarded for each form.

**Fix.** Compare with Float.compare/exact bits (or an epsilon well under the 0.01 field precision); show FOV in a float field or a slider scaled x100; clamp FOV to (0,180).

<a id="f126"></a>

## F126 - Five combo boxes are selected straight from raw file values with no bounds guard: a warp or zone header holding a value past the listed rows throws mid-fill and leaves the previous entry's values on screen under the new entry

**medium** | correctness | `src/ctrmap/humaninterface/WarpEditForm.java:121`

git grep 'setSelectedIndex(&lt;field.path>)' over src (control: WarpEditForm:121 is listed) = 7 hits; 2 Maison hits restore an index the dialog itself set (safe); 5 are raw file values: WarpEditForm.java:121 warpType&lt;-directionality (u16, 3 rows), :122 posType&lt;-coordinateType (u16, 2 rows), :129 facedir&lt;-faceDirection (byte, 4 rows); ZoneLoadingPanel.java:119 mapTransition&lt;-mapChange (5 bits, 8 rows), :125 tmg&lt;-townMapGroup (s16, name-list rows). Probe ComboIdxProbe (control: index 3 of 4 selects = 3): 4, 8, 31 each THROW IllegalArgumentException 'setSelectedIndex: N out of bounds'. In showEntry, 'warp' is already reassigned (117) before the throw, so x/y/z/w/h/facedir still show the previous warp; saveEntry would build warp2 from them. The tgtZone line just above (119) IS guarded (out of range -> -1), so the guard exists for one combo of four. ZoneLoadingPanel's catch (lead 163) prints and leaves the zone half-loaded.

ADDENDUM: SAVE PATH (WarpEditForm): no 'loaded' guard like ZoneLoadingPanel.store's - the zone save chain calls mWarpEditForm.saveEntry() unprompted (CtrmapMainframe.java:430, ZoneEditors commit), and saveEntry builds warp2 entirely from the widgets and writes it if !warp.sameValues(warp2). So after a throw at :121 the previous warp's directionality/position/size/facing are written into the shown warp on the next File > Save. ZoneLoadingPanel.store's comment (337-350) describes exactly this cross-entry corruption for the header and fixed it at the save site only; the warp form, same class, was not covered.

ADDENDUM: Trigger frequency UNMEASURED: no game data is in the repository and game data may not be read by this audit, so whether retail warps carry faceDirection>3 / directionality>2 / coordinateType>1 is unknown. Consequence if triggered is silent cross-entry corruption on save.

**Fix.** Route every data->combo selection through one helper that selects -1 (or adds a 'raw N' row) when the value is out of range, and make save keep the raw value when nothing is selected.

<a id="f129"></a>

## F129 - 12 shipped auto-catalog buildings have an inverted box (end tile one before start): the palette lists them as 0xN, the paint tool's footprint and hit-test use width 0, while the cut takes a 2-wide box - so a placed one can never be clicked again

**medium** | data-integrity | `src/ctrmap/tools/BuildingHarvester.java:263`

tsvcheck.py over oras_buildings_auto.tsv (control: 3 planted defects in a copy all reported): 3535 rows, all 17 fields, 12 with tx1&lt;tx0 or ty1&lt;ty0 (lines 2560, 2577-2580, 2585, 2599, 2601, 2629, 2639, 2645, 3021; Rustboro City x11, Route 119 x1), every one inverted by exactly 1. Probe CatalogBoxProbe on the catalog the app loads (control: all 3571 ordered boxes agree listed size == cut size): 12 disagree, e.g. 'Rustboro City structure 78' listed 0x3, cut 2x3. Cause: computeTiles floors (max - ORIGIN - 0.01)/TILE for the end but not the start, so a zero-thickness component lying exactly on a tile line gets end = start - 1. Consumers of Entry.tilesW/H: BuildingPaletteDialog.java:197/292/299 (label, anchor), PaintForm.java:632-635/653 (clamp + touchFootprint), :977 (preview), TilePainterForm.java:70 (hit-test x &lt; tx + 0 is never true). MapPrefab.extract uses Math.abs(...)+1 and Box.ofTiles min/max - a second, different width definition. The file header says 'Every row passed the cut->stamp->validate gate'; no test or loader check rejects tx1&lt;tx0 (git grep for an ordering assertion: 0 hits). Also one duplicate box with different signatures (lines 3022/3027, Route 119 structures 43/48).

ADDENDUM: TESTING.md:255-259 tells the in-game tester that every one of the 3,535 auto entries is 'machine-verified to cut and stamp cleanly'; the 12 inverted boxes are among them.

ADDENDUM: Why the gate passes them: BuildingCatalogTest (1-152) extracts, stamps and validates every entry but never asserts tilesW()/tilesH() > 0 or tx0 &lt;= tx1; an inverted box gives tilesW() = 0, ax = 20 - 0/2, and the stamp still lands, so all 12 print 'ok' with a 0-wide footprint.

**Fix.** Clamp the end to >= start in computeTiles (or drop zero-thickness components), make BuildingCatalog.load refuse inverted rows loudly, define width once (Entry.tilesW) and use it in MapPrefab, and regenerate the TSV.

<a id="f132"></a>

## F132 - The committed tree fails its own mutation-baseline gate twice over: two measured files changed after the sweep, and the baseline carries the --anyway override that MutationBaselineTest refuses; the baseline covers 33 of 265 production files

**medium** | process-debt | `mutation_baseline.json:1`

mbcheck.py, digest rule copied from BatteryHygieneTest.digestBytes (CR stripped from text files; control: a planted wrong digest is reported stale): 33 entries, 31 match disk, 2 stale - src/ctrmap/CtrmapMainframe.java and src/ctrmap/humaninterface/ZoneLoadingPanel.java, changed by 1becdda and 4383eb2 after the baseline commit 5d6dfa0 (2026-09-21). MutationBaselineTest.java:180-199 fails each stale entry ('is unchanged since the sweep measured it'). _meta.work_order_override is non-null ('The queue is the 2026-09-13 whole-app census backlog ...'), and MutationBaselineTest.java:115-127 checks override.isEmpty() || 'null' - that check fails on this file as committed. Scope: 33 of 265 prod .java files have an entry (git ls-files src/\*.java minus src/ctrmap/tests). Not run here: the suite spawns 'python tools/mutate2.py --selftest' (MutationBaselineTest.java:345), a source-mutating harness, so the verdict is read from the code, not executed.

**Fix.** Re-run tools/mutate2.py over a stopped tree (no --anyway) and commit the result; make the commit guard refuse a source change to a baselined file without a fresh baseline in the same commit.

<a id="f138"></a>

## F138 - The in-game test world that TESTING.md says to walk first was built once by hand on 2026-08-30, nothing in the tree can rebuild it, and 500 of the 640 commits came after it - so walking it tests the 2026-08-30 pipeline's output, not the editor's

**medium** | process-debt | `TESTZONE.md:23`

TESTING.md:201-206 'START HERE: TESTZONE.md. A ready-made test world is packed into your game data'. TESTZONE.md:23-28 'FULLY REBUILT 2026-08-30 ... through the editor's CURRENT pipeline'; git log of TESTZONE.md ends 2026-08-30 (19e93f3). git ls-files

xargs grep -li testzone: TESTZONE.md, TESTING.md, PROPERTIES.md, the census and two readonly_paths.txt - no tool or suite builds the hub, its four zone slots (473, 474, 153, 157), the challenge NPC or the three trainers. Commits since 2026-08-31: 500 of 640; 76 of them touch src/ctrmap/formats/tilemap, formats/h3d, formats/scripts or TilePainterForm.java, the subsystems its symptom table (TESTZONE.md:147-178) isolates. Its undo section (:180-209) names three timestamped snapshot folders in the owner's tree. The file also tells the user the hub is 'packed into your game data', which is true of one dump.

**Fix.** Make the test world a build product: a tool under tools/ that rebuilds it on a pristine copy through the editor's own operations (zone clone, composite paint, building stamp, sign and challenge injection) and records the commit it was built from, so the in-game checklist states which code it is checking and can be regenerated after a change.

<a id="f140"></a>

## F140 - Restoring from a damaged backup destroys the file it was meant to repair: restoreOne copies the vault's bytes over the game file first and checks their digest afterwards, and nothing in the program ever verifies the vault before a restore (Vault.verify has no production caller)

**medium** | data-integrity | `src/ctrmap/vault/Vault.java:540`

VaultRestoreProbe (probe/VaultRestoreProbe.java; control: a vault whose copy matches its manifest restores the sealed bytes and returns "" - passed). Subject: same manifest, vault copy replaced by 'ROTTED', target a/0/1/3 holding the user's file. Result: returned 'Restored a/0/1/3 but it does not match the backup - the vault is damaged.' and the user's file now reads 'ROTTED' (OVERWRITTEN). Code: Vault.java:557-575 pipe()s straight into dst (no temp file, so an IOException mid-copy also truncates it), and the digest check is at :577-586, after the write. grep for Vault.verify outside vault/Vault.java and tests: 0 callers; VaultUi.restoreEverything (VaultUi.java:143-176) and restoreOne (:179-189) go straight to the restore. docs/vault-plan.md:50-51 named this gap ('Silent rot is discovered at the moment of need') and :70-71/:106-107 designed 'VERIFY ON LOAD ... report drift loudly'; the verify was built and never wired. VaultGuardsTest restores only from intact vaults (:158-171) and verifies rot without restoring (:141-156).

**Fix.** Stage the restore: copy to a temp file beside the target, verify its digest, then rename over the target - a damaged vault then refuses with the user's file untouched. Run Vault.verify(entry, false) where the workspace is opened (as the plan says) and before either restore, and add the rotted-vault restore case to VaultGuardsTest.

<a id="f144"></a>

## F144 - Run with no game data, 50 of the 136 registered suites print a skip and exit 0 (measured by running all 136); test.ps1 refuses the run for 37 of them but only warns for the 11 keyed on the RomFS folder or code.bin, which therefore pass having asserted nothing

**medium** | test-debt | `test.ps1:298`

skipscan.py (main() bodies of src/ctrmap/tests/\*.java; a println containing 'skip' followed in its block by 'return;' with no System.exit(non-zero); controls: CommitGuardTest and BchMapModelTest found, ADPropRegistryOrderTest - FAIL + exit 1 on missing input - not found): 41 suites, all registered in test.ps1. test.ps1:296-299 runs each with '2>&amp;1

Select-Object -Last 2' and records a failure only on LASTEXITCODE != 0; grep -i skip test.ps1 finds no check on output. By reason: (a) no dump - about 28 suites, mitigated by test.ps1 refusing a dump that fails DumpCheck and BatteryHygieneTest.registeredWithItsCorpus; (b) a tool missing - python or git: CommitGuardTest:48-53 (whose javadoc :32-33 says it 'skips if either is missing, rather than passing' and then prints ALL PASS), HookRefusalsTest:55, HooksWiredTest:49, LivenessProbeTest:53, PlantLedgerTest:59, RuleMapTest:47, RuleRefusalsTest:55; (c) the guarded record itself missing - MutationBaselineTest:87-91 prints 'skip: no baseline' and ALL PASS, so deleting mutation_baseline.json silences the mutation gate (no other guard refuses that: tools/guard grep finds only work_order.py:74 exempting the file); (d) the corpus not having the expected shape, the confident-empty class CLAUDE.md names - PaintedFloorTest:68-72 ('region 1 is not a map model' -> ALL PASS, so a regression in BchMapModel.isMapModel passes it), TextureCarryGuardsTest:57-61 and :86, DoorPropGuardsTest:72, IntegrityTest:45; (e) a wrong argument - BatteryHygieneTest:92-95 (no test sources at the given root -> ALL PASS).

ADDENDUM: A second suite documents the opposite of what it does: DoorPropGuardsTest.java:27-28 'If no such door exists in the dump the test says so and skips, rather than passing' - and :71-74 prints the skip, then 'ALL PASS', and returns 0.

ADDENDUM: Third suite whose javadoc promises the opposite: HookRefusalsTest.java:37-38 'Needs python on PATH and says so and skips rather than passing' - :54-58 prints skip, then ALL PASS, return (exit 0).

ADDENDUM: Fourth: HooksWiredTest.java:31-32 same sentence, :48-52 prints skip + ALL PASS + return.

ADDENDUM: 41 is a FLOOR: skipscan only sees skip-then-return. Suites that skip a section and fall through to ALL PASS are not counted - ItemIconPatchTest:76-79 (no code.bin -> the measured offsets are never checked, ALL PASS; test.ps1:91 warns once in yellow), and LZ11Test:81-83 whose corpus-missing message does not contain the word skip ('synthetic cases only').

ADDENDUM: Also falls through green: MaisonPoolGuardTest:104-106 'note: no pristine snapshot found - exact-path checks skipped' then ALL OK - the retail occupancy 217/873/36 and the free-slot proof (its javadoc's point 2) go unmeasured; it defaults to the owner's ../Workspace.

ADDENDUM: Fifth: PlantLedgerTest.java:40-41 'Needs python, and says so and skips rather than passing' -> :58-62 skip + ALL PASS + return. Count of javadocs promising the opposite of the code so far: CommitGuardTest, DoorPropGuardsTest, HookRefusalsTest, HooksWiredTest, LivenessProbeTest (:35-36), PlantLedgerTest.

ADDENDUM: Seventh promise-vs-code mismatch: RuleMapTest.java:29-30 / :46-50.

ADDENDUM: Eighth: RuleRefusalsTest.java:34-35 / :54-58.

ADDENDUM: Also falls through green without the word skip: SetupWizardTest.java:38-41 'no display available - wizard layout not exercised' + ALL PASS (the first-run wizard is never driven on a headless run); :47-50 'suite skipped' + ALL PASS. ADDENDUM - MEASURED BY RUNNING ALL 136 (scratchpad/m_runsuites.py + m_runsuites2.py). Every suite test.ps1 registers was run, headless, from a scratch clone with the tree compiled into the scratchpad, every path argument pointed at a folder that does not exist, and a 300 s cap. CONTROLS: entries run = 136 = the '@{ n =' rows in test.ps1. WorkflowGuardsTest and ZoneLimitPatchTest, documented as skip-then-ALL-PASS, both come out exit 0 after a skip line. RESULT: 79 of 136 exit 0 with no game data at all, and 57 fail. Of the 79, 25 are synthetic or structural and pass honestly. 50 exit 0 AFTER PRINTING THAT THEY SKIPPED their data checks: 54 matched a skip phrase, 5 of those (GameFilesSeamTest, SilentCatchTest, WorkspaceSessionTest, UtilsTest, MainframeEdgesTest) were read and found to carry the phrase in an assertion, and SetupWizardTest's 'no display available - wizard layout not exercised / ALL PASS' was added. test.ps1's pre-check refuses the whole run without the pristine a/0/1/3, a/0/3/9, a/0/4/0, which covers 37 of the 50 in a real run (but not when a suite is run alone). It only WARNS for $gamedir/$code, so 11 suites still pass having asserted nothing on any machine without the full RomFS title folder or a decompressed code.bin: GarcSniffTest, ItemDataTest, RecordSchemaTest, ItemEditTest, ItemIconPatchTest, DumpCheckTest, SetupWizardTest, DressUpIndexTest, WorkflowGuardsTest, ZoneLimitPatchTest, ShopDataTest. TextureCodecTest (a/0/1/4) and MaisonPoolGuardTest ('exact-path checks skipped') are exposed the same way. The earlier regex count of 41 under-counted by 9. The run also REFUTES one suspicion: MapModelObjTest, MapModelObjImportTest, MapModelObjV2Test, RegionFactoryTest, MapPrefabTest and MapResizerTest (leads 206/212) do not pass on a missing corpus; GARC.parse throws and they fail loudly.

**Fix.** Give skip its own exit code (e.g. 3) and have test.ps1 count and print skips separately, refusing 'ALL SUITES PASS' when any suite skipped; turn (c) and (d) into failures outright - a missing baseline or a corpus that no longer has the shape the suite needs is a defect, not an absence.

<a id="f147"></a>

## F147 - Nine file-chooser sites ignore the dialog's answer and act on getSelectedFile(), which still holds a file the user clicked before pressing Cancel - so Cancel can replace a container in the workspace (shipped at the next pack), overwrite an export target, or overwrite and reload the tile database

**medium** | data-integrity | `src/ctrmap/humaninterface/builder/Builder.java:193`

ChooserCancelProbe (probe/ChooserCancelProbe.java, the real Metal JFileChooser UI headless; control: cancel with nothing clicked -> null, passed): selecting the file row (what one click does) then cancelSelection() (what Cancel calls) -> getSelectedFile() = that file. Sites that discard the int showXDialog returns (git grep, each read): Builder.openFileDialog:193, whose callers include btnImportContainer:468-478 (Files.copy(chosen, container, REPLACE_EXISTING) then Workspace.addPersist - packed into the game), btnContSaveExternal:482-489 and btnFAExport:573-580 (Files.copy(..., chosen, REPLACE_EXISTING) - and these pick a SAVE target with an Open dialog), importGeneric:650-657 (storeFile into the container); TileDBWriter:327 (Open: clears the tile list), :487 (New: clears it), :543 (Save as: writes over the clicked file); CtrmapMainframe.picked:1349 (Open GR mapfile :1361, Open MM :1383, Open Zone :1413, OBJ to collisions :1483); WorkspaceSettings:365, 376, 404, 434 (workspace, game, ESPICA and tileset paths). The 14 other chooser sites test == / != APPROVE_OPTION (e.g. CtrmapMainframe.java:1907, ItemEditDialog.java:339, SetupWizard.java:681). Builder's IOException branches only log (Logger.SEVERE) - a failed copy says nothing; btnImportContainer reads currentAGFC.getOriginFile() at :467 before its null check at :469.

ADDENDUM: Why nothing caught it: the dialog seam (DialogSeamTest rule one, RAW_CALL at :120) covers JOptionPane only. DialogCallProbe (probe/DialogCallProbe.java over ClassFileScanner; control: ctrmap/Ui found as a JOptionPane caller) finds JOptionPane.show\*Dialog called by exactly the six classes DialogSeamTest's ALLOWED list names, while JFileChooser.show\*Dialog is called directly by 9 classes (CtrmapMainframe, TileDBWriter, GeoEditForm, ItemEditDialog, ShopEditDialog, WorkspaceSettings, ZoneLoadingPanel, Builder, SetupWizard) - so every branch behind a file chooser is unreachable from a headless suite, the reason DialogSeamTest's own javadoc (:23-31) gives for the seam.

**Fix.** One helper that returns the file only on APPROVE_OPTION (SetupWizard.java:681 is already that shape) and route all nine through it; give the Builder's save targets a Save dialog; then a source rule beside DialogSeamTest refusing a bare 'showOpenDialog(...);' / 'showSaveDialog(...);' statement.

<a id="f149"></a>

## F149 - The zone-script reader validates nothing in its header: a wrong magic, backwards table offsets and a declared length past the end are all accepted (the missing bytes become zero 'code'), a truncated header is logged and returns a half-built script, and defsize 0 throws ArithmeticException past every caller that catches IOException

**medium** | silent-failure | `src/ctrmap/formats/scripts/GFLPawnScript.java:71`

PawnHeaderProbe (probe/PawnHeaderProbe.java; control: a well-formed empty 60-byte script parses with magic E0F1, 0 publics, 0 code bytes - passed): (a) magic 0000 -> accepted; (b) defsize 0 -> java.lang.ArithmeticException: / by zero (GFLPawnScript.java:94-99 divide by defsize); (c) len 4000 over 60 bytes -> accepted with compCode = 3940 bytes, because in.read(compCode) at :106 ignores its short count and the rest stays zero; (d) publics offset 200 > natives offset 60 -> accepted, negative entry count silently reads none; (e) 30 bytes -> EOFException logged at SEVERE (:107-108) and the constructor returns an object with compCode null. ZoneEntities catches only IOException (ZoneEntities.java:95, 147, 222, 238), which EditorBench.java:383-390 records ('All-zero bytes therefore divide by zero and throw ArithmeticException out of a constructor that only catches IOException'). OUTSTANDING.md:308-319 (item A, 2026-09-13) closed this exact class for the containers and GARC with readFully/verify()/whyNot(); the Pawn reader - whose output the talker, sign, giver and challenge injectors rewrite and Zone.store writes back - was not included.

**Fix.** Give GFLPawnScript the container treatment: check magic and defsize, require ordered offsets inside len, use readFully, and throw a named refusal instead of logging; ZoneEntities then refuses the zone in words.

<a id="f150"></a>

## F150 - X and Y are advertised as supported games, but the X/Y profile enables one feature (H3D_MAPS) and refuses every other, its text indices and most archive paths are unmeasured or cited from pk3DS, and no suite has ever run against an X/Y dump

**medium** | unfinished | `src/ctrmap/gamedef/XyProfile.java:62`

README.md:3 'A world editor for the Nintendo 3DS Pokemon games - Omega Ruby, Alpha Sapphire, X and Y'; QUICKSTART.md:1 '(Gen 6: X/Y, Omega Ruby/Alpha Sapphire)'. XyProfile.supports (XyProfile.java:62-69) returns true for H3D_MAPS only ('not yet corpus-verified on XY' for the rest) - the Feature enum holds H3D_MAPS, TILE_PAINTER, AREA_ENV, WATER_SPLICE, TRAINER_EDITING, MAISON, POKE_PREVIEWS, SCRIPT_NATIVES, CODE_PATCHES, ITEM_EDITING, AREA_FORK, ZONE_APPEND and more, so the Map Builder, atmospheres, trainer/Maison/item editing, zone fork and append are all refused on X/Y. textIndex answers LOCATION_NAMES only (72, 'established by upstream CTRMap') and -1 for everything else (:51-58); ITEM_DATA is a pk3DS citation 'NOT measured against an XY dump' (:39-42); trainer, Maison, personal and move archives are null (:44-46). GameProfileTest.java:207-212: XY's archive-tail counts are 'cited from upstream CTRMap, not measured here'. test.ps1 names no X/Y dump (grep -i xy: 0 lines). docs/species-scope.md:34-35 tells readers 'XY 12 substantially wired'.

**Fix.** Either say on README/QUICKSTART exactly what works on X/Y (map viewing/editing of the formats upstream supported) and that the rest is ORAS-only, or measure the X/Y profile against a dump and add an X/Y corpus run to test.ps1 before advertising it.

<a id="f152"></a>

## F152 - The three writers of the shared code.ips patch have three merge policies: the shop editor treats a CLOSED merge question as 'overwrite', the zone-limit writer never asks and always overwrites, and only the item editor refuses a closed dialog - so closing a dialog, or adding zones after editing shops, silently deletes the other patches the game needs to boot

**medium** | data-integrity | `src/ctrmap/humaninterface/ShopEditDialog.java:154`

ShopEditDialog.java:150-161: Ui.confirm(YES_NO_CANCEL 'MERGE ...? (No = overwrite with shops only)'); only rsl == CANCEL_OPTION returns, so CLOSED_OPTION (-1, also what a headless or dismissed dialog answers) falls through past the YES branch to Files.write(out, shops-only ips). ItemEditDialog.java:484-495 does it right: rsl != YES &amp;&amp; rsl != NO returns. ZoneLoadingPanel.java:1876-1886 writes ZoneLimitPatch.buildIPS straight to the chosen file with a FileOutputStream - no existence check, no merge (JFileChooser does not confirm an overwrite), and its default target is new File("code.ips") relative to the working directory. TESTING.md:436-438 and :468-471 tell the tester that 'one code.ips carries' the zone, shop and icon patches and to 'save with MERGE when asked' - the zone writer never asks. Why the guard missed it: DialogSeamTest rule three (CANCEL_CASE, DialogSeamTest.java:122-132) counts only 'case JOptionPane.CANCEL_OPTION:' labels in switches; git grep finds two if-comparisons against CANCEL_OPTION in production (ShopEditDialog:154, GeoEditForm:675), invisible to it. The same blind spot covers GeoEditForm.store:672-683, a keep-changes prompt outside Ui.askToKeep (DialogSeamTest.java:517-518 says every such question now goes through askToKeep) where closing the dialog discards the edit.

**Fix.** One code.ips writer used by all three editors that always merges (or refuses in words) and treats anything but YES/NO as cancel; route GeoEditForm.store through Ui.askToKeep; widen DialogSeamTest rule three from switch labels to any comparison against a confirm's answer (or require every confirm result to go through one helper).

<a id="f153"></a>

## F153 - InteriorWirerTest claims to validate 'every interior the building catalog links' from a hand-copied list that covers 15 of the 39 the catalog now links - 24 interiors' exit-warp detection and rewire byte-preservation are never checked

**medium** | test-debt | `src/ctrmap/tests/InteriorWirerTest.java:29`

interiors.py (parses oras_buildings.tsv Columns header; control: interiorZone is column 12 and at least one row carries one): 40 rows / 39 distinct interiorZone values in the TSV; INTERIORS = {228,229,428,223,227,230,235,238,244,273,321,345,256,339,522} = 15, all in the TSV; missing from the test: 225, 234, 252, 264, 267, 271, 272, 275, 290, 294, 297, 310, 312, 366, 367, 368, 382, 385, 386, 389, 390, 391, 395, 397. Javadoc :11-12 'for every interior the building catalog links'; :28 comment '(oras_buildings.tsv interiorZone column)'. The catalog grew; the copy did not, and nothing compares them - the CLAUDE.md 'scope derived from the tree, never from a list' class.

**Fix.** Derive INTERIORS from BuildingCatalog.entries() (interiorZone >= 0), with a floor (>= 39) so a reader that returns nothing fails.

<a id="f155"></a>

## F155 - Restore ONE archive refuses the path its own dialog suggests: the prompt pre-fills GameProfile.archivePath (leading slash, '/a/0/1/3'), the vault manifest keys are stored with leading slashes stripped, and restoreOne looks the input up verbatim - so accepting the suggestion answers 'The backup does not contain /a/0/1/3'

**medium** | correctness | `src/ctrmap/vault/Vault.java:545`

CtrmapMainframe.java:2446-2456 example = prof.archivePath(ArchiveType.ZONE_DATA), passed as the input's initial value; Vault.java:394 manifest keys built with rel.replace('\\','/').replaceAll("^/+", ""); Vault.java:545 'man.entries.get(relPath)' with no normalisation (a Windows user typing a\\0\\1\\3 is refused the same way). MainframeActionGuardsTest.java:345-349 and :420-427 PIN this as-is: 'accepting the path the dialog itself offered is REFUSED today'. This is the recovery path for exactly the case the vault exists for (Vault.java:535-537: a stale pack once corrupted a single archive).

**Fix.** Normalise relPath in Vault.restoreOne with the same replace/strip the manifest writer uses (one shared function, so the two cannot drift), and flip the pinned check in MainframeActionGuardsTest to assert the suggestion restores.

<a id="f161"></a>

## F161 - The prop editor commits a model change before it knows the area can hold it: saveAndRefresh() writes the spinner's uid into the record first and only then tries to register it, so a refused registration leaves the record (and the next save) pointing at a model the area's registry does not have; Save also rebuilds every record from the widgets' two-decimal TEXT and a fresh GRProp, rounding positions, reverting record-only moves and zeroing the 4-byte unknown tail

**medium** | data-integrity | `src/ctrmap/humaninterface/PropEditForm.java:1032`

PropEditForm.java:1030-1042 saveAndRefresh: saveProp(); showProp(...); regentry = reg.entries.get(prop.uid); if null -> autoRegisterProp(prop.uid), which 'returns null after reporting what failed' (:1053) - the uid already in the record is not rolled back. PropEditFormGuardsTest.java:261-303 pins it: 'the record names model 321 in the file while the area's registry still does not - see the report'; :235-254 Save reads widget TEXT ('typing 1773.456 ... the file holds 1773.46', a record moved without a form refresh is put back); :256-323 the unknown tail is written as 0 and equalsData ignores it. The test's own javadoc :38-40 says these are pinned as wrong rather than fixed; no tracked report holds them.

**Fix.** Register first, commit the uid only on success (else put the spinner back); read the formatted fields' committed values (getValue) at full precision; copy the old record and overwrite only the edited fields so the tail survives; include the tail in equalsData.

<a id="f167"></a>

## F167 - Eight guards exist twice - diverged copies in .claude/hooks and .claude/bundle-hooks - and both dispatchers run on every tool call, under two environment prefixes

**medium** | duplication | `.claude/settings.json:9`

settings.json wires PreToolUse '\*' to BOTH .claude/hooks/guard_all.py and .claude/bundle-hooks/dispatch.py; guard_all.py:94 globs guard_\*.py beside it and dispatch.py:74-81 lists guard_\*.py beside it. Same-named in both folders (ls): guard_background_pipe, guard_blocked_runner, guard_fanout, guard_heredoc, guard_idle_machine, guard_idle_poll, guard_machine_worktrees, guard_mutation_read. They are not copies: diff sizes 237/276/602/241/213/250/123/113 lines (guard_fanout 440 vs 238 lines). Five of them read their overrides through hook_env (PREFIX 'CTRMAP', hook_env.py:24) in one folder and bundle_env (PREFIX 'VERIFY', bundle_env.py:29) in the other, so the owner override each docstring names (e.g. CTRMAP_ALLOW_PIPE, guard_background_pipe.py:28; CTRMAP_AGENT_CAP/WIDTH_CAP, guard_fanout.py:47) clears one copy while the other still refuses under VERIFY_\*. hook_env.py:4-14 and bundle_env.py:4-13 each describe exactly this ('ONE RULE WITH TWO SPELLINGS IS TWO RULES') as the defect they were written to end - inside their own folder only. ADDENDUM - the two stacks also disagree on what is allowed: the bundle's held-change refusal names "git stash" as a way out (bundle_rules.py:1364; guard_held_writes.py:29 and the RESTORES regex at :64 let "(checkout|restore|stash)" through a hold), while the project stack refuses it (.claude/hooks/forbidden_commands.txt:14; guard_forbidden_command.py:19-23) and CLAUDE.md says "Never git stash". A held session is told to run a command the other dispatcher blocks. Observed live in this audit: the project guard refused a python command whose quoted ARGUMENT merely contained those two words. ADDENDUM - the end of a turn is judged twice as well: settings.json wires Stop to BOTH .claude/hooks/guard_unfinished.py and bundle-hooks/dispatch.py (which runs guard_promise.py). guard_promise.py:144-148 says its admission patterns were "ABSORBED FROM CTRMap's guard_unfinished.py ... It was deleted only once this guard refused everything it did" - but guard_unfinished.py is still tracked in .claude/hooks and tools/hooks and still wired. guard_promise also keeps its once-per-message memo under a temp folder named "dtengine-promise" (:490), the source project's name. ADDENDUM - the fan-out caps, the guard CLAUDE.md names as this owner's most expensive lesson, exist twice with different numbers and different safety: the project copy (.claude/hooks/guard_fanout.py:59-61) caps agents 10, workflows 5, width 6 per 6 h under CTRMAP_\*, its docstring (:47) suggests lifting to 20/10/12; the bundle copy (bundle-hooks/guard_fanout.py:44, :192) caps agents 4, workflows 1 under VERIFY_\*, each with its own counter file in its own folder (.fanout-count). An owner who raises CTRMAP_AGENT_CAP has raised one of two caps. And the project copy still reads its caps with a bare int(os.environ.get(...)), which raises at import on a value like "six" - the bundle copy's docstring (:25-29) records that as the defect it fixed, and hook_env.py:45 defines the safe cap() reader for exactly this, with ZERO callers (git grep 'hook_env.cap('). ADDENDUM - the project copies still carry the defects the bundle copies record as fixed, read off the two texts: guard_idle_poll (project :46/:57 SLEEP.match on the raw command, anchored at its start - bundle copy :202-205 "so cd /tmp &amp;&amp; sleep 300; cat x matched nothing ... four of five real spellings walked through"); guard_machine_worktrees and guard_blocked_runner (project is_read_only/is_escape search the whole line - bundle copies :131-139 / :88-92 "the cheapest way past the refusal was to put a read in front of the write"); guard_background_pipe (no newline split - F172); guard_fanout (raw int() caps). Where the bundle twin also runs and guards the same thing, the hole is closed by the accident of duplication; where it does not (the pipe guard's list, F172; guard_game_data, which has no bundle twin, F173) it is open.

**Fix.** keep one implementation of each guard (the bundle's, with the project's divergences upstreamed or kept as separately-named guards), one dispatcher, and one PREFIX; make the install check refuse a guard name present in both folders.

<a id="f170"></a>

## F170 - Guards copied from another project watch machinery this project does not have: nine guard files keyed on a .sweep runner nothing here writes, and a pipe guard whose list names scripts that do not exist

**medium** | dead-code | `.claude/bundle-hooks/guard_blocked_runner.py:70`

MEASURED. (1) No producer: `git ls-files

grep -i sweep` is empty and a tree-wide grep finds no writer of `.sweep/blocked.json`, `.sweep/tree-N`, `.sweep/ledger-\*.jsonl` or `.sweep/idle-on-purpose.json` outside the hook folders and tests/test_bundle.py - the sweep runner (`sweep.py`) belongs to the project these hooks were copied from. Yet guard_blocked_runner (BLOCKED = ROOT/.sweep/blocked.json), guard_idle_machine (STATE = ROOT/.sweep) and guard_machine_worktrees (`\.sweep[/\\]tree-`) are installed in all three copies (.claude/hooks, tools/hooks, .claude/bundle-hooks) - nine guard files whose only subject cannot occur here, run on every tool call. (2) Wrong list: the bundle's guard_background_pipe reads destructive_scripts.txt from its own folder (bundle_shell.destructive_scripts, folder = .claude/bundle-hooks), which has none, so it falls back to DESTRUCTIVE_IF_INTERRUPTED = tools/audit/mutate.py + tools/audit/weigh.py - neither exists here (checked). scratchpad/pipeprobe.py, run as the harness runs hooks (subprocess, payload on stdin), controls: bundle copy refuses its own default and the project copy refuses `python tools/mutate2.py x

tail -5` -> the bundle copy ALLOWS that real driver pipe and `python tools/mutate.py x

head -3`. The project copy still covers it, so no live hole - but bundle_shell.py:689-694 describes exactly this ("pointed at a codebase whose sweep is tools/mutate2.py the guard allowed the exact pipe it exists to refuse") as fixed. The bundle guard_blocked_runner's ESCAPES also name tools/dev/safe.py and tools/dev/plants.py, which do not exist here. (3) tools/hooks/\* is a byte-identical copy of .claude/hooks/\* (19 .py + 3 .txt, cmp), kept equal by tools/guard/hooks_wired.py - both copies are tracked in git, so the "versioned copy" is a second tracked copy of the same 22 files. (4) MEASURED (scratchpad/hookpaths.py over all 67 tracked files in .claude/hooks, .claude/bundle-hooks and tools/hooks; controls: tools/mutate2.py found and present, tools/dev/safe.py found): the hooks spell 22 distinct repo paths and 18 do not exist here. Four are illustrations (src/u.py, tools/x.py, tools/audit/x.py, tools/../mutate.py); the other 14 are another project's machinery, mostly inside refusal REMEDIES the session is told to run: tools/dev/safe.py (12 files - e.g. guard_heredoc's "run it through python tools/dev/safe.py run-script &lt;path>"), tools/dev/plants.py, tools/dev/progress.py, tools/dev/start_sweep_forever.ps1, tools/audit/{sweep,mutate,weigh,baselines,liveness,tiers}.py, docs/.sweep-forever.log, docs/PROGRESS.md, docs/layer5-integration-items.md, tests/exercised_by.json. A refused session here is handed a way out that does not exist.

**Fix.** remove the .sweep guards from all three copies (or add the runner they exist for); give the bundle hooks the project's destructive_scripts.txt or make the fallback refuse rather than guard nonexistent paths; keep one tracked copy of the project hooks.

<a id="f171"></a>

## F171 - The mutation-read guard reads the whole multi-line lock as the locked path, so a read of the mutated file itself is allowed under every lock this project writes

**medium** | silent-failure | `.claude/hooks/guard_mutation_read.py:111`

MEASURED (scratchpad/lockread.py: each installed copy run as the harness runs it - subprocess, payload on stdin - with CLAUDE_PROJECT_DIR at a scratch folder holding only the lock; control: a ONE-line lock naming src/ctrmap/formats/zone/ZoneHeader.java makes both copies DENY `cat` of it). locked_module() (.claude/hooks/guard_mutation_read.py and bundle-hooks copy) returns the WHOLE stripped lock file as the path. Every writer of the lock in this project writes more than one line: tools/mutate2.py:1083-1087 and tools/guard/replant.py:425-428 write "path\npid=N\nwhat=...\n", and bundle_rules.take_lock (used by tools/prove_plants.py:316) writes "path\nnonce\n". With either real format, BOTH copies ALLOW `cat &lt;locked file>` and a Read of it - readers_of() looks for the multi-line string in the command, which can never occur - and only `grep -rn x &lt;parent dir>/` is still refused (locked.startswith(token + "/") survives). The project copy also lets a Read of the locked file through even with a one-line lock (it judges commands only; the bundle copy added paths). The class this guard exists for - an AST walk over the unparsed mutant reported as fact - is open for the file itself. bundle_rules.locked() reads only the FIRST line (bundle_rules.py:171), so the two readers of one lock disagree on its format. ADDENDUM (scratchpad/lockwhere.py): mutate2.py writes its lock in the repository's parent, where this guard never looks (F179), so the lock this reader actually meets is replant.py's - and replant plants into the repository's own files, so under every replant run a read of the planted file itself is allowed.

**Fix.** Read only the first line of the lock (as bundle_rules.locked does) through ONE shared reader of the lock format used by every guard; add a test that takes the lock the way mutate2.py, replant.py and take_lock do and asserts that "cat &lt;locked file>" and a Read of it are refused.

<a id="f172"></a>

## F172 - A mutation run on the second line of a command passes both pipe guards: one copy has the right script list and a splitter blind to newlines, the other the right splitter and another project's list

**medium** | silent-failure | `.claude/hooks/guard_background_pipe.py:156`

MEASURED (scratchpad/pipeprobe2.py, both installed copies run as the harness runs hooks; controls: the project copy refuses `python tools/mutate2.py x

tail -5` on ONE line, and the bundle copy refuses its own default script when it is on the SECOND line, so its newline split is real). With a first line of `cd src` and the pipe on the second line, BOTH copies ALLOW `python tools/mutate2.py x

tail -5`, and the project copy also allows a FOREGROUND `python tools/mutate2.py x` on line 2 (refused on one line) - the two shapes this guard exists for, a mutation run that a pipe or the foreground timeout can kill mid-mutant. Cause, one half in each copy: the project copy's segments() (.claude/hooks/guard_background_pipe.py:156-192, and invokes() at :83) splits on ; &amp;&amp; || but not a newline - the exact defect bundle_shell.py:40-43 and the bundle copy's segments() docstring (:186-192) record as fixed by moving to the shared splitter; the bundle copy has that splitter but guards tools/audit/mutate.py and weigh.py, which do not exist here (F170). Neither copy has both the right splitter and the right list. The project copy also imports hook_env twice (:42 and :255).

**Fix.** one guard: the shared newline-aware splitter (bundle_shell.split_commands) with this project's destructive_scripts.txt; add the two-line cases to its tests.

<a id="f178"></a>

## F178 - The ledger's declared reviewer requires a subagent at every turn end that CLAUDE.md section 2 forbids, and the fan-out cap is told not to count it

**medium** | process-debt | `.claude/bundle-install.json:10`

MEASURED (read, not inferred): .claude/bundle-install.json:10-12 declares "_review": {"model": "haiku"}, and request_ledger.problems (:954-975) then keeps EVERY turn open, once the ledger is itemised and resolved, until a subagent review has been launched synchronously with exactly the prompt `request_ledger.py review` prints (launch_problem :1003-1028; is_owed_review :1031-1036 is how guard_fanout's cap is told NOT to count it - tests/test_bundle.py test_the_fan_out_cap_counts_every_launch_but_the_owed_review). The same file prices a review at "about 50k tokens each" (:891, and tests/test_bundle.py:2333 "Four and five reviews in a row, at about 50k tokens each, on the owner's usage"), with up to REVIEW_ROUNDS reviews per owner message before it escalates. This collides head-on with CLAUDE.md section 2, "Work in the main thread with no agent at all. A fan-out needs the owner's explicit yes, with the agent count and a token estimate stated first", and with this session's owner instruction "Everything, NO AGENTS!": the hook layer the owner installed REQUIRES an agent launch at every turn end that the owner's own written rule forbids, and the one cap that is supposed to price agent launches is told not to see it. Nothing reconciles the two - no setting lets a session that the owner told to use no agents end its turn without the review, and the rule file does not mention the reviewer at all (grep -n "review" CLAUDE.md: the word appears only in "A review of one project's guards read fourteen of them"). Also observed live: guard_requests refuses `ls -la` of the store and a `git check-ignore` naming it - reads refused for naming the folder (the bundle test only asserts the Read TOOL is allowed), the same mention-as-access shape as the gate_stamps lead.

**Fix.** Make the reviewer opt-in per session (or off by default) and state it in CLAUDE.md beside the no-agent rule, with its price; or have guard_fanout count it like any other launch so the owner's yes-and-a-price rule applies to it too. One of the two owner rules has to yield in writing.

<a id="f179"></a>

## F179 - Three lock writers disagree: replant.py overwrites the repo lock and never counts its release (the came-and-went detection is blind to every plant run), and mutate2.py writes its lock in the repo's parent where no reader looks

**medium** | correctness | `tools/guard/replant.py:425`

MEASURED (git grep over the tree for every writer of `.mutation-in-flight` and every caller of the lock doors - control: it finds tools/prove_plants.py:316 take_lock and :349 release_lock, the one runner that uses them; and scratchpad/lockwhere.py, which evaluates each writer's own path expression and each reader's - control: replant's ROOT is the repository). Three programs write the lock and they disagree about everything. (1) prove_plants goes through bundle_rules.take_lock (exclusive create, signed with a nonce) and release_lock, which calls note_release BEFORE removing the lock - the counter bundle_rules.lock_state reads at the start and end of every judgement so a plant that 'came AND went' inside one judgement reads UNKNOWN (bundle_rules.py:181-200). (2) tools/guard/replant.py - which plants defects straight into the repository's own files, the files bundle_rules judges - writes repo/.mutation-in-flight with mode 'w' (hold, :417-432: not exclusive, so it silently OVERWRITES a lock another run holds) and removes it with os.remove (release, :435-441), never counting the release: `git grep note_release` finds it called only inside bundle_rules. So for every replant plant - 225 of them, each planted, run and restored in turn, the lock removed and rewritten between every pair - the came-and-went detection is blind, which is the exact 'lock read in the gap between two plants' shape tests/test_bundle_action.py AVerdictTakenWhileAMutantCameOrWentIsUnknown documents from the 2026-09-26 incident (a planted text HELD as a command's write, every act in a session stopped on `lint 0 -> 1`); and a replant beside a prove_plants run can delete prove_plants' lock while its plant is still on disk. (3) tools/mutate2.py writes its lock at BASE/.mutation-in-flight where BASE = Path(__file__).resolve().parent.parent.parent - the repository's PARENT (~/.mutation-in-flight here), beside the separate worktree it mutates (BASE/wt/_guardcheck) - while every reader looks in the repository: guard_mutation_read at CLAUDE_PROJECT_DIR (the repository, since settings.json runs $CLAUDE_PROJECT_DIR/.claude/hooks/...), bundle_rules.locked at root, guard_suite_running at the repository and the folders directly under it. mutate2's lock is visible to no reader at all - its own comment (:1061-1065) says the hook 'finds it by reading this' and calls the hook's earlier state 'a consumer with no producer'; it is now a producer with no consumer, left behind when .claude moved from the session folder into the repository.

**Fix.** Route replant and mutate2 through bundle_rules.take_lock/release_lock against the repository root (one door, one place); make writing .mutation-in-flight by any other path refuse at the write - a test that greps the writers of the lock name and allows only bundle_rules.

<a id="f183"></a>

## F183 - commit_gate's test-count rule calls require_build.subject_digest/NothingToDigest, which this tree's (declared not-installed) require_build.py does not define - any commit claiming N tests crashes

**medium** | correctness | `tools/commit_gate.py:327`

MEASURED (scratchpad/countprobe.py: commit_gate imported with this tree's tools/ on the path, its ROOT swapped to a scratch folder holding a recorded run {ran: 12, rc: 0}, exactly as the bundle's own test_an_UNKNOWN_subject_refuses_a_count sets it up; control: a message with no count answers [] without reaching require_build). tools/require_build.py here is CTRMap's own - .claude/bundle-install.json:73-74 declares the bundle's copy not_installed because 'CTRMap's require_build.py is the original' - and it defines neither subject_digest nor NothingToDigest (has subject_digest: False, has NothingToDigest: False). commit_gate.test_counts (:324-330) calls the first and catches the second, so a message claiming 'N tests' while a run is recorded RAISES AttributeError instead of answering. It is wired twice: .githooks/commit-msg runs commit_gate.py after commit_guard.py, and commit_gate.message_problems is the AT_COMMIT rule guard_command_rules asks before git starts. So every commit whose message states a test count dies with a traceback - an honest, correct count included - and CTRMap's own commit_guard.py, which checks the same count against .last-suite-run its own way, is a second implementation of the same rule (README rule 4) with a different notion of subject. The bundle's 13 require_build tests in tests/test_bundle.py fail for the same reason (AttributeError, scratchpad/pysuite.txt).

**Fix.** One require_build: either install the bundle's subject_digest into CTRMap's require_build (it already has tree_digest) or make commit_gate's count rule CTRMap's commit_guard rule, not both; add a test that imports commit_gate with this tree's tools/ and drives test_counts once.

<a id="f184"></a>

## F184 - Nothing runs the Python suite (347 tests in tests/): run here, 19 fail - two real defects among them - and no battery, hook or gate would have shown it

**medium** | test-debt | `tests/test_bundle.py:1378`

MEASURED. Run once here: python3 -B -m unittest discover -s tests -> Ran 347 tests, FAILED (failures=6, errors=13), output in scratchpad/pysuite.txt; the tree was hashed before and after (git ls-files -s and git diff digests identical, status unchanged), so the run touched nothing. What runs these 347 tests: nothing. test.ps1 runs Python twice - tools/guard/work_order.py --gate and tools/mutate2.py --selftest (:26-34, :316-320); .githooks/pre-commit runs bundle_install, tiers, timeouts, format_strings, prove_plants --owed; commit_gate drives single test ids for plants only (git grep for unittest over \*.ps1, .githooks and tools finds only prove_plants' per-id runs - control: it does find prove_plants.py:187). The 19 red, by cause: 13 AttributeError on require_build (the bundle tests expect subject_digest/NothingToDigest/tree_manifest/OUTPUT_ROOT/BUILD_TIMEOUT; this tree keeps CTRMap's own require_build - F183); 3 gate-stamp tests whose scratch commit-msg is written 0644 and so never runs on POSIX (F182); ACapturedChildOpensNoWINDOW - 19 captured children in tools/ with no creationflags (scratchpad/consoleprobe.py lists them: tools/guard/commit_guard.py x6, replant.py x2, record_run.py x2, autoplant.py x2, require_build.py, mutate.py, mutate2.py, approve_push.py, work_order.py, tools/hooks/guard_push.py x2) - the console windows the rule exists to stop, on the owner's Windows machine; EveryToolHereIsAskedAtTheActItGuards - 35 tools refuse with no declared act (scratchpad/tiersprobe.py), while tools/tiers.py itself exits 0 by adopting all 35 as 'starting debt', so the test asserts a stricter rule than the ratchet it tests; and one that needs the out-of-tree bundle (environment). A guard suite nobody runs is a red suite nobody sees: two of these are real defects found only by running it.

**Fix.** Run python -m unittest discover -s tests from test.ps1 (or the pre-commit) and record its result beside the Java battery's; fix or ratchet the 19 so the suite starts green.

<a id="f186"></a>

## F186 - Two commit-msg checkers implement the same rules differently; a production change whose subject is not 'fix' and that only mentions the escape marker in prose passes both

**medium** | duplication | `tools/guard/commit_guard.py:408`

MEASURED (scratchpad/twogates.py: both checkers imported, the staged diff stubbed to one production Java file - nothing staged for real; controls: with no marker anywhere commit_guard REFUSES that commit, and commit_gate refuses a 'fix:' subject that touches no guard path). .githooks/commit-msg runs tools/guard/commit_guard.py and then tools/commit_gate.py, and the two implement the same rules from README sections 2 and 14 separately and differently. The measured hole between them: a commit that changes production code, has a subject that does not start with 'fix', and merely MENTIONS the escape marker in a sentence ('drops the old rule that let a No-guard: line of any length through') PASSES BOTH - commit_guard because check_fix_has_a_guard asks `NO_GUARD.lower() in message.lower()` (:408, :430), commit_gate because its is_fix asks only whether the subject starts with 'fix' (is_fix -> False), which is the trigger commit_guard's own docstring (:369-380) measured as reaching 17% of fixes (33 of 213) and replaced. commit_gate's docstring names exactly this marker defect ('EVERY MARKER COUNTS ONLY AT THE START OF A LINE ... the commit adding a rule once exempted itself by explaining it') and fixed it in its own copy only. In commit_guard 11 escapes are matched anywhere in the message with no minimum reason - No-guard, No-refusal (:446), No-review (:665), No-test (:719), No-coupling (:786), Owner-tested (:818), Control (:844), Cheapest-evasion (:869), Menu-item (:916), Undo and No-undo (:956); only Deferred-gap and Reviewed are anchored at a line start. The other divergences, read: a git that cannot be asked is PASS in commit_guard (staged_files -> [] -> 'nothing to judge; do not block', :411) and REFUSE in commit_gate ('UNKNOWN is not none'); the guard-path lists differ (src/ctrmap/tests + .claude/hooks vs tests/); Deferred-gap needs 60 characters in one and 80 in the other and is triggered by two different vocabularies (_HOLE_PHRASES plus an open queue item vs guard_promise admissions); the test-count rule digests the tree two different ways (source_digest vs require_build.subject_digest, which does not exist here - F183), so even without the crash the two could never agree on what a recorded run measured. ADDENDUM: the project's own plant ledger names this defect. tools/guard/plants.json:1878-1888, 'the-commit-gate-reads-the-subject-line-again', puts back `return subject_of(message).lower().startswith("fix")` into commit_guard and calls it 'the one-line edit that makes the whole gate decorative again' - and that is, near word for word, tools/commit_gate.py's is_fix (:131-133), shipping live in the hook that runs right after it. The planted defect is guarded in one copy and installed in the other. ADDENDUM - A FIX CLAIMED IN HISTORY THAT THE DIFF NEVER MADE. TileEditFormGuardsTest records it in its own javadoc: 'The twenty-two-defect commit lists, among the defects it says it fixed, "Clicking the already-selected tile unlocked the inspector without re-showing anything." That commit does not touch Selector and nothing since has' (TileEditFormGuardsTest.java:133-141). Both commit-msg checkers police the test-count arithmetic and whether a production change names its guard. Neither can ask whether each defect a message says it fixed corresponds to a changed line, so a commit can list a fix that is not in its diff and every gate passes.

**Fix.** One implementation per rule, asked once: keep commit_guard's diff-based trigger and commit_gate's start-of-line markers, delete the other copy of each rule; anchor every escape marker at a line start with a minimum reason, as Deferred-gap already is.

<a id="f191"></a>

## F191 - The mutation sweep only measures lines added by 23 sf/\* fix merges (last on 2026-09-03): 33 of 265 production modules, and nothing changed since can ever enter it

**medium** | test-debt | `tools/guard/whole_tree_baseline.json:8`

MEASURED (scratchpad/mutcover.py, counted from the tree rather than read from whole_tree_baseline.json; control: the reader finds the record's own keys; a second per-name grep with GARC and Zone as positive controls, both found). The committed mutation baseline, mutation_baseline.json, scores 33 of the 265 tracked production modules (12.5%). whole_tree_baseline.json records the gap as missing 385 of 418 against a ceiling of 385 - a number that counts the 153 test files too, so it reads as 'a record that covers little of everything' rather than 'seven eighths of the program has no mutation score'. Not scored, among others: every class that WRITES the owner's game data outside GARC itself - ZoneAppender, ZoneCloner, ZoneRemover, ZoneLinker, TextForker, MapResizer, ModDeployer, WorkspaceSession, GarcRebuilder, AbstractGamefreakContainer, LZ11, GFMessageFile, EncounterTable, GameFiles. The rule 'EXTRACTION IS NOT TESTING' is argued from a mutation score (94.7% vs 37.3%); the sweep that produces such scores has looked at an eighth of the program, and the ratchet holding the gap only forbids it widening. ADDENDUM - WHY, BY CONSTRUCTION (read tools/mutate2.py:78-145, 242-274, 426-481; measured with git). The sweep does not choose modules: its subject is the lines ADDED by merges whose message is "Merge branch 'sf/&lt;cluster>'" into master, for the clusters a hand-kept CLUSTERS table names. There are 23 such merges on master's first-parent history (git log --merges --first-parent --grep), the last one 3025b34 'Merge branch sf/talkerfix' on 2026-09-03. Since then master has taken 202 first-parent commits and 221 production .java files have changed (git diff --name-only 3025b34 master, tests excluded) - none of which can ever enter the mutation baseline, because none arrived as an sf/ merge. So the 12.5% is not a coverage gap the sweep is closing: it is the size of one September fix campaign, and every line written since is outside the measurement by definition.

**Fix.** Extend the sweep's module list, highest-risk first (the game-data writers named), and record the ceiling against production modules only so the number says what fraction of the program is unscored.

<a id="f194"></a>

## F194 - The initComponents guard's documented shell half does not exist: sed -i or a script rewriting a form's generated block, and an edits[] batch, are all allowed

**medium** | silent-failure | `.claude/hooks/guard_generated_code.py:76`

MEASURED (scratchpad/genprobe.py: .claude/hooks/guard_generated_code.decide asked about AboutDialog.java, one of the 18 tracked .java files with a GEN-BEGIN:initComponents region; nothing edited or run; control: an Edit whose old_string is a line inside initComponents is REFUSED). The docstring promises two things (:15-16): 'a Write or an Edit whose target text falls inside the generated region ... and a shell command that rewrites such a file in place'. The second does not exist: decide() (:76-104) looks only at tool_input.file_path with old_string/content/new_string, and has no shell branch at all - a sed -i over the form's .java and a python script handed the file are both ALLOWED. An edits[] list (the MultiEdit shape, which the bundle's guard_write_rules already reads) carrying the same inside line is ALLOWED too. The file is read at the path as given, relative to the hook process's working directory rather than the payload's cwd. The rule it enforces is the owner's 'Never hand-edit a NetBeans initComponents block'; rule_map.json maps that clause to this file and reports it enforced; no other tracked guard names GEN-BEGIN (git grep over tools and .claude finds only this hook and its tools/hooks copy).

**Fix.** Ask the written paths of a command (shellin.writes_something) and every entry of edits[] the same question; better, make it an AT_WRITE_CHANGE rule so guard_held_writes judges command writes too.

<a id="f198"></a>

## F198 - The convergence stop rule is computed twice and the copy asked at the act judges only the newest scope: one round recorded under a new scope name silences the launch refusal while convergence.py check still says STOP

**medium** | correctness | `.claude/bundle-hooks/guard_agent_campaign.py:83`

MEASURED (scratchpad/convprobe.py points both readers at a scratch log by replacing their path constants; the repository log is never touched, and docs/audit-convergence.json does not exist here). The stopping rule from 2026-08-17 (thirteen flat audit rounds) is computed twice. tools/guard/convergence.py check (:80-106) judges EVERY scope. .claude/bundle-hooks/guard_agent_campaign.py trend_problem (:76-95), the only copy asked at the act (it refuses an agent launch), judges only the scope whose latest row has the newest 'recorded' time. CONTROL: one scope at 10, 10, 10 gives check exit 1 and the hook REFUSES. CASE: the same log plus ONE round recorded under any new scope name gives check exit 1 (STOP AND REPORT) while the hook ALLOWS the launch, because the latest scope has fewer than three rounds. The cheapest way past the at-act guard is to rename the campaign, the evasion that guard exists to prevent. The two copies also differ in what they tolerate: the hook catches malformed rows and refuses in words, while check raises KeyError on a row with no scope. convergence.py itself is not counted as a guard by tools/tiers.py: it says 'NOT CONVERGING', not the word SAYS_NO looks for (scratchpad/saysno.py: 90 files counted as guards; 3 stop-capable files are not counted, 2 of them excused in NOT_GUARDS and convergence.py not excused).

**Fix.** One trend function in one module, imported by both the hook and convergence.py; it judges every scope (a stale non-converging scope stays a refusal until a falling round is recorded for it, or it is closed with a recorded reason). Plant: add a fresh one-round scope after a flat one and assert the hook refuses.

<a id="f204"></a>

## F204 - Measured from bytecode: 73 production methods have no live caller anywhere, 114 more are kept alive only by tests, and 217 fields are never read - all invisible to DuplicateWorkTest, whose reachability rule stops at classes

**medium** | dead-code | `src/ctrmap/tests/DuplicateWorkTest.java:224`

MEASURED FROM BYTECODE. The whole tree was compiled into the scratchpad (javac --release 8, 418 sources, 966 classes). scratchpad/jtool/Decl.java lists every declared method and whether it overrides a supertype. scratchpad/m_dead.py runs a reachability fixed point over invoke\* edges. Roots: every test-class method, main(String[]), constructors and static initialisers, overrides, enum values/valueOf, BootstrapMethods method handles (lambdas and Foo::bar), and any method whose name appears as a string constant (reflection). Edges are matched by name+descriptor regardless of owner, so the count can only under-report. CONTROLS: H3DModel.adjustBoneVerticesToMatrix, PawnDisassembler.isSubCorrupted, and the cliffQuads -> slopedQuad -> cliffQuad chain all come out dead; GARC.getDecompressedEntry comes out live. Fields: TileMapPanel.cm2dOverlayImage and PaintForm.holderUnused come out never-read and GARC.entries does not. ItemEffectLabels.idsByEffect does NOT, because filling it reads it (idsByEffect.get(eff).add(id)), so 'never read' here means no get\* instruction at all. RESULT, production only (4,693 methods incl. constructors): 73 methods are dead (no live caller anywhere, tests included) in 39 classes, and 114 more are live ONLY because a test calls them, in 43 classes. Dead examples: ItemData's 8 setters + setEv + u16 + sellPrice (11); PICACommandReader's 10 getters; DressUpIndex.categoryOfPart/designsOfItem/itemsInCategory/texturesOfPart; the GRCollisionBounds/GRCollisionFile legacy writer; PaintedCliffMesh cliffQuads/slopedQuad/cliffQuad/flatQuad/stripMixed; Workspace.createWorkspace; AbstractGamefreakContainer.whyNot(); MsgWrapperInjector.canInject and SignWrapperInjector.canInject; GfEnvPicker.pick(Dialog, ...) (the live calls use the Window overload); CameraEditForm.getFloatFromField; NPCEditForm.modelWithPreview; ZoneLoadingPanel.browser; ZonePreviewPane.hasGame; ModelPicker.hasModels; TerrainCatalog.canImport; TerrainTextures.image/any; ResizeableMatrix.resizeMatrix; ZoneLimitPatch.totalZoneSlots; ZoneResource.forkable. Test-only: ItemData 28, PartyParam 7, RecordSchema 6, DressUpIndex 5, WorldAnim 5, ZoneBrowserPane 4. FIELDS: 217 production fields are never read by any get\* (synthetic this$N excluded). 145 of them are in the BCH/H3D port (H3DMaterial 61, BCHContentHeader 38, H3DModel 14, BCHHeader 10), parsed for completeness. 21 are in the UI, e.g. the reg/livePreviews pairs in five wizard forms (ChallengeForm, GiveBpForm, GiverForm, TalkerForm, TrainerForm), TileMapPanel.tilemapImage/cm2dOverlayImage, WorldEditorToolbar.inspector, Builder.BuilderFile.raw/bchData, CM3DInputManager.originRotateZ and TileEditForm.pickSetTool. DuplicateWorkTest's reachability rule is class-level, so none of these 73 + 114 + 217 is visible to any suite; the old census found about a dozen by hand. ADDENDUM - 73 IS A LOWER BOUND, BY CONSTRUCTION. m_dead.py makes a method live when ANY invoke anywhere names the same method name and descriptor, ignoring the owner, so it never calls a live method dead. The cost is that a dead method sharing a common signature with a live one is counted live. Known example: GRCollisionMesh.write(LittleEndianDataOutputStream) has no invoke of it in the whole bytecode (javap: the only reference to the legacy chain is its own call into Triangle.write, javap_all.txt:268265). It survives because a dozen live record writers share 'write:(Lctrmap/LittleEndianDataOutputStream;)V'. With it, Triangle.write and its printStackTrace-only catch (Triangle.java:44-55) are the last link of the proven-invalid legacy collision serializer (F059). An owner-aware pass (resolve each invoke's owner up its class hierarchy) would tighten the number; 73 + 114 test-only is what the conservative rule can prove.

**Fix.** Delete the 73 dead methods and the app-side never-read fields; for the 114 test-only methods either give them a production caller or move them into the test that needs them. Close the class by moving DuplicateWorkTest to member granularity over bytecode (the m_dead.py fixed point: roots = mains, overrides, handles, reflection names; edges = invoke name+descriptor), ratcheted with a ceiling that only falls and planted with one dead private method.

<a id="f205"></a>

## F205 - Per-game counts (536 zones, 949 trainers, 775/776 items, 622 moves, 721 species, 279 classes, NPC cap 31, 0x38 header row) live nowhere in gamedef and are re-typed as literals beside their own named constants in 15+ sites

**medium** | architecture | `src/ctrmap/humaninterface/TrainerEditDialog.java:330`

MEASURED (scratchpad/m_literals.py over production .java with comments and string literals blanked; CONTROLS 536 in CtrmapMainframe, 0x38 in ZoneLoadingPanel and 31 in NPCEditForm all found; then grep -w per literal with comment lines excluded, read site by site). GameProfile.java:6-9 says no game-detected constant may live outside ctrmap.gamedef. ctrmap.gamedef holds NONE of these counts as code. Each is defined once somewhere else, and then re-typed as a literal beside its own name. ZONES 536: ZoneLimitPatch.BASE_ZONES = 536 (:47), yet CtrmapMainframe.java:2520 'Integer.parseInt(f.getName()) >= 536' and :2529 clearForkDeclinesFrom(536), BuildingHarvester.java:77 and TerrainDonorHarvester.java:58 'Math.min(536, zo.length - 2)', plus 9 user-facing strings that say '536' (CtrmapMainframe 889, 1107, 2500, 2630-2631; ZoneLoadingPanel 79, 229, 1822; ZoneAppender 227; ZoneRemover 48). TRAINERS 949: NpcTemplates.TRAINER_ID_MAX = 949 (:90), re-typed at ChallengeForm.java:56 and GauntletScriptWizard.java:65-66 (lead 92) and in TrainerEditDialog's prompt (:73). ITEMS 775/776: NpcTemplates.ITEM_ID_MAX = 775 (:107), re-typed at TrainerEditDialog.java:335 and MaisonEditDialog.java:394/411; ItemIconTable.COUNT = 776 (:40) and an independent 776 fallback at PokeData.java:220. MOVES 622 at PokeData.java:210 (fallback). SPECIES 721 at TrainerEditDialog.java:330 and MaisonEditDialog.java:391/408. TRAINER CLASSES 279 at TrainerEditDialog.java:147. NPC REGISTRY CAP 31: NPCRegistry.MAX_ENTRIES = 31, re-typed at NPCEditForm.java:309 and NPCRegistryEditor.java:377 (leads 128, census M.57). ZONE-HEADER ROW 0x38: four named definitions (GeometryForker.MASTER_ROW, ZoneCloner.ZONE_HEADER_SIZE, ZoneLimitPatch.MASTER_ROW, WorkspaceIntegrity.MASTER_ROW) and 9 raw uses (ZoneLoadingPanel.java:462/463/467, BchTexturePack.java:396, GroundMaterialAudit.java:72-74, TerrainHarvester.java:428-429). The other 0x38s in the tree are BCH offsets and Pawn opcodes, read and excluded. X and Y have different trainer, item and zone counts, so every one of these is an ORAS fact in code that a second game would silently get wrong. The literal re-typed beside an existing named constant is the shape that lets a constant be corrected in one place and not the others. SourceSeamTest's seam scan looks only for archive paths (census M.83), so nothing refuses any of it.

**Fix.** Move each count onto GameProfile (zoneCount, trainerCount, itemCount, moveCount, speciesCount, trainerClassCount, npcRegistryCap, zoneHeaderStride), delete the scattered constants and literals, and widen SourceSeamTest so a literal equal to any profile count outside gamedef is refused (with an argued exception table), planted with one re-typed 949.

<a id="f206"></a>

## F206 - Two previews start one raw thread per list selection and never cancel superseded work - the building palette queues full prefab cuts and region builds behind a static lock on non-daemon threads, and the zone preview lets two workers rebuild one TileMapPanel at once

**medium** | efficiency | `src/ctrmap/humaninterface/BuildingPaletteDialog.java:277`

MEASURED (grep over production .java for every thread the editor makes; every site read). The tree starts raw threads in 5 places and has no executor anywhere (0 hits for Executor/ExecutorService); 8 files use SwingWorker. Three of the five call setDaemon(true) (UpdateUI.java:52, CM3DInputManager.java:167, ZonePreviewPane.java:180); ESPICAControl.java:143 and BuildingPaletteDialog.java:277 do not. Two of the five start ONE THREAD PER LIST SELECTION and never cancel one. (1) BuildingPaletteDialog.java:266-319: each selection does 'new Thread(() -> { cachedPrefab(e); PaintedRegionBuilder.build(... 40x40 grass ...); p.stampGeometry(...); donorTextures(...) }, "building-preview").start()'. The newest-wins check 'if (seq != previewSeq[0]) return' runs inside invokeLater AFTER all the work, so arrowing through the 3,583-entry catalogue runs every superseded cut and build to completion. They queue on the static synchronized cachedPrefab/donorTextures monitor, the threads are non-daemon, and the grass base is identical every time (census M.69, L.51). (2) ZonePreviewPane.java:129-182: one daemon thread per selection; 'seq' is a plain final int[] (:48) written on the EDT (:141) and read by every worker (:157, :240) with no volatile or lock. The check-then-act at :157 is followed by draw() -> mapView().loadRegions(...) (:203-205) on the ONE cached TileMapPanel. mapView() is synchronized only around constructing the panel (:266), and TileMapPanel.loadRegions (:521) is not synchronized, so two workers that both pass :157 rebuild the same panel's matrix, arrays and models at once (census M.14). Neither site can be bounded by a suite today: there is no seam to hand in an executor. The ESPICA worker also writes Swing labels off the EDT (census M.92).

**Fix.** One single-slot worker per preview (a SingleThreadExecutor or a SwingWorker cancelled on the next selection), marked daemon, with the sequence check BEFORE the work and an AtomicInteger sequence; build the palette grass base once per dialog; make the preview draw on a panel the worker owns, or serialise draw(). A battery check that production code constructs no raw Thread outside one named helper closes the class.

<a id="f208"></a>

## F208 - After one ZoneData entry fails to open, no zone can be opened at all: the half-filled zone table keeps null slots that LoadedZone.at documents, and the warp form (plus four other readers) dereferences them - measured NPE at WarpEditForm.java:36 on every zone open

**medium** | silent-failure | `src/ctrmap/humaninterface/WarpEditForm.java:36`

MEASURED BY RUNNING IT (scratchpad/jtool/NullSlotProbe.java against the tree compiled into the scratchpad, headless). LoadedZone.at(i) is documented to return null 'for a slot the rebuild could not fill' (LoadedZone.java:45-49). That state is produced by ZoneLoadingPanel.loadEverything: it sets loadedZone.table(new Zone[totalZones]) FIRST (:243) and fills it slot by slot (:244-247). 'new ZO(...)' throws (AbstractGamefreakContainer.open refuses a container that fails verify), so one unreadable ZoneData entry leaves every later slot null; done() reports the error (:186-198) and the half-filled table stays. CONTROL: WarpEditForm.loadFromEntities over an EMPTY table returns normally. RESULT: over a table with unfilled slots it throws 'NullPointerException: Cannot read field "header" because the return value of "ctrmap.LoadedZone.at(int)" is null at WarpEditForm.loadFromEntities(WarpEditForm.java:36)'. That call is in the warp form's ZoneView.show (CtrmapMainframe.java:416-417), and ZoneEditors.show hands every opened zone to every view with no catch (ZoneEditors.java:65-69). So after one unreadable entry, the zones the list still shows (0..k-1) cannot be opened either: the open fails on an NPE in the warp form's target-zone dropdown fill, which has nothing to do with the zone opened. Census of the reads (grep of production for .at(...) results): guarded at AreaForkPrompt:138, PropEditForm:688 and ZoneLoadingPanel:1610 (and :147/:1624 inside those filters). UNGUARDED at WarpEditForm.java:36 and :72 (targetName checks only '>= count()'), ZoneLoadingPanel.java:1698 (Clone-zone picker) and :1811 (Add-zones picker), and MatrixEditForm.java:84, whose catch (:94-) now empties the whole matrix form on the same NPE (its own comment describes exactly this table state). One contract, nine readers, five that forgot it.

**Fix.** Make the null unrepresentable: LoadedZone.table/replace must refuse a table with holes (loadEverything builds the array locally and installs it only when complete, or installs a table that holds a typed UnreadableZone placeholder naming the error), and at(i) returns non-null or throws naming the slot. Then the five unguarded readers cannot be wrong; plant a null slot in a suite that opens a zone.

<a id="f209"></a>

## F209 - Undo/redo exists in 4 of the 29 editors that write data, as four unrelated mechanisms (a static tile deque, two private snapshot stacks, RecordEditPanel); entity, registry, header, Game Data, text and drag-tool edits have none, and Ctrl+Z always routes to the tile history

**medium** | unfinished | `src/ctrmap/humaninterface/TileUndo.java:30`

MEASURED (scratchpad/m_undo.py). The subject is derived: every ctrmap/humaninterface class that writes data (applyToZone, storeFile, Files.write, addPersist, a modified/dirty flag, .write(), saveEntry). The first derivation missed PaintForm, which writes through TilePainterForm.applyToZone; the control caught it and the derivation was widened. CONTROLS: PaintForm, GeoEditForm and TileEditForm read HAS UNDO and CollEditPanel reads NONE. RESULT: 29 editor classes. After reading each 'undo' mention (PropEditForm's is a comment; MaisonClassListDialog's 'Restore retail row' is a revert; ExtrasPanel's three are 'It cannot be undone' warnings; TileMapPanel only clears TileUndo), exactly 4 implement undo: PaintForm (its own ArrayDeque&lt;Object[]> snapshot stack, cap 100, :64-588), GeoEditForm (its own Deque&lt;Snapshot>), TileEditForm via TileUndo (a STATIC process-wide undo/redo deque, :30), and ItemEditDialog via RecordEditPanel.canUndo/undo/redo. That is four separate undo mechanisms for four editors. The other 25 have none, including every entity editor (NPCEditForm, WarpEditForm, TriggerEditForm, CameraEditForm, PropEditForm, CollEditPanel, MatrixEditForm), the registry editors (ADPropRegistryEditor, NPCRegistryEditor), the zone header (ZoneLoadingPanel), the Game Data editors (TrainerEditDialog, EncounterEditDialog, MaisonEditDialog, ShopEditDialog), TextEditor, AreaLightingDialog, the raw Builder, TilePainterForm's apply, and the four drag tools (NPCTool, PropTool, TriggerTool, WarpTool). One keystroke exists for all of it: CtrmapMainframe.java:745 binds 'control Z' to tileUndo for the whole World Editor tab (census M.145), so Ctrl+Z after moving an NPC, a prop or a warp reverts some earlier tile edit instead. CLAUDE.md section 2 states the rule outright: 'Undo/redo everywhere.'

**Fix.** One undo model the tools and forms share (an UndoableEdit-style command stack per open document, owned by the session, not static), with Ctrl+Z/Ctrl+Y routed to the active editor; migrate the four existing mechanisms onto it, then the entity forms and drag tools. A battery rule that every class the m_undo.py derivation calls an editor registers with the stack makes a new editor without undo fail by name.

<a id="f210"></a>

## F210 - Six format records override equals with no hashCode, four over a hand-picked field subset; those equals are the editors change detectors and their identity lookups - measured: two New camera entries are equal and indexOf(second)==0, so editing the second overwrites the first

**medium** | data-integrity | `src/ctrmap/humaninterface/CameraEditForm.java:242`

MEASURED FROM BYTECODE AND BY RUNNING IT. scratchpad/m_equals.py: every production class that overrides equals(Object), its instance fields, and which of them its equals reads (getfield inside the equals body). CONTROL: ADPropRegistryEntry's u2 shows as not compared. RESULT: 6 format records override equals, and NONE declares hashCode (the census found one). Four compare a hand-picked subset: CameraData skips unknownBytes/unknownInt1/unknownFFFF (3 of 19), NPCRegistryEntry skips u5/uD/uE/u10/u12/u13/u16/u17 (8 of 22), ADPropRegistryEntry skips u2 and returns true for null (F084), ZoneEntities.NPC skips xFlags/yFlags/ground and the drag accumulators (5 of 28). CameraCoordinates compares all 7 but rounds them (F123). These equals are the CHANGE DETECTORS of the editors: 'if (!e2.equals(e))' at ADPropRegistryEditor.java:154 and NPCRegistryEditor.java:139, '!cam.equals(cam2)' at CameraEditForm.java:239, '!npc2.equals(npc)' at NPCEditForm.java:380. So a change confined to an uncompared field is invisible. They are also IDENTITY LOOKUPS: CameraEditForm.java:242 'f.camData.set(f.camData.indexOf(cam), cam2)' and NPCEditForm.java:386 'int idx = e.npcs.indexOf(npc)'. scratchpad/jtool/EqualsProbe.java, CONTROL indexOf of a one-entry table = 0. RESULT: 'new CameraData().equals(new CameraData())' is TRUE; with two of them in the table 'indexOf(second)' answers 0, not 1; and the two hash codes differ (1829164700 / 2018699554), which breaks the equals/hashCode contract. So after the camera form's 'New entry' twice, editing the second entry writes the edit over the FIRST and leaves the second untouched (census H.25, now reproduced). The NPC form has the same lookup; the uid in NPC equality makes a collision rarer, not impossible.

**Fix.** Editors address records by the index they show (camIndex, the NPC list index), never indexOf; replace equals-as-change-detector with a byte comparison of the record serialised (write() before vs after), which cannot skip a field; then delete the subset equals or make them full value equals with hashCode. A suite that edits the second of two identical entries and asserts the first is untouched plants it.

<a id="f211"></a>

## F211 - SilentCatchTest counts 118 swallowed failures and is blind to 99 more: catch blocks that answer a default (return null/-1/empty, continue) after or instead of a log line are outside both its patterns, so the catches that turn could-not-read into absent are unratcheted

**medium** | test-debt | `src/ctrmap/tests/SilentCatchTest.java:80`

MEASURED (scratchpad/m_silentcatch.py). STEP 1, CONTROL: SilentCatchTest's own two patterns over the same files reproduce its number exactly: empty 55 + log-only 63 = 118, its CEILING. STEP 2: every production catch block parsed brace-aware, in comment/literal-blanked text. 436 catch blocks: 54 empty, 64 log-only, 99 SUBSTITUTE, 219 other. SUBSTITUTE means no throw, no Ui/dialog/status report and no rethrow; the block answers a default ('return null/false/-1/0/""/new X[0]/new ArrayList&lt;>()', continue/break, or assigns a literal), possibly after a log line. CONTROLS: TrainerEditDialog text() 'catch (Exception ex) { return new String[0]; }', InteriorWirer.zoneArea 'return -1' and BchTexturePack 'return null; //never block an edit' are all classified SUBSTITUTE. So 217 catch blocks swallow a failure and the ratchet counts 118 of them. It cannot see the other 99, by construction: its CATCH pattern requires a body with no nested braces, and LOG_ONLY requires the body to be exactly one log statement, so 'log; return null;' and a bare 'return -1;' are both outside it. These are the catches that DECIDE something: they turn 'could not read' into 'absent', 'empty', 'not shared', 'no names' or 'zone -1'. That is CLAUDE.md's 'A QUERY THAT CANNOT READ ITS SUBJECT MUST NOT REPORT IT ABSENT', and several are filed individually (F065, F102, F112, census M.121/M.135/L.56). Spread: Updater 6, TerrainHarvester 6, NPCEditForm 5, UpdateChecker 3, MaisonEditDialog 3, MaisonClassListDialog 3, GfEnvPicker 3, and 50 more files. Some SUBSTITUTE blocks are legitimate parse probes (e.g. PawnInstruction.java:223 'return false' on a NumberFormatException is the method's documented answer), so 99 bounds the defect count from above. The point is that none of the 99 is under any ceiling, and the class can grow without anything noticing. The census's own M.82 found SilentCatchTest's second check inverted, and that check is unchanged. ADDENDUM - THREE OF THE 99 ARE SAFETY GUARDS, AND ALL THREE FAIL OPEN. (1) AreaForkPrompt.ensurePrivate, the 'this area is shared, fork it first?' question in front of prop, texture and atmosphere edits: 'catch (Exception ex) { return unforked(...); //cannot tell - let the edit proceed as before }' (AreaForkPrompt.java:67-71; census M.121). (2) BchTexturePack.zonesUsingArea, the guard against texture carries changing other zones' maps (F065). (3) NPCEditForm.talkerMessageProblem, the 'this NPC will soft-lock the game' warning, which is 'defensive: any lookup failure returns null rather than a false alarm' with six 'return null' exits and a final 'catch (Exception ex) { return null; }' (NPCEditForm.java:544-575; shown at :391-398). Each guard was written to stop a specific damage: a shared area edited, other maps changed, a game that freezes on talk. Each answers 'safe' when it cannot read the thing it guards, the opposite of CLAUDE.md's 'Uncertainty is ALIVE, PRESENT, UNKNOWN - never absent'. A fourth answer, 'could not check - proceed anyway?', is missing from all three.

**Fix.** Parse catch bodies brace-aware and count the SUBSTITUTE shape too (m_silentcatch.py classification), with an argued allowlist for documented parse probes, ceiling at the measured total, planted with a log-then-return-null catch; and fix the inverted worst check (census M.82) in the same change.

<a id="f213"></a>

## F213 - Every wired hook entry point (both PreToolUse stacks, both Stop hooks, PostToolUse) allows the act when it cannot parse its payload - measured 15 of 15 silent exit-0 runs on garbage, empty and truncated input, while the same hooks refuse the controls

**medium** | silent-failure | `.claude/hooks/guard_all.py:305`

MEASURED BY RUNNING THEM (scratchpad/m_hookfeed.py, against a scratch 'git clone --no-hardlinks' of the repository with CLAUDE_PROJECT_DIR pointed at the clone; the repository's git status was identical before and after). All five entry points .claude/settings.json wires were fed three inputs they cannot read: 'this is not json', '{}', and a JSON payload cut mid-command. CONTROLS: a harmless Bash 'ls' is allowed by both PreToolUse stacks. 'git push origin master' is refused by guard_all.py (JSON permissionDecision 'deny') and 'git commit --no-verify -m x' by bundle-hooks/dispatch.py (exit 2), so both stacks can say no. RESULT: all 15 runs (guard_all, dispatch for PreToolUse, guard_unfinished and dispatch for Stop, after_rules for PostToolUse) exit 0 with nothing said. The code says it on purpose: guard_all.py:304-306 'except Exception: sys.exit(0) # never break a turn on a parse failure'; dispatch.py:272-278 'Never wedge a turn over a malformed hook payload. return 0'; guard_unfinished.py:151-153; after_rules.py:174-180, which also returns 0 when the repo root cannot be found (lead 228). A second run (m_hookshape.py) showed the stacks DO trigger on the shape of a command whatever tool carries it (Bash, PowerShell, an MCP tool, Task), so the gap is not the tool list. It is the read: when the hook cannot read its subject, both stacks report the act as clean. CLAUDE.md names this twice: 'A QUERY THAT CANNOT READ ITS SUBJECT MUST NOT REPORT IT ABSENT' and 'AN UNMEASURABLE QUANTITY IS NOT A SMALL ONE. Refuse it'. A realistic trigger is already measured: F177, where one character outside cp1252 makes guard_all's json.load raise and every project guard is skipped for that call. A harness payload change is another (a renamed field reads as '{}').

**Fix.** Refuse in words when the payload cannot be read (exit 2 naming the parse error and the hook), as CLAUDE.md requires; keep a single, logged, env-var override for a known harness break. Decode stdin as UTF-8 in guard_all (closes F177 too). A suite that feeds each wired entry point garbage and asserts a refusal makes the class impossible to reintroduce.

<a id="f214"></a>

## F214 - 32 of 37 production packages form one dependency cycle (30 mutual pairs); the format layer reaches up only through the two byte-stream classes living in the root package, MapObject from the UI package, one BchTexturePack->AreaForker call and JOGL rendering inside 13 format classes

**medium** | architecture | `src/ctrmap/LittleEndianDataInputStream.java:1`

MEASURED FROM BYTECODE (jdeps -verbose:package and -verbose:class over the tree compiled into the scratchpad; scratchpad/m_pkggraph.py does a Tarjan SCC over production packages, ctrmap.tests excluded. CONTROLS: the edge ctrmap.formats.garc -> ctrmap exists; ctrmap.gamedef does not reach ctrmap.humaninterface). RESULT: 37 production packages with 195 internal edges, 30 mutually dependent pairs, and ONE strongly connected component holding 32 of the 37. Every package but five depends, transitively, on every other: the application root, all of humaninterface (and .builder, .pokepick, .tools), setup, update, vault, util, resources, and 22 of the 24 format packages. The format layer reaches upward through exactly four things, measured at class level. (1) The two byte-stream classes sit in the root package 'ctrmap', beside the window and the Workspace global: 30 format classes -> ctrmap.LittleEndianDataInputStream and 25 -> ctrmap.LittleEndianDataOutputStream, 13 of 24 format packages (census M.17). (2) ctrmap.humaninterface.MapObject, implemented by formats.propdata.GRProp and formats.zone.ZoneEntities$NPC (lead 105). (3) One call, formats.h3d.BchTexturePack.java:399 -> ctrmap.AreaForker.zonesUsingArea, a format class asking an application service. (4) JOGL: 13 format classes in 5 format packages render themselves (CMVD, Triangle, GRCollisionFile/Mesh, BCHFile, H3DModel, H3DSkeleton, H3DMaterial), so a byte format cannot be loaded without OpenGL on the classpath (lead 58). Nothing refuses a new edge: SourceSeamTest's noFormatClassReachesAboveItself is a member allowlist (PURE_UTILS / PURE_UTILS_EDGES, census M.17), and no suite measures the package graph. CLAUDE.md's 'DETANGLE, DO NOT RELOCATE' asks for exactly this count (coupling, not line count), and the tree has none.

**Fix.** Move LittleEndianData{In,Out}putStream to ctrmap.util and MapObject to a neutral package, hand BchTexturePack its sharer answer instead of calling AreaForker, move the render() methods out of the format classes into the UI renderers; then add a bytecode suite that computes the package SCC and refuses any cycle that includes a ctrmap.formats package (ceiling on the SCC size, falling), planted with one reintroduced edge.

<a id="f217"></a>

## F217 - Five shipped actions say in their own dialogs they are experimental or unproven in-game (Script Editor tab, Battle facility independent battles and grown facilities, Resize map, Import map model .bch, Add zones), and none is in OUTSTANDING.md - the owner in-game test queue lives only in dialog strings

**medium** | unfinished | `src/ctrmap/CtrmapMainframe.java:3224`

MEASURED (git grep over production string literals for EXPERIMENTAL / experimental / unproven / untested / never booted / not yet verified; every hit read in context and traced to its action). Five shipped user actions say, in their own UI, that they are not finished or not proven in the game. (1) The 'Script Editor (experimental)' tab, CtrmapMainframe.java:732. (2) Set up Battle facility, setupFacilityAction (:3003): independent battles '(New - unproven in-game.)' (:3041) and a grown or custom facility 'unproven in-game - test it.' (:3101). (3) Resize map (resizeMapAction, :3116): 'EXPERIMENTAL: multi-region retail maps prove the engine path, but a grown...' (:3142; cf. F020). (4) Import map model (.bch) (importMapModelAction, :3171): 'EXPERIMENTAL - untested on real hardware.' (:3224). (5) Add zones (ZoneLoadingPanel.java:1848-1850): 'FIRST-OF-ITS-KIND ... the in-game boot is unproven - test in Azahar first'. OUTSTANDING.md, which says it holds 'anything known to be broken' and the owner's queue, names none of the five: its 8 open items (:40-206) are all tooling or process, and its Known limitations (:444-) list only the shared zone script and the two unreached formats. TESTZONE.md's test world predates them (F138). So the in-game test queue CLAUDE.md gives the owner ('THE OWNER DOES THE IN-APP AND IN-GAME TESTING') exists only as warnings inside dialogs, where a reader sees one only by clicking the action it guards. Related stale TODOs found by the same scan: GRPropData.java:18 still says '(TODO - injecting textures)' although PropEditForm.offerTextureImport and autoRegisterProp import textures today; the SM/USUM profile TODOs are F136.

**Fix.** Add an In-game verification owed section to OUTSTANDING.md (one line per action: what to try in Azahar, the date it shipped), derive it mechanically from the EXPERIMENTAL/unproven strings (a suite that fails when a production string carries the marker and OUTSTANDING.md does not name the action), and drop each marker when the owner records the in-game result.

<a id="f218"></a>

## F218 - GlobalStateTest ratchets 34 of 186 process-global mutable fields; state derived from the open game is cached in statics with seven different invalidation rules, two never invalidated (the building palette caches survive a repoint, MaisonClassListDialog table names are frozen at class load)

**medium** | architecture | `src/ctrmap/humaninterface/BuildingPaletteDialog.java:46`

MEASURED FROM BYTECODE (scratchpad/m_statics.py over javap -v -p of the scratchpad build; synthetic and enum-constant fields excluded. CONTROLS: TileUndo.undoStack and BuildingPaletteDialog.prefabCache are FINAL-MUTABLE, and CtrmapMainframe carries NONFINAL-PUBLIC fields). RESULT: 186 process-global mutable fields in production. 34 are public static non-final in 4 classes, which is what GlobalStateTest ratchets (F135: ceiling 36). 67 are non-public static non-final in 21 classes, and 85 are static final references to arrays or collections whose contents change, in 37 classes. So the ratchet sees 34 of 186. Some FINAL-MUTABLE arrays are constant tables used as constants (label arrays in NpcMoveCodes and WarpTransitions), so 186 is an upper bound. The ones that matter are the state derived from the OPEN GAME, which the tree invalidates in seven different ways (each read in source). PropDatabase is dropped after every pack (WorkspaceSession.java:1034). PokeData's species/ability/move/item names reload on workspace open (Workspace.java:215) and unload on reset (:400), but not after a pack. TerrainCatalog keys its caches to the GameFiles identity (cachesFor). MoveModelPool keys to the GameFiles identity, and its invalidate() has no production caller (census L.17). BuildingPaletteDialog's prefabCache/donorTexCache are keyed to nothing and survive a workspace repoint (census H.24): a cached cut from game A is what Apply stamps after repointing to game B. MaisonClassListDialog.TABLE_NAMES is a static final array computed once at class load from Workspace.getArchivePath (:39-58), so it shows '?' for the whole session if the class first loads with no workspace, or game A's paths after a repoint (lead 125). The pending\*Overrides maps are drained by the pack (F014). The classes holding the most unratcheted global state: CtrmapMainframe 25, NpcMoveCodes 11, PokeData 10, TerrainCatalog 8, WarpTransitions 8, WorkspaceSession 5, Workspace 4, TileUndo 4.

**Fix.** Hang every game-derived cache off WorkspaceSession (one owner whose lifetime is the open game), so repoint and pack invalidate it by construction; widen GlobalStateTest to non-public static non-final fields and static final collections (with an argued table for constant arrays), ceiling at the measured count, planted with one private static cache.

<a id="f224"></a>

## F224 - Seven user-facing messages send a blocked user to menu paths that do not exist, and the suite written to refuse exactly that passes all seven: it skips a path whose menu is unknown as 'prose', reads 'Run File >' as a menu named 'Run File', and never matches the '->' spelling

**medium** | test-debt | `src/ctrmap/tests/MainframeShapeTest.java:491`

MEASURED (scratchpad/m_menupath.py replays MainframeShapeTest.everyMenuPathAMessageNamesExists, MainframeShapeTest.java:462-512, over all 265 production sources, with the real menu names from CtrmapMainframe.buildMenuBar :858-911. CONTROL: 'Map > Fork area.' is matched and its menu recognised, so it would be CHECKED). HOW THE CHECK WORKS: pattern '(\w[\w ]{1,20}?) > ([A-Z][^\n):]{2,60}?)[.):]', and 'if (!menus.containsKey(menu)) continue; //not a menu path at all, just prose'. RESULT: 26 candidates CHECKED; 33 SKIPPED as prose; 9 menu paths written with '->' never matched at all. Three ways it goes blind. (1) A path into a menu that DOES NOT EXIST is the defect the suite is for, and it is skipped as prose: 'Tools > Import OBJ into map region' (CtrmapMainframe.java:2697, filed as F006). (2) Group 1 is lazy but anchored only at its end, so it starts at the earliest word that fits in 21 characters: 'Run File > Deploy to emulator' gives the menu name 'Run File', 'Now run File > Pack Workspace' gives 'Now run File', and '\nRun Options > Setup wizard' gives 'nRun Options'. All are skipped, so every path written after a verb in a sentence goes unchecked: 9 real menu paths in 9 places, among them SetupWizard.java:334 'with File > Deploy mod', which names an item that does not exist (it is 'Deploy to emulator (mod)...'). (3) The arrow spelling is never matched: 'via Tools -> Empty zone' (:3031, filed as F010); 'Game Data -> Trainers' (:3052, ChallengeForm.java:91; the item is 'Edit trainer (party/battle)...'); 'Game Data -> Facility opponents' (:3079, :3098; the item is 'Edit battle facility opponents...'). So the tree carries 7 user-facing messages that send a blocked user to a menu path that does not exist (2697, 3031, 3052, 3079, 3098, ChallengeForm:91, SetupWizard:334), and the suite passes all 7. MainframeActionGuardsTest.java:573 pins 'Game Data -> Trainers' in a message, which is the history the suite's own javadoc tells about 'Map > Fork area' ('one suite pinned the wording'). Its floor 'checked >= 5' is met by the 26 that happen to start a sentence.

**Fix.** Fix the seven messages (Map > Import OBJ into map region (Blender); Zone > Empty zone; Game Data > Edit trainer; Game Data > Edit battle facility opponents; File > Deploy to emulator) and the MainframeActionGuardsTest pin. Then make the check fail closed: match ' > ' and '->' both, take the menu name as the LAST one-or-two words before the arrow, and REFUSE (not skip) a capitalised word-pair before an arrow that names no menu unless it is on an argued allowlist of prose (comparison operators in code are excluded by reading only string literals). Plant one of each blind shape.

<a id="f225"></a>

## F225 - 'Find reusable zones' and 'Remove added zones' judge safety from the game folder's ZoneData as of the last pack, not the workspace: an unpacked warp into a candidate zone is not counted, and an unpacked warp into an added zone is missing from the dangling-warp warning; an unreadable zone reads as empty, i.e. safest

**medium** | data-integrity | `src/ctrmap/ZoneRepurposeScanner.java:54`

READ IN SOURCE. Two zone-safety scans judge the game from an archive that does not hold the user's edits. WorkspaceSession opens each archive from the GAME folder (archiveFiles: 'new File(gameDir.getPath() + rel)', WorkspaceSession.java:203; openArchives :296-305). Zone edits are saved to the workspace's extracted copies (ws.getWorkspaceFile(ZONE_DATA, i), e.g. ZonePreviewPane.open) and reach that archive only at the next pack. (1) Zone > Find reusable base zones: ZoneRepurposeScanner.scan() (:53-100) reads Workspace.getArchive(ZONE_DATA).getDecompressedEntry(i) for all 536 base zones. A warp the user has added since the last pack, pointing into zone X, is not counted in incoming[X], so X can be listed as safe to overwrite while the user's own unpacked edit leads into it. A zone the user has filled since the last pack still reads as empty, which is tier 0/1, the safest. (2) Zone > Remove added zones computes ZoneRemover.referencesToAdded(zoArc) (CtrmapMainframe.java:2488, ZoneRemover.java:85-110) BEFORE the confirm, and the pack that captures pending edits runs only after it (:2508; the dialog itself says 'The workspace is packed first (pending edits captured)'). So an unpacked warp into an added zone is missing from the WARNING list of warps that will dangle. Neither scan looks at scripts or triggers that name a zone (lead 54). The scanner also turns an unreadable zone into an EMPTY one. ZoneEntities(byte[]) catches IOException, logs it and returns (ZoneEntities.java:95-97, into F219's no-sink), so a truncated entity block yields short NPC/warp/trigger lists, and an empty one is ranked safest, the same shape census H.57 found for a failed NAME lookup. ZoneRemover's loop catches RuntimeException and skips the zone (:108-109, 'the scan is advisory'), so a zone it could not read contributes no dangling warp. All 536 decompressions of the repurpose scan run on the EDT behind a wait cursor (CtrmapMainframe.java:2614-2620), the same freeze census M.70 filed for the atmosphere picker, and any RuntimeException out of scan() leaves the action dead with nothing shown. The scanner also re-spells the zone header's parent-map field inline ('u16(hdr, 0x1C) &amp; 0x3FF', :68), outside ZoneResource/ZoneHeader (lead 55).

**Fix.** Read zones through the session's workspace view (the extracted ZO when present, else the archive entry) in both scans - the same accessor the zone loader uses - or pack (or refuse with pending edits) before scanning; let a zone whose entities could not be read be its own tier ('unreadable - not a candidate') instead of empty, and make ZoneEntities throw like the other readers; run the scan in a SwingWorker. Guard: a suite that stages an unpacked warp into a base zone in a scratch workspace and asserts the scanner no longer lists that zone.

<a id="f232"></a>

## F232 - Three pristine copies (workspace snapshot, Vault, ItemTable baseline) and the verified one feeds only Restore; retail-byte readers use the unverified snapshot through hand-built paths, two of which fall back to the LIVE edited game when it is absent - breaking the snapshot's own 'consumers refuse an absent snapshot' contract

**medium** | data-integrity | `src/ctrmap/humaninterface/BuildingPaletteDialog.java:124`

READ IN SOURCE. The tree keeps three pristine copies with three different guarantees. (1) The workspace snapshot (WorkspaceSession.snapshotOriginals, :856-905; folder originalSnapshotDir(), :726): only the MODDABLE archives, inside the workspace, unverified. (2) The Vault (ctrmap.vault.Vault): the whole dump, outside every workspace, per-file digests, single-file restore. (3) ItemTable's own per-archive baseline for ITEM_DATA (ItemTable.baselineArchive, :368-411; ModDeployer.MODDABLE_IN_PLACE). The Vault's class javadoc says it exists because the snapshot 'is not enough, in four measured ways' (Vault.java:7-21: a 161 MB subset, nothing restores from it, it dies with the workspace, nothing verifies it). Yet the verified copy feeds exactly one consumer, Options > Restore (CtrmapMainframe.java:2413-2415). Every reader that needs RETAIL bytes, for donor cuts, donor textures, retail atmosphere values or deploy diffs, still reads the unverified in-workspace snapshot. It does so through two doors. The GameFiles.pristine() seam is used by BuildingCatalog (:201, :239) and MaisonPoolGuard (:187). Three hand-built paths, 'originalSnapshotDir().getAbsolutePath() + rel', bypass it: BuildingPaletteDialog.donorTextures (:122-123), GfEnvPicker.pristineOrLive (:276-282) and ModDeployer.deploy (:160). The snapshot's own contract reads 'Consumers refuse to work from an absent snapshot (BuildingCatalog.pristineRegion); none of them can detect a present-but-wrong one' (WorkspaceSession.java:876-878). Two of the three hand-built paths break it. BuildingPaletteDialog.donorTextures falls back to 'Workspace.GAMEDIR_PATH + rel' when the snapshot file is missing (:124-126), and GfEnvPicker.pristineOrLive falls back to the live archive ('Pristine snapshot GARC when available (true retail values), else the live one'; its catch is 'catch (Exception ignore)'). With no snapshot, the donor textures stamped into a new building and the 'retail' atmosphere values come from the user's edited game, which is the contamination the snapshot's history describes ('Six of the archives in the author's own workspace were contaminated exactly this way', :871-874). This is the 'guard at call sites is one call site from broken' class: the refusal lives in BuildingCatalog, and the two callers that did not go through it do not refuse.

**Fix.** One pristine reader on GameFiles: pristine(ArchiveType) that answers the snapshot, else the sealed Vault entry (verified by digest), else refuses - never the live folder; route BuildingPaletteDialog.donorTextures, GfEnvPicker.pristineOrLive and ModDeployer through it and delete the hand-built 'originalSnapshotDir() + rel' paths. Guard: a source rule refusing originalSnapshotDir() outside WorkspaceSession, and a suite with no snapshot asserting donorTextures refuses rather than reading GAMEDIR_PATH.

<a id="f233"></a>

## F233 - A cloned zone shares its donor's story text, and the dialogue editor's 'is this line shared?' check counts only this zone's talkers - so editing an NPC's line in a cloned town rewrites the retail town's line too; area and map sharing have guards, story text has none

**medium** | data-integrity | `src/ctrmap/humaninterface/NPCEditForm.java:891`

READ IN SOURCE. ZoneCloner.cloneIntoSlot (:39-53) copies the donor's ZO into the slot and its master row (cloneIntoFiles), patching only the zone number in the unknown flags. Every resource id in the header comes across unchanged: area 0x02, map 0x04, story text 0x06, script 0x18 (ZoneResource :39-51). The Clone zone dialog offers to make one of them private, the map, behind a checkbox (ZoneLoadingPanel.java:1702-1745, 'Give this zone its own private map (edit it without changing the source)'). Nothing on that path forks the area or the story text (TextForker runs only for appends) or the script archive (F025). Two of the shared resources have a sharing guard: the area (AreaForker.areaSharers/zonesUsingArea, :137-208, which every prop/texture edit consults) and the map (GeometryForker.matrixSharers, :689, :752). Rename reports and handles name sharers (CtrmapMainframe.java:2372-2375), although census L.8 says it still overwrites the retail name. STORY TEXT HAS NONE. NPCEditForm.editDialogue (:880-917) decides whether to rewrite a line in place or add a new one by counting talkers that use the line in THIS zone's script only: 'TalkerScriptWizard.countTalkersUsingLine(zone.s, msgLine) > 1'. zone.s is the clone's own copy of ZO subfile 2 (Zone.java:40). A line the donor town's NPCs also display therefore counts as unshared, and msg.setLine rewrites it in STORYTEXT[textID], the donor's file. Editing what an NPC says in a cloned town changes what the same NPC says in the retail town, the same leak the area and map guards exist to stop, on the one resource without a guard. The zone-repurpose workflow ('pick a zone -> Empty zone -> Clone your template map into it', CtrmapMainframe.java:2642) and the facility setup (:3086) both create clones, and InteriorWirer clones every interior it places (:75). CORRECTION (2026-10-06, census cross-check) - the sentence above that Rename 'reports and handles name sharers' holds only when a free name slot exists. When findFreeBlankSlot finds none, ZoneManager.renameZone renames the shared name IN PLACE (ZoneManager.java:150-154, 'fall back to renaming the shared name in place (affects all sharers)'), after the pre-write dialog promised 'this zone gets its OWN name and the others are left unchanged' (CtrmapMainframe.java:2323). Census L.8 is therefore still true; the report tells the user only after the write.

**Fix.** Add the text to the sharing guards: a textSharers(zone) beside areaSharers/matrixSharers over the master table, and when the zone's text file is shared either fork it (TextForker already copies one per zone for appends) or always add a new line rather than rewrite in place; offer 'own text' in the Clone dialog beside 'own map'. Guard: a suite that clones a zone in a scratch workspace, edits a talker line in the clone, and asserts the donor's STORYTEXT file is byte-identical.

<a id="f235"></a>

## F235 - After one area fork, ZoneTables.areaCount (length minus trailing) stops naming the per-area table: forks are appended AFTER the table at 228, so Extras' 'inject into ALL areas' writes the 228 retail areas then throws on the table, and the per-area-table patch opens the last forked area as the table

**medium** | correctness | `src/ctrmap/ZoneTables.java:107`

READ IN SOURCE. The tree holds two models of where AreaData's global per-area table is, and they agree only until the first area fork. MODEL 1, 'the table is the last entry': ZoneTables.areaCount returns 'areaData.length - p.areaDataTrailingEntries()' (ZoneTables.java:96-107; ORAS trails 1), and ExtrasPanel treats that number as both the count of areas and the table's index: 'The table is the first AreaData entry past the last area, so its index IS the area count' (:295-300; openAreaTable :308-315 opens entry 'areas'). MODEL 2, 'the table is at 228 and forks follow it': AreaForker.AD_GLOBAL_TABLE = 228 (:55), new areas take their id from ad.length (:237, :278-300), so the first fork is 229, and the table file is always read at 228 (:290, :388). WorkspaceIntegrity agrees with model 2 (a >= AD_GLOBAL_TABLE means forked, :99). ARITHMETIC after k >= 1 forks: AreaData has 229 + k entries, so areaCount = 228 + k. (1) Extras > 'Inject dummy camera collisions into ALL areas' (freecamActionPerformed, ExtrasPanel.java:152-170) loops i = 0 .. 227 + k and opens each entry as an AD container. It writes the dummy collision into the 228 retail areas, then reaches i = 228, the global table, which is not an AD container, so AbstractGamefreakContainer.open refuses it and the RuntimeException leaves the handler, which has no catch around the loop (into F219's no-sink). The irreversible mass edit (F100) has been applied to every retail area and to none of the forked ones, and the dialog had promised 'ALL 228+k AreaData areas'. (2) The per-area-table patch opens entry 228 + k, the LAST FORKED AREA, as the global table (openAreaTable). It either refuses because the length is not a whole number of rows, or derives a stride from an area container and writes flag bytes into it. ZoneTables' 'trailing entries' model is correct for a retail dump and wrong for every workspace that has used the area fork the editor offers from the prop and texture editors.

**Fix.** One answer for both questions, owned by the profile and the fork: the table index is the profile's measured constant (228 on ORAS), the area ids are 0..table-1 plus the forked range table+1..length-1, and ZoneTables exposes areaIds() and areaTableIndex() instead of a count; ExtrasPanel iterates areaIds() and opens areaTableIndex(). Guard: a suite over a FakeGameFiles AreaData with one forked area asserting both Extras paths address exactly the areas and the table.

<a id="f238"></a>

## F238 - Alpha Sapphire and Y are never told apart from Omega Ruby and X: with the dump folder not named by title id, Deploy pre-fills and writes the mod to Omega Ruby's/X's mods folder - an AS/Y player's mod silently never loads - and the zone-limit instructions name OR's Luma path for everyone

**medium** | correctness | `src/ctrmap/ModDeployer.java:129`

READ IN SOURCE. One profile covers two titles (OrasProfile: 'Omega Ruby / Alpha Sapphire'; XyProfile likewise X and Y), and nothing in the tree finds out WHICH title was dumped. git grep finds no read of an exheader, program id or title id from the dump; GameProfile.titleId is documented as 'The 3DS title id of this game's first version' (GameProfile.java:258-263), and OrasProfile.titleId returns Omega Ruby's 000400000011C400 with the note 'Alpha Sapphire is 000400000011C500 and this profile covers both, which is why the deployer prefers the dumped folder's own name' (:170-177). ModDeployer.guessTitleId uses the game folder's name when it is 16 hex digits and otherwise falls back to the profile's id (:122-130). An Alpha Sapphire (or Y) dump unpacked into a folder named anything else (for example 'RomFS' or 'AlphaSapphire') therefore pre-fills Deploy's Title ID field with Omega Ruby's (CtrmapMainframe.java:1935), and Deploy writes the LayeredFS mod into Azahar's mods/000400000011C400 folder (azaharModRoot). The emulator loads mods by the running title's id, so an AS player's mod is never loaded, while Deploy reports success. The same first-version id is spelled into user instructions: the zone-limit patch tells everyone 'sdmc:/luma/titles/000400000011C400/code.ips' (ZoneLoadingPanel.java:1854), and ZoneLimitPatch.TITLE_ID is Omega Ruby's (:50-51; census M.46 notes the copy is dead). The Vault is keyed the same way (census H.42). Nothing warns that the id was guessed. The Deploy dialog shows the field but does not say it may be the other version.

**Fix.** Read the dumped title id from the dump itself (exefs/exheader program id, or the code.bin/RomFS fingerprint the setup wizard already checks), store it on the session, and use it for Deploy, the Luma path text and the Vault key; when it cannot be read, ask once in setup with both versions offered rather than defaulting to the first. Guard: a suite with a Y/AS-shaped dump in a non-id folder asserting guessTitleId does not answer the other version's id.

<a id="f242"></a>

## F242 - The Script Editor shows native calls by name ('SYSREQ_N(PlayerSetBP, 4)') but its live-edit parser accepts only numbers, so editing the arguments of any native call silently does nothing - the editor cannot re-read its own rendering

**medium** | correctness | `src/ctrmap/formats/scripts/PawnInstruction.java:211`

READ IN SOURCE. The disassembly the Script Editor shows renders every SYSREQ_N native call with the native's NAME when the script's natives table resolves it: case 0x87 -> sysreqDisasm, 'so a facility call reads e.g. "SYSREQ_N(PlayerSetBP, 4)" instead of a bare index' (PawnInstruction.java:780-785, :880-895). Two parsers read instruction text back. The assembler, fromString, accepts the name ('SYSREQ_N by native NAME: resolve to the natives-table index', :139). The live-edit parser the editor uses on every argument keystroke, setArgsFromString (:211-240), strips the parentheses, splits on commas and runs Integer.parseInt on each argument; a name throws NumberFormatException and the method answers false. Its two callers are the Script Editor's document listener and filter (ScriptEditor.java:425 drops the boolean; :469 skips the refresh when it is false; census M.116 counts the dropped answer). So editing the arguments of any native call the editor displayed by name, for example changing the 4 in 'SYSREQ_N(PlayerSetBP, 4)', does nothing to the instruction. The typed text stays on screen, so the editor looks edited, and a save writes the old instruction. The editor cannot parse its own rendering of a native call. The tab is labelled experimental (F217); the two parsers have diverged, and only one learned names.

**Fix.** One argument parser: make setArgsFromString delegate to the assembler's argument resolution (fromString's name lookup), or have both call one parseArg(String, PawnAssembly); refuse visibly (status line) when a line does not parse. Guard: a round-trip suite - disassemble a script with resolvable natives, feed every rendered argument line back through the live-edit parser, assert each parses to the same cells.

<a id="f247"></a>

## F247 - Re-activating the map painter discards the user's unapplied painting and its undo history whenever the region cannot be re-read: 'cannot tell' is answered as 'the region changed underneath', which re-seeds - silently - and the seed's own read failure is swallowed into 'no donor'

**medium** | data-integrity | `src/ctrmap/humaninterface/PaintForm.java:405`

READ IN SOURCE. Every time the map painter tool is activated, PaintForm.activate re-seeds when 'seededZone != loadedZone.index() || regionChangedUnderneath()' (:385-387). regionChangedUnderneath (:392-408) exists to notice that another editor rewrote the region since the painter seeded it, which is a good reason to start over. It re-reads the region from the workspace and compares, but on ANY exception it answers true ('catch (Exception ex) { return true; }', :405-407), and it also answers true when the first region cell cannot be found (:399-401). seed() (:418-470) then resets the user's whole unapplied painting: every grid cell back to GRASS (:424-426), heights, ramps and touched (:427-431), placed objects (:432), and the undo and redo stacks (:433-436). Nothing asks or tells. So a transient read failure, such as the emulator or a virus scanner holding the FieldData file (the case the container code names as one this project's users hit, AbstractGamefreakContainer.java:273-275), is treated as proof the region changed and discards the work in progress with its undo history. seed() fails silently the other way too: its own read of the donor region sits in 'catch (Exception ignore) { }' (:448-464), which leaves donorModel null and the edge-blend option disabled, with the zone label giving no reason. 'Could not read the region' becomes 'the region changed' in the first case and 'there is no donor' in the second, the two answers CLAUDE.md names for uncertainty that is not absence.

**Fix.** Three answers, not two: regionChangedUnderneath returns CHANGED / SAME / UNKNOWN; on UNKNOWN keep the document and say the region could not be checked; on CHANGED ask before discarding painted cells (offer to keep painting over the new region). seed() reports a donor read failure in the zone label instead of 'catch (Exception ignore)'. Guard: a suite that seeds, paints, makes the region unreadable, re-activates, and asserts the grid and undo stack survived.

