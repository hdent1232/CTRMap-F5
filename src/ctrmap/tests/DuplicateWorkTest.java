package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A capability has ONE implementation, and a new class has to say what it is not.
 *
 * <p>WHY THIS EXISTS, and it is not a general theory. On 2026-09-12 the owner asked
 * why a zone preview had been built as a second, smaller copy of the editor's map
 * loader instead of using the one that has drawn zones correctly for years. Every
 * defect they reported about that preview - one region instead of the map, a region
 * belonging to the zone next door, four cells of a thirteen-cell map - was a hole in
 * the copy, not in the editor. The copy is deleted now.
 *
 * <p>The instruction "remove duplication" has been given to this project more than
 * once, and a sweep was run against it. That sweep counted public statics, coupling
 * to the window globals, god objects, dialogs and mutation coverage - every one of
 * them a number that could be ratcheted - and never counted implementations, which
 * is where duplication actually shows up. So the sweep could not have removed this
 * and the report did not say so. A rule with no counter behind it applies to
 * nothing; that has now been true here three times, for three different rules.
 *
 * <p>WHAT IT REFUSES:
 * <ul>
 * <li>A second file doing something a named class already owns. The table below is
 *     seeded with the capabilities that have actually been duplicated, each with the
 *     marker that gives it away and the files allowed to carry it. New entries are
 *     welcome; new UNARGUED users are not.</li>
 * <li>A production class that is not in {@code tools/guard/classes.txt} and does not
 *     say, in its own javadoc, which existing class does the nearest job and why it
 *     could not be used. That line is the search this project keeps skipping, made
 *     into something a build can check: you cannot write it without having looked.</li>
 * </ul>
 *
 * <p>IT RUNS IN THE BUILD, not only in the battery. A guard that runs forty minutes
 * later lets the wrong thing be written, run and shipped first; the point of action
 * for authoring is the build, and this is the whole reason it sits there.
 *
 * <p>ORDER: needs no game, no dump and no display; reads source and writes nothing.
 *
 * Usage: java ctrmap.tests.DuplicateWorkTest [src-root]   (default "src")
 */
public class DuplicateWorkTest {

	/**
	 * {capability, marker regex, comma-separated owners, why it has one owner}.
	 *
	 * <p>Seeded from what has actually gone wrong. A marker is chosen to be the
	 * thing only that capability does - not a word that could appear anywhere.
	 */
	private static final String[][] OWNED = {
		{"placing a map region in the world", "720f \\+ 360f|720 \\+ 360f|720 \\+ 360[^0-9]",
			"TileMapPanel.java",
			"a region sits at cell * 720 + 360, and the height lookups and picking divide by"
			+ " the same number. A second answer to where region (3,4) goes is a map whose"
			+ " pieces do not line up, which is what the owner was shown."},
		{"blanking a region's subfiles", "RegionFactory\\.blank\\s*\\(|\\b(?:voidTilemap|emptyCollision)\\s*\\(",
			"RegionFactory.java",
			"RegionFactory.blankRegionFiles is the one blanking - its ground chosen by the blank canvas's rule, every"
			+ " write answered for. MapResizer carried a private copy of the subfile numbering that floored new"
			+ " cells with the BIGGEST mesh - a wall, a fence or the sea on 312 of the first 400 retail regions"
			+ " (F017, F073). Anything blanking a region calls it."},
		{"assembling a matrix into a drawable map", "readRegions\\s*\\(",
			"TileMapPanel.java,ZonePreviewPane.java",
			"TileMapPanel.readRegions is the body (its show half, showRegions, installs what it"
			+ " read - F220); the preview and the window's own load CALL it."
			+ " Anything else naming it is fine - anything else DOING it is the defect this"
			+ " suite exists for."},
		{"walking a Pawn routine and its callees",
			"static void dfsClosure\\s*\\(|static void validateStubInsertPreconditions\\s*\\(",
			"PawnClosure.java",
			"one closure walk, one set of stub-insert preconditions. This existed twice - the "
			+ "message and sign transplants were measured at 293 of 326 substantive lines "
			+ "shared, byte-identical in the walk itself - and it rewrites bytecode in zone "
			+ "scripts bound to the save, so a fix landing on one copy and not the other is "
			+ "a zone that hangs on entry for whoever used the other one"},
		{"reading a game's zone files for a transplant donor",
			"new\\s+(?:[\\w.]+\\.)?PawnClosure\\.ScriptSource\\s*\\(|extractZoneScript\\s*\\(\\s*(?:java\\.nio\\.file\\.)?Files\\.readAllBytes",
			"PawnClosure.java",
			"PawnClosure.zoneScripts is the donor search's one source: the handed game's own zone files, staged or"
			+ " extracted. The message routine's loader, the sign routine's and the map painter's were three"
			+ " copies of it over the application's open game (F248). A new transplant asks donorFrom."},
		{"transplanting a Pawn routine into a zone script",
			"\\.instructions\\.addAll\\(\\s*1\\s*,|\\bcrcCell\\s*\\(",
			"PawnClosure.java",
			"PawnClosure.inject is the transplant - natives merge, the stub insert after the HALT_P at 0 with"
			+ " its publics and entry-point fixups, the block append and the self-check - and its fingerprint the"
			+ " donor's pin. The walk was shared and the rest was not: 259 of the sign injector's 278 substantive"
			+ " lines were also in the message one, and the same refusal re-wrapping was pasted nine times (F087)."
			+ " A new transplant is a PawnClosure.Spec."},
		{"building a GameFreak name tree", "<<\\s*3\\)\\s*-\\s*1|\\*\\s*8L?\\s*-\\s*1\\b",
			"Patricia.java",
			"the discriminating-bit start of a Patricia insertion. It existed three times - the"
			+ " texture pack, the model appender and the area animation each built their own"
			+ " tree, each claimed byte-exact on its own - and a fix to one would not have"
			+ " reached the others (F066)."},
		{"recognising a BCH by its magic", "\\[1\\]\\s*[!=]=\\s*'C'\\s*(&&|\\|\\|)\\s*\\w+\\[2\\]\\s*[!=]=\\s*'H'",
			"BchSections.java",
			"BchSections.hasMagic is the sniff and isBch the whole-header check; seven hand-written"
			+ " copies disagreed on three bytes or four and on how long a file must be (F066)."},
		{"reading a BCH header or its relocation table", "relocLen\\s*/\\s*4|i32\\(\\s*\\w+\\s*,\\s*0x1C\\s*\\)",
			"BchSections.java",
			"BchSections reads the header for every layout and decodes the relocation words; five"
			+ " readers did it themselves and one placed command-section entries at contents"
			+ " addresses (F066)."},
		{"reading a NUL-terminated string", "\\(char\\)\\s*\\(\\s*[\\w.]+\\[\\w+\\]\\s*&\\s*0xFF\\)",
			"StringUtils.java,DressUpIndex.java",
			"StringUtils.cString is the one loop; there were seven, with two contracts, and the"
			+ " stream reader beside them looped forever on a string nothing ended (F066)."
			+ " DressUpIndex builds a two-byte magic this way, not a string. The array may be a"
			+ " field (m.raw[q]): a marker that read only a bare name missed two more copies (F093)."},
		{"reading a Wavefront OBJ", "case\\s+\"v\"\\s*:|startsWith\\(\\s*\"v \"\\s*\\)",
			"MapModelObj.java",
			"MapModelObj.parse is the one OBJ reader, strict, and MapModelObj.collision makes collision of"
			+ " what it reads (F070). Map > OBJ to collisions had a reader of its own, and the two disagreed:"
			+ " one refused bad input by its line, the other threw at the same input."},
		{"writing a GARC's tables","reverseBytes\\(\\s*0xC\\s*\\+\\s*\\w+\\s*\\*\\s*(4|16)\\s*\\)",
			"GarcWriter.java",
			"GarcWriter writes every GARC's header, FATO, FATB and FIMB - the repack's and the exact"
			+ " rebuild's (F056). There were two writers agreeing on every convention by hand, and both wrote"
			+ " the FATB length twelve short of its section, so the rebuild could not trust its own output."},
		{"writing a wild-encounter pack","\\[\\s*1\\s*\\]\\s*=\\s*'N'",
			"EnPack.java",
			"EnPack is the one EN pack writer and its one strict reader (F053). The encounter editor, the"
			+ " zone append and the zone removal each re-emitted the header and offset table with their own"
			+ " arithmetic, and only the zone paths checked what they read - the editor took any bytes for a"
			+ " pack, and a bad pair of offsets was a NegativeArraySizeException mid-edit."},
		{"reading a collision subfile","==\\s*'c'\\s*&&\\s*\\w+\\[1\\]\\s*==\\s*'o'|\"coll\"",
			"GfColl.java,Builder.java",
			"GfColl is the one collision parser (F058, F061). The collision editor had a reader of its own:"
			+ " a bad magic was a console line, a short read part-filled the buckets and was saved over the"
			+ " real collision, and its de-duplication compared every triangle with every one kept (F060)."
			+ " Builder names a file's type in its browser by the magic and reads nothing of it."},
		{"reading a material's texture slots", "0x1C\\s*,\\s*0x20\\s*,\\s*0x24",
			"BchMapModel.java",
			"BchMapModel.getMaterialTextureNames reads the three slots, strictly. The terrain importer"
			+ " and the prefab cutter each wrote the loop out, and each answered a string that ran off"
			+ " the end cut short - a material carried with fewer textures than it draws with comes out"
			+ " white (F093)."},
		{"rounding a painted outline", "\\*\\s*0\\.5f\\s*\\+\\s*[\\w.]+\\[\\w+\\]\\s*\\*\\s*0\\.25f\\s*\\+\\s*[\\w.]+\\[\\w+\\]\\s*\\*\\s*0\\.25f",
			"PaintedGeometry.java",
			"PaintedGeometry.roundOutline is the one 0.25 / 0.5 / 0.25 smoothing, its travel cap a parameter."
			+ " The shoreline carried its own copy beside the cliff's, capped at 0.34 of a tile against 0.3,"
			+ " and a change to one never reached the other (F090). A different cap is an argument, not a copy."},
		{"flipping a quad's winding", "\\.pos\\[1\\]\\s*=\\s*[\\w.]+\\.pos\\[2\\]",
			"PaintedGeometry.java",
			"PaintedGeometry.swapCorners12 is the one flip, and it carries a corner's position, UV, normal"
			+ " and shading together. There were three; two moved position and UV and left the shading"
			+ " where the corner had been, so a cliff gusset's back face was lit diagonally (F090)."},
		{"setting a form field's number class", "\\.setValueClass\\s*\\(",
			"Forms.java",
			"Forms.numberClass is the one loop. Nine forms each wrote their own and chose by hand which"
			+ " field got which class, which is how the prop registry's ea14 went without one (F095, F113)."},
		{"listing a zone's script ids in a dropdown", "buildScriptIdItems\\s*\\(",
			"ScriptIdDropdown.java,TalkerScriptWizard.java",
			"ScriptIdDropdown fills, shows and reads the dropdown, with the one flag that keeps its own"
			+ " setting of the box from reaching the number field; the NPC and trigger forms each kept a"
			+ " copy of all three and a flag of their own (F113). TalkerScriptWizard builds the items."},
		{"reading the integer a string starts with",
			"static\\s+int\\s+parseLeadingInt\\s*\\(|Integer\\.parseInt\\(\\s*\\w+\\s*==\\s*-1\\s*\\?",
			"Text.java",
			"Text.parseLeadingInt is the one reader; the NPC and trigger forms each kept a stricter copy"
			+ " beside the picker's, answering a different sentinel for the same string (F113)."},
		{"reading a GameFreak mini-pack's offset table",
			"\\b(?:i32|u32|le32|getInt|readInt)\\s*\\(\\s*[\\w.\\[\\]]+\\s*,\\s*(?:(?:0x)?4|HEADER)\\s*\\+\\s*"
			+ "(?:\\(?[\\w\\s+-]+\\)?\\s*\\*\\s*4|4\\s*\\*\\s*\\(?[\\w\\s+-]+\\)?)\\s*\\)",
			"ContainerBytes.java",
			"ContainerBytes.subfile reads one entry and ContainerBytes.offsets the whole table. It said the"
			+ " thirty-four hand slicers answered through it, and thirteen in the program still read the table"
			+ " themselves - PropDatabase kept a whole second subfile() - while twenty-two in the suites did,"
			+ " six of them the same zone-script slice, disagreeing on an empty subfile (F215)."},
		{"assembling or storing a little-endian integer by hand",
			"(?:\\(\\s*(\\w+)\\s*\\[\\s*([^\\]\\[]+?)\\s*\\]\\s*&\\s*0x[fF]{2}\\s*\\)\\s*\\|\\s*\\(*\\s*\\1\\s*\\[\\s*(?:\\2\\s*\\+\\s*1|(?:0x)?[0-9A-Fa-f]+)\\s*\\]\\s*(?:&\\s*0x[fF]{2}\\s*\\)\\s*)?<<\\s*8\\b)|(?:\\(byte\\)\\s*\\(?\\s*([\\w.]+(?:\\[[^\\]\\[]+?\\])?)\\s*(?:&\\s*0x[fF]{2}\\s*)?\\)?\\s*[;,]\\s*(?:[\\w.]+\\s*\\[[^\\]\\[]+?\\]\\s*=\\s*)?\\(byte\\)\\s*\\(\\(?\\s*\\3\\s*>>>?\\s*8\\b)|(?:\\bwrite\\s*\\(\\s*\\(?\\s*([\\w.]+)\\s*(?:&\\s*0x[fF]{2}\\s*)?\\)?\\s*\\)\\s*;\\s*[\\w.]+\\s*\\.\\s*write\\s*\\(\\s*\\(\\(?\\s*\\4\\s*>>>?\\s*8\\b)|(?:ByteOrder\\s*\\.\\s*LITTLE_ENDIAN)",
			"LittleEndian.java",
			"LittleEndian reads and stores every width the formats use (u16, u24, i32, u32, f32 and their put"
			+ " halves). Little-endian integers were read six ways and stored by hand in fourteen files, and the"
			+ " one ByteBuffer copy that wrote was wrong: BCHFile relocated through an absolute putInt(0, ...), so"
			+ " every relocation also overwrote the first four bytes of the file (F228). The marker is low byte"
			+ " first; a big-endian read (an IPS record, a magic check) is high byte first and is not this job."},
		{"escaping typed text for a message file", "replace\\(\\s*\"\\\\n\"\\s*,\\s*\"\\\\\\\\n\"\\s*\\)",
			"GFMessageFile.java",
			"GFMessageFile.escapeTyped turns typed line breaks into the message file's \\n escape; the NPC"
			+ " form and the painter each kept a copy (F113), and it lives in the text layer so the map"
			+ " painter's engine reaches it without the UI (F248)."},
		{"checking a picked model is on screen", "gluProject\\s*\\(",
			"Picking.java",
			"Picking.isPicked does the box test and the on-screen test, and places box and vertices alike"
			+ " through Picking.place. The NPC form and the prop form each pasted the on-screen loop in"
			+ " and placed the vertices without the model's rotation - the prop copy without its scale"
			+ " too (F113)."},
		{"scaling the tile map between world units and pixels", "400d?\\s*/\\s*720d?|720f?\\s*/\\s*400f?",
			"TileMapPanel.java",
			"TileMapPanel.toScreen and toWorld are the view's one scale. The prop, trigger and warp tools wrote"
			+ " it out seventeen times, so a change to the view had to find every one (F117)."},
		{"finding where the tile map image starts",
			"getWidth\\(\\)\\s*-\\s*[\\w.()]*tilemapScaledImage\\.getWidth\\(\\)",
			"TileMapPanel.java",
			"TileMapPanel.imageOrigin is the one centring division; the three tools and the panel itself"
			+ " repeated it seven times and the input router once more (F117)."},
		{"placing what stands on a tile at the tile's centre",
			"\\*\\s*18f?\\s*\\+\\s*9f?\\b|-\\s*9f?\\)\\s*/\\s*18f?",
			"WorldGrid.java",
			"WorldGrid.tileCentre and tileOfCentre are the zone grid's one rule, 18 * tile + 9 and back;"
			+ " fifteen copies across the entity records, the NPC and warp forms, the painter and the warp"
			+ " tool (F117)."},
		{"scaling the matrix grid between world units and pixels", "100[df]?\\s*/\\s*720[df]?",
			"MatrixCanvas.java",
			"MatrixCanvas.toPx is the matrix grid's one scale. The camera bounds were drawn with a float"
			+ " 100f / 720f and hit-tested with a double 100d / 720d, and the two put an edge on different"
			+ " pixels: measured 116 edges in 2,000,000 (F117)."},
		{"sizing a region's tilemap file", "\\b6528\\b",
			"Tilemap.java",
			"Tilemap.REGION_BYTES is the one size, a 4-byte header and 40 x 40 four-byte tiles padded to"
			+ " 0x80; four builders wrote 6528 out (F117)."},
		{"rounding up to a boundary", "\\(\\s*\\w+\\s*\\+\\s*\\w+\\s*-\\s*1\\s*\\)\\s*/\\s*\\w+\\s*\\*\\s*\\w+",
			"Bytes.java",
			"Bytes.align is the one copy; the texture pack, the model appender and the area"
			+ " animation each had their own (F066)."},
		{"finding the mesh that draws a named material",
			"for\\s*\\(\\s*int\\s+(\\w+)\\s*=\\s*0\\s*;\\s*\\1\\s*<\\s*(?:\\w+\\.)?(?:meshCount|meshes\\.size\\(\\))"
			+ "\\s*;\\s*\\1\\+\\+\\s*\\)\\s*\\{\\s*if\\s*\\(\\s*[\\w.]+\\.equals\\(\\s*(?:\\w+\\.)?getMaterialName\\("
			+ "\\s*(?:\\w+\\.)?getMeshMaterialIndex\\(\\s*\\1\\s*\\)",
			"BchMapModel.java",
			"BchMapModel.meshWithMaterial is the one first-match lookup by exact name. Seven loops"
			+ " wrote it out across the importers, the prefab stamper and the painter (F092); a loop"
			+ " that also filters on layout, or matches loosely, is a different job and does not"
			+ " open this way."},
		{"starting a background thread", "new\\s+Thread\\s*\\(|Executors\\s*\\.\\s*new\\w+\\s*\\(",
			"NewestOnly.java,UpdateUI.java,ESPICAControl.java",
			"a preview asks NewestOnly: one daemon worker, newest request wins (F206). Both previews"
			+ " started a raw thread per list selection and ran every superseded one to the end. The"
			+ " other two are argued: the updater's download and ESPICA's process reader are one"
			+ " long-lived daemon each. The 3D walker was a third until F016 - it wrote the camera the"
			+ " event thread writes, so it is a Swing Timer now. SwingWorker is not this and is not"
			+ " counted."},
		{"reading text with a Scanner", "new\\s+(java\\.util\\.)?Scanner\\s*\\(",
			"ScriptEditor.java,PawnAssembly.java",
			"a Scanner keeps a read failure to itself and simply runs out of lines (F211): the terrain and"
			+ " ground-material tables, the building catalog and the snapshot stamp each read a failed read as"
			+ " a shorter file. These two scan a String, which cannot fail; a file or a stream is read with a"
			+ " BufferedReader, whose readLine throws."},
		{"finding the game as it shipped", "originalSnapshotDir\\s*\\(",
			"WorkspaceSession.java,Workspace.java",
			"GameFiles.pristineArchive is the one pristine reader: the snapshot, else the sealed backup's"
			+ " verified copy, else nothing - never the live folder (F232). Three readers built"
			+ " \"snapshot + path\" by hand and two of them, with no snapshot, read the user's edited"
			+ " game instead. The session owns the snapshot folder; the static facade only names it."},
		{"escaping text for an HTML label", "replace(All)?\\(\\s*\"&\"\\s*,\\s*\"&amp;\"\\s*\\)",
			"Ui.java",
			"Ui.html is the one escape. The update dialog and the setup wizard each kept a private"
			+ " copy, and the painter's preview note (F211) would have been the third; a copy that"
			+ " forgets one character draws a reason with a '<' in it as a broken tag."},
		{"repointing one zone's row in the master zone-header table",
			"\\.set\\(\\s*(?:ZoneResource\\.\\w+|res)\\s*,\\s*zoneIndex\\s*,",
			"GeometryForker.java",
			"GeometryForker.repointMasterRow is the one copy, for any resource a header names. AreaForker"
			+ " carried its own repointMasterArea, the same read, range check, set and write with its own"
			+ " offset constant, while repointMasterRow's javadoc said there was one (F012)."},
		{"decoding and encoding GameText", "0x2983",
			"GFMessageFile.java",
			"GFMessageFile is the one GameText codec, read and write. Location names were read by a second,"
			+ " legacy decoder that named the text variables differently from the text editor and appended"
			+ " the digits 9792 where a female sign belonged (F089)."},
		{"saying why something failed", "\\.getMessage\\s*\\(\\s*\\)",
			"Bytes.java",
			"Bytes.reason is the one place that asks an exception for its message: the message, or the"
			+ " exception's type when it gave none, and whatever rode on it. Ninety-one sites asked"
			+ " getMessage themselves (F229) and showed 'null' for a NullPointerException on Java 8;"
			+ " call Bytes.reason (or Ui.reason, which is it)."},
		{"opening a resource the build ships", "getResourceAsStream\\s*\\(",
			"ResourceAccess.java",
			"ResourceAccess.getStream is the one opener, and refuses a resource this build does not have by"
			+ " name. Six loaders called getResourceAsStream themselves and five took its null for an empty"
			+ " table: no native names, no version, no gizmo arrows, each reading as 'nothing matched' (F130)."},
		{"formatting a field's number as hex", "extends\\s+(?:javax\\.swing\\.text\\.)?DefaultFormatterFactory",
			"Forms.java",
			"Forms.HexFormatterFactory is the one hex field, and it takes bounds. The tile form and the tile"
			+ " database writer each kept a verbatim copy, neither bounded, so 1FF typed into a tile byte was"
			+ " stored as FF (F125)."},
		{"naming where a zone header holds a resource's id",
			"static\\s+final\\s+int\\s+HDR_\\w+\\s*=|\\(\\s*\"[a-z ]+\",\\s*0x[0-9A-Fa-f]+,\\s*ArchiveType\\.",
			"ZoneResource.java",
			"ZoneResource holds each id's offset in the header (area 0x02, map 0x04, story text 0x06,"
			+ " script 0x18) and reads and writes it. AreaForker and WorkspaceIntegrity each declared"
			+ " their own HDR_AREA_OFF, and WorkspaceIntegrity an HDR_MATRIX_OFF nothing read (F030)."},
		{"defining the zone header's size", "static\\s+final\\s+int\\s+\\w+\\s*=\\s*0x38\\s*;",
			"ZoneHeader.java",
			"ZoneHeader.SIZE is the one definition. Six classes each wrote their own 0x38 (F205); the"
			+ " master table, both forkers, the cloner, the integrity check and the zone-limit patch now"
			+ " alias it, so a correction lands everywhere at once."},
		{"preparing map geometry for drawing", "\\.makeAllBOs\\(\\)",
			"H3DModel.java,NPCRegistry.java,ADPropRegistry.java,CustomH3DPreview.java,"
			+ "MapPreview3D.java,PropEditForm.java,TileMapPanel.java",
			"the six views and registries that had this before the rule existed. The list may"
			+ " shrink for free; it grows only when somebody argues for a seventh."},
	};

	/** Classes nothing in the application reaches, each with what it is instead. */
	private static final String[][] UNREACHED = {
		{"BuildingHarvester.java",
			"a research tool the owner runs directly: it walks every region and writes the "
			+ "building catalogue TSV. Not shipped in a menu on purpose"},
		{"TerrainDonorHarvester.java",
			"the same again, for donor terrain"},
		{"GroundMaterialAudit.java",
			"a one-shot audit the owner runs to check ground materials across the dump"},
		{"BchModelVerifier.java",
			"the acceptance oracle for BchModelAppender output: three suites run it over the "
			+ "857-model corpus and BchModelAppender names it as the rule its writes must "
			+ "satisfy - in prose, which is why nothing reaches it in code"},
		{"DressUpIndex.java",
			"MEASURED, NOT SHIPPED. The whole XY wardrobe index is decoded and proven by "
			+ "DressUpIndexTest; nothing in the editor can reach it yet. It is half a feature "
			+ "and it is recorded here rather than left looking alive"},
		{"DressUpArchive.java",
			"the other half of the same unshipped feature"},
	};

	/**
	 * How many may be unreached. It may fall and may not rise quietly. TIGHTENED 7 -> 6 on 2026-10-08,
	 * measured by this suite: TerrainHarvester went, its two catalogues read by nothing (F119).
	 */
	private static final int UNREACHED_CEILING = 6;
	/** The snapshot of classes that predate the rule. */
	private static final String LIST = "tools/guard/classes.txt";

	/** What a new class must carry: the thing it is NOT, and why. */
	private static final Pattern NOT_LINE = Pattern.compile(
			"(?m)^\\s*\\*\\s*(?:<p>)?\\s*Not:\\s*\\{?@?l?i?n?k?\\s*([A-Za-z0-9_.#]+)[^-]*-\\s*(.{20,})");

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		if (!new File(root, "ctrmap").isDirectory()) {
			System.out.println("FAIL: source root not found: " + root.getAbsolutePath());
			System.exit(1);
		}
		File repo = root.getParentFile() == null ? new File(".") : root.getParentFile();
		aCapabilityHasOneImplementation(root);
		aNewClassSaysWhatItIsNot(root, new File(repo, LIST));
		everyClassIsReachedBySomething(root);
		everySuiteClassIsRunOrUsed(root);
		everyResourceIsLoadedBySomething(root);
		everyToolsOutputIsRead(root);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * Every offline tool's output is a catalogue the program reads (F119). TerrainHarvester wrote
	 * terrain_kit.tsv and terrain_runs.tsv, which its javadoc presented as the fix for synthesised
	 * cliffs, and nothing read either: neither was ever committed, and the cliff generator went on
	 * synthesising from the grid. A producer with no consumer - the class this project has filed seven
	 * audits running. So each class under ctrmap/tools with a main names, in its own javadoc as
	 * {@code name.tsv}, the resource its output becomes; that resource is bundled; and a production
	 * class outside the tools loads it by name. A tool that cannot say all three has no reader.
	 */
	static void everyToolsOutputIsRead(File root) throws Exception {
		System.out.println("--- every offline tool's output is a catalogue the program reads (F119)");
		Pattern named = Pattern.compile("\\{@code\\s+([\\w.-]+\\.(?:tsv|json|csv|txt|bin))\\s*\\}");
		List<String> readers = new ArrayList<>();
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			String dir = f.getParentFile() == null ? "" : f.getParentFile().getName();
			if (!dir.equals("tests") && !dir.equals("tools")) {
				readers.add(SourceSeamTest.stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
			}
		}
		File[] tools = new File(root, "ctrmap/tools").listFiles((d, n) -> n.endsWith(".java"));
		int asked = 0;
		boolean control = false;
		for (File f : tools == null ? new File[0] : tools) {
			String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			if (!src.contains("static void main(")) {
				continue;
			}
			asked++;
			int cls = src.indexOf("public class ");
			int doc = cls < 0 ? -1 : src.lastIndexOf("/**", cls);
			Matcher m = named.matcher(doc < 0 ? "" : src.substring(doc, cls));
			List<String> unread = new ArrayList<>();
			int outputs = 0;
			while (m.find()) {
				outputs++;
				String out = m.group(1);
				boolean bundled = new File(root, "ctrmap/resources/" + out).isFile();
				Pattern load = Pattern.compile("[\"/]" + Pattern.quote(out) + "\"");
				boolean loaded = false;
				for (String r : readers) {
					loaded |= load.matcher(r).find();
				}
				if (!bundled || !loaded) {
					unread.add(out + (bundled ? " is bundled and loaded by nothing" : " is not bundled"));
				}
				control |= f.getName().equals("BuildingHarvester.java") && out.equals("oras_buildings_auto.tsv") && bundled && loaded;
			}
			if (outputs == 0) {
				unread.add("its javadoc names no catalogue it writes ({@code name.tsv})");
			}
			check(unread.isEmpty(), f.getName() + " writes a catalogue the program reads" + (unread.isEmpty() ? ""
					: " - but " + unread + ". A tool whose output nothing reads is a producer with no consumer: give"
					+ " the output a reader in the program, or delete the tool"));
		}
		check(control, "control: BuildingHarvester's oras_buildings_auto.tsv is seen bundled and loaded by name");
		check(asked >= 3, "control: the offline tools are found (" + asked + ")");
	}

	/**
	 * Every file shipped under {@code ctrmap/resources} is named by production code (F128): whole,
	 * inside one string literal, or as the join of two literals - the tool icons are a prefix
	 * ("_tool_cam") and a state ("_stale.png") put together at run time. Three pictures from 2019
	 * and three Blender sources for the move gizmo were in every release and loaded by nothing;
	 * the converter the sources were for had been deleted. Comments do not count: a name in a
	 * comment loads nothing.
	 */
	static void everyResourceIsLoadedBySomething(File root) throws Exception {
		System.out.println("--- every bundled resource is loaded by something (F128)");
		File dir = new File(root, "ctrmap/resources");
		File[] shipped = dir.listFiles();
		if (shipped == null) {
			check(false, "the resource folder can be listed - " + dir.getAbsolutePath() + " could not, so what"
					+ " it ships is UNKNOWN, not empty");
			return;
		}
		Pattern literal = Pattern.compile("\"((?:\\\\.|[^\"\\\\\\n])*)\"");
		java.util.Set<String> literals = new java.util.HashSet<>();
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			Matcher m = literal.matcher(SourceSeamTest.stripComments(
					new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
			while (m.find()) {
				literals.add(m.group(1));
			}
		}
		List<String> unloaded = new ArrayList<>();
		int whole = 0, joined = 0, files = 0;
		for (File r : shipped) {
			String n = r.getName();
			if (!r.isFile() || n.endsWith(".java")) {
				continue;
			}
			files++;
			if (namedWhole(n, literals)) {
				whole++;
			} else if (namedByJoin(n, literals)) {
				joined++;
			} else {
				unloaded.add(n);
			}
		}
		check(namedWhole("DefaultTileset.mets", literals) && !namedWhole("_tool_cam_stale.png", literals)
				&& namedByJoin("_tool_cam_stale.png", literals),
				"control: a resource named whole is seen whole, and a tool icon is seen only as a join");
		check(unloaded.isEmpty(), files + " bundled resources: " + whole + " named whole, " + joined
				+ " named as a join, and none loaded by nothing" + (unloaded.isEmpty() ? "" : " - these are: "
				+ unloaded + ". Delete them, or move an authoring source out of src/ so it stops shipping"));
	}

	private static boolean namedWhole(String name, java.util.Set<String> literals) {
		for (String l : literals) {
			if (l.contains(name)) {
				return true;
			}
		}
		return false;
	}

	private static boolean namedByJoin(String name, java.util.Set<String> literals) {
		for (String p : literals) {
			if (!p.isEmpty() && p.length() < name.length() && name.startsWith(p)
					&& literals.contains(name.substring(p.length()))) {
				return true;
			}
		}
		return false;
	}

	/** Rule one: the marker of an owned capability appears only where it is owned. */
	static void aCapabilityHasOneImplementation(File root) throws Exception {
		System.out.println("--- a capability has one implementation");
		for (String[] owned : OWNED) {
			Pattern marker = Pattern.compile(owned[1]);
			List<String> allowed = Arrays.asList(owned[2].split(","));
			List<String> strays = new ArrayList<>();
			boolean seen = false;
			for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
				if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
					continue;
				}
				String src = SourceSeamTest.stripComments(
						new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
				Matcher m = marker.matcher(src);
				boolean found = m.find();
				seen |= found && allowed.contains(f.getName());
				if (found && !allowed.contains(f.getName())) {
					strays.add(f.getName());
				}
			}
			//a marker that matches nothing anywhere passes the stray check by never looking:
			//it must find the capability where it IS owned before its silence elsewhere means anything
			check(seen, "control: the marker for " + owned[0] + " is found in its owner " + owned[2]);
			check(strays.isEmpty(), "only " + owned[2] + " does " + owned[0]
					+ (strays.isEmpty() ? "" : " - but so does " + strays + ". " + owned[3]
					+ " If this really is a different job, give it its own entry in OWNED;"
					+ " if it is the same job, call the owner."));
		}
	}

	/**
	 * Rule two: a class written after this rule names the nearest existing thing.
	 *
	 * <p>The snapshot is what existed when the rule arrived - 268 classes nobody is
	 * going back to annotate. Everything after it carries one line:
	 * {@code Not: SomeClass - why it could not be used}.
	 */
	static void aNewClassSaysWhatItIsNot(File root, File list) throws Exception {
		System.out.println("--- a class written since the rule says what it is not");
		if (!list.isFile()) {
			check(false, "no class snapshot at " + list.getPath() + " - regenerate it with"
					+ " tools/guard/snapshot_classes.py, deliberately, because everything"
					+ " missing from it is asked to justify itself");
			return;
		}
		List<String> known = Arrays.asList(new String(Files.readAllBytes(list.toPath()),
				StandardCharsets.UTF_8).split("\\R"));
		List<String> silent = new ArrayList<>();
		int newOnes = 0;
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			String path = relative(root, f);
			if (known.contains(path)) {
				continue;
			}
			newOnes++;
			String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			if (!NOT_LINE.matcher(src).find()) {
				silent.add(path);
			}
		}
		check(silent.isEmpty(), newOnes + " class(es) written since the snapshot, each saying"
				+ " what it is not" + (silent.isEmpty() ? "" : " - except " + silent
				+ ". Add a javadoc line \"Not: <the nearest existing class> - <why it could not"
				+ " be used>\". The point is not the line; it is that you cannot write it"
				+ " without having looked, and not looking is how this tree got a second map"
				+ " loader."));
	}

	/**
	 * Every class in the test package is run by test.ps1, or named by a class that is (F151). The
	 * production rule skipped the test package, and a "utility for the deploy script" sat there
	 * unregistered with no script to run it - a class that compiled, shipped in the test jar and was
	 * read as a suite by anyone counting them. Reached is a closure: test.ps1's suites are the
	 * roots, and a class one of them names (comments stripped, as a whole word) is reached.
	 */
	static void everySuiteClassIsRunOrUsed(File root) throws Exception {
		System.out.println("--- every class in the test package is run by test.ps1, or used by one that is");
		File ps1 = new File(root.getAbsoluteFile().getParentFile(), "test.ps1");
		if (!ps1.isFile()) {
			check(false, "test.ps1 is where the suites are registered, beside " + root + " - not found at " + ps1);
			return;
		}
		String runner = new String(Files.readAllBytes(ps1.toPath()), StandardCharsets.UTF_8);
		Map<String, String> src = new HashMap<>();
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap/tests"))) {
			src.put(f.getName().substring(0, f.getName().length() - ".java".length()),
				SourceSeamTest.stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
		}
		List<String> live = new ArrayList<>();
		for (String cls : src.keySet()) {
			if (Pattern.compile("ctrmap\\.tests\\." + Pattern.quote(cls) + "\\b").matcher(runner).find()) {
				live.add(cls);
			}
		}
		int registered = live.size();
		for (int i = 0; i < live.size(); i++) {
			String body = src.get(live.get(i));
			for (String other : src.keySet()) {
				if (!live.contains(other) && Pattern.compile("\\b" + Pattern.quote(other) + "\\b").matcher(body).find()) {
					live.add(other);
				}
			}
		}
		check(registered > 150 && live.contains("DuplicateWorkTest") && live.contains("EditorBench")
				&& registered < live.size(), "control: " + registered + " suites are registered, this one among"
				+ " them, and the helpers they name - EditorBench among them - are reached through them");
		List<String> stranded = new ArrayList<>(src.keySet());
		stranded.removeAll(live);
		java.util.Collections.sort(stranded);
		check(stranded.isEmpty(), live.size() + " of " + src.size() + " test-package class(es) are run by test.ps1 or"
				+ " used by one that is" + (stranded.isEmpty() ? "" : " - these are not: " + stranded + ". Register it,"
				+ " call it from a suite that is registered, move it beside the tool that runs it, or delete it"));
	}

	/**
	 * Every production class is reached by the application, or is named here with a reason.
	 *
	 * <p>WHY THIS EXISTS. This is the hole that let a previous "remove the debt" sweep
	 * report success: nothing measured REACHABILITY, so every ceiling in this battery was
	 * satisfiable by code that never runs. A dead class opens no window, holds no global
	 * and duplicates no capability - it compiles, ships in the jar, and counts toward the
	 * class snapshot as though it were alive. The census found 1,830 lines of it, including
	 * a third stale copy of the tile-semantics constants under the same names as the live
	 * one, and an 83-line class whose javadoc told the next reader it was live.
	 *
	 * <p>REACHED means: walk the references from CtrmapMainframe and the updater, through
	 * production sources only, to a fixed point. A class with its own main() is NOT live by
	 * that alone - four of the dead ones had one, which is exactly how they looked alive.
	 * Standalone tools and measured-but-unshipped formats are real, and they are listed
	 * below with what they are, which is the difference between a decision and a leftover.
	 */
	static void everyClassIsReachedBySomething(File root) throws Exception {
		System.out.println("--- every production class is reached, or is argued for by name");
		Map<String, String> src = new HashMap<>();
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			src.put(f.getName().substring(0, f.getName().length() - ".java".length()),
				new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
		}
		List<String> live = new ArrayList<>();
		for (String root_ : new String[]{"CtrmapMainframe", "Updater"}) {
			if (src.containsKey(root_)) {
				live.add(root_);
			}
		}
		for (int i = 0; i < live.size(); i++) {
			String body = SourceSeamTest.stripComments(src.get(live.get(i)));
			for (String other : src.keySet()) {
				if (live.contains(other) || other.equals(live.get(i))) {
					continue;
				}
				if (Pattern.compile("\\b" + Pattern.quote(other) + "\\b").matcher(body).find()) {
					live.add(other);
				}
			}
		}
		List<String> stranded = new ArrayList<>();
		for (String cls : src.keySet()) {
			if (live.contains(cls)) {
				continue;
			}
			boolean argued = false;
			for (String[] e : UNREACHED) {
				argued |= e[0].equals(cls + ".java");
			}
			if (!argued) {
				stranded.add(cls);
			}
		}
		check(stranded.isEmpty(), live.size() + " of " + src.size() + " production class(es)"
			+ " are reached from the application" + (stranded.isEmpty() ? "" : " - and these are not, and are not argued for: " + stranded + ". Either something should call it, or it should be deleted, or it goes in UNREACHED with what it is. A class nothing reaches still compiles, still ships, and still counts toward every other ceiling in this battery"));
		check(UNREACHED.length <= UNREACHED_CEILING, UNREACHED.length + " class(es) argued as unreached, ceiling " + UNREACHED_CEILING + " - this may fall and may not rise quietly");
		check(Ratchet.tight(UNREACHED.length, UNREACHED_CEILING), Ratchet.said("unreached-class", UNREACHED.length,
				UNREACHED_CEILING));
		//an argument for a class that is reached now, or is gone, is a record of a tree that no
		//longer exists: it tells the next reader "nothing uses this" about something that is used
		List<String> stale = new ArrayList<>();
		for (String[] e : UNREACHED) {
			String cls = e[0].substring(0, e[0].length() - ".java".length());
			if (!src.containsKey(cls)) {
				stale.add(e[0] + " (no such production class)");
			} else if (live.contains(cls)) {
				stale.add(e[0] + " (reached from the application)");
			}
		}
		check(stale.isEmpty(), "every class argued as unreached still is one" + (stale.isEmpty() ? ""
			: " - not " + stale + ". Take its entry out of UNREACHED and lower the ceiling with it"));
	}

	static String relative(File root, File f) {
		String r = root.getAbsolutePath();
		String p = f.getAbsolutePath();
		return (p.startsWith(r) ? p.substring(r.length() + 1) : p).replace('\\', '/');
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
