# Audit 2026-10-06 - findings, low

Full evidence and proposed fix for each finding. Back to the [summary](AUDIT-2026-10-06.md).

<a id="f005"></a>

## F005 - Help > Support/Issue tracker opens the upstream 2019 repository (HelloOO7/CTRMap), not this project's tracker

**low** | ui | `src/ctrmap/CtrmapMainframe.java:1538`

issueTrackerAction: browse(new URI("https://github.com/HelloOO7/CTRMap/issues")) (1538). README.md: 'Bugs and questions: open an issue' -> https://github.com/hdent1232/CTRMap-F5/issues. F5 users are sent to a tracker for different code. Its URISyntaxException/IOException is also log-only (1539-1540): a failed browse says nothing to the user.

**Fix.** Point it at the F5 tracker (ideally read from the same place the updater reads its repo), and Ui.error on failure.

<a id="f006"></a>

## F006 - The OBJ export dialog sends users to 'Tools > Import OBJ into map region' - there is no Tools menu; the item is under Map, and the guard meant to refuse exactly this let it through

**low** | ui | `src/ctrmap/CtrmapMainframe.java:2697`

2697: "Tools > Import OBJ into map region to bring the changes back." grep of 'new JMenu(' finds File, Map, Zone, Game Data, Options, Help only (858-911); the item is 'Map > Import OBJ into map region (Blender)...' (880). Javadocs at 2681/2733 also say '(Tools menu)'. OUTSTANDING Done item D claims MainframeShapeTest reads every 'Menu > Item' named in a production string and refuses one the menu bar cannot resolve - this one survives it (guard gap to check when reading the suite).

**Fix.** Fix the string; widen the guard's pattern to whatever shape this line has.

<a id="f008"></a>

## F008 - 19 production files import the window's statics (18 'import static ctrmap.CtrmapMainframe.\*', 1 by name) and 16 of them use nothing through it (re-measured; an earlier 21 counted javadoc mentions; same subject as F143)

**low** | dead-code | `src/ctrmap/humaninterface/GfEnvPicker.java:36`

Bytecode scan (control validated) finds no getstatic/putstatic of any CtrmapMainframe field outside the class. GfEnvPicker.java:36 'import static ctrmap.CtrmapMainframe.mTileMapPanel;' - mTileMapPanel appears on that line only. The wildcard imports are in TilePainterForm, Selector, TileEditForm, PaintForm, TrainerEditDialog, TriggerEditForm, MaisonEditDialog, WorkspaceSettings, MatrixEditForm, TilemapPanelInputManager, MatrixPanelInputManager, NPCEditForm, TileUndo, WarpEditForm, TileMapPanel, GeoEditForm, MapMatrixPanel, ZoneLoadingPanel. They keep every window global one identifier away in 21 files, so a reintroduced reach needs no visible change at the top of the file.

ADDENDUM: Bytecode scan (control validated) finds no getstatic/putstatic of any CtrmapMainframe field outside the class. GfEnvPicker.java:36 'import static ctrmap.CtrmapMainframe.mTileMapPanel;' - mTileMapPanel appears on that line only. The wildcard imports are in TilePainterForm, Selector, TileEditForm, PaintForm, TrainerEditDialog, TriggerEditForm, MaisonEditDialog, WorkspaceSettings, MatrixEditForm, TilemapPanelInputManager, MatrixPanelInputManager, NPCEditForm, TileUndo, WarpEditForm, TileMapPanel, GeoEditForm, MapMatrixPanel, ZoneLoadingPanel. They keep every window global one identifier away in 21 files, so a reintroduced reach needs no visible change at the top of the file. ADDENDUM (staticimp.py, javap constant pool, controls: TriggerEditFormGuardsTest positive, util/Bytes negative): 25 files carry the wildcard (20 production + 5 tests). 17 production files reference nothing of CtrmapMainframe at all; 3 (MaisonEditDialog->showGameData, WorkspaceSettings->onWorkspaceOpened/unloadEditors, ZoneLoadingPanel->refreshSceneFog/showWorldEditor) use it only for static METHOD calls (F143's subject). ClassFileScanner (a test tool) also imports it unused. WarpEditForm:163 and WarpTransitionsTest:151-153 still say saveEntry 'reaches the main window' - WarpEditForm's bytecode has no CtrmapMainframe reference.

**Fix.** Delete the imports; have the compiled-class guard also refuse 'import static ctrmap.CtrmapMainframe' in source.

<a id="f010"></a>

## F010 - A second dialog sends users to the nonexistent Tools menu: 'via Tools -> Empty zone' (the item is Zone > Empty zone)

**low** | ui | `src/ctrmap/CtrmapMainframe.java:3031`

3029-3031: "...repurpose a base zone (e.g. an unused one, via Tools -> Empty zone) and try again." No Tools menu exists (menus: File, Map, Zone, Game Data, Options, Help). Written with '->' rather than '>', a shape a 'Menu > Item' pattern would not read - see F006.

**Fix.** Fix the text; make the menu-path guard read both arrow shapes.

<a id="f012"></a>

## F012 - AreaForker.repointMasterArea is a second copy of the master-row repoint that GeometryForker.repointMasterRow's javadoc says exists only once

**low** | duplication | `src/ctrmap/AreaForker.java:442`

GeometryForker.java:853-857: 'the offset comes from ZoneResource and there is one copy of the arithmetic rather than one per resource' - repointMasterRow(zo, zoneIndex, ZoneResource res, newId). AreaForker.java:442-456 repointMasterArea repeats it with its own HDR_AREA_OFF constant (63) and is still called by forkArea (425).

**Fix.** forkArea calls GeometryForker.repointMasterRow(zo, zoneIndex, ZoneResource.AREA, newArea); delete repointMasterArea and HDR_AREA_OFF.

<a id="f016"></a>

## F016 - The loaded-zone owner is written from SwingWorker threads and read on the event thread with plain fields - a documented data race

**low** | correctness | `src/ctrmap/LoadedZone.java:29`

Javadoc 29-31: 'Plain fields, as the panel's were: the panel writes from its workers and the window reads on the event thread ... it gives the state an owner, not a lock.' table/open/index (35-37) are neither volatile nor guarded; table(..) swaps the array and replace(i,..) fills it from the worker while at(i)/open()/index() are read on the EDT. Under the JMM the EDT may see a stale index with a new open zone (or vice versa). ADDENDUM - THE SAME SHAPE IN TWO MORE PLACES. (1) CM3DInputManager: the held-key list is a plain ArrayList (:29). The EDT adds and removes in keyPressed/keyReleased (:130-133, :215-216), and the daemon 'camera-walk' thread reads it on every 10 ms tick (walking() :172-175, step() :185-206). There is no lock or volatile, so the walker has no guarantee it ever sees a key release; the class's own comment records 'a thread whose key release was lost keep walking the camera for the life of the process' (:159-160). The walker also writes m3DDebugPanel.translateX/Y/Z from that thread while the EDT reads them to draw and the mouse handlers write rotateX/Y. (2) ZonePreviewPane's 'newest request wins' counter is a plain int[] (seq, :48), written on the EDT (:141) and read by each preview thread (:157) to decide whether to draw. m_edt.py (F220) could not see either: it counts Swing calls, and these are plain fields.

**Fix.** Make the writes happen on the EDT (publish from the worker's done()/process()), or make the three fields one immutable snapshot behind a volatile reference.

<a id="f020"></a>

## F020 - Resize map can only grow, only up to 4x4 regions, and is flagged EXPERIMENTAL (never booted in-game)

**low** | unfinished | `src/ctrmap/MapResizer.java:33`

MapResizer javadoc 31-32 'Growing only (no shrink)'; buildResizedMatrix refuses newW &lt; w || newH &lt; h (63-65). CtrmapMainframe resizeMapAction spinners max 4x4 (3132-3133) and the dialog says 'EXPERIMENTAL: ... a grown custom map has not been booted in-game yet' (3142-3143).

**Fix.** Implement shrink (drop trailing cells, leave regions orphaned or reclaimed) and record an in-game verification.

<a id="f022"></a>

## F022 - Switching the mod off/on falls back to copy-then-delete and ignores every delete result, so a file the emulator holds open leaves both copies on disk - the state deploy() refuses to work with

**low** | silent-failure | `src/ctrmap/ModDeployer.java:297`

moveDir: any IOException from ATOMIC_MOVE (not only cross-volume - also a locked file) falls back to copyTree+deleteTree (276-280). deleteTree calls f.delete() and drops the boolean (297-305). The toggle then reports success; afterwards both modRoot and its parked copy exist, which deploy() (151-157) and enable()/moveDir ('already exists, refusing to overwrite', 267-268) both refuse.

**Fix.** Check every delete; if the source cannot be fully removed, delete the copy and throw naming the held file.

<a id="f023"></a>

## F023 - Deploy auto-detects only the Windows Azahar folder (%APPDATA%), though the portable release is advertised for macOS/Linux

**low** | unfinished | `src/ctrmap/ModDeployer.java:101`

azaharModRoot: 'String appdata = System.getenv("APPDATA"); if (appdata == null ...) return null;' (101-108). README: portable zip 'Run run.bat (Windows) or run.sh (macOS/Linux)'. On those platforms the Deploy dialog's folder starts empty and the user has to find Azahar's load/mods folder themselves.

**Fix.** Add the Linux (~/.local/share/azahar-emu/load/mods) and macOS (~/Library/Application Support/Azahar/load/mods) locations.

<a id="f027"></a>

## F027 - Seven production classes still carry a standalone main() from when they were programs: TileDBWriter plus the NetBeans 'Nimbus' launcher stubs in five forms, and TextFile.main reading '072.bin' from the working directory

**low** | dead-code | `src/ctrmap/TileDBWriter.java:597`

main() 597-623 sets Nimbus and builds a frame with EXIT_ON_CLOSE; nothing calls it (it is reached only from CtrmapMainframe.tilesetEditorAction as a panel). 'private static TileDBWriter mSingleton' (32) is assigned in the constructor (39) only to call two setters on itself (40-41). The javadoc 'Creates new form TileDBWriter' (29-31) sits on the field, not the constructor.

ADDENDUM: Measured 2026-10-06: git ls-files src/\*.java minus tests, grep 'static void main(' = 13 files. Entry points by design: CtrmapMainframe, update/Updater, tools/{BuildingHarvester, GroundMaterialAudit, TerrainDonorHarvester, TerrainHarvester}. Leftovers: TileDBWriter.java:597, the generated NetBeans launcher (Nimbus look-and-feel then 'new X().setVisible(true)') in humaninterface ADPropRegistryEditor.java:484, AboutDialog.java:90, ESPICAControl.java:110, NPCRegistryEditor.java:418 and WorkspaceSettings.java:441 - forms that now live inside the main window (OUTSTANDING.md:336-345) - and formats/text/TextFile.java:126, which opens new File("072.bin") and prints every line.

**Fix.** Delete main() and mSingleton; move the javadoc.

<a id="f028"></a>

## F028 - The whole Swing UI is built on the main thread, not the event dispatch thread

**low** | correctness | `src/ctrmap/CtrmapMainframe.java:248`

main(): '...UIManager.setLookAndFeel(...); createAndShowGUI();' (242-248) - no SwingUtilities.invokeLater. createAndShowGUI builds every panel, starts the 3D panel's animator (comment 569-571), calls Workspace.validate (837, which opens archives and may show dialogs) and the setup wizard (835) from the launcher thread. Swing components must be created and touched on the EDT; this is the kind of race that shows up as intermittent layout or repaint glitches at startup.

**Fix.** SwingUtilities.invokeLater(CtrmapMainframe::createAndShowGUI) in main().

<a id="f030"></a>

## F030 - WorkspaceIntegrity redefines MASTER_ROW and the header offsets of area and matrix, a fifth copy beside GeometryForker/ZoneCloner/ZoneLimitPatch and ZoneResource

**low** | duplication | `src/ctrmap/WorkspaceIntegrity.java:44`

44-46: 'MASTER_ROW = 0x38; HDR_AREA_OFF = 2; HDR_MATRIX_OFF = 4;'. GeometryForker.MASTER_ROW (59), AreaForker.HDR_AREA_OFF (63), ZoneResource.headerOffset (used by GeometryForker 867, TextForker 97/107) carry the same facts. ARCHITECTURE.md already lists four independent 0x38 definitions and counts this one among them.

ADDENDUM: RE-MEASURED 2026-10-06 (git grep 'final int NAME = 0x38|56' over prod src; control: ZoneCloner.ZONE_HEADER_SIZE listed): the four named definitions ARCHITECTURE.md lists (2026-09-06) are all still there - GeometryForker.java:59, WorkspaceIntegrity.java:44, ZoneCloner.java:29, ZoneLimitPatch.java:49 - so the documented cleanup candidate never moved. A FIFTH user writes the master table with bare literals: ZoneLoadingPanel.java:462-467 'dos.skipBytes(zoneIndex \* 0x38); byte[] test = new byte[0x38]; ... dos.seek(zoneIndex \* 0x38)' - the header save path. 44 bare 0x38 tokens in prod src overall (many are unrelated BCH offsets), 5 of them in ZoneLoadingPanel.

**Fix.** One MasterTable/ZoneHeaderLayout source of these numbers (see F013).

<a id="f032"></a>

## F032 - WorkspaceIntegrity ends with a javadoc for a method that is no longer there

**low** | doc-drift | `src/ctrmap/WorkspaceIntegrity.java:298`

298-302: '/\*\* One subfile out of a Gamefreak container: u16 magic, u16 subfile count, then count+1 u32 offsets. Null when ... \*/' followed directly by the closing brace (303). The method moved to ContainerBytes.subfile (imported at 10).

**Fix.** Delete the orphan comment.

<a id="f037"></a>

## F037 - After a pack each archive handle is re-opened up to seven times: reloadGARC deliberately reloads everything after the named archive, and packArchives names seven

**low** | efficiency | `src/ctrmap/WorkspaceSession.java:1025`

reloadGARC (454-471) reloads RELOAD_ORDER[start..end] - 'the original switch's missing breaks, kept on purpose' (150-156). packArchives then calls it for AREA_DATA (start 0: all 17), FIELD_DATA (15), ZONE_DATA (10), MAP_MATRIX (13), BUILDING_MODELS (16), NPC_REGISTRIES (11), GAMETEXT (14) at 1025-1031, plus STORYTEXT (9) at 1021 when text was packed: 96-105 GARC constructions where 17 would do. The fall-through also means 'reload X' secretly means 'reload X and everything listed after it', which callers like ZoneLoadingPanel cannot see.

**Fix.** One reloadAll() after a pack; make reloadGARC(t) reload exactly t.

<a id="f041"></a>

## F041 - The single-zone append payload builder survives only for its own tests

**low** | dead-code | `src/ctrmap/ZoneAppender.java:365`

buildAppendPayloads (365-385), AppendPayloads (57-67) and rebuildEN (432-434) have no production caller - grep finds them only in src/ctrmap/tests/ZoneAppendTest.java and ZoneAppendMultiTest.java. The NOTE at 69-73 says the single-zone path was REMOVED; its byte transform was kept and is still tested as though it shipped. DuplicateWorkTest checks unreachable CLASSES, so dead methods in a live class pass. ADDENDUM: ZoneAppendTest:23 still calls the append 'EXPERIMENTAL' and its chainedAppend (:208-224) asserts appends chain 'over successive pack cycles' (536->537->538) - a capability the product refuses: WorkflowGuardsTest:270 'Appending is ONE-SHOT: appendZones refuses unless the table is at the stock base'. rebuildEN (:432) is reached in production only from buildAppendPayloads (:374,:383).

**Fix.** Delete them and port the tests to buildMultiAppendPayloads(addCount=1).

<a id="f042"></a>

## F042 - ZoneAppender points readers at CtrmapMainframe.repairSharedPaddingZones, which does not exist

**low** | doc-drift | `src/ctrmap/ZoneAppender.java:276`

276: '...is what CtrmapMainframe.repairSharedPaddingZones exists to undo.' grep finds the name only on this line; the repair is PaddingZoneRepair.repairOnOpen -> GeometryForker.repairSharedAppendedZones. Same file also stacks orphan javadocs (139-151 over 152-168; 333-337 over 338-344; 436-441 over 442-458).

**Fix.** Point at PaddingZoneRepair; fix the stacked javadocs.

<a id="f047"></a>

## F047 - A second IPS writer lives in ZoneLimitPatch, beside the one ItemIconTable's javadoc says must stay the only one

**low** | duplication | `src/ctrmap/formats/codepatch/ZoneLimitPatch.java:118`

ZoneLimitPatch.buildIPS writes PATCH/records/EOF by hand (117-131) with its own writeAscii/le helpers (152-160). ShopData holds diffIPS/mergeIPS/parseIPS/applyIPS (131-242) and ItemIconTable 201-203 says 'Shares ShopData's IPS machinery on purpose. A second IPS writer would be a second place for the merge to be subtly different'. Three classes also carry three private indexOf(byte[], byte[]) scanners (ItemIconTable 209-223, ShopData 250-261) and two ascii writers.

**Fix.** Move IPS read/write/merge/apply into one ctrmap.formats.codepatch.Ips class; ZoneLimitPatch emits records through it.

<a id="f049"></a>

## F049 - verify() opens the file and leaks the handle on every read failure - the try/finally around it is empty

**low** | resource-leak | `src/ctrmap/formats/containers/AbstractGamefreakContainer.java:163`

145: 'LittleEndianDataInputStream dis = new LittleEndianDataInputStream(new FileInputStream(f));' then 'try { ... } finally { }' (146-164) with dis.close() AFTER the finally (165). Any IOException while reading the header/offsets (a truncated file, which is exactly what verify exists to catch) skips the close. On Windows an open handle blocks the later delete/rewrite of that file until GC. Also create() logs and swallows its IOException (123-125), leaving a container object with no file behind it, and getIOFile (247-258) NPEs on a null getFile and leaks its stream on IOException.

**Fix.** try-with-resources in verify/create/getIOFile; create throws.

<a id="f051"></a>

## F051 - Creating a move-model container lays it out unpadded and then treats it as padded: the type that decides padding is assigned after the base constructor has already written the file

**low** | correctness | `src/ctrmap/formats/containers/MM.java:30`

MM(File, int len, int type, ...) { super(f, len, files); this.type = type; } (30-33). The base create() (AbstractGamefreakContainer 99-126) calls getIsPadded() while type is still 0 = MM_MAP_MATRIX, so it writes an UNPADDED header/offsets; afterwards getIsPadded() answers true for MM_MOVE_MODEL (71-76) and storeFile pads every subfile against offsets that were never 0x80-aligned. Reached from Builder.java:609 'new MM(target, currentAGFC.len, ((MM) currentAGFC).type, ...)' when the current container is a move model. Same this-escape shape as F050.

**Fix.** Make type a constructor argument consumed before create(), e.g. a static factory, or pass padding into the base constructor.

<a id="f053"></a>

## F053 - The 'EN' encounter pack is rebuilt by three separate writers, and validated by one of them

**low** | duplication | `src/ctrmap/formats/encounters/EncounterTable.java:167`

EncounterTable.write (167-203) re-emits magic, count and offset table from blobs; ZoneAppender.rebuildENMulti (ZoneAppender 488-515) and truncateEN (459-486) each re-emit the same header and offsets with their own shift arithmetic. Only ZoneAppender's paths run validateEN on input/output (ZoneAppender 393-422, 464, 484); EncounterTable.read/write accept any pack (no magic check, count from ContainerBytes, offsets unchecked - a bad o1 &lt; o0 is a NegativeArraySizeException at 157/179).

**Fix.** One EnPack class: parse (validated), blobs(), withBlob(zone, bytes), grownBy(n), truncatedTo(n), bytes().

<a id="f056"></a>

## F056 - Two independent GARC writers must agree on every header convention by hand, and both write a FATB length the code itself calls quirky

**low** | duplication | `src/ctrmap/formats/garc/GarcRebuilder.java:22`

GarcRebuilder.write (24-102) 'mirrors packDirectory's game-validated conventions verbatim' - i.e. it re-implements GARC.writeRepacked's header patching, FATO (0xC + n\*4, i\*16 offsets), FATB (length n\*16), 0xFF padding and max-size hint (GARC 523-644). Line 39: 'FATB length field (quirky in our own output; recompute position instead)' - the n\*16 both write (GARC 553, here 73) is not the section length a reader would compute, so GarcRebuilder cannot trust its own output's field. A convention fixed in one writer stays wrong in the other.

**Fix.** One GarcWriter that both a repack (replace/append) and a rebuild (exact list) go through; write the FATB length the format defines.

<a id="f059"></a>

## F059 - The proven-invalid legacy collision writer is still compiled and public: computeMeshOrder, GRCollisionBounds.updateBounds/write/debugSysout, GRCollisionMesh.write, and the fields only they used

**low** | dead-code | `src/ctrmap/formats/gfcollision/GRCollisionFile.java:200`

grep over src (excluding tests): updateBounds, computeMeshOrder and debugSysout each match only their own declaration (GRCollisionBounds 25, GRCollisionFile 200, GRCollisionBounds 109). write() (150-163) goes through GfColl.build, and its javadoc says the in-class bounds/bucketing math 'was PROVEN game-invalid ... and must never be used for writes again' (144-148) - enforced by prose only. offsets/lengths (29-30) and the five unknown_const fields (25-26, 34-36) are assigned at parse and never read; 'bounds.originalExtremes' (83) is never read.

**Fix.** Delete the legacy writer and its fields so the invalid path cannot be called.

<a id="f060"></a>

## F060 - Loading a region's collision runs a quadratic de-duplication over every triangle (array-equals against every kept triangle)

**low** | efficiency | `src/ctrmap/formats/gfcollision/GRCollisionFile.java:114`

dedupe(): for each triangle, compares x/y/z arrays against every triangle kept so far (114-138) - O(n^2) Arrays.equals triples, run in the constructor (72) on every collision load; computeMeshOrder repeats it (206-226). GfColl handles bucketing on write, so a HashSet keyed on the nine floats does the same job in O(n).

**Fix.** Key a HashSet on the nine float bit patterns.

<a id="f062"></a>

## F062 - appendGeometry and setMeshGeometry repeat the same ~50 lines of index-buffer location, width upgrade and relocation-flag flip

**low** | duplication | `src/ctrmap/formats/h3d/BchMapModel.java:861`

appendGeometry (759-845) and setMeshGeometry (861-931) each: locate vtxCmdLoc/idxCmdLoc/numVerticesLoc (772-776 / 878-881), scan idxBuffers for elemSize (777-786 / 882-891), check 16-bit range, upgrade u8->u16 (807 / 906), encode indices (816-824 / 908-916), call rebuildRawData with upgradeOrderFix, write NUMVERTICES, and flip reloc 0x28->0x27 (836-843 / 922-929). appendGeometry is setMeshGeometry with (old+extra) vertices and (old+extra) indices.

**Fix.** Implement appendGeometry as setMeshGeometry(meshIndex, concat(oldVtx, extra), concat(oldIdx, extraIdx)).

<a id="f063"></a>

## F063 - The BCH verifier keeps its working state in static fields, so two verifications at once corrupt each other's result

**low** | correctness | `src/ctrmap/formats/h3d/BchModelVerifier.java:18`

'static byte[] raw; static BchMapModel m; static List&lt;String> fails;' (18-20), all reassigned at the top of verify() (25-26) and read by every check and by minSub() (165). The 3D preview and zone-browser panes decode on background threads while imports/appends verify on the EDT; an interleaving returns one model's failures for another, or NPEs. Leftover scaffolding: 'cur=pp; // replaced below by next-start logic' / 'cur=pp; // placeholder' (102-103); two stacked class javadocs (5-13 orphaned above 16); subs.get(0) (112) and streams.get(0) (143) throw on a zero-mesh model instead of reporting it.

**Fix.** Make the state locals of an instance (new Verifier(bch).run()); report empty models as a failure.

<a id="f068"></a>

## F068 - Every geometry-tool operation is quadratic in the region's mesh count: per mesh it recomputes the geometry of ALL meshes several times and re-parses the whole model

**low** | efficiency | `src/ctrmap/formats/h3d/GeoBoxOps.java:137`

duplicate/delete loop meshes and call model.geometry().get(mi) (137, 195) - geometry() walks every mesh and scans every index buffer (BchMapModel 393-474) - then getVertexPositions (which calls geometry() again, BchMapModel 503-505), getTriangles, appendGeometry/setMeshGeometry (each calling geometry() again, BchMapModel 760, 862), and finally re-parses the whole BCH 'model = new BchMapModel(current)' (121, 180, 240). query() and move() do the same per mesh (74-79, 105-121). For a 20-40 mesh region that is hundreds of full geometry passes per click.

**Fix.** Compute geometry() once per model instance (cache it; the instance is immutable over raw), and batch all meshes' replacements into one rebuildRawData.

<a id="f070"></a>

## F070 - Two OBJ parsers: the map-model one handles n-gons, relative indices and missing groups; the collision import uses the other, which handles none of them

**low** | duplication | `src/ctrmap/formats/h3d/MapModelObj.java:126`

MapModelObj.parse (126-204): fan-triangulates polygons, resolves negative indices (215-218), defaults the group to 'default' (132). WavefrontOBJ (formats/WavefrontOBJ.java) - used by Map > OBJ to collisions - writes degenerate quads, drops n-gons, crashes without a group, and has no relative-index support (F044). Same input format, two readers, the worse one on the collision path.

**Fix.** Have the collision import read MapModelObj.parse output (positions + triangles) and delete WavefrontOBJ.

<a id="f071"></a>

## F071 - The PICA command reader is a half-ported Ohana class: texture wrap/filter modes and units 1-2 formats are commented-out C#, border-color getters would throw on any colour channel over 127, and each instance allocates 512 KB

**low** | unfinished | `src/ctrmap/formats/h3d/PICACommandReader.java:440`

Five methods remain as commented-out C# (getTexUnit0Mapper 440-457, getTexUnit1Mapper 497-514, getTexUnit1Format 539-546, getTexUnit2Mapper 555-572, getTexUnit2Format 597-604) - so the 3D view never knows a texture's wrap or filter mode. getTexUnit{0,1,2}BorderColor build 'new Color((byte)(rgba &amp; 0xff), ...)' (465-470, 522-527, 580-585): the byte cast makes any channel >127 negative and java.awt.Color throws IllegalArgumentException (no caller today - grep finds none). Doc comments are C# '/// &lt;summary>' XML, not javadoc. 'private long[] commands = new long[0x10000]' (24) - 512 KB per reader, and one is built per mesh, per face group and per material (H3DModel 190/242, H3DMaterial 161, H3DTexture 30). ADDENDUM - THE RENDERER'S SIDE OF THE SAME GAP. Because the reader never decodes wrap or filter modes, H3DModel's draw path forces GL_NEAREST for both filters (H3DModel.java:782-783). It binds one texture per material, and it special-cases tall grass by MATERIAL NAME: '"blc"' and 'enc_grass' decide whether vertex colour and alpha testing apply (:694, :789, :795, :823). Its own comment calls this 'a workaround for tall grass rendering, which uses weird vertex coloring and alpha testing' (:765). The 3D view is therefore an approximation: TEV combiners and lighting LUTs are not modelled, so what the user previews is not what the game draws, and a custom material that happens to contain 'blc' is drawn as grass.

**Fix.** Finish or delete the commented methods; fix the casts (&amp; 0xff ints); use a sparse map for commands.

<a id="f073"></a>

## F073 - MapResizer still carries its own copy of the region-blanking subfile writes that RegionFactory.blankRegionFiles exists to be the only copy of

**low** | duplication | `src/ctrmap/MapResizer.java:244`

RegionFactory 51-56: 'Two copies of "subfile 1 is the model, 2 is the collision, 0 is the tilemap, 3 is the props, and 7..10 are the extra layers..." is two places to get the numbering wrong.' MapResizer.resize 243-255 writes storeFile(1/2/0/3) and the 7/8-or-9/10 extra-layer rules by hand, with its own ground choice (F017) and no try/answer contract.

**Fix.** Call RegionFactory.blankRegionFiles(reg, -1) from MapResizer.

<a id="f074"></a>

## F074 - The BCH string reader has the same end-of-stream infinite loop, and copies the rest of the file for every string it reads

**low** | correctness | `src/ctrmap/formats/h3d/StringUtils.java:13`

'while ((read = in.read()) != 0) { sb.append((char)read); }' (13-15) never sees -1 as the end, so an unterminated string at EOF loops until OutOfMemoryError (same defect as H3DModelNameGet, F069). readString(address, b) does 'Arrays.copyOfRange(b, address, b.length)' (19) - a full tail copy per string - which makes reading N names from one BCH O(N x file size).

**Fix.** Stop at -1 (and refuse it); read in place from the array without copying.

<a id="f076"></a>

## F076 - H3DModel.adjustBoneVerticesToMatrix is marked deprecated in prose and has no caller

**low** | dead-code | `src/ctrmap/formats/h3d/model/H3DModel.java:418`

Javadoc 'Deprecated. Replaced by fixed rigid skinning instead.' (415-417); grep -w finds only its declaration. Not @Deprecated, so nothing flags a new call.

**Fix.** Delete it.

<a id="f078"></a>

## F078 - Uploading a mesh with no material throws NullPointerException on the render thread, and the immediate-mode fallback dereferences the null material too

**low** | correctness | `src/ctrmap/formats/h3d/model/H3DModel.java:743`

makeAllBOs passes makeBOs(null) when materialId is out of range (455-459); makeBOs leaves tcbo = new DoubleBuffer[3] all null for a null material (650-659); uploadVBO then calls tcbo[0].rewind() (743-745) BEFORE its own 'if (tcbo[0] != null)' (747). render()'s non-VBO path calls mat.name.contains(...) when hasColor (823) with mat possibly null. Also uploadVBO deletes its texture and buffer names every upload and immediately rebinds the deleted names (739-757).

**Fix.** Null-check tcbo entries before rewind; guard mat in render; reuse GL names instead of delete+rebind.

<a id="f079"></a>

## F079 - Ohana-port material/mesh decoders carry unused state, a commented-out C# block and a throw-on-unknown enum decode

**low** | dead-code | `src/ctrmap/formats/h3d/texturing/H3DMaterial.java:78`

H3DMaterial decodes coordinators[], combiners[6] (TEV stages), userData metadata, lightSetIndex/fogIndex, colConstant0-5, stencil/depth/cull: grep finds no consumer outside H3DMaterial for coordinators/combiners/cullMode/depthTest/stencilTest/lightSetIndex (only mappers[].wrapU/V, params.blendop.mode and params.alphaTest are read, H3DModel:660-790). Every enum is decoded as Enum.values()[raw bits] (lines 78,85,148-151,190): (wrapAndMagFilter >> 24) is a signed shift, so any byte >=0x80 or unknown value throws ArrayIndexOutOfBounds and the whole BCH fails to open. getMetaData utf8 loop (212) only stops on a DOUBLE NUL, so utf8 strings swallow the next string (ported Ohana bug). OhanaMeshUtils:120-162 is commented-out C# (bool/uint/foreach/.Count) and optimizedMesh/optimizerLookBack are referenced only by it; getColorFloat (82) has no caller and would throw IllegalArgumentException for any channel >0.5 (byte cast makes it negative). The only consumer of userData is a commented-out matdump in BCHFile:248.

**Fix.** Decode only what the renderer/writers use, map unknown enum values to a fallback instead of indexing values(), delete the C# block and dead helpers.

<a id="f089"></a>

## F089 - Location names are read by a second, legacy text decoder that disagrees with GFMessageFile, and an out-of-range id is named "NullPointerException"

**low** | duplication | `src/ctrmap/formats/text/LocationNames.java:108`

Two decoders for the same GameText format: GFMessageFile (read/write, pk3DS-exact, used by the text editor, ItemText, PokeData) and TextFile (legacy read-only Kaphotics port) whose ONLY user is LocationNames (79). They disagree on output: TextFile names variables SETCOLOR/PKSPECIES/TRNICK-INITIAL/MAISONTITLE where GFMessageFile writes COLOR/PKNAME/1stchrTR/MAISTITLE, renders a blank-line marker as '[NO DATA - n]' vs '[~ n]', and always remaps the private-use glyphs; line 101 sb.append((0x2640)) appends the digits '9792' instead of the female sign. TextFile's constructor returns with lines == null for a zero-line file (getLine then NPEs), logs-and-continues on IOException, and carries a dead main() reading '072.bin' (126-131). LocationNames.getLocName returns the literal string "NullPointerException" for an id past the table (107-108), and DataSafetyGuardsTest:961 filters that string out instead of failing on it.

**Fix.** Read location names through GFMessageFile and delete TextFile; return an explicit 'unknown location #n' label (or throw) instead of an exception class name.

<a id="f090"></a>

## F090 - The PaintedRegionBuilder split is a relocation: every stage wildcard-imports every other, and the bytecode graph is cyclic through the hub

**low** | architecture | `src/ctrmap/formats/tilemap/PaintedCliffMesh.java:17`

Each of the 7 Painted\*.java files carries 'import static ctrmap.formats.tilemap.PaintedX.\*' for all 6 siblings (42 wildcard edges; grep). Measured actual use with javap over the compiled classes (control: PaintedCliffMesh references TerrainCatalog 5x): 16 real edges - PaintedRegionBuilder -> all 6 stages, and CliffMesh, Collision, FloorMesh, Heights, Materials each -> PaintedRegionBuilder (cycle through the hub), plus CliffMesh &lt;-> FloorMesh and Heights -> CliffMesh. The class doc says so itself: 'Every method here is exactly the one that was there, moved and not rewritten... What this is NOT is decoupling' - CLAUDE.md's DETANGLE, DO NOT RELOCATE. Inside PaintedCliffMesh: byLevel (96-99) is computed and never read (the comment at 137-144 says per-step splitting was abandoned); the lip parameter of emitChain is 'currently unused' yet emitCliffStrips computeIfAbsent's an empty list for lipMesh just to pass it (210), creating an empty mesh bucket; a System.getProperty("nogusset") debug switch sits in production geometry (221); the collar comments contradict each other (452-456 'goes in the GROUND mesh' vs 462-468 'belongs in the cliff mesh') and flatQuadV's doc ('it is GROUND... textured exactly as the floor is') contradicts its only call, which passes the cliff's V_LIP/V_MID bands into the cliff list. ADDENDUM - THE STAGES ALSO COPY EACH OTHER'S ALGORITHMS, AND THE COPIES HAVE DRIFTED. The outline smoothing that rounds a painted edge (iterate, pull each point toward its neighbours, cap the pull) is written twice: PaintedCliffMesh caps it at 'final float MAX_PULL = TILE \* 0.3f' (:624-630), and PaintedFloorMesh.emitShoreChain carries its own inline copy capped at 'TILE \* 0.34f' (:638, :673-679). A cliff edge and the shoreline beside it are rounded by different rules, and a change to one does not reach the other. The three winding fixers (census L.33) are the same shape. Splitting the builder into stage files moved these copies into separate files instead of sharing them.

**Fix.** Replace wildcard imports with explicit ones, move the shared constants/Quad type out of PaintedRegionBuilder into a leaf class so stages stop depending on the hub, delete byLevel/lip/nogusset, reconcile the collar docs.

<a id="f091"></a>

## F091 - ~220 lines of cliff geometry are unreachable: the 'per-tile fallback' and two strip builders have no caller

**low** | dead-code | `src/ctrmap/formats/tilemap/PaintedCliffMesh.java:945`

grep over src (prod and tests): flatQuad (851), stripMixed (885) and cliffQuads (945) have no call site; slopedQuad's only callers are inside cliffQuads (982-983) and cliffQuad's only caller is slopedQuad (995), so lines ~837-1067 are dead. cliffQuads' javadoc still calls itself 'the per-tile fallback, for edges that never made it into a chain', but emitCliffStrips seeds a chain from every unused edge (150-211), so no edge is left for a fallback. The dead block carries long 'measured' bug narratives (8 of 384 faces inside out, flatness 0.0000 over 1164 triangles) that now describe code that never runs; 837-841 is an orphaned javadoc for a strip helper that no longer exists, stacked above flatQuad's.

**Fix.** Delete the dead helpers (and their narratives) or wire a real fallback with a test.

<a id="f093"></a>

## F093 - TerrainCatalog promises to throw on a failed import but returns the model unchanged on five failure paths, and parses its table three ways

**low** | silent-failure | `src/ctrmap/formats/tilemap/TerrainCatalog.java:395`

Class doc: 'A real failure ... is now THROWN with its reason'. ensureNamedMaterial (395-464) returns r unchanged, saying nothing unless -Dcliffdebug is set, when the donor region cannot be opened (421-423), is not a map model (426-428), the append produced no mesh of the name (439-441) or the merged model fails validate() (447-450); ensureMaterial (484-554) does the same at 513-518, 535-537 and 542-544 ('never ship a model that stopped parsing' - and never say so). The painter then paints the brush with whatever the map already has, the exact fallback the class exists to prevent. The TSV row is parsed by three separate copies: donors() (216-229), cliffDonor() (280-293, re-opening the file) and rowNamed() (357-362, which skips the v-band columns), and the shared comment 'row is: BRUSH region area mesh material injectName [vLip vMid vFoot]' names three optional columns while four are read (vWall). ensureNamedMaterial and ensureMaterial duplicate the whole append + find-by-name + blank-to-one-vertex + validate sequence (420-455 vs 512-548). textureNamesOf (595-614) is yet another hand-rolled BCH material texture-name reader (0x2C stride, slots 0x1C/0x20/0x24) beside PropDatabase.getMaterialTextureNames.

**Fix.** Throw (or return a typed refusal) on every failed import, parse the row once into Donor, and share one import routine.

<a id="f094"></a>

## F094 - The tile painter's textured top-down preview is built but never wired: TerrainTextures.build has no caller

**low** | unfinished | `src/ctrmap/formats/tilemap/TerrainTextures.java:27`

TerrainTextures' class doc promises 'a top-down in-game textured preview (grass looks like grass, water like water) instead of flat swatches'. grep over src: TerrainTextures.build( has 0 callers in production and 0 in tests; image(), any() and resolveMaterial are reachable only through it. The only used entry is materialImage (CtrmapMainframe:2917, the blank-canvas material picker). resolveMaterial (98-108) also contradicts the doc's 'The mapping matches the geometry builder': it is a bare name-substring match with none of PaintedMaterials.resolveMesh's edge/sprite/flatness filters, so it would preview the very cliff/sprite materials the builder learned to reject. Same package: TileClip.segmentTouchesRegion is referenced only by a suite although TileClip's class doc presents it as how vertical triangles are classified - compositeMesh uses clipVerticalPoly instead - and segmentRectInterval/segmentIntersectsRect are two copies of the same Liang-Barsky loop.

**Fix.** Either wire the textured preview into the painter grid (sharing resolveMesh's filters) or delete it; fix TileClip's doc and fold the two Liang-Barsky copies.

<a id="f097"></a>

## F097 - Camera editor writes XY's neutral-zone value on ORAS and rewrites two unknown fields on any edit

**low** | data-integrity | `src/ctrmap/humaninterface/CameraEditForm.java:209`

saveCamera: ticking 'Neutral camera zone' on a non-neutral camera writes isNeutral = 0x2 with the comment 'OA uses 0x1 for this while XY uses 0x2 ... for now, 2 (XY) is the default' (203-209) - the reference game is ORAS, and the neutralCheckbox handler at 877 does the same (? 2 : 0) on every toggle. Any change to a camera also sets unknown01or03 = (movementDirection == 7) ? 3 : 1 (241, 'no idea what the difference between 00 and 01 is'), so a retail 00 becomes 01; and getMotionIndex maps an unknown movementDirection to row 'Static' with only a System.out (290), after which saveCamera writes 7 unless the original was 0 or unknown01or03 was 3 (230-237) - an unrecognised code is replaced, the opposite of the preserve-unknown rule NpcMoveCodes/WarpTransitions adopted. getFloatFromField (251-259) has no caller. ADDENDUM - A NEUTRAL-ONLY CHANGE IS NEVER SAVED. neutralCheckboxActionPerformed writes the LIVE record, 'cam.isNeutral = (neutralCheckbox.isSelected()) ? 2 : 0' (CameraEditForm.java:876-877). The save then builds cam2 from the form and copies 'cam2.isNeutral = cam.isNeutral' when the box is ticked (:204-211), and marks the file modified only 'if (!cam.equals(cam2))' (:239-244). After a toggle and nothing else, cam and cam2 agree, so f.modified stays false and the neutral change is never written, even though the in-memory record holds it until the zone closes. The handler beside it, transTimeActionPerformed, is the tree's only remaining NetBeans '// TODO add your handling code here:' stub (:904-906; git grep finds exactly one), an empty handler wired to the transition-time field.

**Fix.** Take the neutral value from the profile (or from the area's other cameras), preserve unknown01or03 and unknown motion codes unless the user changed the control, delete getFloatFromField.

<a id="f104"></a>

## F104 - Typing a movePerm2 code into 'Move events' compares the RAW code with the dropdown ROW count, so 5-6 named codes per game never sync and the dropdown keeps showing the previous behaviour

**low** | correctness | `src/ctrmap/humaninterface/NPCEditForm.java:2275`

mp2ActionPerformed (2274-2278) guards with (Integer) mp2.getValue() &lt; mot2Dropdown.getItemCount(); movePerm2 rows are NOT codes (NpcMoveCodes:204 'row N means code movePerm2Raws(game)[N], NOT code N'; base raws {0,1,2,3,4,7,9,12,14,15,16,20}). Measured with scratchpad/probe/Mp2Probe.java against the compiled tree (control: every named code round-trips through movePerm2Index/Raw): XY 5 refused [15 16 20 22 23], ORAS 6 [14 15 16 20 22 23], SM/USUM 5 [12 14 15 16 20]. The saved value is right (saveEntry reads mp2), the label beside it is the previous one. showEntry (277) tests the mapped index correctly; this handler is the one call site still in code space - the exact confusion NpcMoveCodes was written to remove. ADDENDUM - THE FIELD'S OWN COMMENT GIVES THE WRONG CODE. ZoneEntities.NPC declares 'public int movePerm2 = 0; //4 on items and 10 on double battle paired NPCs' (ZoneEntities.java:249). NpcTemplates, which measured the retail pairs, says 'all 21 pair NPCs use movePerm2=9' and builds pairs with TRAINER_PAIR_MOVE_EVENT = 9 (NpcTemplates.java:26-27, :99-101); NpcMoveCodes names no retail NPC with 10. The format class is the first place anyone reads what the field means, and it is the stale one.

**Fix.** Test getMot2Index(raw) and set the dropdown to it (-1 for an unnamed code); put the sync in one helper that showEntry and the handler share.

<a id="f108"></a>

## F108 - 'New entry' on a loose GR map (no prop registry) throws NullPointerException half way, leaving the prop appended, the form inert and the model list out of step

**low** | correctness | `src/ctrmap/humaninterface/PropEditForm.java:1522`

File > Open GR Mapfile reaches PropEditForm.loadDataFile(GR, textures) via CtrmapMainframe:629 'mPropEditForm.loadDataFile(region, null)', which calls loadDataFile(props, null, ...) (709) - reg is null by design on that path. btnNewEntryActionPerformed (1513-1527): loaded=false; props.props.add(newProp) (1521); models.add(reg.getModel(newProp.uid)) (1522) - unguarded, NPE on the event thread. loaded stays false (the entry box, Save and the coordinate listeners all test it), props.props now has one more element than models, and props.modified was never set. Every other reg use in the class is guarded: placeSelectedPaletteProp 446, renderCM3D 859, renderOverlayCM3D 871, saveAndRefresh 1034, updateModel 1001. btnRemEntry (1486-1511) likewise runs models.remove(-1) when the box is empty.

ADDENDUM: Also Remove entry on the same single-region path throws IndexOutOfBoundsException (PropEditFormGuardsTest.java:446-454, pinned).

**Fix.** Guard reg as the palette path does (or give a loose GR an empty registry), and do the list mutations after anything that can throw.

<a id="f113"></a>

## F113 - Entity-form helpers are re-written per form: value-class setters x6, leading-int parser x3, script dropdown fill/sync x2, typed-text escaper x2 - while a shared Forms helper class exists

**low** | duplication | `src/ctrmap/humaninterface/TriggerEditForm.java:52`

grep over src/ctrmap (tests excluded): setIntegerValueClass in NPCEditForm:1563, TriggerEditForm:52, WarpEditForm:106; setFloatValueClass in PropEditForm:700, CameraEditForm:36, MatrixEditForm:54; parseLeadingInt in NPCEditForm:608, TriggerEditForm:111, PokePickers:94; populateScriptDropdown NPCEditForm:581 + TriggerEditForm:84 and syncScrDropdown/syncScriptDropdown NPCEditForm:595 + TriggerEditForm:98 (bodies identical but for the field names); escapeTypedText NPCEditForm:825 + TilePainterForm:1118 (identical). ctrmap.humaninterface.Forms already hosts shared form helpers (getFloatFromDocument, stackedForm, addLabeled). The per-form value-class setup is also where F095 (ea14 never given Short) and F096 (zoneRefNumber cast both ways) came from: each form decides by hand which field gets which boxed class. ADDENDUM - THE 3D PICKING WORKAROUND IS PASTED INTO TWO FORMS. Picking.isBoxSelected returns true as soon as the projected box contains the click, under a comment saying 'GLU is buggy and sometimes completely fucks up the maths in certain camera angles. We can work around this by checking if the actual object is seen by the camera' (Picking.java:52-54). The method does not do that check. Each caller does, with the same ~18-line loop that gluProjects every vertex of every mesh and keeps the hit only if one lands on screen: NPCEditForm.doSelectionLoop (:2364-2381) and PropEditForm's selection sweep (:1595-1613, with the comment pasted too). The two copies differ already: neither applies the object's rotation, and the prop copy ignores the prop's scale that its own isBoxSelected call passes. H3DRenderingPanel's gizmo picks (:206-216) use isBoxSelected without the workaround. The check belongs inside Picking, once.

**Fix.** Move these to Forms (one formattedField(Class) factory that sets the value class at construction, one script-dropdown helper), and delete the copies.

<a id="f117"></a>

## F117 - World/tile/screen coordinate conversions are re-derived inline at every use - 17 copies of the 400/720 pixel scale and 15 of the 18\*t+9 tile-to-world rule - with no shared converter

**low** | duplication | `src/ctrmap/humaninterface/tools/WarpTool.java:65`

grep -E '400d? ?/ ?720d?|720f? ?/ ?400f?' over src/ctrmap (tests excluded) = 17 lines: PropTool 5, TriggerTool 4, WarpTool 8. grep for the tile&lt;->world forms ('\* 18f + 9f', '\* 18 + 9', '- 9f) / 18f') = 15 lines: ZoneEntities 1, NPCEditForm 2, TilePainterForm 6, WarpEditForm 2, WarpTool 4. Further unpatterned copies seen while reading: MatrixEditForm 100d/720d and 25/100 cell sizes (drawToolGraphics, checkCamTool), PaintForm cellX\*40, TileMapPanel '\* 720f + 360f' region placement (342, 545, 562), PropTool/TriggerTool/WarpTool each recomputing imgstartx/imgstarty from the panel's size (also in TileMapPanel.paintComponent and getRawAtViewportCentre). A change to the map view's scale or origin has to find every one; MatrixPanelInputManager's comment (52-54) records that exactly this happened once ('a change to the panel's layout had to find it here'). ADDENDUM - MORE INLINE SCALES, IN THE TWO 2D EDITORS. MatrixEditForm.drawToolGraphics hard-codes its cell scale as 100/720 and a 25-pixel step, and builds 'new Font(...)' per cell per repaint (lines 226, 250, 276; lead 127). PaintForm spells the region cell as the literal 40 in localX/localY and drawOverlay (:524, :528, :929) beside its own DIM constant, and sizes its carrier tilemap as 'new byte[6528]' (:856) with no named derivation (lead 140). These are the same 'derive it again inline' shape as the 17 copies counted here.

**Fix.** One MapCoords helper owned by TileMapPanel (tileToWorld, worldToScreen, screenToTile, imageOrigin) used by every tool and form.

<a id="f119"></a>

## F119 - The terrain-kit harvester produces two catalogues nothing consumes, and its 'dominant cliff material' is the first one it meets

**low** | unfinished | `src/ctrmap/tools/TerrainHarvester.java:58`

TerrainHarvester.main writes terrain_kit.tsv (default ./terrain_kit.tsv, 58) and terrain_runs.tsv (123) - its javadoc (21-33) presents them as the fix for synthesised cliffs ('Game Freak's artists already modelled this terrain; the fix is to use it'). Measured: git ls-files has neither file (the shipped harvest outputs are oras_buildings\*.tsv, oras_ground_materials.tsv, oras_terrain.tsv), and grep 'terrain_kit|terrain_runs|TerrainHarvester|floorHeights(' over src/ctrmap outside this file finds only DuplicateWorkTest:91 naming the file - no production reader. A producer with no consumer: the cliff generator (PaintedCliffMesh) still synthesises from the grid. Second defect: rockDensity (198-247) comments 'whichever cliff material contributes most triangles here is what this tile looks like', but rockBest counts ALL cliff triangles in the tile and 'if (++rockBest[ty][tx] > 0 &amp;&amp; rockMaterial[ty][tx] == null)' (240) is true for the first triangle seen, so the first mesh in model order wins. rockMaterial is also a static mutable array (195) shared across calls.

**Fix.** Either wire the kit into the cliff path (the javadoc's stated purpose) or delete the tool; count per-material triangles per tile and pick the max.

<a id="f120"></a>

## F120 - Updater leaves '&lt;name>.old-&lt;hex>' files beside the installed app whenever a file is in use during apply, and nothing ever removes them

**low** | resource-leak | `src/ctrmap/update/Updater.java:583`

replace (570-604): when Files.copy over a file fails (Windows: still mapped by a process) it renames the target to new File(to.getParentFile(), to.getName() + '.old-' + Long.toHexString(System.nanoTime())) and copies the new file into place - the renamed file stays in the INSTALL folder, not in the hidden staging folder. grep '\.old-' over src/ctrmap (tests excluded) = 1 line, this creation (control: it is found); no sweep, no startup cleanup, run.bat/build scripts have no match either. The class javadoc (22-29) promises 'nothing here is ever visible next to the app ... there is only ever one CTRMap'; each update that hits a locked file adds a stale jar/dll copy next to the real one, indefinitely.

**Fix.** Move set-aside files into the staging folder (or delete .old-\* in sweep(), which already runs at every startup).

<a id="f124"></a>

## F124 - Six spinners for unsigned 16-bit fields (NPC model, spawn flag, NPC/prop registry uid and model) have no lower bound, so negative values are accepted and saved as 0xFFFF-wrapped values that reload as different numbers

**low** | ui | `src/ctrmap/humaninterface/NPCEditForm.java:1762`

git grep 'SpinnerNumberModel([^)]\*null' over src: 6 hits (control: NPCEditForm alone = 2) - ADPropRegistryEditor.java:230 uid, :236 bm; NPCEditForm.java:1762 mdl, :1771 evtFlag; NPCRegistryEditor.java:201 uid, :205 mdl. All are SpinnerNumberModel(0, null, 65535, 1). The fields are read with readUnsignedShort and written with writeShort((short) v) (ZoneEntities.java:289-336, NPCRegistry.java:263/313), so -1 is written as 65535 and the editor shows 65535 after reload. Set in the .form (SpinnerModel without minimum).

**Fix.** Set minimum=0 in the six .form SpinnerModels and regenerate.

<a id="f125"></a>

## F125 - Tile raw-byte spinners are unbounded: typing a hex value past FF (or negative) is stored truncated to its low byte with no warning; the hex formatter class is copy-pasted in TileDBWriter

**low** | data-integrity | `src/ctrmap/humaninterface/TileEditForm.java:162`

byte0..byte3 are new JSpinner() with no setModel (TileEditForm.form 107-150, no setModel in TileEditForm.java); HexFormatterFactory.stringToValue parses any hex; the listener stores ((Integer) src.getValue()).byteValue() (162, and 171-176 for SetTool). Probe TileByteProbe (control: bounded model reports 0..255 = true): default model min=null max=null; typed 100 -> stored 0x00, 1FF -> 0xFF, -1 -> 0xFF. HexFormatterFactory is duplicated verbatim at TileDBWriter.java:400 and TileEditForm.java:413 (only the TileDBWriter copy null-guards valueToString).

**Fix.** Give the four spinners SpinnerNumberModel(0, 0, 255, 1) in the .form; move HexFormatterFactory to one shared class.

<a id="f127"></a>

## F127 - A camera whose motion code the editor does not name is shown as 'Static' and rewritten to 7 the moment the user switches entries, with no edit made

**low** | data-integrity | `src/ctrmap/humaninterface/CameraEditForm.java:279`

getMotionIndex maps 0/1/2/7 and answers 2 ('Static') for anything else after System.out.println('Unknown motion type'). entryBoxActionPerformed (897-901) calls saveCamera() before showing the next entry; saveCamera keeps the raw code only for 0 or when unknown01or03 == 3, otherwise writes getMotionRaw(2) = 7; CameraData.equals compares movementDirection (CameraData.java:123), so cam != cam2, the entry is replaced and f.modified = true. The user then gets a 'Camera data' keep-changes prompt for an edit they never made. Same class as F126 (unnamed raw value collapsed to a row) and F123 (lossy round-trip through the widget). Unknown-code frequency in retail data UNMEASURED (no game data in repo).

**Fix.** Show unknown codes as an extra 'Raw N' row (or no selection) and write the raw code back when that row is selected.

<a id="f128"></a>

## F128 - Dead resources and their dead converter: six resource files no code loads (three pngs, three MoveArrow\*.ply Blender sources, ~92 KB in the jar), and ply2cmvd.bat launches a class (ctrmap.PLY2CMVD) that no longer exists

**low** | dead-code | `src/ctrmap/resources/test.png:1`

Per-resource name search across src (prod, tests) and all other tracked files: 0/0/0 hits for test.png, textFlags.png, npcreg.png, MoveArrowX.ply, MoveArrowY.ply, MoveArrowZ.ply. Control: the same scan finds MoveArrowX.cmvd (CM3DNavigator.java:29), DefaultTileset.mets (Workspace.java:92). A first pass reported 0 for every _tool_\* icon too - those are built at runtime (Forms.java:54-57 prefix + '_stale.png', prefixes in WorldEditorToolbar.java:31-40), so a second search for constructed names was run: no constructed name reaches the six. The .ply are authoring sources (CMVD has a writer, CMVD.java:45, but nothing converts .ply), 23 KB each; the pngs date from 2019 (84e1d54).

ADDENDUM: THE CONVERTER IS GONE TOO: ply2cmvd.bat (root, 2 lines) runs 'java -cp build/classes ctrmap.PLY2CMVD' and no class PLY2CMVD exists anywhere in the tree (git grep 'class PLY2CMVD' over src: 0; git ls-files -i ply2cmvd: only the .bat). So the three .ply 'sources' can no longer be turned into the .cmvd gizmos that ship, and the script fails with ClassNotFoundException.

**Fix.** Delete the three pngs; move the .ply sources out of src/ (e.g. tools/assets) so they stop shipping.

<a id="f130"></a>

## F130 - Bundled data tables load silently: a missing resource becomes an empty table in all five loaders, and the building catalog also drops short rows and lets one bad number truncate the rest

**low** | silent-failure | `src/ctrmap/formats/h3d/BuildingCatalog.java:93`

load(): 'if (f.length &lt; 14) continue;' (silent skip); Integer.parseInt on 11 columns inside a loop whose only catch is OUTSIDE the loop ('BuildingCatalog: load ... failed' to System.err), so a bad cell at row N silently truncates the catalog to rows &lt; N; a missing resource returns silently (in == null). Today's files are clean on both counts (tsvcheck.py: 0 short, 0 non-int, control planted both and saw both), so this is a latent trap for the next harvest, which regenerates a 3,535-row file by tool. The 17th column (signature) is never read by the app.

ADDENDUM: CLASS MEASURED (git grep getResourceAsStream in prod src, 6 sites; ResourceAccess.getStream is the 6th and NPEs instead): BuildingCatalog.java:96 'if (in == null) return;', PaintedMaterials.java:287 'if (in != null)' (its javadoc at 272-278 says the failure 'is said on stderr rather than swallowed' - true for an exception, false for a missing resource), TerrainCatalog.java:200-203 'if (in == null) { donors = read; return donors; }', GfHash.java:75 'if (in != null)' (native-name table: every hash prints unnamed), AppVersion.java:34 'if (in != null)' -> version stays UNKNOWN, which the comment says the updater treats as 'offer any release'. Five silent empties; each table's absence produces output indistinguishable from 'nothing matched'. ADDENDUM - A SIXTH BUNDLED LOADER WITH THE SAME SHAPE. CMVD(InputStream) prints 'CMVD magic invalid.' to stderr and returns an EMPTY mesh (CMVD.java:60-67). Its only callers load the three bundled move-gizmo arrows (CM3DNavigator.java:29-31, MoveArrowX/Y/Z.cmvd), so a damaged or missing resource gives a 3D navigator with invisible arrows and a line on a console the shipped build does not have (F219).

**Fix.** Parse per row with the row number in the error, refuse (or count and show) skipped rows, and validate the box ordering (F129) at load.

<a id="f131"></a>

## F131 - Six whole-file reads still use new byte[in.available()] + one unchecked in.read(b) with the stream closed only on success, where the rest of the code (67 sites, 22 files) uses Files.readAllBytes

**low** | api-trap | `src/ctrmap/resources/ResourceAccess.java:16`

git grep '.available()' over prod src (control: ResourceAccess.java listed, 2 hits): sized-buffer reads at ResourceAccess.java:16 (getByteArray) and :30 (copyToTemp), NPCEditForm.java:708, :779, :810, TextEditor.java:126. None checks read()'s return value; none uses try-with-resources, so an IOException leaks the stream; ResourceAccess on a missing resource dereferences a null stream (NPE escapes the IOException catch); copyToTemp also leaks its FileOutputStream on a write error. Today's inputs are local files and jar resources under 10 KB (largest getByteArray/copyToTemp target is below the 7,975-byte textFlags.png), where available() is exact and one read fills the buffer - a latent trap, not a live truncation. Files.readAllBytes: 67 sites in 22 prod files (git grep -c).

**Fix.** Replace the six with Files.readAllBytes / a single ResourceAccess.readAll(InputStream) that loops to EOF; make a missing resource throw with its name.

<a id="f133"></a>

## F133 - The release jar still ships the four developer harvesters (1,649 lines, each with a main) - package.ps1 strips ctrmap/tests and \*Test.class, the same 'a release is the program, not its proofs' rule never reached ctrmap/tools

**low** | build-debt | `package.ps1:64`

package.ps1:64-82 removes ctrmap\tests and refuses any top-level \*Test.class; src/ctrmap/tools holds BuildingHarvester (687), TerrainHarvester (454), TerrainDonorHarvester (257), GroundMaterialAudit (251), all with static main. Production references (git grep outside tools/ and tests/): 0 code references - GroundMaterialAudit and TerrainDonorHarvester appear only in javadoc {@link}s (PaintedRegionBuilder.java:912, TerrainCatalog.java:24). They read a game dump and write TSVs (TerrainHarvester.java:58 'terrain_kit.tsv', :123 'terrain_runs.tsv' - the kit with no consumer, F119), runnable from the shipped jar like the suites the script's own comment objects to. The exclusion is a list of two names, not the class 'not part of the program'. ADDENDUM - THE HARVESTERS OVERWRITE SHIPPED RESOURCES BY DEFAULT. Run with one argument, BuildingHarvester writes 'src/ctrmap/resources/oras_buildings_auto.tsv' (:65) and TerrainDonorHarvester writes 'src/ctrmap/resources/oras_terrain.tsv' (:49), both tracked resource files the release jar ships. A developer who points either at a dump that is not retail, or at another game, rewrites the shipped catalog in place, with no prompt and no diff. GroundMaterialAudit's javadoc lists four arguments ('FieldData, AreaData, ZoneData, output TSV', :42), while main refuses fewer than five and reads the output path from args[4] (:57, :66), because the MapMatrix was added without updating the doc. TerrainDonorHarvester probes three hard-coded ORAS region ids, PROBE_TARGETS = {153, 745, 1} (:41).

**Fix.** Stage only what the app reaches (e.g. exclude ctrmap/tools too, or move the harvesters to a tools source root outside src/).

<a id="f134"></a>

## F134 - Measured numbers are restated by hand across the docs and scripts and have drifted: battery size five ways (124, 128, 129, 130, 137 vs 136 registered), mutation sweep three ways (227/227, 209/209 vs baseline 202/202, 35 vs 33 files), guard suites 15 vs 24

**low** | doc-drift | `ARCHITECTURE.md:13`

suitecheck.py (control: SourceSeamTest found, a fabricated name not): test.ps1 registers 136 distinct suites (no duplicates, none naming a missing class) + 'Mutation harness selftest'; 137 classes under src/ctrmap/tests have a main, the one unregistered being GarcContentDiff (a diff utility, not a suite). Prose counts (git grep): ARCHITECTURE.md:13 '124-suite battery', PROPERTIES.md:3 '128 suites', build.ps1:30 '129 suites', tools/guard/classify_guards.py:14 and commit_guard.py:645/650 '130 suites', README.md:116 '137 suites'. None carries the tree it was counted on - the class the project's own CLAUDE.md rule ('A RECORDED MEASUREMENT MUST CARRY THE SUBJECT') names.

ADDENDUM: Same class, other measurements restated by hand (2026-10-06): the mutation sweep is '227 of 227 ... on 84db86d' and 'those 35 files' in TESTING.md:161-173, '209/209' in PROPERTIES.md:221, while mutation_baseline.json _meta records measured_at b48610a, attempted 202, killed 202, over 33 file entries (mbcheck.py). TESTING.md:87 'the 15 named \*Guard\*Test'; git ls-files 'src/ctrmap/tests/\*Guard\*Test.java' counts 24. PROPERTIES.md:209-210 '57 of the 128 registered suites still have no plant' carries the stale 128 as its denominator. BatteryHygieneTest:678 checks only catalog and suite counts, in four docs (not ARCHITECTURE.md or PROPERTIES.md). ADDENDUM - THE SAME CLASS IN SUITE PROSE, static imports (git grep over tracked .java, 2026-10-06; control: GeoEditForm.java:29 is found). Files with an 'import static ctrmap.CtrmapMainframe' line: 19, all production. The suites state it four ways, none of them 19: ClassFileScanner.java:22 '33 files', SourceSeamTest.java:175 '32 files ... name the mainframe's widgets that way', LoadedZoneTest.java:60 and MainframeEdgesTest.java:46 'thirty-six'. SourceSeamTest.java:376 says '33 files ... static-import a class'; measured, 120 tracked .java files carry some 'import static ctrmap.' (67 production). None of these figures carries the subject or date it was measured against. Separately, only 9 live 'Class(.java):NNN' line references exist outside dated records (scratchpad/m_linerefs.py; control: the census's H3DTexture.java:208 is caught as past-EOF), and all 9 are in range, so stale line references are not a live class here (the dated census holds 2,419). ADDENDUM - CORRECTION TO THIS FINDING'S OWN LINE-REFERENCE CLAIM. The sentence above, 'all 9 are in range, so stale line references are not a live class here', was a clean negative from a check that only asked whether the line EXISTS. Re-checked by CONTENT (scratchpad/m_linerefs2.py prints each cited line), all three references in source and tests point at the wrong line. WorkspaceIntegrity.java:69 says 'ZoneHeader:249 reads getWorkspaceFile(NPC_REGISTRIES, areadataID)'; ZoneHeader:249 is now a throw in fetchArchives, and the read is at :268, through files.staged, not getWorkspaceFile. ForkGuardsTest.java:276 says 'The guard at AreaForker:247 says exactly that'; :247 is the middle of a javadoc sentence. IntegrityTest.java:146 says 'The sentence at WorkspaceIntegrity:250 is the guard'; :250 is a closing brace (lead 197 found it at :261). The six in tools/mutate2.py narrate past mutation runs, and their lines (TilePainterForm.java:518, CtrmapMainframe:1256, ...) now hold braces and returns. They are history without a date. So 3 of 3 live source references are stale. The class is live and small, and it is exactly what a line number in prose does on the next edit above it. ADDENDUM - PROSE ABOUT THE TREE, IN TESTS, THAT THE TREE HAS OUTGROWN. EditorBench's 'WHY THIS EXISTS' (EditorBench.java:43-50) says every tool 'reaches its data through import static ctrmap.CtrmapMainframe.\*' and that AbstractTool's constructor calls onToolInit, so 'a tool cannot even be CONSTRUCTED' without the window. Today 2 of the 14 tool sources import the window's statics (git grep), and AbstractTool runs onToolInit from start(), with a javadoc saying it was moved out of the constructor (AbstractTool.java:30-47). CommitGuardTest's section 2 javadoc says 'Only a subject beginning "fix" is policed; everything else passes untouched' (:113). The gate it tests now inspects the diff, not the subject (the suite's own later section, :690-699). Both read as a description of the system, and both describe an older one. ADDENDUM - THE BATTERY'S OWN PROSE: DEFECTS CALLED OPEN THAT ARE FIXED, AND COUNTS THAT DRIFTED. Measured or read 2026-10-06. (1) Static-import counts: SourceSeamTest:175 says '32 files ... name the mainframe's widgets that way', LoadedZoneTest:60 and MainframeEdgesTest:46 say 'thirty-six', and SourceSeamTest:376 says '33 files ... already static-import a class'. git grep finds 19 files static-importing CtrmapMainframe (the same 19 F008 counts, which is the control) and 120 static-importing any ctrmap class. (2) Defects described as open that the tree has fixed: ModDeployerTest:41-42 lists 'garcContentsEqual calls two UNREADABLE files equal', which the same suite (:350) and ModDeployer (:315-339) record as fixed. TileMapPanelStateTest:423-426 says loadTileMap 'clears neither this history nor Selector', but TileMapPanel.java:252-253 now does both. ZoneManagerTest:38-41 says out-of-range 'does NOT produce the IOException ... The current exception type is pinned', while its :187-200 says the defect is fixed. (3) Pointers to a 'suspected_defects' note (MainframeActionGuardsTest:227, TileMapPanelStateTest:127-128) name a file git ls-files does not have. (4) Snapshot counts: WorkflowGuardsTest:16 '127 suites' (test.ps1 registers 136); DuplicateWorkTest:169 'the snapshot ... 268 classes', while tools/guard/classes.txt holds 263 lines. commit_guard.source_digest's comment says CLAUDE.md sits above the repository root and git has never heard of it (:104-110), but CLAUDE.md is tracked at the root now.

**Fix.** Print the count from test.ps1 at run time and drop the hand-written numbers, or have a suite assert each doc number against the registered list.

<a id="f135"></a>

## F135 - The public-static ratchet sits two above the truth (ceiling 36, measured 34), so two new globals pass unnoticed; ARCHITECTURE.md states the count as both 36 and 38 and gives CtrmapMainframe 20 where it has 18

**low** | process-debt | `src/ctrmap/tests/GlobalStateTest.java:118`

StaticFieldProbe over the compiled tree (same rule as ClassFileScanner.java:309-310: public, static, not final, not synthetic; ctrmap/tests excluded; 747 classes loaded without init, 0 unloadable; control: a Selector class found with such fields): 34 fields - CtrmapMainframe 18, Workspace 5, MatrixSelector 6, Selector 5. GlobalStateTest.java:118 CEILING = 36 and checks 'globals.size() &lt;= CEILING' - the suite's own javadoc (111-114) calls a ceiling above the truth one that 'cannot fail on a real regression'. ARCHITECTURE.md:228 '36 public static mutable fields', :233 'CtrmapMainframe

20', :265 'if the count of 38 rises'.

ADDENDUM: TESTING.md:135 adds a third figure for the window: 'it still owns nineteen public statics' - StaticFieldProbe (control: a Selector class found holding such fields) measures 18 in ctrmap.CtrmapMainframe (34 total: Mainframe 18, Workspace 5, MatrixSelector 6, Selector 5).

**Fix.** Set CEILING to the measured 34 (or make the suite fail when measured &lt; CEILING, forcing the ratchet down) and print the number into ARCHITECTURE.md from the suite rather than by hand.

<a id="f136"></a>

## F136 - Gen 7 (Sun/Moon, USUM) support is two placeholder profiles that can never be selected: every answer is TODO/null/-1, so GameProfile.detect skips both and nothing else constructs GameType.SM/USUM

**low** | unfinished | `src/ctrmap/gamedef/SmProfile.java:26`

SmProfile.java:28,33,43 and UsumProfile.java:25,30,40 return null/-1 with '// TODO measure from a ... dump'. GameProfile.detect (GameProfile.java:299-312) tries archivePath(SOUND_BCSAR) and detectFile(); both are null for the two profiles, so neither can ever be returned. grep for GameType.SM|GameType.USUM|SmProfile|UsumProfile outside the two files and tests: one hit, the ALL registry (GameProfile.java:276). ARCHITECTURE.md:4 lists Sun/Moon and USUM as supported games and :300 carries the porting outline; QUICKSTART.md:16-17 says Gen 7 is not supported. Consequence: refusal texts that name Sun/Moon (ZoneLoadingPanel.java:1786-1795, GameProfile.java:265 javadoc) are written for a game the editor cannot open.

ADDENDUM: docs/species-scope.md:33-41 tells the reader 'SM 1 stub' verified feature and that 'Gen 7 is not blocked on architecture ... It is blocked on MEASUREMENTS'; SmProfile.supports returns false for every Feature (SmProfile.java:36-38), and F054 shows the GARC reader cannot read the v6 archives Gen 7 uses - a format-layer gap, not only a measurement gap.

**Fix.** Either keep the profiles out of the registry until a dump is measured (and say so in ARCHITECTURE.md:4), or leave them and mark them in DuplicateWorkTest's UNREACHED table as PLANNED, so the scaffolding is named rather than looking live.

<a id="f137"></a>

## F137 - The quick start guide that Help opens describes features that have since changed: a button that no longer exists by that name, a zone limit the program now lifts, signs limited to 69 zones, and 'no undo' beside a Restore-from-backup menu item

**low** | doc-drift | `QUICKSTART.md:109`

QUICKSTART.md:109 names 'Add new zone (EXPERIMENTAL)...' and :113-116 says whether the game accepts a 537th zone 'is unknown until tested'; the button is 'Add zones (lift limit)...' (ZoneLoadingPanel.java:830) and generates the code.ips zone-limit patch (ZoneLimitPatch.buildIPS, ZoneLoadingPanel.java:1877), as README.md:99-100 advertises. QUICKSTART.md:84-85 says Sign is 'Available in the 69 zones whose script already contains a sign routine'; NPCEditForm.java:801 and :1062 now pick a donor and transplant the sign routine (SignWrapperInjector). QUICKSTART.md:19-20 says Pack Workspace has 'no undo'; Options > 'Restore from pristine backup...' exists (CtrmapMainframe.java:907) and README.md:128-129 promises the backup. QUICKSTART is opened by Help > Quick start guide (CtrmapMainframe.java:951) and shipped by package.ps1:92,131. Measured guard scope: BatteryHygieneTest:678 reads QUICKSTART.md only for catalog and suite counts; MainframeShapeTest checks 'Menu > Item' names in production strings, and QUICKSTART writes 'Menu -> Item' with an arrow and names buttons, so no suite reads its UI names.

ADDENDUM: The owner's in-emulator checklist has the same defect: TESTING.md:328, 368, 408, 429 and 480 send the tester through 'Tools -> ...' (Tile painter, Edit area fog &amp; lighting, Rename zone, Edit shop inventories, Remove added zones), while TESTING.md:313-315 itself records 'Menus: Tools replaced by Map / Zone / Game Data'. The menu bar (CtrmapMainframe.java:858-917) has File, Map, Zone, Game Data, Options and Help; grep for a JMenu named Tools finds 0.

**Fix.** Correct the four passages. Extend the MainframeShapeTest menu-path check to the shipped docs (README, QUICKSTART) and to the arrow form, so a renamed item or button breaks the guide's check rather than going stale.

<a id="f139"></a>

## F139 - The reverse-engineering docs cite their probes and raw results under wt/_state/..., which is not in the repository: their measurements cannot be re-run from the tree, and only one probe (docs/probes/Param.java) was kept

**low** | process-debt | `docs/customization-assets.md:614`

git ls-files

grep -c '^wt/' = 0 and no wt directory exists in the checkout. Citations: docs/customization-assets.md:613-617 (eleven probes Enum1..Line1 in wt/_state/queue1/assets/probe/), docs/customization-scope.md:377 (wt/_state/queue2/runtime/ rtti.py and others), docs/items-plan.md:5 (raw findings wt/_state/items_feasibility.json). docs/ holds one probe source, docs/probes/Param.java. The dress-up decoding itself is reproducible (DressUpIndexTest); the code.bin call-site tracing, the 298-archive keyword scan and the item feasibility data are not.

**Fix.** Commit the probes the docs rely on under docs/probes/ (as Param.java already is), or mark each figure that depends on a missing probe as not reproducible from the tree.

<a id="f141"></a>

## F141 - The NetBeans project is a 2019 leftover that still builds: 9 of its 11 run configurations name classes that no longer exist, its per-user files (a Windows home path, open editor tabs) are committed, it links an AbsoluteLayout library nothing uses, and an IDE build writes into build/classes without build.ps1's structure gates

**low** | build-debt | `nbproject/configs/ParserLoader.properties:1`

git ls-files nbproject = 20 files. nbproject/configs main.class values: CameraDataTest, CollDecoder, GLRenderTest, GfmapMainframe, ParserLoader, TermCheck - git ls-files finds none of these files; gfmap.ParserLoader and gfmap.TileDBWriter name a package 'gfmap' that does not exist; ctrmap.formats.garc.GARC has no main (grep -c 0). Only ctrmap.CtrmapMainframe ('GfmapMainframe (1)', the active config per nbproject/private/config.properties) and ctrmap.formats.text.TextFile resolve. Committed per-user state: nbproject/private/private.properties:8 (user.properties.file=C:\\Users\\\*\\AppData\\...\\NetBeans\\11.0), private.xml (ten open-file URLs under C:~/eclipse-workspace/GRParser), private/config.properties, private/configs/; NetBeans keeps nbproject/private out of version control by default. project.properties:47-50 puts ${libs.absolutelayout.classpath} on javac.classpath; grep -rl AbsoluteLayout src = 0. project.properties:12 build.classes.dir=build/classes - the directory build.ps1 stamps - and private.properties:1 compile.on.save=true; build-impl.xml:1097-1101 compiles and copies all of src/, src/ctrmap/tests included, with none of build.ps1's gates. The stamp makes test.ps1 refuse the result (TESTING.md:38-43), so the cost is a refused battery, not a silent one. application.title / dist.jar are still GRParser.

ADDENDUM: GUARD BLIND SPOT, measured (homepath.py: SourceSeamTest's own HOME_PATH regex over every tracked file; control: both synthetic shapes match): 11 real home-directory lines - nbproject/private/private.xml:6-15 'C:/Users/*>' x10 and nbproject/private/private.properties:8 - and SourceSeamTest, whose javadoc :24-25 promises the home-path rule covers 'ANY file, tests included', never visits them: SKIP_DIRS :72 contains "nbproject" and TEXT_EXT :69-71 has no .py (3 more matches in .py files are synthetic examples). The rule written for this leak exempts the folder the leak is in.

**Fix.** Decide whether the IDE project is supported. If not, delete nbproject/ and build.xml (README.md:135 already says no IDE is required). If it is, drop the dead run configurations and the AbsoluteLayout reference, untrack nbproject/private, and point build.classes.dir somewhere build.ps1 does not stamp.

<a id="f142"></a>

## F142 - BchMapModelTest's append and u8-to-u16 upgrade checks answer OK when they found nothing to test, and the suite asserts no count of either, so a regression that makes every append throw or never upgrade passes green

**low** | test-debt | `src/ctrmap/tests/BchMapModelTest.java:304`

checkAppendGeometry (211-250) catches IllegalStateException and moves to the next mesh, then returns null ('one successful grow per region is enough') - also when no mesh grew; checkU16Upgrade (254-305) catches every Exception and skips meshes whose u16 count did not rise, ending 'return null; // no u8 mesh available to upgrade'. main() (27-144) counts tested regions, meshes and vertices but neither successful grows nor upgrades, and its only floor is tested > 0. Production callers of appendGeometry: GeoBoxOps:179, MapPrefab:552, MapModelObjImporter:106, so other suites reach the method; grep finds no other suite naming the u8->u16 upgrade (BchMapModel.java:806-833, 934-960). Not measured here: the suite needs the FieldData dump, which this container does not have, so whether the upgrade branch currently runs on the corpus is unknown. Same file: missing GARC prints 'SKIP' and exits 0 (29-31); the comment at 38 says the step 'keeps it fast' while step = 1.

**Fix.** Count successful grows and u16 upgrades across the corpus and require each to be above zero (the AreaShareGuardTest 'both private and shared areas exist' pattern); exit non-zero on a missing dump rather than SKIP.

<a id="f143"></a>

## F143 - The main window's 'nothing reaches in, count zero' is measured for fields only: 5 classes still call 5 of its static methods, recorded nowhere and guarded by nothing, and 16 more still carry a now-dead 'import static ctrmap.CtrmapMainframe.\*' through which a new bare call compiles silently

**low** | architecture | `src/ctrmap/tests/MainframeEdgesTest.java:136`

MainframeEdgesTest.everyReachIntoTheWindowIsNamed counts Refs with r.owner == window &amp;&amp; !r.method (MainframeEdgesTest.java:136) - Fieldrefs only. MainframeCallProbe (probe/MainframeCallProbe.java, over ClassFileScanner.application; control: outside field reads = 0, the suite's own known answer, and the window's own method refs = 118 > 0) finds 7 method edges from 5 classes: onWorkspaceOpened &lt;- WorkspaceSettings, setup/SetupWizard; refreshSceneFog &lt;- AreaLightingDialog, ZoneLoadingPanel; showGameData &lt;- MaisonEditDialog; showWorldEditor &lt;- ZoneLoadingPanel; unloadEditors &lt;- WorkspaceSettings. git grep 'import static ctrmap.CtrmapMainframe' in src (production, excluding comments): 19 files; MainframeImportProbe (control: DataSafetyGuardsTest shows an edge, Ui none) finds 16 of them reference no CtrmapMainframe member at all (GeoEditForm, GfEnvPicker - which imports mTileMapPanel by name -, MapMatrixPanel, MatrixEditForm, MatrixPanelInputManager, NPCEditForm, PaintForm, Selector, TileEditForm, TileMapPanel, TilePainterForm, TileUndo, TilemapPanelInputManager, TrainerEditDialog, TriggerEditForm, WarpEditForm). The other 3 reach the window only through those methods. TESTING.md:129-135 and PROPERTIES.md:185-186 state the property without the 'fields only' qualifier; ClassFileScanner.java:22-23 still says 33 files carry the import.

ADDENDUM: LoadedZoneTest.java:59-61 says 'thirty-six files in this tree do exactly that' (import static ctrmap.CtrmapMainframe.\*); measured 19 (grep -l '^import static ctrmap\.CtrmapMainframe\.' over git ls-files \*.java; control: GeoEditForm is one), 16 of which reference nothing through it (MainframeImportProbe).

ADDENDUM: Same stale count again at MainframeEdgesTest.java:46-47 'thirty-six files import this window's statics by name' (measured 19). ADDENDUM - THE BATTERY'S OWN TEST DOUBLE KEEPS THREE WINDOW GLOBALS ALIVE. ZoneEditorsSpy, the double the zone-switch suites use in place of the window's ZoneEditors, commits by reading CtrmapMainframe.mNPCEditForm, mWarpEditForm and mTriggerEditForm and calling their saveEntry (ZoneEditorsSpy.java:55-62). The production ZoneEditors has been handed its editors since the 'nothing reaches in' work. The double still reaches for the statics, so a suite's behaviour depends on whatever an earlier suite left in those fields, and the fields cannot be deleted without editing a test that has nothing to do with them.

**Fix.** Delete the 16 dead imports; hand the 5 callers the operation (as was done for ZoneSaver/ZoneList), then extend the MainframeEdgesTest count to method refs so the zero covers calls too.

<a id="f145"></a>

## F145 - The behavioural half of 'a sweep refuses a tree the plants were not proven against' is a tautology: it passes for any non-empty output, a Python traceback included, so the work-order plant gate is guarded only by a text search for its call line

**low** | test-debt | `src/ctrmap/tests/CommitGuardTest.java:834`

CommitGuardTest.java:820-835 runs a probe printing len(wo.unproven_plants()) and checks (!said.isEmpty() &amp;&amp; !said.startsWith("0") || said.startsWith("0")). Evaluated in jshell (scratchpad taut.jsh): '' -> false, '0' -> true, '2' -> true, 'Traceback (most recent call last): ImportError' -> true. The comment above it says 'the three states it must refuse, driven through python so this is the real code', but no state is set up: no .last-replant is written, so the three branches of work_order.unproven_plants (work_order.py:79-140: no record, notProven > 0, digest moved) are never asserted. work_order.py has no selftest (grep: only the __main__ block at :223). What remains are source-text checks (CommitGuardTest.java:815-818: body.contains('def unproven_plants()') and the call line), the shape BatteryHygieneTest.everyBannedLiteralPinsItsSymbol's javadoc describes as passing once the text is respelled; plants.json:1955 plants the deletion of that call line, and the text check is what kills it.

**Fix.** Drive the three states: write a .last-replant into a scratch root (missing, notProven=1, a stale digest) and require a non-empty reason list for each and an empty one for a matching record; replace the tautology with an exact parse of the printed count.

<a id="f146"></a>

## F146 - DataSafetyGuardsTest lists 'a sign wrapper must be injected BEFORE any talker or sign is wired' as one of its guards, but the check is arithmetic on four local constants and cannot fail; the ordering is a convention each caller follows, and no suite drives the wrong order

**low** | test-debt | `src/ctrmap/tests/DataSafetyGuardsTest.java:244`

DataSafetyGuardsTest.java:51-54 (class javadoc, item 2) and :244-253: scriptBoundaries sets instructionStart=0x40, code=100, data=7, appended=20 and checks grownDataStart - parsedDataStart == appended\*4, an identity of its own algebra; it calls no production code. Its javadoc (:241-242) says 'SignWrapperInjectTest is what actually holds this'; SignWrapperInjectTest.composeAndVerify (:553-575) injects both wrappers WITH a reserialize/reparse between them, the safe path, and never the unreserialized wrong order the rule is about. SignWrapperInjector has no ordering precondition (grep 'order|before' finds only DFS-preorder comments at :71, :358, :426). The callers that must keep the order: NPCEditForm (:1062-1107) and TilePainterForm (:1152-1187, F088's fifth copy).

**Fix.** Make the order impossible: have injectSignWrapper refuse a script whose instruction/data boundary has moved since it was parsed (the injector can compare against the parse-time dataStart), and drive that refusal in SignWrapperInjectTest; delete the arithmetic check.

<a id="f148"></a>

## F148 - WarpTool and TriggerTool still carry a copy of CameraTool's navigator callback that refreshes the CAMERA form, unreachable because both tools drop the navigator; a characterization suite pins the wrong line 'for a step about to move this code' rather than it being removed

**low** | dead-code | `src/ctrmap/humaninterface/tools/WarpTool.java:147`

WarpTool.java:147-149 and TriggerTool.java:131-133: updateComponents() { camera.showCamera(camera.camIndex, false); } - each is handed a CameraEditForm only for this. The only caller is H3DRenderingPanel.doNavi:249-251, after a navigator drag; both tools answer getNaviEnabled() false (WarpTool.java:152-154, TriggerTool.java:136-138), so no navigator is ever bound to them. EditToolGuardsTest.java:51-62 records both as 'odd on purpose', asserting today's behaviour ahead of a refactor; the constructors that exist only to receive the camera form are what DataSafetyGuardsTest.java:391/432 and NpcEditFormGuardsTest:945 have to satisfy with 'new CameraEditForm(REDRAW)'. Same suite :60-61: PropTool.updateComponents (PropTool.java:115-118) marks the prop file modified on every navigator drag event, moved or not.

**Fix.** Delete both updateComponents bodies (leave the AbstractTool default) and the camera-form parameter they were the only use of; set props.modified only when doNavi actually changed a coordinate.

<a id="f151"></a>

## F151 - GarcContentDiff calls itself a 'utility for the deploy script', but no script in the tree runs it: it sits in the test package, unregistered, outside the reachability rule that would have asked about it

**low** | dead-code | `src/ctrmap/tests/GarcContentDiff.java:8`

git grep GarcContentDiff (excluding the file): one hit, tools/guard/whole_tree_baseline.json:300 (a file list). Not registered in test.ps1 (suitecheck.py: the one class under src/ctrmap/tests with a main that test.ps1 does not run). DuplicateWorkTest.everyClassIsReachedBySomething skips the tests folder (DuplicateWorkTest.java:227-229), so a dead class there is never asked about. The deploy that exists is ModDeployer (Java), which does not call it.

**Fix.** Delete it, or move it beside the tool that uses it; extend the reachability rule to test-package classes that are neither registered suites nor referenced by one.

<a id="f154"></a>

## F154 - Three suites say no RomFS path lives outside gamedef and 'a suite is not exempt from the rule it exists to protect' (ItemDataTest:43-46, ItemEditTest:63, RecordSchemaTest:51-52) - but SourceSeamTest exempts every file under tests/ (its javadoc :42-48 says so; its bullet :20-21 reads as if it did not), and 64 test files hard-code 138 ORAS archive paths (ItemDataTest:268 among them), so every corpus suite is ORAS-only by construction

**low** | doc-drift | `src/ctrmap/tests/SourceSeamTest.java:20`

seampaths.py (SourceSeamTest's own GARC_PATH regex over every tracked .java, comments stripped, SEAM-OK honoured; controls: ItemDataTest found, gamedef has hits): tests 64 files / 138 lines, gamedef 2 files / 33 lines, other 1 (docs/probes/Param.java:7, outside the scanned src root). SourceSeamTest.java:635 'boolean isTest = path.contains("/ctrmap/tests/")' and :645 'if (!isTest &amp;&amp; !isGamedef &amp;&amp; GARC_PATH...)'. ItemDataTest.java:43-46 and ItemEditTest.java:63 claim the opposite; ItemDataTest.itemNames:268 'new GARC(new File(romfs + "/a/0/7/3"))' with gametext index 114 literal, and a catch Throwable -> null.

ADDENDUM: CORRECTION on reading SourceSeamTest whole: the class javadoc :42-48 does state 'Tests are now exempt from the first two rules only' - the guard is consistent with its own paragraph; the contradiction is in the three suites' comments and in the bullet at :20-21.

**Fix.** Either state the exemption in the javadoc and drop the two false comments, or route suites through GameProfile.archivePath and remove the exemption so an XY corpus can be pointed at the battery.

<a id="f156"></a>

## F156 - Remove added zones reports 'This ZoneData has no added zones (stock layout)' when the ZoneData archive is not loaded at all - a statement about an archive it never read; the three sibling actions say 'ZoneData archive unavailable'

**low** | correctness | `src/ctrmap/CtrmapMainframe.java:2483`

CtrmapMainframe.java:2483 'if (zoArc == null || zoArc.length &lt;= stockLength)' -> one message for both. MainframeActionGuardsTest.java:223-229 and :240-242 pin it 'AS IS, NOT AS IT SHOULD BE' and point at 'suspected_defects', which is not a tracked file (git ls-files

grep -i suspected: nothing).

**Fix.** Split the null case into the same 'ZoneData archive unavailable.' refusal the other three use; update the pinned check.

<a id="f157"></a>

## F157 - Set up Battle facility picks the source zone by String identity: 'kind == kinds[1]' - any answer equal to 'Battle Institute (single test)' but not the same object clones the Maison instead

**low** | correctness | `src/ctrmap/CtrmapMainframe.java:3069`

CtrmapMainframe.java:3060 'String[] kinds = {...}' and :3069 'final int srcIndex = kind == kinds[1] ? INSTITUTE_ZONE : MAISON_LOBBY_ZONE'. Correct today only because JOptionPane hands back the very element it was given. MainframeActionGuardsTest.java:547-554 and :604-612 pin it 'AS IS' with new String(...) and assert the WRONG facility is chosen. The cost of the wrong branch is irreversible: the destination zone is COMPLETELY REPLACED by a copy of the other lobby. TREE-WIDE (scratchpad/m_streq.py: every ==/!= in production whose operand is a string literal, a String-typed name or an element of a String[] name, null excluded; CONTROL: this site is found): this is the ONLY String identity comparison in production. An isolated instance, not a class.

**Fix.** Compare by index (Ui.option returning the chosen index) or .equals; flip the pinned check to assert the Institute is chosen.

<a id="f158"></a>

## F158 - The same window counts are ratcheted in two or three places with different numbers and operators: public statics are '== 19' in MainframeEdgesTest and '&lt;= 21' in MainframeShapeTest; menu items are pinned by EXPECTED_MENUS, by 'items == 35' and by MENU_ITEM_CEILING 35 - the weaker copies can never fail first and must each be edited by hand

**low** | duplication | `src/ctrmap/tests/MainframeShapeTest.java:138`

MainframeEdgesTest.java:100 PUBLIC_STATICS = 19, :580 statics.size() == PUBLIC_STATICS (all public static fields); MainframeShapeTest.java:138 STATIC_CEILING = 21, :1043 names.size() &lt;= STATIC_CEILING (public static, !isSynthetic) - its javadoc :115-120 says 'LOWER this as widgets find owners' and was not lowered when the other went 21->19; the javadoc is also orphaned (stacked above a line comment and MENU_ITEM_CEILING's javadoc :123-135). Menu: MainframeShapeTest.java:76-83 exact list, :251 'items == 35', :136/:198 'items &lt;= MENU_ITEM_CEILING (35)' - the ceiling's javadoc :128-130 argues the exact list is only a pin, but :251 is an exact count beside it.

**Fix.** Keep one ratchet per quantity: delete STATIC_CEILING (MainframeEdgesTest owns it) and the 'items == 35' line (EXPECTED_MENUS already pins the exact list; the ceiling is the refusal).

<a id="f159"></a>

## F159 - frameSingleRegion is a hand-copied frameMatrix(1,1) that diverged: it sets translateX = 0 under the comment '720/2 to center the camera', where the matrix formula gives -360, so a loose GR map opens half a region off centre - pinned by a suite rather than fixed

**low** | duplication | `src/ctrmap/humaninterface/PanelScene3D.java:51`

PanelScene3D.java:51-54 translateX 0f / translateY -360f / translateZ -720f / rotateX 45f (+ rotateY added later after the same copy had already dropped it once); :75-79 frameMatrix: translateX = -cellsAcross \* 360f, translateY = -cellsDown \* 360f, translateZ = -cellsDown \* 720f. MainframeShapeTest.java:737-739 asserts the difference 'is pinned, not fixed ... so a loose GR sits half a region off centre'. Second divergence of the same copy (the yaw was the first, :55-58).

**Fix.** Make frameSingleRegion() call frameMatrix(1, 1) and delete its body; drop the pinned translateX check.

<a id="f160"></a>

## F160 - Two silent losses in every map-matrix save, both pinned as-is rather than decided: any save rewrites the camera section to just the counted entries (retail matrix 1: 324 bytes on disk -> 64, 260 trailing bytes of unknown content dropped), and unticking 'Allow LOD and Multizone' drops the whole zone-switch + LOD layer (2,176 of 2,312 bytes) on the next save with no prompt

**low** | data-integrity | `src/ctrmap/formats/mapmatrix/MapMatrix.java:292`

MapMatrix.java:288-298 assembleCamData writes count + cambounds only; :307 storeFile(1, ...) on every save. MatrixEditForm.java:952-956 allowExtendedActionPerformed sets mm.hasLOD with no confirmation; :120 re-reads the checkbox on every saveAll. MatrixEditFormGuardsTest.java:46-53 and :291-354 pin both ('Whether that should be possible without a warning is a question for the report'); nothing measures what the 260 trailing camera bytes are across the 431 retail matrices.

**Fix.** Measure the trailing camera bytes over every retail matrix (zero padding vs data); if not all zero, carry them through assembleCamData. Ask before a save drops the LOD/zone layer (or keep it until the user confirms).

<a id="f162"></a>

## F162 - ShopDataTest's 'merged keeps zone patch' compares bytes at 0x112bc0 - the VIRTUAL address - where ZoneLimitPatch writes the file offset 0x012bc0, so it compares two copies of untouched stock bytes and cannot fail; a merge that dropped the zone patch passes it

**low** | test-debt | `src/ctrmap/tests/ShopDataTest.java:79`

ZoneLimitPatch.java:12 'virtual address = file offset + 0x00100000', :56 OFF_MASTER_IDX = 0x012bc0, :137-148 applyToCode writes at p.fileOffset; the five patch sites are 0x012bc0, 0x2d9774, 0x2d99f8, 0x012c0c, 0x00eae0 - none at 0x112bc0, and the shop table is 0x47AA3E. ShopDataTest.java:79-80 copyOfRange(both, 0x112bc0, 0x112bc4) vs copyOfRange(zoneOnly, 0x112bc0, 0x112bc4). The same check written correctly exists at ItemIconPatchTest.java:257-258 (0x012bc0). Both run only when code.bin is present.

**Fix.** Use ZoneLimitPatch's own offset (expose OFF_MASTER_IDX or a sites() list) rather than a literal, and assert the bytes differ from stock as well as from each other.

<a id="f163"></a>

## F163 - The LA4 texture converter neither widens its 4-bit channels to 8 bits nor masks the sign: luminance 0x7 decodes as 0x07 (near-black) instead of 0x77, alpha 0xC as 0x0C (near-transparent) instead of 0xCC, and a high nibble 8..F sign-extends - the l4/a4 converters beside it double their nibbles correctly; the suite pins the wrong output as 'recorded behaviour of the port, not endorsed'

**low** | correctness | `src/ctrmap/formats/h3d/texturing/TextureCodec.java:233`

TextureCodec.java:232-237 la4: out = (byte)(data[d] >> 4) x3 and (data[d] &amp; 0xf) - no \*0x11 and an arithmetic shift on a signed byte. TextureCodecTest.java:142-145 pins 0x7C -> 07 07 07 0c ('neither widened to 8 bits') and 0xF0 -> ff ff ff 00 ('sign-extends ... not endorsed'), while :146-149 show l4/a4 doubling (0x5 -> 0x55). :41-42 notes la4 appears in no retail AREA pack, so the corpus digest cannot catch it; any model or imported texture in LA4 renders dark and see-through in the 3D view.

**Fix.** out = ((data[d] >> 4) &amp; 0xF) \* 0x11 for luminance and (data[d] &amp; 0xF) \* 0x11 for alpha; re-point the two pinned checks to the widened values.

<a id="f164"></a>

## F164 - UpdaterTest zip-slip 'nothing escaped' check looks one directory too high, so it can never fail

**low** | test-debt | `src/ctrmap/tests/UpdaterTest.java:149`

UpdaterTest:144 unzips into new File(install,"out"); the entry '../escaped.txt' resolves to install/escaped.txt (Updater.unzip:659 new File(dest, e.getName())). :149 checks new File(install.getParentFile(), "escaped.txt") - ctrmap_updater_test/escaped.txt, one level above where an escape would land. If Updater.unzip:661-664 stopped refusing, this assertion would still pass; only the 'refused' flag at :148 guards.

**Fix.** check new File(install, "escaped.txt"), and prove it by deleting the refusal in unzip and watching both checks fail.

<a id="f165"></a>

## F165 - Bytes.getTrimmedArray has no production caller and two known defects, kept alive only by UtilsTest pinning them

**low** | dead-code | `src/ctrmap/util/Bytes.java:122`

Caller count per public static in Bytes (control: ba2int=3 prod files, checkBCHMagic=3): getTrimmedArray prodFiles=0, only its definition. UtilsTest:143-170 pins 'SUSPECTED DEFECTS, PINNED NOT FIXED': index 0 never scanned, and i+(4-i%4) overruns (AIOOBE on {0,0,0,0,9}). The characterization exists 'for a later split' that has happened (UtilsTest:18-22 still says Utils 'A later step SPLITS it'; Utils is now ctrmap.util.Bytes/Forms/Picking/Ui).

**Fix.** delete getTrimmedArray and its UtilsTest section; refresh the UtilsTest class javadoc.

<a id="f166"></a>

## F166 - ResourceAccess reads a bundled resource with one read() into an available()-sized buffer: from a jar it silently returns zero-padded bytes, and the battery (directory classpath) can never see it

**low** | api-trap | `src/ctrmap/resources/ResourceAccess.java:15`

MEASURED (probe/ResJarProbe.java, JDK 21, every src/ctrmap/resources file packed into a deflated jar ahead of build classes; control: a full drain of getStream equals the file for 49/49): getByteArray returns the right LENGTH but wrong bytes for 17 of 49 - 16 tool-button PNGs (last 3 bytes zeroed) and oras_buildings_auto.tsv (zeros from byte 33999 of 352484). Today's consumers survive: probe/IconDecodeProbe.java shows all 30 tool icons decode to identical 25x25 pixels from the damaged bytes (only the IEND CRC is lost), and the three binary resources read this way (DummyKAGE.bin, DummyLumioseCollision.bin, DummyBCH3DModel.bch via copyToTemp's identical read) came back exact. So no live corruption - but the next resource read through getByteArray past a few KB is truncated in the shipped jar/app-image only, while UtilsTest:237 and every suite load from build/classes where FileInputStream reads whole. Same available()+single-read shape at NPCEditForm:708/779/810 and TextEditor:126 (FileInputStream, reliable in practice).

**Fix.** read with a drain loop (InputStream.readAllBytes on 9+, or a ByteArrayOutputStream loop on 8) in ResourceAccess and the four editor sites; add a suite that runs ResourceAccess against a jar of the resources, since a directory classpath cannot fail this.

<a id="f168"></a>

## F168 - A rule whose AT_SCOPE is not a valid regex is silently never asked - its own header says a rule that cannot answer is not a pass

**low** | silent-failure | `.claude/bundle-hooks/bundle_rules.py:330`

MEASURED (scratchpad/badscope.py, scratch root, control: the same checker with a valid scope refuses the write {'rule_good': (0, 1, [...])}): with AT_SCOPE r'^src/(.\*\.py$' the rule is discovered with error '' and the write passes with nothing said. declarations_in (:470-474) accepts any str as a scope and Rule.watches (:328-331) catches re.error and returns False; it is the only re.error handler in the tree (git grep). bundle_rules.py:52 'A RULE THAT CANNOT ANSWER IS NOT A PASS' and :506-508 'a rule somebody meant to wire and got wrong must not read as a rule that does not exist'.

**Fix.** compile the scope in declarations_in and return it as an error row, which ask_changes already turns into a refusal.

<a id="f169"></a>

## F169 - The bundle dispatcher's per-guard timeout is documented and never applied: a hung guard holds every tool call for the harness's 1800 s

**low** | dead-code | `.claude/bundle-hooks/dispatch.py:102`

dispatch.py:66-68 'A guard gets this long to answer ... short enough that a hung one is noticed' TIMEOUT = 20; ask(path, raw, timeout=TIMEOUT) opens with 'del timeout' (:102) and calls the guard's main() in-process with no limit (:120-124). grep: TIMEOUT is read nowhere else in the file. settings.json gives this dispatcher a harness timeout of 1800 (PreToolUse) and 300 (Stop), against 20 for the project's guard_all.py. Also stale: :21-22 says it finds '.claude/hooks/guard_\*.py' - it lists its own folder, .claude/bundle-hooks. ADDENDUM - the project dispatcher has the same gap in another form: .claude/hooks/guard_all.py:21-27 lists among what it refuses "a guard that takes longer than PATIENCE seconds - ... a guard that never returns is one that cannot answer", but ask() (:103-145) only measures time.time() AFTER decide() has returned (:137-140) - a guard that never returns is never refused, it holds the call until the harness's 20 s timeout. Neither dispatcher can stop a hung guard; both say they can.

**Fix.** either delete TIMEOUT and say the harness timeout is the only bound (and lower it), or run each guard under a real deadline (a thread with join(timeout), counted as CRASHED).

<a id="f174"></a>

## F174 - CLAUDE.md forbids Bash heredocs for Java or Python outright; both installed heredoc guards allow a short one by design

**low** | process-debt | `.claude/hooks/guard_heredoc.py:30`

CLAUDE.md section 2: 'No Bash heredocs for Java or Python - write the script to a file.' guard_forbidden_command.py:4-13 counts this clause among those now 'behind' a guard. MEASURED (scratchpad/heredocprobe.py, both copies run as hooks; control: 'cat > /tmp/x.py &lt;&lt;EOF' refused by both): a 2-line python3 heredoc and a jshell heredoc are ALLOWED by .claude/hooks/guard_heredoc.py and the bundle copy. Both docstrings say so on purpose ('a short heredoc feeding an interpreter is allowed', project :30; bundle MAX_SCRIPT_LINES 20). The rule and its guard state two different rules.

**Fix.** either make the guard refuse any heredoc feeding python/java (the rule as written), or reword the CLAUDE.md clause to the guard's narrower rule - one rule, one spelling.

<a id="f180"></a>

## F180 - Plant-ledger size stated as 213 and 221 in present-tense prose; the ledger holds 225, and only one of the four mentions is dated

**low** | doc-drift | `tests/test_bundle_gates.py:221`

MEASURED (scratchpad/plantcount.py with a known-answer control on list- and dict-shaped ledgers): tools/guard/plants.json holds 225 plants today. Present-tense prose states three other numbers with no date or subject: the bundle ledger's entry at line 1827 says CTRMap's is a list of 213 entries; its entry at line 1979 and tests/test_bundle_gates.py:221 say 221 plants. Only tools/commit_gate.py:517 dates its figure (Measured on CTRMap, 2026-09-25: 221 plants over 95 files), which is the form CLAUDE.md asks for (A RECORDED MEASUREMENT MUST CARRY THE SUBJECT IT WAS TAKEN AGAINST). The two stale ones are each correct about some past tree and say nothing about which.

**Fix.** Date each figure as commit_gate.py:517 does, or drop the number; nothing reads it.

<a id="f181"></a>

## F181 - gate_stamps refuses any tool handed a file that merely mentions the stamp store, so the ledger and gate tests cannot be passed to a linter, pytest or json.tool

**low** | api-trap | `tools/gate_stamps.py:453`

MEASURED (scratchpad/forgeprobe.py, gate_stamps.forges asked directly - it only reads files; controls: a command appending to the store is refused, git status is not). forges() (tools/gate_stamps.py:420-468) takes every argument after a script - and after any -m module not on its six-name read-only list - as a program that might run, and refuses when that FILE'S TEXT names the stamp store. So handing a file that merely MENTIONS the store to any tool as data is refused as forging a stamp: python -B tests/test_bundle_gates.py, python -m pytest tests/test_bundle_gates.py, python -m json.tool on the bundle's plant ledger, python -B tools/format_strings.py on that ledger, and a script given a quoted note whose first word is the ledger's path - all REFUSED; python -m unittest tests.test_bundle_gates and cat of the ledger are allowed. Observed live in this audit: recording a lead whose text began with the ledger's path was refused as 'runs &lt;ledger>, whose text reaches the gate's stamp store'. The ledger and the bundle's test files are exactly the files that must mention the store (they plant and test it), so they are the ones no tool may be pointed at. Its own docstring names the trade (a script can run what it is handed); commit_gate.py:378 names the cost - a gate that refuses honest work is one somebody removes.

**Fix.** Ask a data argument only when the program is known to execute its arguments (an interpreter, a shell, a runner), or read the candidate as a program only if it is a .py/.sh/.ps1 file whose text WRITES the store (open/append/redirect near the name), not one that names it.

<a id="f185"></a>

## F185 - The census guard's whole-tree scope keeps only a hand list of suffixes: 53 tracked files - all three git hooks among them - can never count as unread

**low** | process-debt | `tools/guard/census.py:68`

MEASURED (scratchpad/censusscope.py; controls: expand of tools/guard contains census.py, and every path expand returns exists). The census guard's scope for the whole tree, census.expand(['.']), walks directories from disk but keeps only files whose names end in SOURCE_SUFFIXES (:68-69), a hand list. Of 661 tracked files, 53 fall outside it: 38 .png, 4 jars under lib/, the 3 ASCII .ply arrow models the program loads, DefaultTileset.mets, an .ico, manifest.mf, LICENSE, .gitignore - and all three files in .githooks (commit-msg, pre-commit, gate-before), the commit gate itself. So a census can report the whole tree covered without opening the hooks that gate every commit. CLAUDE.md: A MEASUREMENT'S SCOPE IS DERIVED FROM THE TREE, NEVER FROM A LIST; the module docstring's own history (:62-67) is the list having been short once already - shell scripts and shipped binaries were added after a census skipped build.ps1 and run.bat. ADDENDUM (read tools/guard/citations.py): its file walk is the same shape with a DIFFERENT list - _text_files (:122-123) keeps nine suffixes (.md .py .java .ps1 .txt .json .bat .sh .properties), so it does not look in .form, .xml, .tsv files or the suffix-less git hooks either, and the two guards' lists disagree with each other (census has .tsv .form .xml .bin ...; citations has neither). Two hand lists of 'what a text file is' in one folder.

**Fix.** Expand scope from git ls-files (tracked and untracked-not-ignored) and exclude by an explicit, reviewed list of binary kinds instead of including by suffix.

<a id="f187"></a>

## F187 - The project hook stack is tracked twice (.claude/hooks and tools/hooks, 22 identical files each); the reason given for the second copy - .claude/hooks is not version-controlled - is no longer true

**low** | duplication | `tools/guard/hooks_wired.py:361`

MEASURED: git ls-files .claude/hooks -> 22 files; git ls-files tools/hooks -> 22 files; diff -rq of the two folders differs only by the untracked .fanout-count state file. tools/guard/hooks_wired.py keeps tools/hooks as 'the version-controlled copy' because, it says, '.claude/hooks is outside version control entirely, so a hook edited there leaves no trace anywhere: no diff, no history' (:357-360, and again :395-397) - true of the layout it was written for, false of this tree, where .claude/hooks is tracked. So the project stack exists twice under version control, every edit has to be made twice (hooks_wired refuses a difference, so the second copy is pure upkeep), and the tests split between them: tests/test_guard_push.py loads tools/hooks/guard_push.py while the harness runs .claude/hooks/guard_push.py - correct today only because the bytes happen to be equal. Same shape as the census suffix list: a premise recorded once and never re-measured.

**Fix.** Delete tools/hooks, point hooks_wired's drift check and tests/test_guard_push.py at the tracked .claude/hooks, and keep the 'installed differs from HEAD' question as git diff --exit-code .claude/hooks.

<a id="f188"></a>

## F188 - 10 suites are proven only by a machine plant whose must_say is a crash message - several pinning game-data array sizes - so the proof shows the suite runs the code, not that it catches anything

**low** | test-debt | `tools/guard/autoplant.py:192`

MEASURED (scratchpad/autoplants.py over tools/guard/plants.json; control: the classifier calls an 'Index 0 out of bounds' text a crash and an assertion sentence not one). 225 plants, 53 marked auto; 48 registered suites are proven ONLY by auto plants, and for 10 of those the one line the suite must say is a raw exception: gauntletscript, givebpscript and mapmodelobj 'Index 0 out of bounds for length 0'; paintedregion 'Index 534916 out of bounds for length 534912'; terrainimport 'Index 808748 out of bounds for length 808704'; compositeleftover 'ArrayIndexOutOfBoundsException: Index 347404 ...'; msgwrapperinject and signwrapperinject 'arraycopy: last destination index 47/22 out of bounds'; and the two GFMessageFile suites, red because the negated bounds check now throws on valid text. A negate-if that makes the code under test crash on ordinary input proves the suite RUNS that code, not that it would notice the defect the guard is about - autoplant's own docstring (:18-25) drops a suite that fails 'for an unrelated reason', and a crash pinned as must_say passes that filter by construction. The pins also hold game-data sizes (534912, 808704, 347404): a different dump or a re-harvested corpus changes the number, the suite still goes red, the must_say no longer matches, and replant reports a rotted plant that is not rotten. ADDENDUM (scratchpad/autoshared.py, control: two plants known identical collide, two known different do not): the 53 auto plants stand on 37 distinct mutations. Three foundational decoder lines carry 16 of them - BchMapModel 'if (sourceBase &lt; 0)' for 6 suites, PawnInstruction 'if (hasCompressedArgument)' for 5, GARC's bit-flag test for 5 - i.e. break the GARC/BCH/Pawn decoder and every suite that loads through it goes red, which proves those suites load files, not that any one of them guards its own defect class.

**Fix.** Make fail_line refuse a line carrying an exception class or an 'out of bounds' text (treat it like an unnamed failure), and re-plant those 10 suites with a mutation the suite reports in its own words.

<a id="f189"></a>

## F189 - Two plant-ledger ratchets carry slack of 4 each, and the only slack check recognises one phrasing that one suite prints

**low** | process-debt | `tools/guard/record_run.py:187`

MEASURED (scratchpad/ratchetslack.py, calling replant's own owed() and without_a_cost() directly - `replant.py --owed` was NOT run because it records into tools/guard/magnitudes.json, a tracked file; control: a ledger that also plants the missing suites owes zero; git status unchanged afterwards). The plant ledger's ratchets today: owed 53 against owed_ceiling 57 (slack 4); owed_real_defect 48 against 52 (slack 4); owed_generalisation 3/3 and no_cost 64/64 tight. So four more registered suites can land with no plant at all, and four more can be 'proven' only by a machine plant, with PlantLedgerTest and replant --selftest still green - exactly what record_run.slack_ratchets' own docstring calls a defect ('A RATCHET ONLY BITES WHILE IT IS KEPT TIGHT ... a plant that added a silent catch landed in it and SURVIVED'). That slack refusal cannot see these: it parses only the phrase 'N ... against a ceiling of M' (record_run.py:187), which one suite prints (git grep 'against a ceiling of' in src/ctrmap/tests: SilentCatchTest only), and test.ps1 echoes at most each suite's last two lines anyway (record_run.py:32-37). The battery declares 13 int CEILING constants across 9 suite files, and the JSON records 9 more ceilings (tests/plants.json x4, tools/guard/plants.json x4, rule_map.json x1); one phrasing of one is asked whether it is tight.

**Fix.** Make every ceiling a record the slack rule reads (one declared shape: name, measured, ceiling) and refuse ceiling > measured at the write of the record; lower owed_ceiling to 53 and owed_real_defect_ceiling to 48 now.

<a id="f190"></a>

## F190 - 9 plant entries record an agent's fix brief ('Take answer (a)', 'Do not touch...', 'Write one small new suite') in place of the defect they put back

**low** | doc-drift | `tools/guard/plants.json:1153`

MEASURED (scratchpad/plantwhy.py, a shape test - the why opens with an imperative or a decision, or names an analyst; controls: a descriptive why does not match, the 'Fix the seven PropEditForm sites' why does). 11 of 225 matched; reading each, 2 are false positives (one opens with the menu item 'Remove added zones', one with 'Fixing the appender cannot reach...'), so 9 plants carry a fix brief where the ledger's _why says each entry records the defect it puts back: prop-edit-form-redraw-reaches-nobody ('Fix the seven PropEditForm sites...'), loose-map-clear-leaves-warp-and-trigger-forms ('Take answer (a)...'), paintform-restore-reaches-for-a-map-view-that-is-not-there ('Decided OPTIONAL...'), failed-workspace-leaves-the-old-games-zone-open ('Do not touch unloadEditors or repointGameDir...'), zone-close-leaves-the-prop-editor-loaded ('Add the missing ninth ZoneView ... the analyst's answer two'), single-region-framing-leaves-stale-yaw ('Zero the yaw...'), fork-decline-save-failure-swallowed ('Change the WRITE swallow only...'), rescale-lands-on-the-windows-panel ('The production fix ... is correct ... so I guard updateAll'), tile-palette-follows-a-stale-cursor ('Write one small new suite...'). They read as the brief an agent was handed, pasted in; they say what to do, not what the user lost, and none of them names a cost - which is also why they count against the no_cost ratchet (64, tight).

**Fix.** Rewrite those 9 whys as the defect and its cost, in the ledger's own style; have replant.check_shape refuse a why that opens with an imperative.

<a id="f192"></a>

## F192 - The work-order gate reads an unreadable queue as an empty one and a git that cannot answer as a clean tree, so a long measurement may start with neither looked at

**low** | silent-failure | `tools/guard/work_order.py:63`

MEASURED (scratchpad/workorder.py, module globals pointed at a scratch folder, nothing in the repository written; controls: the real queue gives 8 open items, a scratch repository with one modified tracked file is reported dirty). tools/guard/work_order.py - the mechanism rule_map.json names for COMMIT BEFORE RUNNING ANYTHING THAT MUTATES SOURCES (work_order.dirty_files) and FINISH CHANGES BEFORE STARTING A LONG MEASUREMENT (work_order.blockers) - answers 'nothing' when it cannot look: dirty_files() returns [] when git cannot answer (it never reads the return code, and catches OSError/Timeout to return [], :63-76), and open_items() returns [] when OUTSTANDING.md cannot be opened (:49-54); gate('the battery', '') then returns 0, i.e. the battery may start, with the queue unread. Both docstrings say so ('Empty when git cannot be asked'). This is the shape CLAUDE.md names A QUERY THAT CANNOT READ ITS SUBJECT MUST NOT REPORT IT ABSENT, and the bundle's guard_promise already does the opposite for the same file (tests/test_bundle.py test_a_queue_that_cannot_be_READ_is_UNKNOWN_and_not_queued). commit_guard.staged_files has the same 'Empty when git cannot be asked' contract (F186).

**Fix.** Return None for could-not-look from open_items and dirty_files (check git's return code) and make blockers()/gate() refuse on None, as guard_promise does for the same OUTSTANDING.md.

<a id="f193"></a>

## F193 - The project fan-out cap refuses the harness's to-do tools (TaskCreate, TaskUpdate, TaskGet) as agent launches of unreadable width, because it decides by the word 'task' in a tool name

**low** | api-trap | `.claude/hooks/guard_fanout.py:224`

MEASURED (scratchpad/fanoutnames.py: .claude/hooks/guard_fanout.decide with its counter pointed at a scratch file - the real .claude/hooks/.fanout-count was not read or written, its mtime unchanged; controls: an Agent launch is allowed and counted, TaskList and TaskStop are allowed). The harness's own to-do list tools, which spawn nothing, are REFUSED as fan-outs of unreadable width: TaskCreate, TaskUpdate and TaskGet each get 'BLOCKED BY PROJECT POLICY: this launch does not say how wide it fans out', and so does the task-suggestion tool mcp__ccd_session__spawn_task. Cause: spawns_workers (:224-239) calls a tool a fan-out when its NAME contains any of FANOUT_NAMES ('workflow', 'agent', 'task', ...), exempts only names containing a NOT_A_SPAWN word (stop, kill, cancel, abort, output, list, status, read, wait), and treats an unclear spawn as workflow-shaped, whose width is then unreadable. The guard has paid for this shape once already - NOT_A_SPAWN exists because TaskStop was refused (:170-173, 'A guard that refuses the brakes is one that gets switched off') - and fixed the instance, not the class: a name test with a hand-kept exemption list, one new tool name from wrong. The bundle's twin decides by the input's shape (bundle_shell.launches) and lets these through.

**Fix.** Decide by what the input carries (a prompt plus subagent_type, or a script/scriptPath), as bundle_shell.launches does, and drop the name list and its exemption list.

<a id="f195"></a>

## F195 - The superseded mutator tools/mutate.py depends on a branch and a commit that exist in no clone, yet is still guarded, planted and listed as a destructive script

**low** | dead-code | `tools/mutate.py:98`

MEASURED (git rev-parse --verify on each ref the script names, in this full, non-shallow clone of 640 commits; control: master and HEAD resolve). tools/mutate.py, the 'superseded mutator' (its own comment at :89-93: 'This mutator is superseded and nothing runs it today'), resets its worktree to a branch called silent-failures (:98, :132) and diffs 4ed7eee against branches sf/c1, sf/c3, sf/c5a, sf/c5b, sf/c6 (:40-48, :101). None of those refs exists here - silent-failures MISSING, sf/c1 MISSING, sf/c3 MISSING, 4ed7eee MISSING - and git ls-remote finds no such head on origin. So the script cannot run in any clone of this repository; it is history that only ever ran on one machine. It is still carried and maintained as live: it is listed in .claude/hooks/destructive_scripts.txt and .claude/bundle-install.json, the plant 'a-long-measurement-that-asks-nobody' re-proves its work-order gate on every commit that touches it, CommitGuardTest asserts it asks the work order, and its captured child has no creationflags (one of the 19 console-window offenders, F184). Every guard on it guards nothing that can execute.

**Fix.** Delete tools/mutate.py and the entries, plant and suite assertions that keep it alive; its findings live in wt/_state/mutation.json history if anyone needs them.

<a id="f196"></a>

## F196 - prove_plants judges a plant through two hand-copied verdict functions that have already drifted: the declared-runner copy accepts a child that never ran a test as a kill

**low** | duplication | `tools/prove_plants.py:442`

READ tools/prove_plants.py:170-221 and :440-458. The verdict on one plant is written twice. named_test_fails (Python tests) and run_declared.run (a project's declared runner) carry the same HUNG message, the same must_say refusal (copied word for word), and the same returncode==0 means not reddened rule. They have already drifted. named_test_fails refuses any child that did not print unittest's 'Ran 1 test': 'A VERDICT NEEDS A RUN. A child that dies before unittest's summary - os._exit, a crash, an interpreter that never started - exits non-zero with no test having failed anything' (:208-213). run_declared has no equivalent. Any non-zero exit that is not 127 and does not print the declared 'unresolved' text counts as a KILL when the plant carries no must_say. That covers a JVM that cannot start its class for a reason other than the one declared string, an exception in static init, or an OutOfMemoryError. In CTRMap the declared path is unreachable today: .claude/bundle-install.json declares _tests as {ledger, not_covered} with no find/run, so declared_tests() returns None (:383). It is the bundle's code carried here, and it is live in any project that declares a runner. The fix that closed the run-marker hole was applied to one of the two copies.

**Fix.** One verdict function taking (exit code, output, ran-marker) used by both runners; declared runners declare their own ran-marker the way they declare unresolved, and an absent marker is refused.

<a id="f197"></a>

## F197 - prove_plants --merge-from and --only given without a value crash with IndexError, while --add, --reanchor and --retire refuse in words

**low** | api-trap | `tools/prove_plants.py:921`

MEASURED (scratchpad/ppargs.py imports tools/prove_plants.py and calls main() with a value-taking flag given as the LAST argument. Control: --add with no value is caught and refused with exit 1, as the file intends). Results: --add returns 1 with 'REFUSING: --add takes a JSON file'. --merge-from raises IndexError (list index out of range) at :921. --only raises IndexError at :947. git status was identical before and after. Five flags take a value. Three of them (--add, --reanchor, --retire) catch IndexError and refuse in words; the other two read argv[index+1] bare. The file's own FLAGS comment (:877-879) exists because an argument that was ignored silently ran the default, which drives every plant. The same care stops short of the two flags whose value is read without a guard. --merge-from followed by another flag takes that flag as a folder path.

**Fix.** One helper that reads a flag value and refuses when it is missing or is itself a flag; use it for all five value-taking flags.

<a id="f199"></a>

## F199 - The every-child-takes-a-timeout checker keys on the module name subprocess: aliased or from-imported run/check_output, Popen.wait(), os.system and os.popen all block forever and pass it (none exist today)

**low** | silent-failure | `tools/timeouts.py:83`

MEASURED (scratchpad/timeoutprobe.py). CONTROL: timeouts.unbounded on a synthetic source flags subprocess.run([...]) with no timeout (line 5) and .communicate() (line 13), the two shapes it was written for. On the same source it MISSES six calls that block forever: sp.run after 'import subprocess as sp', run(...) and check_output(...) after 'from subprocess import run, check_output', Popen(...).wait() with no timeout, os.system(...) and os.popen(...).read(). The checker keys on the owner NAME 'subprocess' (:83-85), a list of names rather than the shape 'starts a child and waits'. CLAUDE.md: A GUARD'S TRIGGER IS A CLASS, NOT A LIST OF NAMES. Tree today: timeouts.gates() derives 56 gate files, and every .py the .githooks scripts name is among them (8 of 8). A second scan for the six missed shapes over those files, itself controlled (it finds exactly lines 6, 7, 8, 10, 11, 12 on the synthetic source), finds 0, and git grep finds none outside tests/. So no unbounded child exists today. The ceiling of zero holds only because nobody has yet written one of the six shapes it cannot see, and the at-write refusal (AT_WRITE findings_in) would let any of them through.

**Fix.** Ask the shape: any call that resolves (through import aliases and from-imports) to a subprocess wait, any .wait()/.communicate() with no timeout, and os.system/os.popen in a gate file; plant one of each.

<a id="f200"></a>

## F200 - 23 tracked bundle files say Adapted per project: see ADAPT.md, and no ADAPT.md exists in this repository; whole_tree refuses an unclassified record with no command to classify it

**low** | doc-drift | `tools/whole_tree.py:79`

MEASURED (git grep -l 'ADAPT\.md': 23 tracked files, 16 of them in .claude/bundle-hooks, plus tools/bundle_install.py, commit_gate.py, gate_stamps.py, tiers.py, timeouts.py, whole_tree.py and tests/test_bundle.py. git ls-files has no ADAPT.md anywhere; the only README is README.md). Each of these files carries an ADAPT tuple of per-project constants and the comment 'Adapted per project: see ADAPT.md'. The instructions for those constants live only in the verification-bootstrap repository, which this checkout cannot fetch (the SessionStart hook reports 'the verification bootstrap is NOT reachable from here'). Consequence, read in tools/whole_tree.py: an unclassified record is refused with 'Record it as covers or exempts', but no command records a kind. main() offers check, record and list, and record() only carries 'kind' forward (:297, :322). The one kind in tools/guard/whole_tree_baseline.json ({'mutation_baseline.json': 'covers'}) was typed into the install commit a85bc9a. So the documented remedy is a hand edit, guided by a document that is not in this repository.

**Fix.** Vendor ADAPT.md (or the CTRMap-specific answers) into the repo beside the bundle copies, and give whole_tree a classify LABEL covers|exempts command that refuses an unknown label, so the remedy a refusal names can be run.

<a id="f201"></a>

## F201 - The 2026-09-13 census cut its verification evidence at exactly 1400 characters: 235 of 280 Verified paragraphs end at that length, 174 mid-word, while the other fields show no cut

**low** | process-debt | `docs/CENSUS-2026-09-13.md:87`

MEASURED (scratchpad/censuscut.py counts the CHARACTER length of each labelled paragraph body in docs/CENSUS-2026-09-13.md). Verified: n=280, the longest is 1400 characters, and 235 of the 280 are EXACTLY 1400; 174 of those end mid-word (for example H.1 ends '4) The path is reached in the ordinary workflow, not just for loose GRs. TileMap', and H.7 ends 'mm.regions.get(x, negativ'). CONTROL, the sibling fields written by the same run: What the code does (max 1539, 1 at max), What the user gets (max 801, 1 at max) and Fix (max 597, 1 at max) show no pile-up at any length. So the verification evidence, and only that field, was cut at a fixed 1400 characters when the document was assembled. The document's own HOW TO USE THIS (:64-71) says 'Each entry carries the fix its finder proposed AND the evidence the verifier used to confirm it, so a fix can start from the line rather than from a re-investigation'. For 84% of the 280 entries, the part of the evidence that ran past 1400 characters (often the reachability or harm half) is not in the tracked record. A second pile-up of 19 bodies at exactly 1343 characters suggests a second cut that was not investigated further.

**Fix.** Regenerate the document from the run journal (if it survives) with the evidence untruncated, or mark each cut paragraph as truncated. Any assembler of records refuses to shorten a field without saying so.

<a id="f202"></a>

## F202 - 81 javadoc blocks document nothing or the wrong member: 69 stacked on another javadoc, 2 orphaned at a class end, 10 Creates-new-form stubs over a field (55 in production, 11 in CtrmapMainframe alone) - plus misattached blocks a shape rule cannot see

**low** | doc-drift | `src/ctrmap/CtrmapMainframe.java:133`

MEASURED (scratchpad/m_javadoc.py over every tracked .java, 418 files). Rule: a /\*\* \*/ block documents the next declaration, so a block followed by another /\*\* block (STACKED; javac and javadoc keep only the second), or by '}' or end of file (ORPHANED), documents nothing. CONTROLS, each found: WorkspaceIntegrity.java:298 (orphan, the F032 instance), PaintedFloorMesh.java:186 (stacked) and ZoneLinkDialog.java:36 (stacked). RESULT: 69 STACKED blocks in 43 files (43 production, 26 test); 2 ORPHANED (WorkspaceIntegrity.java:298, TilePainterForm.java:1279); 9 NetBeans '/\*\* Creates new form X \*/' stubs whose next declaration is a FIELD, not the constructor (TileDBWriter:29, ADPropRegistryEditor:26, CameraEditForm:18, ESPICAControl:15, LoadingDialog:26, NPCEditForm:60, NPCRegistryEditor:23, PropEditForm:49, ScriptEditor:33). That is 80 misplaced doc blocks, 54 of them in production. CtrmapMainframe alone carries 11 (133, 928, 1239, 1589, 1622, 1623, 1757, 1770, 1781, 2385, 2389). Spot check CtrmapMainframe.java:133: 'Which editing tool the World Editor is holding' sits directly above 'Every editor that holds unsaved work', so the tool selection field is documented as the editor list. Several orphans are the only written record of an invariant: PaintedFloorMesh:186 (why fixWindingUp may skip AO) and PaintedMaterials:449 (groundMeshOr's contract) both sit over a different method. The class recurs at every split or move: the method moves and its doc stays. Nothing refuses it: no suite scans for it, and javac 8-21 has no lint for it (JDK 23 adds -Xlint:dangling-doc-comments). ADDENDUM - A FOURTH SHAPE THE SCAN CANNOT SEE: a doc block attached to a declaration, but to the WRONG one. CtrmapMainframe.java:1884-1891 is the javadoc of the .bch model import ('Milestone 1 of native map editing: replace a map region's visual 3D model ... with an externally authored .bch'), and it sits on deployModAction (:1892), the emulator-mod deploy. The method it describes, importMapModelAction (:3171, called from the Map menu at :878), is 1,280 lines further down and has no doc block at all; the line before it closes resizeMapAction. A shape rule (a doc must be followed by a declaration) passes this block, because a declaration does follow it. Only a check that a doc block's subject words appear in the declaration's name or body would catch it, so the 80 measured are a lower bound on misplaced documentation. ADDENDUM - TWO MORE MISATTACHED BLOCKS, RECORDED DURING THE LEAD FOLD. NPCEditForm.java:1530: '/\*\* A form's widgets stacked top to bottom, each behind its label. \*/', then four blank lines, then scrDropdownActionPerformed, which it now documents. The helper it described has moved. PropEditForm.java:338-342: refreshPalette's javadoc ('Refills the palette list from the database...') sits on SEARCH_ALIASES, a static array, with a line comment between; refreshPalette itself (:352) has none. Neither is among the 80 the shape scan counts, because each is followed by a declaration. ADDENDUM - RE-MEASURED 2026-10-06 (m_javadoc.py, re-run, not edited). The first run's 'Creates new form over a FIELD' rule required no '(' anywhere in the next declaration, so a field whose initializer calls a constructor was taken for a method. Builder.java:48's multi-line stub over 'public DefaultListModel&lt;String> GARCmodel = new DefaultListModel&lt;>();' was the one it missed. The rule now looks only before the '=' and Builder is a fourth control. Result: STACKED 69 (43 production), ORPHANED 2, CREATES-NEW-FORM over a field 10. Total 81, of which 55 are in production. A cross-check (m_stubs.py) found 16 'Creates new form' blocks in the tree: 1 on its constructor, 10 on a field, and the other 5 stacked on another javadoc and counted there.

**Fix.** Move each block onto the declaration it describes (or delete it with the dead member it described). Make the class impossible: a battery scan that refuses a doc comment not immediately followed by a declaration (the shape m_javadoc.py measures), plants one of each shape, and when the build moves to JDK 23+ add -Xlint:dangling-doc-comments -Werror to build.ps1.

<a id="f203"></a>

## F203 - 181 unused imports (154 in production, in 64 files); 63 import a ctrmap type the file never names, faking dependency edges - 11 of them on CtrmapMainframe, 2 putting the global Workspace into the format layer

**low** | dead-code | `src/ctrmap/formats/tilemap/PaintedTiles.java:1`

MEASURED (scratchpad/m_imports.py over every tracked .java). Each single-name import's simple name is searched, unqualified, in the file body after the import block, with comments and string literals blanked. CONTROLS: RecordSchema.java's import of ctrmap.Workspace and SetupWizard.java's import of ctrmap.CtrmapMainframe (used only fully qualified) are flagged; CtrmapMainframe's javax.swing.JFileChooser (used) is not. The first version of the scan counted 'ctrmap.CtrmapMainframe.x' as a use of the import; control 2 caught that and the pattern now requires an unqualified name. RESULT: of 4,094 single-name imports, 181 are unused (154 production, 27 test) in 64 files, and 12 more are named only in comments. 63 of the production ones import a ctrmap.\* type the file never names. These are false dependency edges, and the source is the only place a reader sees dependencies: 11 x ctrmap.CtrmapMainframe, 6 x RegionFactory, 5 x MapModelObj, 5 x MapModelObjImporter, 4 x GameFiles, 4 x GfColl, 2 x ctrmap.Workspace (the format layer's RecordSchema and SchemaRegistry: lead 183), and nine tool classes in TilemapPanelInputManager. The worst files are the PaintedRegionBuilder split (PaintedTiles 14, PaintedHeights 13, PaintedCollision 11, PaintedMaterials 7, PaintedCliffMesh 7, PaintedFloorMesh 5) and TilePainterForm (15). Every stage kept its parent's import list (cf. F090). Spot check: CtrmapMainframe.java:46-47 import ScriptView and ZoneSaver; grep finds neither name anywhere else in the file. 65 wildcard imports in 29 files are not judged here (their use cannot be read off a name). Nothing in the build or battery refuses an unused import.

**Fix.** Delete them (the split files and TilePainterForm first), then refuse the class: a battery scan with the m_imports.py rule (unqualified use after the import block, comments and literals blanked), planted with one unused import, held at zero.

<a id="f207"></a>

## F207 - The which-game rule sees 1 of the 7 classes that branch on GameType (it flags only classes that also ask the global); ZoneHeader reads and writes its flag bits as XY-or-else, handing every non-XY game ORAS layout - the else-branch shape its sibling format classes were rewritten to forbid

**low** | architecture | `src/ctrmap/formats/zone/ZoneHeader.java:132`

MEASURED FROM BYTECODE (scratchpad/m_gametype.py: every production class outside ctrmap.gamedef whose constant pool holds a Fieldref to a GameType constant, and whether it also calls Workspace.game()/WorkspaceSession.game()/GameProfile.type(). CONTROL: ZoneLoadingPanel, the rule's one allowed asker, is listed as seen). RESULT: 7 classes branch on which game is loaded. SourceSeamTest.noApplicationClassAsksWhichGameIsLoaded (:190-272) can see exactly 1 of them (ZoneLoadingPanel), because it flags only a class that ALSO asks the global for the game ('decides = !asks.isEmpty() &amp;&amp; !names.isEmpty()', :252). The 6 it cannot see are handed a GameType: NpcMoveCodes and WarpTransitions (ORAS, XY), ZoneHeader (XY), and the three harvesters (ORAS; developer tools, ORAS-only by design). The rule as GameProfile states it ('never which game it is') is about the branch, not about where the game came from. Two of the six are written to the standard: NpcMoveCodes.move1Ext etc. (:258-300) and WarpTransitions.extLabels/extRaws (:150-176) switch with an explicit default of NO_LABELS/NO_RAWS, under a javadoc saying 'an unlisted game inheriting whichever game happened to be the else is the defect this shape exists to make unrepresentable'. ZoneHeader, in the same package, is that defect. The read at :132 is 'int relocator = (game == GameType.XY) ? 0 : 1;' and :138 'if (game == GameType.XY) ... else ...', and the write in calculateFlags (:216-) is the same binary. So every game that is not XY (ORAS, a null game for a loose zone file opened with no workspace, and any future profile) gets ORAS's bit layout for the dowsing, breath, 3D, ghosting and special-walking flags on read AND on write, and the round trip writes them back in that layout (cf. F115).

**Fix.** Move ZoneHeader per-game bit positions behind GameProfile as data (a flag-layout table per game, absent for unmeasured games, refusing to read or write the flags when absent), and widen the identity rule to any GameType constant named outside gamedef with an argued exception table (NpcMoveCodes/WarpTransitions qualify; the harvesters are tools).

<a id="f212"></a>

## F212 - The unchecked read/skip ratchet (76, no slack) counts lines, not dropped answers: 41 are dropped reads, 35 are used answers or parser factories named read (AreaEnv.read, MaisonSet.read, DressUpIndex.read) - fixing a real one cannot lower it and a new format reader raises it

**low** | test-debt | `src/ctrmap/tests/SilentCatchTest.java:143`

MEASURED (scratchpad/m_reads.py). CONTROL: SilentCatchTest.uncheckedReadsAndSkips's own rule (a LINE matching \.(read|skip|skipBytes)\s\*\( without readFully|skipFully|.read()) reproduces exactly 76 lines today, its UNCHECKED_READ_CEILING (:75), with zero slack. Classified by what each counted line is: 41 are statement-level calls whose answer is dropped, the defect the ratchet exists for. The other 35 are lines where the answer IS used, or where 'read' is not a stream read at all but a parser's factory method. Read examples: CtrmapMainframe.java:1572 'AreaEnv env = AreaEnv.read(ad.getFile(4));', DressUpArchive.java:112 'idx = DressUpIndex.read(...)', MaisonPoolGuard.java:124 'out[i] = MaisonSet.read(rec);', LittleEndianDataInputStream.java:45 'return dis.read(b);', RandomAccessBAIS.java:66 'int read = super.read(b);'. No dropped call hides on a line the rule exempts (0). So the recorded measurement of 'answers this program still drops' is 46% about other things. Converting a dropped read to a checked one written on one line ('int n = in.read(b); if (n &lt; len) throw ...') does not lower the count. Adding a new format reader with a static read(...) factory raises it. Deleting or renaming a factory makes room for a new real dropped read. CLAUDE.md: a ratchet over a number that does not measure its subject is satisfied by changing the wrong things.

**Fix.** Count the statement-level shape only (a read/skip/skipBytes call that is the whole statement, in comment-blanked text, on a receiver that is a stream), re-seed the ceiling to the measured 41, and plant one dropped skip.

<a id="f215"></a>

## F215 - ContainerBytes says the 34 hand-written container slicers now answer through it; 35 hand reads of the same offset table remain in 19 files (13 in 8 production files, 22 in 11 suites - six copies of the same zone-script slice), and no rule refuses the next

**low** | duplication | `src/ctrmap/formats/containers/ContainerBytes.java:11`

MEASURED (grep over every tracked .java for a hand-read of the GameFreak mini-pack offset table, i32/u32/readInt/getInt/le32(x, 4 + k\*4) and the (k+1) form. CONTROLS: EncounterTable.java:153 and NpcTemplatesTest.java:373, both read in this audit, are found). ContainerBytes.java:11-14 says each caller 'wrote their own eight-line slicer; there were thirty-four of them (seven in the program, twenty-seven in the battery) and they had drifted on what a corrupt table or an empty subfile should answer. Now they answer this.' RESULT: 35 hand-read sites remain in 19 files. PRODUCTION, 8 files, 13 sites: ZoneAppender.java:410/468/496 (EN pack), ZoneRemover.java:97, EncounterTable.java:153/178 (no bounds check: census M.47), PropDatabase.java:265-266 (getSubfile, a whole second slicer: census M.49), MsgWrapperInjector.java:337-338 (extractZoneScript), PokeData.java:197, MapResizer.java:59. TESTS, 11 files, 22 sites. Six are the same 'zone script out of a ZO' slice, i32(zo, 4 + 2\*4)..i32(zo, 4 + 3\*4): DispatchGuardsTest:591, ZoneScriptAnalyzerTest:161, PartyParamTest:358, TalkerWizardDryRunTest:220, DispatchTrampolineTest:366, NpcEntityGuardsTest:273 (and NpcTemplatesTest:373 generalised), beside MsgWrapperInjector.extractZoneScript, which other suites import. Plus EncounterTableTest, ZoneRepurposeScannerTest, PokeDataTest, ZoneAppendTest (4) and MapResizerTest (3). The capability was centralised and the copies kept being written. DuplicateWorkTest's OWNED table has no entry for this slicer (grep: no ContainerBytes or subfile marker in it), so nothing refuses the 36th.

**Fix.** Route the 13 production reads through ContainerBytes.subfile (refusing in words on null), have the suites use ContainerBytes or extractZoneScript, and add the offset-table shape (i32(x, 4 + k\*4)) to DuplicateWorkTest OWNED with ContainerBytes.java as its one owner, planted with one hand slice.

<a id="f216"></a>

## F216 - 43 root computations in 40 Python files count parent directories from __file__ instead of the anchored bundle_rules.repo_root (used by 9) that the bundle names as the rule; correct today only because the relocatable bundle-hooks folder sits at the default depth

**low** | architecture | `.claude/bundle-hooks/guard_agent_campaign.py:44`

MEASURED (scratchpad/m_rootcount.py: every tracked .py expression that counts parent directories from __file__, nested os.path.dirname or Path(__file__).parent..., resolved from the file's own location. CONTROLS: guard_agent_campaign.py (lead 229) is listed, and mutate2.py's BASE resolves to the repository's PARENT, as F179 measured). RESULT: 43 counted-root expressions in 40 files. 40 resolve to the repository root TODAY, 2 deliberately to its parent (mutate.py:27, mutate2.py:57), 1 to tools/. Only 9 files use the anchored helper bundle_rules.repo_root, whose own docstring states the rule: 'The project root, ANCHORED on .git or CLAUDE.md - never counted in dirnames' (bundle_rules.py:144-145). Five of the 23 bundle hooks still count (git grep in .claude/bundle-hooks), although the bundle's install declaration '_hooks_at' exists precisely so a project can put the bundle's hooks in a different folder (here .claude/bundle-hooks, two levels down, the same depth as the default). A project that sets _hooks_at one folder deeper gets those five hooks resolving a ROOT one level inside the repository, with nothing saying so. The answer is correct only because two folder depths happen to agree. CLAUDE.md: 'A guard at call sites is one call site from broken'.

**Fix.** Replace every counted root with repo_root() (or one shared helper that anchors on .git/CLAUDE.md and refuses when it finds neither), and add the counted-dirname shape to the bundle install check, planted with one counted root in a hook.

<a id="f221"></a>

## F221 - Open reverse-engineering: 23 fields in 8 format classes are named unknown (36 format-file comments admit a guess); most are preserved verbatim, but GRCollisionFile reads five it never writes, the Zone tab offers two raw 'UnknownFlag' controls for editing, and CameraData ignores a short read of its unknown bytes

**low** | unfinished | `src/ctrmap/formats/cameradata/CameraData.java:15`

MEASURED (scratchpad/m_unknown.py over the field declarations in javap of the scratchpad build, production only. CONTROLS: the five camera fields the lead named are found, and chunkId/chunkLod/btnChunkTool/selectSubChunks/UNKNOWN are NOT matched. The first version of the regex was case-insensitive and counted 'chunk' as 'unk', giving 37 instead of 24). RESULT: 4,140 production fields, of which 24 are named as unknown or padding. 23 of those are reverse-engineering left open, in 8 format classes plus the UI that shows them: GRCollisionFile 5 (unknown_const_0x0/0x1/0x5D8_1/0x5D8_2, unknown_consts_1_3_2_1_2), CameraData 5 (unknownBytes[16], unknown00, unknown01or03, unknownInt1, unknownFFFF; :80's comment is 'also always FFFF except for e4 rooms and guess what, parfum palace'), ZoneEntities$Prop 4 (unknown2/4/6/10), ZoneHeader 2 (unknownFlag, unknownFlags), GRProp 1, GFLPawnScript 1, MapMatrix 1, and ZoneLoadingPanel 4 widgets. 36 comment lines in 24 of the 123 format files say a meaning is unknown or guessed; DressUpIndex 4 and PartyParam 3 lead. HOW EACH IS HANDLED, read in source. CameraData and ZoneEntities$Prop read the bytes and write them back unchanged (CameraData.java:54-80 read, :132-151 write; ZoneEntities.java:214-221 read, :230-237 write), so they are preserved but cannot be edited meaningfully. GRCollisionFile READS its five (:56-78) and never writes them: write() (:150-163) rebuilds subfile 2 through GfColl.build, which reproduces retail. They are dead fields that look like round-tripped state. The Zone tab shows ZoneHeader's two to the user as raw controls, a checkbox captioned 'UnknownFlag' (ZoneLoadingPanel.java:814) and a number field under 'Unknown properties (From PK3DS):' (:902), and store() assigns zone.header.unknownFlags twice, at :401 and again at :403. CameraData.java:54 'dis.read(unknownBytes)' discards the count it read, so a short read leaves zeros in place of camera bytes and says nothing. The debt is small and concentrated. It is mostly honest (preserved verbatim), except for the dead collision fields and the raw flags offered for editing with no meaning attached. ADDENDUM - WORLD ANIMATIONS: ONE OF THE KINDS CAN BE AUTHORED. AreaData subfile 2 is 'the file that makes water scroll, grass sway and waves foam' (WorldAnim.java:13-17). The parser reads all of it, but the only write is WorldAnim.spliceSeaScroll, which grafts the retail chip_sea_b pair copied from area 10 (:18-23, SEA_MAT :44). Its only caller is the map painter's water option (TilePainterForm.enableWaterScroll, :155-175). A painted area can therefore scroll its sea and nothing else: no grass sway, no foam. It is a deliberate, narrow first step, written down only in the class doc. Like the five unmodelled area subfiles in F236, it marks how much of the area format is still read-only. ADDENDUM - THE NPC EDITOR SHOWS ITS OPEN RE AS RAW LABELS, INCLUDING FIELDS THE FORMAT HAS SINCE NAMED. Its generated form captions a section 'There's much to do and many unknowns on the horizon:' (NPCEditForm.java:1826) and labels fields 'U10' and 'U12' (:1837, :1844) and 'ZL2'/'ZL3' (:1860, :1862). The last pair are bound to npc.leashWidth/leashHeight, names the format class already gives them, and the u14/u16 label variables hold areaSX/areaSY. So part of what the screen calls unknown is known in the code. The unbounded spinners in the same section are F124. ADDENDUM - TRIGGERS AND THE TRAINER WIZARD. The Trigger form labels five of its fields 'Unknown 2:', 'Unknown 6:', 'Unknown 8:', 'Unknown 14:' and 'Unknown 16:' (TriggerEditForm.java:222-234): raw offsets the user can edit with no meaning attached, beside the Zone tab's 'UnknownFlag(s)' and the NPC form's 'U10'/'U12'. The trainer NPC wizard still sends the user out of the program: 'Trainer (type to search; edit party/class in pk3DS)' and 'trainer data slot ID is valid (set it in pk3DS)' (TrainerForm.java:41, :47), although Game Data > Edit trainer (TrainerEditDialog) now edits the party, class and slot in-app. The wizard predates it, and the text was not updated when the editor arrived. ADDENDUM - THE ZONE TAB'S HEADER FORM. ZoneHeader reads and writes four background-music slots, BGMSpring, BGMSummer, BGMAutumn and BGMWinter (ZoneHeader.java:40-43, :99-102, :167-169). The Zone tab edits only Spring (store() sets BGMSpring alone, ZoneLoadingPanel.java:378), and nothing records which slot the game plays. A user who changes 'the zone's music' may be changing a slot the game never reads, and cannot reach the other three. The same form labels 'MapChange:' (:883) and 'OA Default spawn point (3D world float coordinate as short): (XY role unknown)' (:923) beside the 'Unknown properties (From PK3DS)' group.

**Fix.** Delete GRCollisionFile's five dead unknown_const fields (GfColl.build owns the bytes) or assert at load that they equal what GfColl writes; move the Zone tab's two raw flags behind an 'advanced/raw' disclosure with a tooltip saying their meaning is unknown, and drop the duplicate assignment at ZoneLoadingPanel.java:403; make CameraData read exactly 16 bytes (readFully) so a short table throws like the rest of CameraDataFile; keep a list of the unknowns in docs so the open RE work is visible.

<a id="f222"></a>

## F222 - The Script Editor tab keeps the closed zone's script after a failed load, a failed workspace open or Clean workspace, and edits there are lost silently - census M.99's fix (route through ZoneEditors.clear) misses it because this view's clear() is a deliberate no-op

**low** | silent-failure | `src/ctrmap/CtrmapMainframe.java:457`

READ IN SOURCE. A zone stops being open in three ways, and none of them takes the script out of the Script Editor tab. (1) A load that fails part way, and a workspace that fails to open, both call ZoneLoadingPanel.unloadZone() (ZoneLoadingPanel.java:1317-1321, reached from :1248 and CtrmapMainframe.java:1817), which runs zoneEditors.clear(). The Script view's clear() is an empty method whose comment reads 'the script editor holds the zone's script; it was not cleared' (CtrmapMainframe.java:457-460), so the tab keeps the GFLPawnScript of the zone that just closed. (2) Options > Clean workspace (CtrmapMainframe.java:1528) and the game-folder repoint call unloadEditors() (:1859-1865), which names five editors and not the script editor (nor warp/trigger, census M.99). (3) A zone switch replaces the script through show(). The Script Editor has no save of its own (grep: no storeFile/store/save in ScriptEditor.java except savePrefixEntry). Its edits change the in-memory script object, which only the zone save writes, and that zone is no longer open. So after (1) or (2) the user can keep editing the previous zone's script in a tab that looks live, and every keystroke is lost without a word. Census M.99 found the same defect for the warp and trigger forms and proposes routing unloadEditors through ZoneEditors.clear(). That fixes those two but not this one, because this view's clear() is a no-op by design, and so is the map view's (:313-317, 'keeps the map it drew'). The asymmetry is written down in comments rather than decided: two of the nine ZoneViews have empty clear().

**Fix.** Give ScriptEditor an unload() (script=null, editor cleared and disabled, a 'no zone open' caption) and call it from the Script ZoneView's clear(); then route unloadEditors() through ZoneEditors.clear() as M.99 proposes, so all three forms are dropped by one list. Guard: a suite that loads a zone into a ZoneEditors built with a spy script view, calls clear(), and asserts the view holds no script - and a shape check that no ZoneView.clear() body is empty without an allowlisted reason.

<a id="f223"></a>

## F223 - The Map and Zone actions exist twice, as a menu and a toolbar written by hand: the comments miscount them (Zone bar 'seven' has six; 'the Map menu repeats these five' - it has four, Encounters is under Game Data), and the two describe the battle-facility action differently

**low** | duplication | `src/ctrmap/CtrmapMainframe.java:1098`

READ IN SOURCE (CtrmapMainframe.java). The World Editor's map-actions bar (:985-995) and the Zone Loader's actions bar (:1099-1110) are second copies of the Map and Zone menus (:871-890), written by hand with different labels, and the comments that state the correspondence are wrong. The javadoc at :983 says 'the Map menu repeats these five'. The bar has five (Blank canvas, Resize map, Fog &amp; lighting, Encounters, Fork geometry). The Map menu has four (:873-876): Encounters lives under Game Data (:898) and in a third place, the Zone tab's context entry (:1735). The comment at :1098 says 'the Zone menu repeats these seven'. The bar has six buttons (:1103-1108) and the menu has the same six (:885-890). The labels also say different things about one action. The bar's 'Custom battle facility' tooltip offers two modes ('INDEPENDENT script-driven battles ... or clone a retail facility's full engine'), while the menu item reads 'Custom battle facility here (clone a retail facility)', naming only one. 'Remove added zones (restore stock 536)' bakes ORAS's zone count into a label. Nothing compares the two lists: 11 actions, each wired twice (Encounters three times) by hand. The next action added to one is the next one missing from the other.

**Fix.** One action table per area (label, menu label, tooltip, handler, enabled-when), rendered into both the menu and the toolbar, so a count or label cannot differ; delete the counting comments. Guard: a MainframeShapeTest check that every toolbar button's handler is reachable from exactly one menu item and vice versa.

<a id="f226"></a>

## F226 - Import OBJ's template-mesh picker is a blind number 0-999, not bounded to the chosen region's mesh count and not previewed: a number past the donor's meshes turns every new-material group into 'skipped: Index N out of bounds for length M'

**low** | ui | `src/ctrmap/CtrmapMainframe.java:2799`

CtrmapMainframe.java:2798-2799 builds the template picker as 'new SpinnerNumberModel(id, 0, fieldCount - 1, 1)' for the region (bounded) and 'new SpinnerNumberModel(0, 0, 999, 1)' for the mesh (not bounded by anything), captioned 'Template mesh number (see exported group names, mesh&lt;N>_...)' - the user is sent to read file names from an earlier export to find a number. The donor model is read only after OK (:2812-2815). BchModelAppender.append(T, D, dj, ...) indexes d.meshes.get(dj) (:119, :129, :140) with no range check; MapModelObjImporter.apply catches the RuntimeException per group and records 'skipped: ' + ex.getMessage() (:113-114), so each new-material group in the OBJ is reported skipped with a raw IndexOutOfBounds text, and if no group had an existing material the whole import fails with 'No OBJ group could be applied' (:117-118). The blank-canvas action next door already shows a picker of the region's real materials with texture icons (:2898-2930); this one asks for an index.

**Fix.** Build the template picker from the chosen region's model the way the blank-canvas picker is built (material name, face count, texture icon), re-read when the region spinner changes; at minimum bound the spinner to the donor's meshCount and refuse an out-of-range index before the import with a sentence that says so.

<a id="f228"></a>

## F228 - Little-endian integers are read six ways: two shared primitives (stream in 28 files, static helpers in 52) plus RandomAccessBAIS's position-tracking subclass, ByteBuffers, three private shifting helpers and 13 inline shift assemblies; RandomAccessBAIS's position drifts on skip, EOF and the reads it does not override

**low** | duplication | `src/ctrmap/formats/h3d/RandomAccessBAIS.java:10`

MEASURED (scratchpad/m_le.py over the 265 production sources, comments blanked. CONTROLS: formats/LittleEndian.java is seen declaring helpers; ZoneRepurposeScanner's 'import static ...LittleEndian.u16' is seen as a shared-helper user. A grep cross-check of every 'int u16/i32/u32(' declaration found the instance wrappers in WorldAnim, BchMapModel and ItemData, which delegate rather than shift, so they are correctly not counted as re-implementations). RESULT: production code reads little-endian integers six ways. The two shared ones carry most of it: LittleEndianDataInputStream/OutputStream in 28 files, and formats.LittleEndian u16/i32/u32 in 52. Four more sit beside them. RandomAccessBAIS is used in 8 h3d files; it subclasses the stream and re-tracks 'position' by overriding nine read methods. ByteOrder.LITTLE_ENDIAN ByteBuffers appear in 3 files. Three classes declare their own shifting helper (BchModelVerifier, TextureCodec, GFMessageFile). 13 inline '&amp; 0xFF) &lt;&lt; n' assemblies sit in 5 files (LZ11 4, WorldAnim 3, ShopData 3, GARC 2, MsgWrapperInjector 1). Both shared primitives live in the application package (census M.17), so every format class that uses the stream depends on ctrmap. RandomAccessBAIS (lead 83): it does not override readFully, skipFully or read(byte[],int,int), so any of those desyncs 'position' from the stream. No current caller uses them (grep over its 7 users: only skip(n) at H3DModel.java:116/126/172 and H3DMaterial.java:129/131). skip(n) adds n to position whatever the stream actually skipped (:76-79), read() and read(byte[]) add -1 at end of stream, and seek ignores input.skip's answer (:82-86). The effects show only on truncated data, where the next read throws anyway, so this is latent rather than live.

**Fix.** Fold the four side mechanisms into the two shared ones (move both into ctrmap.formats per census M.17): replace RandomAccessBAIS with a byte[]-backed reader whose position IS the index (no overrides to forget), route the three private helpers and 13 inline shifts through LittleEndian.u16/i32/u32. Guard: a source rule refusing '&amp; 0xFF) &lt;&lt; 8|16|24' and ByteOrder.LITTLE_ENDIAN outside formats/LittleEndian, with a planted line.

<a id="f229"></a>

## F229 - 53 error dialogs in 17 files show ex.getMessage() - 'null' for a NullPointerException on Java 8 - while 26 use Ui.reason, the helper written because getMessage can be null; census M.62 counted five from its slice

**low** | ui | `src/ctrmap/CtrmapMainframe.java:3167`

MEASURED (scratchpad/m_getmsg.py over the 265 production sources. For every Ui.error/message/confirm/option/input and JOptionPane.show\*Dialog call it takes the full argument list to the matching parenthesis. CONTROLS: CtrmapMainframe.java:3167 'Resize failed:\n' + ex.getMessage() is found; a Ui.reason string handed to a non-dialog method, ZonePreviewPane.say, is not counted; at least one Ui.reason dialog exists). RESULT: 53 user dialogs in 17 files build their failure text from ex.getMessage(), and 26 use the helper the tree wrote for this, Ui.reason/Bytes.reason ('the exception's message, or its type when it gave none'). Two thirds of the error dialogs skip it. CtrmapMainframe 15, NPCEditForm 9, ZoneLoadingPanel 5, ItemEditDialog 4, AreaLightingDialog 3, and 12 more files. getMessage() is null for every NullPointerException on Java 8, which run.bat will start on any 'java' on the PATH, and for most bare runtime exceptions; on later JDKs a helpful-NPE text is written for a developer. Either way the user reads 'Resize failed: null' or a JVM sentence. Lead 35 is one concrete path: MapResizer.resize dereferences three getWorkspaceFile results without a null check (MapResizer.java:195-196, :199, :214). That fails before anything is written, which is good, and the dialog that reports it is CtrmapMainframe.java:3167 above. Census M.62 filed this class from its slice as 'five dialogs'. The tree holds 53, and no suite counts them. ADDENDUM - A REFUSAL THAT THROWS AWAY ITS OWN REASONS. BchModelAppender.append computes both models' validate() lists and, when either is non-empty, throws 'new IllegalStateException("parse problems")' (BchModelAppender.java:216). The lists say what was wrong and are dropped on the same line. MapModelObjImporter shows that text as 'skipped: parse problems' for every new-material group of an OBJ import (:113-114), and the user cannot tell whether the target map or the template donor was the one that failed. The other refusal in the class names its reason ('donor submesh is skinned (bone-dependent) - not supported', :137-138). Skinned donors are a documented v1 limit (:132-136).

**Fix.** Replace the 53 with Ui.reason(ex) mechanically, then make it impossible: Ui.error/message take the Throwable as a parameter (Ui.error(parent, "Resize failed", ex, title)) and format it themselves, and a DialogSeamTest rule refuses '.getMessage()' inside any Ui/JOptionPane argument list, planted with one.

<a id="f230"></a>

## F230 - File > Pack Workspace with no workspace open does nothing and says nothing: packWorkspace returns silently when there is no session, and the menu item is wired to it unguarded

**low** | ui | `src/ctrmap/Workspace.java:533`

Workspace.java:530-533: 'if (current != null &amp;&amp; !current.isReadOnly()) { ... }' with no else - no message, and the onDone a caller hands in never runs. Of the 14 production callers (git grep), 13 sit behind a loaded zone, a guard ('Load a workspace first.' at CtrmapMainframe.java:1893-1896 for Deploy) or an open that just succeeded (repairOnOpen :1831); WorkspaceSettings.java:137 packs before a repoint, where nothing open means nothing to pack. The one user-reachable silent path is the menu item itself, CtrmapMainframe.java:867 'file.add(item("Pack Workspace", Workspace::packWorkspace))': with no game set up, the click is swallowed. The read-only branch is documented as test-only ('the application never opens one').

**Fix.** Say it: an else branch that tells the user there is no workspace to pack (the same sentence Deploy uses), and throw for a read-only session instead of skipping, so a caller's onDone is never silently dropped; or disable the menu item until a workspace is valid.

<a id="f236"></a>

## F236 - The area atmosphere is advertised as 'fog and ambient light' in four places, but the ambient control was removed (it never was a colour) and only fog channels 54-59 of 61 are decoded; AreaEnv.viewFog says it mixes by strength and returns the raw colour; AD still lists subfile 4 as '???'

**low** | doc-drift | `src/ctrmap/formats/area/AreaEnv.java:219`

READ IN SOURCE. The area-atmosphere work learned a great deal and the descriptions around it did not follow. (1) AreaEnv decodes 6 of the block's 61 twelve-float channels: fog density, colour, near and far, channels 54-59, proven from code.bin (AreaEnv.java:25-55). The rest, which the dialog's own comment calls 'light directions, hemisphere, ranges' (AreaLightingDialog.java:213-214), can only be taken wholesale from a GameFreak area ('Copy from GameFreak', :210-217). The dialog removed its 'Ambient / light colour' control on purpose, because 'the bytes it wrote are the constant 1.0 group of that same channel, so it was never a colour the user could choose' (:131-133). Four places still promise it: the class javadoc ('fog/sky color, fog near/far draw distance, and an ambient/light color', :24-29), the World Editor toolbar tooltip ('or hand-tune fog and ambient light', CtrmapMainframe.java:991), the Map menu item 'Edit area fog &amp; lighting...' (:875) and every dialog title 'Area fog &amp; lighting'. (2) AreaEnv.viewFog's javadoc says the colour is returned 'already mixed by that time's strength: fog at strength 0 is no fog' (:212-217). The body only gates on strength &lt;= 0 and returns the raw fogColor (:219-224), so the 3D view paints a strength-0.1 fog at full colour. (3) AD.java's contents list still reads '4 - ???' and types subfile 4 ContentType.UNKNOWN (AD.java:14, :30), the block AreaEnv has since decoded and proven. Subfiles 5 (environment assets), 6 (default camera), 8/9 (LOD) have no reader or editor anywhere in the tree (git grep for a literal getFile(5|6|8|9) finds only TileMapPanel :263 and :538, which read subfile 5 of a GR REGION, its tall grass; areas are otherwise copied whole), and 10 is labelled unused. The open RE (F221) is wider than its field names: five of an area container's twelve subfiles (5, 6, 8, 9, 10) are carried byte-for-byte with no model, and the 3D view (CtrmapMainframe.refreshSceneFog :1578-1582) is the consumer of the unmixed fog colour.

**Fix.** Say what exists: 'Fog' (or 'Fog (and copy GameFreak lighting)') in the tooltip, menu, dialog title and class doc; make viewFog multiply by strength or fix its javadoc; type AD subfile 4 as the environment block AreaEnv reads and keep the open subfiles (5, 6, 8, 9) in the RE list (F221).

<a id="f237"></a>

## F237 - The three 'verify the merged texture pack before storing it' checks pass bytes that are not a BCH at all: BCHFile returns on a bad magic with errorlevel still 0 and no textures, and those checks test errorlevel alone

**low** | silent-failure | `src/ctrmap/formats/h3d/BCHFile.java:37`

READ IN SOURCE. BCHFile(byte[]) reads the magic, and on a mismatch prints 'BCH magic mismatch!' to stderr and RETURNS (BCHFile.java:31-40) with errorlevel still at its initial 0 (:29) and empty model and texture lists. errorlevel is incremented only deeper in the parse (:279, :304). So 'errorlevel == 0' means 'no error was counted', not 'this is a BCH'. Eight production sites outside BCHFile test it. Four add '|| models.isEmpty()' and so refuse a non-BCH (CtrmapMainframe.java:3213, TerrainTextures :34 and :80, MapPreview3D :101). Four test errorlevel alone, and those four are TEXTURE packs, which have no models, so the second check is not available to them. ZoneHeader.java:256 checks Bytes.checkBCHMagic first (:254) and is safe. The other three, PropEditForm.java:644 and :1099 and TilePainterForm.java:951, are the 'decode and verify the merged pack BEFORE storing anything' checks after BchTexturePack.importIntoArea builds a new texture pack (PropEditForm.java:641-646; TilePainterForm.java:948-954). Their whole job is to catch a bad build, and a build whose header is not even 'BCH' passes them: errorlevel 0, zero textures. TilePainterForm then stages the bytes into the area (area.stage(1, merged), :954), and PropEditForm walks an empty texture list and carries on. The class file credits itself as 'mostly a copy-paste' of Ohana3DS (:17-19) and still carries a 28-line commented-out debug dump (:248-276). The sharper problem is the sentinel: a value that cannot say 'not a BCH' is being used as the verdict of a verifier.

**Fix.** Make the constructor throw (or set errorlevel) on a bad magic, a short buffer or any read failure, as CameraDataFile and the containers now do; until then the three verifiers also require Bytes.checkBCHMagic and a texture count equal to target + imported. Guard: a suite feeding the verifier a non-BCH merged pack and asserting the stage is refused.

<a id="f239"></a>

## F239 - Ten geometry paths silently skip meshes whose positions are not Float(3), and the class contradicts itself on whether any exist ('every real ORAS map model, 163,499 verts' vs 'a minority of meshes'); a box move would leave such meshes behind with no count or message

**low** | unfinished | `src/ctrmap/formats/h3d/BchMapModel.java:313`

READ IN SOURCE. BchMapModel.MeshGeom.posOk marks a mesh whose position attribute decoded as Float(3) (BchMapModel.java:313-316, :466-468), and every map-geometry writer and reader skips a mesh where it is false: GeoBoxOps query/move/duplicate/delete (:75, :106, :138, :196), MapModelObj export (:58), MapModelObjImporter (:94), MapPrefab cut and stamp (:113, :785), RegionFactory (:129) and MapResizer's ground pick (:238). Two comments ten lines apart in the same class disagree about whether such meshes exist. :303-304 says 'Position is Float(3) in every real ORAS map model (verified: 163,499 verts, 0 garbage)'. :313-315 says 'A minority of meshes use a vertex-format variant this reader doesn't decode; those are left untouched by edits.' One of the two is stale, and the code cannot say which. If the minority exists, the skip is silent: GeoBoxOps.Selection counts vertices, fullFaces and touchedMeshes and no skipped meshes (:64-69). A Geometry-tool move then moves every decodable mesh in the box and leaves the others where they were, and the collision (rebuilt from the moved geometry) stops matching the picture. An OBJ export likewise omits them and does not say so. If it does not exist, ten guards and a 'left untouched' contract defend against nothing. Either way the vertex-format support is unfinished in the way that matters: no count, no message, and a doc that contradicts itself.

**Fix.** Measure it once over the pristine FieldData (the corpus walk the 163,499 figure came from) and delete the wrong comment. If any exist: decode the variant or count skipped meshes in GeoBoxOps.Selection and the OBJ export and say so in the result. If none: make posOk=false a refusal (throw naming the region) instead of a silent skip, so a future variant is loud.

<a id="f240"></a>

## F240 - Opening a damaged .ctrprefab allocates whatever its length fields say ('new byte[in.readInt()]'): a large value is an OutOfMemoryError that escapes the loader's 'catch (Exception)'; the class doc still calls new-material injection a future layer though the v2 stamp path uses it

**low** | data-integrity | `src/ctrmap/formats/h3d/MapPrefab.java:885`

MapPrefab.load (:865-900) checks the magic CMPF and the version, then trusts every count and length it reads: 'piece.vertexBytes = new byte[in.readInt()]' (:885), 'piece.triangles = new int[in.readInt()]' (:887), and the piece count (:879), with no bound against the file's own size. A negative length is a NegativeArraySizeException, which GeoEditForm's 'catch (Exception ex)' turns into the status line 'Could not load the prefab: -123' (:266-271). A large positive one is an OutOfMemoryError, an Error the catch does not take, which leaves the action through the EDT with nothing shown (F219) and a 1 GB-heap editor in a low-memory state. Prefab files are user-saved and shareable, so a truncated download is enough. The class javadoc says injecting brand-new materials 'is the BchModelAppender path layered on top once available' (:39-41), but the stamp already does it ('stamped as brand-new material+mesh via BchModelAppender (v2 full path)', :189), and the format note calls the encoding 'little-endian via DataOutput = big-endian Java streams' (:43), which contradicts itself (DataOutput is big-endian). Lead 82's orphaned javadoc at :714-719 is one of F202's measured 80.

**Fix.** Bound every length by the bytes remaining in the file (and a sane per-piece maximum) and throw IOException naming the field, the way GARC and the containers now refuse; fix the two stale sentences in the class doc.

<a id="f241"></a>

## F241 - Every battle-challenge NPC defaults to the same streak save variable (0x4020) and nothing checks whether another challenge - or a retail script - already uses the one chosen: two challenges built with the default share one streak

**low** | correctness | `src/ctrmap/formats/scripts/GauntletScriptWizard.java:34`

GauntletScriptWizard.DEFAULT_STREAK_WORK = 0x4020 (:34) is the Config default (:43) and ChallengeForm's pre-filled field ('Streak save variable (hex; script-corpus-free default)', ChallengeForm.java:33, :85). validate checks only the range 0x4000..0x4FFF and that it packs (:69-72); the variable is then pushed into the generated script at five sites (:99, :152, :156, :163, :187). Nothing looks for the same variable in the zone's other challenge scripts or in other zones. 'Corpus-free' describes the retail scripts at the time the default was chosen, not the user's own earlier challenges. So the second challenge NPC anyone builds with the defaults reads and resets the first one's streak: winning at one advances the other. The form says the feature is 'UNPROVEN IN-GAME' (:94; F217).

**Fix.** Allocate the default per challenge: scan the workspace's zone scripts for streak variables the wizard has already emitted (its frame is recognisable) and offer the next free one; refuse a variable another challenge uses unless the user ticks 'share the streak'.

<a id="f243"></a>

## F243 - Help > About opens a separate JFrame that says 'CTRMAP R0' and credits and links only the 2019 upstream - this fork's version appears only in the update dialog; ESPICA runs in a second stand-alone JFrame, the two windows left under the owner's 'never a new window' rule

**low** | ui | `src/ctrmap/humaninterface/AboutDialog.java:36`

AboutDialog extends javax.swing.JFrame (AboutDialog.form declares JFrameFormInfo) and Help > About shows it with setVisible (CtrmapMainframe.java:1547-1551). Its labels are fixed in initComponents: versionLabel 'CTRMAP R0' (:36), 'Developed by HelloOO7 ... github.com/HelloOO7/CTRMap' (:39) and the 2019 research credits (:47); it also keeps a standalone main (:112; F027). The fork's real version, AppVersion.current(), is shown only by the update dialogs (UpdateUI.java:80, :108), and the main window's title never carries it (the one setTitle, :1370, names an opened GR file). F005 sends Help > Support to the same upstream tracker. ESPICAControl is the other remaining JFrame (ESPICAControl.form JFrameFormInfo): Builder.runESPICA opens it as its own window per run (Builder.java:661-668). CLAUDE.md section 2: 'FEATURES LIVE IN THEIR OWN UI AREA. Never menu-dumped, never a new window.' Four more forms still declare JFrameFormInfo while their classes now extend FormPanel (TileDBWriter, ADPropRegistryEditor, NPCRegistryEditor, WorkspaceSettings). FormPanel answers getContentPane/setTitle/pack/setDefaultCloseOperation as shims (FormPanel.java) so the JFrame-shaped generated code still compiles, and the form files and the classes no longer describe the same component (cf. F122).

**Fix.** Show About inside the window (a Help-tab pane or a modal over the frame) with AppVersion.current(), this project's repository and the upstream credit as credit; host ESPICA's output in the Extras tab beside the Builder that starts it; convert the four .form files to JPanelFormInfo through NetBeans (never by hand-editing initComponents) and delete FormPanel's JFrame shims once nothing calls them.

<a id="f244"></a>

## F244 - The 3D move gizmo's camera distance is computed per axis as |model| - |camera|, which is wrong whenever camera and object are on opposite sides of an axis: the gizmo then shrinks and dragging slows in proportion to the error

**low** | correctness | `src/ctrmap/humaninterface/CM3DNavigator.java:73`

CM3DNavigator.renderNavigator (:67-80) computes distFromTarget = sqrt(sum over x,y,z of (|mdl| - |cam|)^2) with cameraVec = -translate. For an object at x=+300 and the camera at x=-300, that axis contributes (300-300)^2 = 0 instead of 600^2. The value then drives two things: the gizmo's scale (scale = distFromTarget / 1000, :77-80, applied to all three arrows) and the drag step, where H3DRenderingPanel moves the target by distFromTarget / 1200 per mouse unit on each axis (:236-245). A loaded map lies at positive coordinates (cell \* 720 + offset), so while the camera is inside the map the signs usually agree and the formula gives the true distance. It goes wrong when the camera's position crosses an axis the object does not, for example when it is lowered below Y = 0 or moved past the map's edge at x or z = 0. The arrows then shrink and dragging slows in proportion to the error. This is how often the bug can bite, read from the arithmetic, not measured in use. The constructor also catches only IOException around the three bundled arrow loads (:28-34); a missing resource is a null stream and a NullPointerException, not that IOException, and a bad one is an empty mesh (F130 addendum).

**Fix.** Euclidean distance on the difference of the vectors: sqrt((m-c).(m-c)). One line; add a unit check with an object and camera on opposite sides of the origin.

<a id="f245"></a>

## F245 - ESPICA is launched with Runtime.exec on one concatenated string, which splits on whitespace: an ESPICA install, OBJ, donor or output path containing a space (a Windows user folder such as 'C:\Users\\* Doe') breaks the command

**low** | correctness | `src/ctrmap/humaninterface/ESPICAControl.java:147`

ESPICAControl.runProcess builds 'Runtime.getRuntime().exec(ESPICAPath + proc.getArguments())' (:147), and getArguments appends ' -i ' + input + ' -d ' + donor + ' -o ' + output and extras with bare spaces, with no quoting (:202-215). Runtime.exec(String) tokenises on whitespace, so a space anywhere in a path turns one argument into several, and ESPICA receives a truncated executable path or wrong file arguments. Builder.runESPICA (Builder.java:661-668) is the only caller, for the OBJ/model conversion in the Extras raw browser. Its failure is a log line plus a window that stops at 'ESPICA running' (census M.92 covers the undrained stderr and the non-daemon thread; neither mentions quoting). The class's standalone main still hard-codes 'D:\\Work\\SPICA-master\\ESPICA\\bin\\Debug\\...' paths from the original author's machine (:131-134; F027 counts the mains).

**Fix.** Build the command as a list and start it with ProcessBuilder(List&lt;String>) so each path is one argument; add the File paths as getAbsolutePath() elements, not concatenated text.

<a id="f246"></a>

## F246 - 'New quad from fill tool' in the Collision Editor builds its two triangles at height 0 whatever the terrain under the selection; the panel's nine field listeners are copy-pasted

**low** | correctness | `src/ctrmap/humaninterface/CollEditPanel.java:536`

CollEditPanel's btnFillCoords handler maps the fill tool's tile rectangle to region-local X/Z (:529-535) and adds two triangles whose Y array is 'new float[3]', all zeros (:536-537). On any map whose ground is not at Y = 0, the new collision floor sits above or below the visible ground, and the player walks on an invisible plane or falls through the terrain. Nothing reads the map model's height or the existing collision under the rectangle. The panel registers its coordinate fields through nine separate anonymous DocumentListeners (git grep counts 9 in the file) that differ only in the field and vertex they write. Census M.74 covers this button's modified-flag and null-collision handling; M.10 covers the 0-on-parse-failure writes; neither mentions the height.

**Fix.** Take each corner's Y from the region's existing collision - GRCollisionFile.getHeightAtPoint (GRCollisionFile.java:95) already answers it - and refuse with a message where there is none; fold the nine listeners into one parameterised listener.

<a id="f248"></a>

## F248 - TilePainterForm is the map-building engine, not a form: 30 static methods and no instance behaviour of its own, in the UI package - and it re-implements helpers beside it (escapeTypedText verbatim, the area-pack loader 3 times, the map-matrix layout by hand twice, a third donor loader)

**low** | architecture | `src/ctrmap/humaninterface/TilePainterForm.java:46`

READ IN SOURCE AND BYTECODE. TilePainterForm (humaninterface, 1,280 lines) is not a form. decl.tsv gives it 30 static methods and no instance methods of its own; its 9 instance methods belong to inner classes (StagedArea, StagedRegion). It is the map-building engine, applyToZone, stampPlaced, StagedArea and its commit, enableWaterScroll, wireSigns, wireDoorWarps and ensureDoorPropRegistered, placed in the UI package under a Form name (the panel the user sees is PaintForm). Being a static grab-bag beside the UI, it re-implements helpers that already exist next to it. (1) escapeTypedText: the same three-line body as NPCEditForm.escapeTypedText (TilePainterForm.java:1118-1120 = NPCEditForm.java:825-827, diffed identical). (2) 'An area's prop texture pack, from the workspace file when there is one, else the archive' is written three times: TilePainterForm.propPackOf (:903-907) and twice in PropEditForm (:618-623 and :1083-1088; census M.59 compares the PropEditForm pair). (3) The map matrix's binary layout is parsed by hand ('int sub0 = i32(mm, 4); w = u16(mm, sub0 + 4) ... u16(mm, sub0 + 8 + (ownY \* w + ownX) \* 2)') in firstRegionCell (:836-853) and zoneRegionModels (:89-95), instead of through MapMatrix, the third and fourth of census M.61's 'three answers to which region does this zone own'. (4) paletteSignDonor (:1123-) is a third copy of NPCEditForm's donor loader (census M.56 counts two). The layering cost is visible in F214's graph: the painter's engine can only be reached, or tested, through the UI package.

**Fix.** Move the engine (applyToZone, stampPlaced, StagedArea, the wiring and water steps) into a formats-level ctrmap.paint package with PaintForm as its only UI client; delete the copies: one escapeTypedText in the text layer, one AreaPacks.propPack(area) beside PropDatabase, MapMatrix for every matrix read, one donor loader.

<a id="f249"></a>

## F249 - ZoneEntities keeps a hand-maintained count beside each record list, and a half-parsed entity block leaves them disagreeing: one 3D site guards min(count, size) 'because a count past the end is a corrupt zone', six other loops trust the count and throw - the zone will not open and the 2D map throws on every repaint

**low** | correctness | `src/ctrmap/formats/zone/ZoneEntities.java:46`

READ IN SOURCE. ZoneEntities keeps each record kind twice: a count field (furnitureCount, NPCCount, warpCount, trigger1Count, trigger2Count; ZoneEntities.java:45-49) beside its list. The count is read from the header (:72-76), adjusted by hand in the forms ('e.NPCCount++' NPCEditForm.java:461, 'e.warpCount++/--' WarpEditForm.java:205, :216, 'e.NPCCount = e.npcs.size()' :1415), and recomputed from the lists only when the block is assembled (:105-109) or by one helper (:181). The two disagree whenever the parse stops early. The constructor's IOException is caught and logged (:95-97, F225), so a truncated or damaged entity block yields a count from the header and a shorter list. The tree knows this: NPCEditForm.modelledNPCs bounds its loop by 'Math.min(e.NPCCount, e.npcs.size())' with the comment 'a count past the end of the list is a corrupt zone, not a reason to throw mid-frame' (:1611-1612). That guard reached one site. Six other loops bound by the count and index the list: NPCEditForm.loadFromEntities (:185, so ZoneEditors.show throws and the zone does not open), WarpEditForm.loadFromEntities (:49), NPCTool.drawOverlay (:40), and three in WarpTool (:63, :83, :104). The last four run inside paint, so the 2D map throws on every repaint, into F219's no-sink. 'A guard at call sites is one call site from broken' (CLAUDE.md) is the shape.

**Fix.** Delete the five count fields: they are list.size(), computed where the block is written. Until then make the parse throw on a short block (F225) so a corrupt zone is refused at open with its index named; and route every loop through the lists. Guard: a suite that opens a ZoneEntities from a block truncated mid-NPC and asserts it throws (not half-parses).

<a id="f250"></a>

## F250 - Nine checks assert less than they say: 'edit it, pack it, read it back' never packs, a clone placement is built and never checked, a sweep passes with zero examined, a ratio is printed not bounded, two check(true) passes, an unreadable neighbour compares equal to itself - and one suite pins correct code as a defect

**low** | test-debt | `src/ctrmap/tests/GarcSniffTest.java:487`

READ IN SOURCE (tests). Four suites check less than their names, messages or javadoc say. CLAUDE.md: 'A test that merely touches a line does not guard it.' (1) GarcSniffTest.aReclassifiedEntrySurvivesAnEdit (:487-517) reports 'a/0/8/8 #N: edit it, pack it, read it back - byte for byte the same', but the body runs LZ11.compress then LZ11.decompress on the entry's bytes (:500-506). packDirectory and entry.compressed, the two things its own comment says must hold (:490-494), are never called, so a regression in how the pack honours the compressed flag passes. (2) GeoBoxOpsTest's duplicate check: '// the clones must sit exactly +100 X from originals' builds 'shiftedBox' (:83-84) and never uses it. Only the face count is asserted (:78-81), so a duplicate placed anywhere, or on top of the original, passes. (3) GroundResolveTest (:68-100) skips any brush without a native match or a measurable surface, counts 'checked', and its only verdict is 'bad == 0'. A regression that stops resolving any ground mesh gives checked = 0, bad = 0 and ALL PASS, the F142 shape with no floor on what was examined. (4) LZ11Test's javadoc gives the ratio as the confirmation of the encoder fix ('the old length-15-capped port bloated everything'), and the body PRINTS the ratio (:75-79) and asserts no bound, so a return to the bloating encoder passes. Nits in the same vein: GameFilesSeamTest.java:201 '.replace("\\r", "")' removes the two characters backslash-r, not carriage returns, so the CR strip it intends never happens (harmless for the regex that follows); LoadedZoneTest.java:423-425 'catch (Throwable) continue' skips a class it cannot load without counting it (0 skipped on the battery classpath today, so harmless). Related findings: F142 (BchMapModelTest answers OK on nothing), F145 (a tautological check), F146 (a guard listed and not tested), F144 (skip-then-ALL-PASS). ADDENDUM - FIVE MORE IN THE SAME CLASS, READ IN THE SUITES. (5) WorkflowGuardsTest counts two passes that cannot fail: 'check(true, "a map was grown, so there is something to ship")' (:378) and 'check(true, "every resource is made private, so there is nothing to refuse")' (:717). (6) WorkflowGuardsTest's 'zone next door is untouched' compares the neighbour's encounters before and after, but encountersBytes answers 'new byte[0]' on any exception (:1026-1028). An unreadable neighbour therefore compares equal to itself, and nothing asserts the neighbour held data to begin with ('a query that cannot read its subject must not report it absent'). (7) MainframeEdgesTest.everyZoneHandedToTheTabIsGivenASlot skips every source line that contains '//' (:452), so a real 'mZonePnl.loadZone(x); // why' call is exempt, and it sees only the literal receiver name mZonePnl. (8) TriggerEditFormGuardsTest hard-sets 'windowed = true' after a WAS-comment saying nothing needs a display (:107-111), so its three 'if (!windowed) skip' branches are dead. Its class javadoc still says it 'says which checks it had to skip' (:57-61), and with no dump it prints ALL PASS and exits 0 (:96-99, F144). (9) UtilsTest labels Picking.getDistanceFromVector a 'SUSPECTED DEFECT, PINNED NOT FIXED' because it adds rather than subtracts (:173). Its callers pass the scene translation, the negative of the camera position (H3DRenderingPanel:135), so the sum IS the camera-to-object vector. The behaviour is correct and the name is wrong, and the suite pins a correct line as a defect.

**Fix.** Make each check do what its message says: GarcSniffTest stages the entry, runs packDirectory into a scratch copy and re-reads it; GeoBoxOpsTest asserts every cloned vertex lies in shiftedBox; GroundResolveTest requires checked >= a floor measured on the corpus; LZ11Test asserts the ratio against retail within a bound. Guard each by breaking the line it protects (CLAUDE.md) and recording the failing output.

<a id="f251"></a>

## F251 - The building palette's 3,535 harvested entries are all named '&lt;location> &lt;kind> [n]' (100%, by construction), and 15 'lamp'/'sign'/'decor' entries are whole rooms or blocks (7 'Sootopolis City lamp' rows are 11x9) - so text search finds buckets, and searching 'lamp' returns rooms

**low** | ui | `src/ctrmap/resources/oras_buildings_auto.tsv:1`

MEASURED (scratchpad/m_autocat.py over the shipped src/ctrmap/resources/oras_buildings_auto.tsv, read with BuildingCatalog's column map, BuildingCatalog.java:110-119. CONTROLS: 'Littleroot Town structure 2' is classed generic; a 'Sootopolis City lamp' row is classed a misfire; a sample of every kind shows the same name shape, while the curated oras_buildings.tsv (48 rows) carries real names such as 'Pokemon Center', 'Poke Mart', 'Gym'). RESULT: 3,535 rows. A_STRUCT 2,130, A_ROCK 968, A_DECOR 171, A_BUILDING 98, A_FENCE 47, A_LAMP 35, A_BRIDGE 32, A_SIGN 21, A_STAIRS 17, A_TREE 16. Every name is '&lt;location> &lt;kind noun>' plus a counter: the harvester's generated label records where a cut came from and what bucket it fell into, not what it is. The kind itself misfires on 15 small-object rows whose box is at least 40 tiles: Sootopolis City lamp 1-7 (11x9 each), Lilycove City sign 5 (9x11) and sign 3 (10x8), Rustboro City lamp (5x13), Route 110 sign 2 (8x8), Mauville City sign (15x4), and 3 more. BuildingPaletteDialog picks the icon and category from e.kind (:90, :104) and filters search on name OR kind text (:215). A user typing 'lamp' therefore gets interior-sized cuts, and a user looking for a particular house has 2,130 'structure N' rows to arrow through, each arrow-press starting the uncancelled preview thread of F206.

**Fix.** Name auto entries by what the cut contains (for example the donor model's dominant material or prop names) and classify kind by footprint as well as by mesh (a 'lamp' over N tiles is a STRUCT); let search match the donor zone and footprint size; group the 3,535 under collapsible location headings in the palette.

<a id="f252"></a>

## F252 - The 'one generic record editor; tomorrow's discovery becomes a TSV edit' plan stopped at two schemas written in Java with one consumer (the item editor): trainers, Maison sets and classes, shops and encounters are still five bespoke dialogs with their own parsers

**low** | unfinished | `src/ctrmap/formats/recordschema/SchemaRegistry.java:53`

docs/items-plan.md:89-108 sets the goal: every fixed-stride record array (items, personal, gift encounters, BP prices, shop stock) described as 'a data row instead' - container, locator with stock bytes, stride and count, typed fields - and rendered by 'One generic record editor ... Tomorrow's discovery becomes a TSV edit.' What exists is ctrmap.formats.recordschema with SchemaRegistry.items() and itemIcons() hand-written in Java (:53, :159), no schema resource file (git ls-files finds none), and one consumer, ItemEditDialog.java:112. TrainerEditDialog, MaisonEditDialog, MaisonClassListDialog, ShopEditDialog and EncounterEditDialog each still carry their own record parsing, layout constants and dialog (their duplicated per-game counts are F205; their split persistence F082). Both schema classes also import ctrmap.Workspace without naming it (F203), a false edge from the format layer to the global.

**Fix.** Either carry the plan through (schemas as data rows in a resource TSV, the five dialogs ported onto the generic editor one at a time, each port deleting its bespoke parser) or mark the plan as stopped in docs/items-plan.md, so the record-schema package is not read as the direction the editors are going.

<a id="f253"></a>

## F253 - Declined fork offers are remembered in machine-wide Preferences under the hashCode of the workspace path, not in the workspace: pointing the same workspace at another game keeps the first game's declines, and the offer then silently never appears for those zone indices

**low** | data-integrity | `src/ctrmap/humaninterface/ZoneLoadingPanel.java:1515`

READ IN SOURCE. ZoneLoadingPanel.forkDeclined (:1505-1525) stores the set of zone indices whose fork offer the user declined under Preferences.userRoot() node 'ctrmap.ZoneLoadingPanel', key 'FORK_DECLINED_' + Workspace.WORKSPACE_PATH.hashCode() (:1515, :1560). It reloads only when WORKSPACE_PATH changes (:1509). It is cleared per index when a slot gets a new zone (clone :1741 and CtrmapMainframe.java:3087, interior placement TilePainterForm.java:1070) and from 536 up on Remove added zones (CtrmapMainframe.java:2529). It is NOT cleared when Options > Workspace settings points the same workspace folder at a different game folder. The path, and so the key, is unchanged, so game A's declined zone indices suppress the offer on game B's unrelated zones at those indices (:1603), and edits there then change every zone sharing the map without the question being asked. The hashCode key also means a moved or re-cased workspace path loses its declines, two paths with one 32-bit hash share them, and nothing ever removes the keys of deleted workspaces. Per-workspace state lives outside the workspace, keyed by something that is not the workspace's identity.

**Fix.** Store the declined set in the workspace folder (beside the persist list, keyed by the game folder it was declined against), clear it with the rest of the workspace's game-derived state on repoint and clean, and drop the Preferences key.

<a id="f254"></a>

## F254 - Measured: 20 of 70 production file handles are closed only on the success path (12 files, including the container storeFile every edit goes through), 1 is never closed - census H.52 estimated 'two dozen'; nothing ratchets the class

**low** | resource-leak | `src/ctrmap/formats/containers/AbstractGamefreakContainer.java:317`

MEASURED (scratchpad/m_streams.py over the 265 production sources, comments blanked. Every new FileInputStream/FileOutputStream/RandomAccessFile/FileReader/FileWriter/ZipFile/Zip\*Stream and Files.new\*Stream/Reader/Writer, classified by its enclosing method. CONTROLS: BuildingPaletteDialog's 'try (java.io.FileOutputStream fo = ...)' is TWR; ZoneLoadingPanel.java:1882's code.ips 'fos = new FileOutputStream(...); fos.write(ips); fos.close();' is SUCCESS-ONLY; TextEditor.java:125 read back by hand and confirmed). RESULT: 70 handle sites. 24 in try-with-resources, 19 closed in a finally, 20 SUCCESS-ONLY (closed as a later statement, so any exception in between leaks the handle), 2 never closed in the method, 5 constructed inline and handed on. The 20 sit in 12 files: AbstractGamefreakContainer 4 (create :112, verify :145 = F049, getIOFile :251, storeFile :317, the editor's hottest write, F048), NPCEditForm 3 (:707, :778, :809, the available()-sized reads of census M.127), TextEditor 2 (:125, :195), TileDBWriter 2, and WorkspaceSession.savePersist :363 (F034), ContainerIdentifier :50, GARC.parse :80 (census M.89), NPCRegistry :53 (census M.90), TextFile :28, ZoneLoadingPanel :461 (the master-table RandomAccessFile, census M.97) and :1882 (lead 166), Builder :69, ResourceAccess.copyToTemp :34. On Windows a leaked handle keeps the file locked until the collector runs, which is exactly the 'emulator or virus scanner holding the archive open' failure the container code names, here caused by the editor itself. NOT-CLOSED: WavefrontOBJ :18 (census L.47) and Updater.holdRunLock :390, which holds a lock for the process lifetime on purpose. Census H.52 estimated 'roughly two dozen production streams ... closed only as the last statement of their try'. Measured, it is 20 plus WavefrontOBJ, and the class has no ratchet.

**Fix.** Convert the 20 to try-with-resources mechanically (each is a local opened and closed in one method), and add a source ratchet at 0: a handle constructed outside a try-with-resources header must be closed in a finally of the same method, planted with one. The intentional Updater lock is the one allowlisted exception.

