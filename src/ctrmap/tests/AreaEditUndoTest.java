package ctrmap.tests;

import ctrmap.AreaForker;
import ctrmap.LoadedZone;
import ctrmap.MasterTable;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.ZoneResource;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.zone.Zone;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.AreaForkPrompt;
import ctrmap.humaninterface.AreaLightingDialog;
import ctrmap.humaninterface.SaveHistory;
import java.io.File;

/**
 * The area a zone's edit goes into is the one the zone is on, a fork leaves the open zone as it was read for the
 * window to read again (F255), and the fork and the fog and lighting saves are kept in the workspace's history
 * (F209c).
 *
 * <p>WHY THIS SUITE EXISTS. Found 2026-10-09 wiring the area fork into a history: the prompt asked the master table
 * whether the zone's area was shared, and then handed back the area its CALLER said the zone was on - the open
 * zone's header, in memory. A fork set that header's id to the new area and left its area container, the map
 * view's prop registry and the camera form on the old one; the Zone tab's area box kept showing the old id, and the
 * next File &gt; Save copied it back into the header. From then on the prompt read the zone as private and handed
 * back the old, shared area: the edit the user forked to keep to one zone went into every zone on that area.
 *
 * <p>ORDER: needs no dump and no display; the game is made here. The source rule reads the tree's sources.
 *
 * Usage: java ctrmap.tests.AreaEditUndoTest [src]
 */
public class AreaEditUndoTest {

	static int fails = 0;

	/** How many zones the made game has. Zones 2 and 3 share area 21; zone 4 is alone on area 23. */
	static final int ZONES = 6;
	/** The per-area table's slot, where Omega Ruby / Alpha Sapphire keep it. */
	static final int AREAS = AreaForker.AD_GLOBAL_TABLE;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			theAreaEditedIsTheOneTheZoneIsOn();
			aForkLeavesTheOpenZoneAsRead();
			aLightingSaveIsKept();
			aLightingSaveOnASharedAreaForksFirst();
			aPickedAtmosphereIsSaidOnlyOnceSaved();
			thePickersPreviewReadsTheZonesOwnRegion();
			nothingRepointsAnOpenZoneInMemory(new File(args.length > 0 ? args[0] : "src"));
			theZoneTabsCloneAndForkAreKept();
			waterScrollOnAZoneWithNoNamedMapModelIsRefused();
		} finally {
			ctrmap.Ui.stopRecording();
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/**
	 * Water scroll on a zone none of whose map cells holds a named map model is refused by name, the area untouched.
	 *
	 * <p>Measured by the 2026-10-12 sweep (MapPainter 215, drop-throw SURVIVED): with the refusal dropped, the splice
	 * ran over no models, changed nothing and answered 0 cells - what it answers when every cell already scrolls - so
	 * the user was told nothing was needed when nothing could be bound.
	 */
	static void waterScrollOnAZoneWithNoNamedMapModelIsRefused() throws Exception {
		System.out.println("--- water scroll on a zone with no named map model is refused by name, the area untouched");
		WorkspaceSession ws = game("waterscroll");
		LoadedZone loaded = open(ws, 0);
		byte[] before = java.nio.file.Files.readAllBytes(ws.getWorkspaceFile(ArchiveType.AREA_DATA, 19).toPath());
		String said;
		try {
			said = "answered " + ctrmap.MapPainter.enableWaterScroll(loaded, 19) + " cell(s)";
		} catch (IllegalStateException ex) {
			said = ex.getMessage();
		} catch (Exception ex) {
			said = "threw " + ex;
		}
		check(said.contains("none of this zone's map cells holds a named map model"),
				"a zone whose only region holds no named map model is refused by name: " + said);
		check(java.util.Arrays.equals(before,
				java.nio.file.Files.readAllBytes(ws.getWorkspaceFile(ArchiveType.AREA_DATA, 19).toPath())),
				"and area 19's file is as it was");
	}

	static void theAreaEditedIsTheOneTheZoneIsOn() throws Exception {
		System.out.println("--- the area handed back to edit is the one the zone's file names, not its open copy's (F255)");
		WorkspaceSession ws = game("own");
		check(AreaForker.currentArea(4) == 23 && AreaForker.areaSharers(4) == 0,
				"control: zone 4 is alone on area 23");
		//its open copy still says 21 - what the Zone tab's area box wrote back after a fork
		LoadedZone loaded = open(ws, 4);
		loaded.open().header.areadataID = 21;
		java.util.List<String> said = ctrmap.Ui.record();
		AreaForker.ForkResult r = AreaForkPrompt.ensurePrivate(loaded, null, 4, "a test edit", history(ws), () -> { });
		ctrmap.Ui.stopRecording();
		check(said.isEmpty() && r != null && !r.forked && r.newArea == 23,
				"a zone on its own area is handed that area, 23, whatever its open copy says (handed "
				+ (r == null ? "nothing" : String.valueOf(r.newArea)) + ", asked " + said + ")");
	}

	static void aForkLeavesTheOpenZoneAsRead() throws Exception {
		System.out.println("--- a fork leaves the open zone as it was read, for the window to read again (F255)");
		WorkspaceSession ws = game("asread");
		LoadedZone loaded = open(ws, 2);
		SaveHistory ops = history(ws);
		int[] reread = {0};
		ctrmap.Ui.record("Give this zone its own area");
		AreaForker.ForkResult r = AreaForkPrompt.ensurePrivate(loaded, null, 2, "a test edit", ops, () -> reread[0]++);
		ctrmap.Ui.stopRecording();
		check(r != null && r.forked && AreaForker.currentArea(2) == r.newArea && r.newArea != 21,
				"control: accepting gives zone 2 its own area: " + (r == null ? "nothing" : r.oldArea + " -> " + r.newArea));
		check(loaded.open().header.areadataID == 21,
				"the open zone still says area 21, the area of everything it holds - not the new id over the old area's"
				+ " containers (it says " + loaded.open().header.areadataID + ")");

		//KEPT (F209c): the fork is a change of its own, and taking it back reads the zone again
		File forked = new File(ws.getExtractionDirectory(ArchiveType.AREA_DATA), String.valueOf(r.newArea));
		check(ops.history().canUndo() && forked.isFile() && reread[0] == 0,
				"control: the fork is in the history, its area written, and nothing read again yet - that waits on its pack");
		ops.history().undo();
		check(AreaForker.currentArea(2) == 21 && !forked.exists() && reread[0] == 1,
				"Undo puts zone 2 back on area 21, takes the new area away, and reads the zone again (on "
				+ AreaForker.currentArea(2) + ", new area " + (forked.exists() ? "there" : "gone") + ", read " + reread[0] + ")");
		ops.history().redo();
		check(AreaForker.currentArea(2) == r.newArea && forked.isFile() && reread[0] == 2,
				"and Redo forks it again, reading the zone again too (on " + AreaForker.currentArea(2) + ", read " + reread[0] + ")");
	}

	static void aLightingSaveIsKept() throws Exception {
		System.out.println("--- a fog and lighting save is kept, and taken back and put again (F209c)");
		WorkspaceSession ws = game("lighting");
		LoadedZone loaded = open(ws, 4);
		SaveHistory ops = history(ws);
		int[] fog = {0};
		byte[] was = environment(ws, 23);
		byte[] fogged = "a new atmosphere".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		java.util.List<String> said = ctrmap.Ui.record();
		AreaForker.ForkResult r = AreaLightingDialog.save(null, loaded, 4, fogged, ops, () -> { }, () -> fog[0]++);
		ctrmap.Ui.stopRecording();
		check(r != null && !r.forked && said.isEmpty() && holds(environment(ws, 23), fogged) && fog[0] == 1,
				"control: zone 4's own area 23 is written without a question, and the fog redrawn: " + said);
		ops.history().undo();
		check(java.util.Arrays.equals(was, environment(ws, 23)) && fog[0] == 2,
				"Undo puts area 23's atmosphere back and redraws the fog (redrawn " + fog[0] + " times)");
		ops.history().redo();
		check(holds(environment(ws, 23), fogged) && fog[0] == 3,
				"and Redo writes it again, redrawing the fog (redrawn " + fog[0] + " times)");
	}

	/**
	 * A picked atmosphere is said to be applied only once it is saved (the 2026-10-14 shared sweep,
	 * AreaLightingDialog 127, negate-if, SURVIVED: the picker is a modal window no recording answers, so
	 * the panel's apply was never reached). Picked on zone 4's own area it is written and said; on zone 2's
	 * shared area with the prompt closed nothing is written and nothing said of it; a block of another
	 * size is refused and nothing written.
	 */
	static void aPickedAtmosphereIsSaidOnlyOnceSaved() throws Exception {
		System.out.println("--- a picked atmosphere is said to be applied only once it is saved");
		WorkspaceSession ws = game("picked");
		LoadedZone own = open(ws, 4);
		SaveHistory ops = history(ws);
		byte[] preset = new byte[environment(ws, 23).length];
		java.util.Arrays.fill(preset, (byte) 7);
		java.util.List<String> said = ctrmap.Ui.record();
		AreaLightingDialog.applyPicked(null, own, 4, environment(ws, 23), preset, ops, () -> { }, () -> { });
		ctrmap.Ui.stopRecording();
		check(holds(environment(ws, 23), preset) && String.join(" | ", said).contains("Atmosphere applied"),
				"picked on zone 4's own area, it is written and said to be applied: " + said);

		byte[] shared = environment(ws, 21);
		LoadedZone sharing = open(ws, 2);
		said = ctrmap.Ui.record();   //nobody answers the area prompt: it was closed, so nothing is written
		AreaLightingDialog.applyPicked(null, sharing, 2, environment(ws, 21), preset, ops, () -> { }, () -> { });
		ctrmap.Ui.stopRecording();
		check(java.util.Arrays.equals(shared, environment(ws, 21)) && !String.join(" | ", said).contains("Atmosphere applied"),
				"picked on a shared area whose prompt was closed, nothing is written and nothing said of it: " + said);

		said = ctrmap.Ui.record();
		AreaLightingDialog.applyPicked(null, own, 4, environment(ws, 23), new byte[3], ops, () -> { }, () -> { });
		ctrmap.Ui.stopRecording();
		check(holds(environment(ws, 23), preset) && String.join(" | ", said).contains("does not match this area's format")
				&& !String.join(" | ", said).contains("Atmosphere applied"),
				"and a picked block of another size is refused, nothing written: " + said);

		//the custom form's Save (the 2026-10-14 shared sweep, AreaLightingDialog 244, negate-if, SURVIVED: the form
		//opens only after the picker, so its save was never reached)
		System.out.println("--- the custom fog form lets go and says so once it is saved, and stays when the prompt is closed");
		ctrmap.formats.area.AreaEnv env = ctrmap.formats.area.AreaEnv.read(new byte[ctrmap.formats.area.AreaEnv.SUB4_LEN]);
		env.fogNear = 123f;
		int[] closed = {0};
		said = ctrmap.Ui.record();
		AreaLightingDialog.saveCustom(null, null, own, 4, env,
				new byte[ctrmap.formats.area.AreaEnv.SUB4_LEN], ops, () -> { }, () -> { }, () -> closed[0]++);
		ctrmap.Ui.stopRecording();
		check(closed[0] == 1 && ctrmap.formats.area.AreaEnv.read(environment(ws, 23)).fogNear == 123f
				&& String.join(" | ", said).contains("Fog & lighting saved"),
				"on zone 4's own area the form's fog is written, the form let go once and the save said: closed "
				+ closed[0] + ", " + said);
		byte[] sharedAgain = environment(ws, 21);
		said = ctrmap.Ui.record();   //nobody answers the area prompt: it was closed
		AreaLightingDialog.saveCustom(null, null, sharing, 2, env,
				new byte[ctrmap.formats.area.AreaEnv.SUB4_LEN], ops, () -> { }, () -> { }, () -> closed[0]++);
		ctrmap.Ui.stopRecording();
		check(closed[0] == 1 && java.util.Arrays.equals(sharedAgain, environment(ws, 21))
				&& !String.join(" | ", said).contains("Fog & lighting saved"),
				"on a shared area whose prompt was closed, nothing is written, the form stays, and no save is said: closed "
				+ closed[0] + ", " + said);
		//and a save that fails is said, nothing written and the form kept (the 2026-10-16 assembly): the form's fog does
		//not fit a block of three bytes
		byte[] ownAgain = environment(ws, 23);
		said = ctrmap.Ui.record();
		AreaLightingDialog.saveCustom(null, null, own, 4, env, new byte[3], ops, () -> { }, () -> { }, () -> closed[0]++);
		ctrmap.Ui.stopRecording();
		check(closed[0] == 1 && java.util.Arrays.equals(ownAgain, environment(ws, 23))
				&& String.join(" | ", said).contains("Save failed") && !String.join(" | ", said).contains("Fog & lighting saved"),
				"a custom save that fails is said, nothing is written, and the form stays: closed " + closed[0] + ", " + said);
	}

	/**
	 * The atmosphere picker's live preview reads the open zone's own region and, where that holds no map model it can
	 * draw, says so (census M.54; the 2026-10-14 shared sweep, GfEnvPicker 282, negate-if, SURVIVED: the picker is
	 * modal). Asked of currentZoneModel over the made game, whose one region holds no map model.
	 */
	static void thePickersPreviewReadsTheZonesOwnRegion() throws Exception {
		System.out.println("--- the atmosphere picker's preview reads the open zone's own region, and says why it cannot draw it");
		WorkspaceSession ws = game("pickerzone");
		LoadedZone loaded = open(ws, 4);
		java.util.List<String> notes = new java.util.ArrayList<>();
		String drawn;
		try {
			java.lang.reflect.Method model = ctrmap.humaninterface.GfEnvPicker.class.getDeclaredMethod("currentZoneModel",
					LoadedZone.class, java.util.List.class);
			model.setAccessible(true);
			drawn = model.invoke(null, loaded, notes) == null ? "nothing" : "a model";
		} catch (ReflectiveOperationException | RuntimeException threw) {
			drawn = "threw " + (threw.getCause() != null ? threw.getCause() : threw);
		}
		check("nothing".equals(drawn) && notes.size() == 1 && notes.get(0).startsWith("Your zone's region 0 holds no map model"),
				"an open zone's own region is read, and one with no map model to draw is said: " + drawn + ", " + notes);
		//where its atmospheres come from on a game with no untouched copy: the workspace's own, said (F211, F232; the
		//2026-10-14 shared sweep, GfEnvPicker 320, negate-if, SURVIVED)
		java.util.List<String> sourceNotes = new java.util.ArrayList<>();
		String source;
		try {
			java.lang.reflect.Method read = ctrmap.humaninterface.GfEnvPicker.class.getDeclaredMethod("pristineOrLive",
					ArchiveType.class, java.util.List.class);
			read.setAccessible(true);
			Object garc = read.invoke(null, ArchiveType.AREA_DATA, sourceNotes);
			source = garc == ws.getArchive(ArchiveType.AREA_DATA) ? "the workspace's own" : String.valueOf(garc);
		} catch (ReflectiveOperationException | RuntimeException threw) {
			source = "threw " + (threw.getCause() != null ? threw.getCause() : threw);
		}
		check("the workspace's own".equals(source) && sourceNotes.size() == 1
				&& sourceNotes.get(0).startsWith("This workspace has no pristine copy of AREA_DATA"),
				"with no untouched copy the picker reads the workspace's own atmospheres and says so: " + source + ", "
				+ sourceNotes);
	}

	static void aLightingSaveOnASharedAreaForksFirst() throws Exception {
		System.out.println("--- a fog and lighting save on a shared area forks it first, packs it, and reads the zone again");
		WorkspaceSession ws = game("sharedlight");
		LoadedZone loaded = open(ws, 2);
		SaveHistory ops = history(ws);
		int[] reread = {0};
		byte[] shared = environment(ws, 21);
		byte[] fogged = "zone two's own fog".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		ctrmap.Ui.record("Give this zone its own area");
		AreaForker.ForkResult[] saved = {null};
		//on the event thread, as the window's click is: the fork's pack shows its progress there (F220)
		javax.swing.SwingUtilities.invokeAndWait(() -> saved[0] = AreaLightingDialog.save(null, loaded, 2, fogged, ops,
				() -> reread[0]++, () -> { }));
		ctrmap.Ui.stopRecording();
		AreaForker.ForkResult r = saved[0];
		check(r != null && r.forked && java.util.Arrays.equals(shared, environment(ws, 21))
				&& holds(environment(ws, r.newArea), fogged),
				"the shared area 21 is untouched and zone 2's new area holds the atmosphere: "
				+ (r == null ? "nothing" : r.oldArea + " -> " + r.newArea));
		check(ws.packs() == 1 && reread[0] == 1 && loaded.open().header.areadataID == 21,
				"the fork is packed and the zone read again after it - the open copy is never half-repointed (packs "
				+ ws.packs() + ", read " + reread[0] + ")");
		ops.history().undo();
		check(java.util.Arrays.equals(shared, environment(ws, r.newArea)),
				"Undo takes the atmosphere back, the pack since notwithstanding: the new area holds area 21's again");
		String refused = null;
		try {
			ops.history().undo();
		} catch (IllegalStateException said) {
			refused = said.getMessage();
		}
		check(refused != null && refused.contains("pack") && AreaForker.currentArea(2) == r.newArea,
				"and the fork, packed into the game, is refused in words and left as it is - no pack takes an area out: "
				+ refused);
	}

	/**
	 * NOTHING BUT THE HEADER'S READER AND THE ZONE TAB'S OWN BOXES SETS AN OPEN ZONE'S AREA OR MAP ID (F255). Two
	 * places set the area id after a fork and left everything read for the old one in place; the class is closed by
	 * reading the zone again, and this keeps a third from being written.
	 */
	static void nothingRepointsAnOpenZoneInMemory(File src) throws Exception {
		System.out.println("--- nothing but the header's reader and the Zone tab sets an open zone's area or map id (F255)");
		check(setsZoneIds("open.header.areadataID = r.newArea;\nif (z.header.areadataID == area) {").size() == 1
				&& setsZoneIds("x.mapmatrixID=3;").size() == 1 && setsZoneIds("a.areadataID >= b").isEmpty(),
				"control: an assignment of either id is found, and a comparison is not");
		java.util.List<String> found = new java.util.ArrayList<>();
		java.util.List<File> sources = new java.util.ArrayList<>();
		collect(new File(src, "ctrmap"), sources);
		for (File f : sources) {
			String rel = f.getPath().replace('\\', '/');
			if (rel.contains("/tests/")) {
				continue;
			}
			String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
			for (String line : setsZoneIds(text)) {
				found.add(rel.substring(rel.indexOf("ctrmap/")) + ": " + line);
			}
		}
		check(sources.size() > 400, "the rule read the tree's sources (" + sources.size() + ")");
		java.util.List<String> others = new java.util.ArrayList<>();
		for (String f : found) {
			if (!f.startsWith("ctrmap/formats/zone/ZoneHeader.java:") && !f.startsWith("ctrmap/humaninterface/ZoneLoadingPanel.java:")) {
				others.add(f);
			}
		}
		check(found.size() > others.size(), "control: the Zone tab's own box writes are found: " + found);
		check(others.isEmpty(), "and nothing else sets an open zone's area or map id - read the zone again instead: " + others);
		//the Zone tab's exemption holds while its save reads a repointed zone again, and the reopen does
		String tab = new String(java.nio.file.Files.readAllBytes(new File(src, "ctrmap/humaninterface/ZoneLoadingPanel.java")
				.toPath()), java.nio.charset.StandardCharsets.UTF_8);
		String store = body(tab, "public boolean store(boolean dialog)");
		String reopen = body(tab, "public void reopenIfChangedOnDisk()");
		check(store.contains("zone.header.repointed()") && store.contains("this::reopenIfChangedOnDisk")
				&& reopen.contains("open.header.repointed()"),
				"and the Zone tab, the one place that sets them, reads the zone again after a save that moved one (store asks: "
				+ store.contains("zone.header.repointed()") + ", reopen reads: " + reopen.contains("open.header.repointed()") + ")");
	}

	/** The body of the method {@code head} opens in {@code text}, by its braces; empty when there is none. */
	static String body(String text, String head) {
		int at = text.indexOf(head);
		if (at < 0) {
			return "";
		}
		int open = text.indexOf('{', at);
		int depth = 0;
		for (int i = open; i < text.length(); i++) {
			char c = text.charAt(i);
			depth += c == '{' ? 1 : c == '}' ? -1 : 0;
			if (depth == 0) {
				return text.substring(open, i + 1);
			}
		}
		return text.substring(open);
	}

	/**
	 * The Zone tab's clone and the map fork it offers are kept by their seams (F209c): each is taken back byte for
	 * byte and put again, the zones read again each time.
	 */
	static void theZoneTabsCloneAndForkAreKept() throws Exception {
		System.out.println("--- a zone cloned, and a zone given its own map, are taken back and put again (F209c)");
		WorkspaceSession ws = game("clone");
		SaveHistory ops = history(ws);
		int[] reread = {0};
		File four = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, 4);
		byte[] fourWas = java.nio.file.Files.readAllBytes(four.toPath());
		ctrmap.WorkspaceOps.clone(ws, 2, 4, ops, () -> reread[0]++);
		check(AreaForker.currentArea(4) == 21, "control: zone 4 is a copy of zone 2 now, on its area 21");
		check(String.valueOf(ops.history().undoName()).startsWith("Clone zone 2 into 4"),
				"and the clone is the change Undo takes back next: " + ops.history().undoName());
		ops.history().undo();
		check(java.util.Arrays.equals(fourWas, java.nio.file.Files.readAllBytes(four.toPath())) && AreaForker.currentArea(4) == 23
				&& reread[0] == 1, "Undo puts zone 4 back byte for byte and reads the zones again (read " + reread[0] + ")");
		ops.history().redo();
		check(AreaForker.currentArea(4) == 21 && reread[0] == 2, "and Redo clones it again (read " + reread[0] + ")");

		int matrices = ws.getArchive(ArchiveType.MAP_MATRIX).length;
		ctrmap.GeometryForker.ForkResult map = ctrmap.WorkspaceOps.forkMap(ws, 3, ops, () -> reread[0]++);
		File newMatrix = new File(ws.getExtractionDirectory(ArchiveType.MAP_MATRIX), String.valueOf(map.newMatrix));
		check(map.forked && map.newMatrix >= matrices && newMatrix.isFile(),
				"control: zone 3 gets its own map, a new matrix " + map.newMatrix);
		check(String.valueOf(ops.history().undoName()).startsWith("Give zone 3 its own map"),
				"and the map fork is the change Undo takes back next: " + ops.history().undoName());
		ops.history().undo();
		check(!newMatrix.exists() && reread[0] == 3, "Undo takes the new map away and reads the zones again");
		ops.history().redo();
		check(newMatrix.isFile() && reread[0] == 4, "and Redo gives it back");
	}

	/** The lines of {@code text} that assign an area or map id. */
	static java.util.List<String> setsZoneIds(String text) {
		java.util.List<String> out = new java.util.ArrayList<>();
		java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\.(areadataID|mapmatrixID)\\s*=(?!=)");
		for (String line : text.split("\n")) {
			if (p.matcher(line).find()) {
				out.add(line.trim());
			}
		}
		return out;
	}

	static void collect(File dir, java.util.List<File> into) {
		File[] in = dir.listFiles();
		if (in == null) {
			return;
		}
		for (File f : in) {
			if (f.isDirectory()) {
				collect(f, into);
			} else if (f.getName().endsWith(".java")) {
				into.add(f);
			}
		}
	}

	/** Whether a stored subfile holds {@code data}: its bytes, then the container's zero padding. */
	static boolean holds(byte[] stored, byte[] data) {
		if (stored == null || stored.length < data.length) {
			return false;
		}
		for (int i = 0; i < stored.length; i++) {
			if (stored[i] != (i < data.length ? data[i] : 0)) {
				return false;
			}
		}
		return true;
	}

	/** A workspace history over the made game. */
	static SaveHistory history(WorkspaceSession ws) {
		return new SaveHistory(() -> java.util.Collections.<Object>singletonList(ws));
	}

	/** Area {@code area}'s atmosphere subfile as the workspace holds it. */
	static byte[] environment(WorkspaceSession ws, int area) {
		return new ctrmap.formats.containers.AD(ws.getWorkspaceFile(ArchiveType.AREA_DATA, area), ws)
				.getFile(ctrmap.formats.containers.AD.ENVIRONMENT);
	}

	/** The made game with zone {@code zone} open, read from its workspace file. */
	static LoadedZone open(WorkspaceSession ws, int zone) throws Exception {
		LoadedZone loaded = new LoadedZone();
		loaded.open(zone, new Zone(new ZO(ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zone), ws), GameType.ORAS));
		return loaded;
	}

	/**
	 * A made Omega Ruby / Alpha Sapphire game: {@link #ZONES} zones - zone i on area i + 19, but zones 2 and 3 both on
	 * 21 - the master table and the encounter pack after them; {@link #AREAS} area containers and the per-area table;
	 * one NPC registry per area; a matrix and a region.
	 */
	static WorkspaceSession game(String name) throws Exception {
		File root = Scratch.dir("ctrmap_areaedit_" + name);
		byte[][] zo = new byte[ZONES + 2][];
		byte[] rows = new byte[ZONES * MasterTable.ROW];
		for (int z = 0; z < ZONES; z++) {
			int area = z == 3 ? 21 : z + 19;
			zo[z] = zone(area);
			MapResizeTest.putU16(rows, z * MasterTable.ROW + ZoneResource.AREA.headerOffset, area);
		}
		zo[ZONES] = rows;
		zo[ZONES + 1] = MapResizeTest.container(0x454E, new byte[ZONES][0]);
		File zoFile = new File(root, "zonedata");
		HandedGameTest.writeGarc(zoFile, zo);
		byte[][] areas = new byte[AREAS + 1][];
		byte[][] registries = new byte[AREAS][];
		for (int i = 0; i < AREAS; i++) {
			areas[i] = MassEditTest.area(i);
			registries[i] = new byte[]{(byte) i, 9};
		}
		areas[AREAS] = AreaIdBudgetTest.table(AREAS);
		File ad = new File(root, "areas");
		HandedGameTest.writeGarc(ad, areas);
		File np = new File(root, "registries");
		HandedGameTest.writeGarc(np, registries);
		File mm = new File(root, "matrices");
		HandedGameTest.writeGarc(mm, MapResizeTest.matrix(0));
		File fd = new File(root, "regions");
		HandedGameTest.writeGarc(fd, MapResizeTest.region("region zero"));
		new File(root, "workspace").mkdirs();
		WorkspaceSession ws = Sessions.bare(new File(root, "workspace"), new File(root, "game"), GameType.ORAS)
				.withArchive(ArchiveType.ZONE_DATA, new GARC(zoFile))
				.withArchive(ArchiveType.AREA_DATA, new GARC(ad))
				.withArchive(ArchiveType.NPC_REGISTRIES, new GARC(np))
				.withArchive(ArchiveType.MAP_MATRIX, new GARC(mm))
				.withArchive(ArchiveType.FIELD_DATA, new GARC(fd));
		ws.prepareDirectories();
		Workspace.install(ws);
		return ws;
	}

	/** A zone container on {@code area}: a header, no entities, an empty script - one {@link Zone} reads. */
	static byte[] zone(int area) {
		byte[] header = new byte[0x38];
		MapResizeTest.putU16(header, ZoneResource.AREA.headerOffset, area);
		byte[] script = HandedGameTest.amx();
		byte[] entities = new byte[12 + script.length];
		MapResizeTest.putI32(entities, 0, 8);
		System.arraycopy(script, 0, entities, 12, script.length);
		return HandedGameTest.container("ZO", header, entities, script);
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			fails++;
			System.out.println("  FAIL: " + what);
		}
	}
}
