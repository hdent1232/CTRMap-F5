package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.Workspace;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.LocationNames;
import ctrmap.formats.text.TextFile;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.humaninterface.WarpEditForm;
import ctrmap.humaninterface.ZoneLoadingPanel;
import java.awt.Point;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;

/**
 * The guards that stand between an ordinary mistake and unrecoverable game
 * data. Each of these was a live defect, and each was silent.
 *
 * <ol>
 * <li>A GARC whose file has been rewritten by somebody else since it was read
 *     must notice before packing. Its entry table is a list of offsets; pack
 *     from a stale one and every "unchanged" entry is copied from garbage. A
 *     CTRMap window sat open for eleven hours while a headless tool rewrote
 *     ZoneData five times - one click on Pack Workspace would have done it.</li>
 * <li>A sign wrapper must be injected BEFORE any talker or sign is wired into
 *     the same script, because updateRaw moves the address the injector writes
 *     at. Recomputing those boundaries "correctly" was tried and is wrong - it
 *     breaks the 467-zone injection corpus - so the ordering is the guard.</li>
 * <li>A warp with no destination must not serialise. It used to default to
 *     zone 0, warp 0, which is a real place: adding a warp in the editor and
 *     saving built a working door into the first zone in the game.</li>
 * <li>The warp editor must add a warp that IS unset, and must address the
 *     warp list by index. It used to point a new warp at the open zone - a
 *     real door, indistinguishable from a finished one, so the refusal above
 *     could never fire - and looked warps up by value: two warps on one tile
 *     are field-for-field identical, so Save rewrote the first twin and
 *     Remove deleted it while the dropdown claimed to hold the second.</li>
 * <li>A warp whose target zone no longer exists ("Remove added zones" warns
 *     and proceeds) must still open. Indexing the zone table with it threw,
 *     and the zone-switch worker discarded the throw - the zone looked loaded
 *     with the previous zone's warps, triggers and script still in their
 *     editors, and edits made there were never written.</li>
 * <li>Every SwingWorker in src must call get() in done(). One of the two in
 *     ZoneLoadingPanel did, with a comment saying why; the other dropped every
 *     exception on the floor - and so did six more across Builder, TileMapPanel,
 *     Workspace and SetupWizard once the scan looked past that one panel.</li>
 * </ol>
 *
 * Usage: java ctrmap.tests.DataSafetyGuardsTest &lt;path-to-any-garc&gt; [pristine-dump-root]
 * The worker check reads src/ from the working directory.
 */
public class DataSafetyGuardsTest {

	static int fails = 0;
	/** A SwingWorker completion hook; the same shape whatever the class extends. */
	private static final Pattern DONE = Pattern.compile("void done\\(\\)\\s*\\{");

	public static void main(String[] args) throws Exception {
		String garcPath = args.length > 0 ? args[0] : "../RomFS_original_garcs/a/0/4/0";
		File dump = new File(args.length > 1 ? args[1] : "../RomFS_original_garcs");
		staleArchive(new File(garcPath));
		scriptBoundaries();
		unsetWarp();
		warpEditor(dump);
		workerErrorsSurface(new File("src"));
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A GARC must detect that its file moved underneath it. */
	static void staleArchive(File src) throws Exception {
		if (!src.isFile()) {
			System.out.println("  skip: no GARC at " + src);
			return;
		}
		File tmp = File.createTempFile("ctrmap_stale", ".garc");
		tmp.deleteOnExit();
		Files.copy(src.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);

		GARC g = new GARC(tmp);
		check(!g.isStale(), "a freshly read archive is not stale");

		//somebody else rewrites the file - a second editor, another tool
		byte[] bytes = Files.readAllBytes(tmp.toPath());
		byte[] longer = new byte[bytes.length + 64];
		System.arraycopy(bytes, 0, longer, 0, bytes.length);
		Files.write(tmp.toPath(), longer);
		//lastModified can be coarse; the length change alone must be enough
		check(g.isStale(), "an archive rewritten underneath is detected as stale");

		//and a re-read clears it
		GARC g2 = new GARC(tmp);
		check(!g2.isStale(), "re-reading clears the staleness");
		tmp.delete();
	}

	/**
	 * The sign-injection ordering rule, stated so it is not silently lost.
	 *
	 * <p>updateRaw moves dataStart, and SignWrapperInjector writes at
	 * dataStart - instructionStart, so appending code first moves the target.
	 * The arithmetic below shows how far: recomputing the boundary from the
	 * grown code, which is the intuitive "fix", relocates the write by the size
	 * of whatever was appended. SignWrapperInjectTest is what actually holds
	 * this - it verifies 272 injections at byte level across 467 zones.
	 */
	static void scriptBoundaries() {
		int instructionStart = 0x40, code = 100, data = 7, appended = 20;
		int parsedHeapStart = instructionStart + (code + data) * 4;
		int parsedDataStart = parsedHeapStart - data * 4;
		int grownHeapStart = instructionStart + (code + appended + data) * 4;
		int grownDataStart = grownHeapStart - data * 4;
		check(grownDataStart - parsedDataStart == appended * 4,
				"recomputing the boundary after an append moves the injection site by the"
				+ " appended size - which is why signs are injected first, not last");
	}

	/** An unconfigured warp must refuse to serialise. */
	static void unsetWarp() {
		ZoneEntities.Warp w = new ZoneEntities.Warp();
		check(w.isUnset(), "a new warp starts with no destination");
		check(w.targetZone != 0, "a new warp does NOT default to zone 0");

		ZoneEntities e = null;
		try {
			e = new ZoneEntities(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
		} catch (RuntimeException ignore) {
		}
		if (e == null) {
			System.out.println("  skip: could not build an empty ZoneEntities to assemble");
			return;
		}
		e.warps.add(w);
		try {
			e.assembleData();
			System.out.println("  FAIL: an unset warp serialised without complaint");
			fails++;
		} catch (IllegalStateException ex) {
			System.out.println("  ok: an unset warp refuses to serialise (" + ex.getMessage() + ")");
		}

		//and a configured one is fine
		w.targetZone = 473;
		w.targetWarpId = 0;
		check(!w.isUnset(), "setting a destination clears the unset state");
	}

	/**
	 * The warp editor on real zones, without a window: what "New entry" adds,
	 * what Save and Remove touch when two warps share a tile, and a target zone
	 * past the end of the table. Every one of these reached the user silently.
	 */
	static void warpEditor(File dump) throws Exception {
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump);
			return;
		}
		Workspace.game = Workspace.GameType.ORAS;
		Workspace.GAMEDIR_PATH = dump.getAbsolutePath();
		GARC zo = new GARC(new File(Workspace.GAMEDIR_PATH + Workspace.getArchivePath(Workspace.ArchiveType.ZONE_DATA, Workspace.game)));
		GARC texts = new GARC(new File(Workspace.GAMEDIR_PATH + Workspace.getArchivePath(Workspace.ArchiveType.GAMETEXT, Workspace.game)));
		LocationNames.textfile = new TextFile(temp(texts.getDecompressedEntry(LocationNames.gametextIndex())));
		//three zones make a zone table; the editor is told zone 2 is open
		ZoneLoadingPanel zonePnl = new ZoneLoadingPanel();
		zonePnl.zones = new Zone[3];
		for (int i = 0; i < zonePnl.zones.length; i++) {
			zonePnl.zones[i] = new Zone(new ZO(temp(zo.getDecompressedEntry(i))), Workspace.game);
		}
		zonePnl.zoneIndex = 2;
		CtrmapMainframe.mZonePnl = zonePnl;
		ZoneEntities e = zonePnl.zones[2].entities;
		e.warps.clear();
		e.warpCount = 0;
		WarpEditForm form = new WarpEditForm();
		form.loadFromEntities(e);

		//what "New entry" adds, twice on the same tile
		form.addEntry(new Point(20, 20));
		form.addEntry(new Point(20, 20));
		ZoneEntities.Warp a = e.warps.get(0), b = e.warps.get(1);
		check(a.isUnset() && b.isUnset(), "a warp added in the editor has no destination until one is chosen");
		check(entry(form, 1).endsWith("<no destination>"), "and the dropdown says so: " + entry(form, 1));
		try {
			e.assembleData();
			System.out.println("  FAIL: a zone with an editor-added warp saved without a destination");
			fails++;
		} catch (IllegalStateException ex) {
			System.out.println("  ok: the zone refuses to save it (" + ex.getMessage() + ")");
		}

		//Remove with the second twin selected
		check(e.warps.indexOf(b) == 1, "a warp is found in its list by identity, not by value");
		form.setWarp(1);
		form.removeEntry();
		check(e.warps.size() == 1 && e.warps.get(0) == a, "Remove deleted the selected warp, not its twin");

		//Save with the second twin selected
		form.addEntry(new Point(20, 20));
		form.setWarp(1);
		((JComboBox<?>) field(form, "tgtZone")).setSelectedIndex(1);
		((JFormattedTextField) field(form, "tgtWarp")).setValue(3);
		form.saveEntry();
		check(e.warps.get(1).targetZone == 1 && e.warps.get(1).targetWarpId == 3, "Save wrote the selected warp");
		check(e.warps.get(0) == a && a.isUnset(), "and left its twin alone");
		check(!entry(form, 1).endsWith("<no destination>"), "the dropdown follows the save: " + entry(form, 1));
		check(e.warps.size() == 2 && e.warpCount == 2, "the list and its count agree");

		//Save on an empty destination field. Every check above saves a warp
		//whose destination IS filled in, and on that path the hardened code and
		//the old code agree - mutation testing reverted saveEntry's empty-field
		//handling and the whole battery still passed. Cleared fields reach
		//saveEntry whenever the user blanks one before pressing Save, and the
		//old cast of a null Integer threw out of the button handler.
		form.addEntry(new Point(21, 21));
		form.setWarp(2);
		((JComboBox<?>) field(form, "tgtZone")).setSelectedIndex(-1);
		((JFormattedTextField) field(form, "tgtWarp")).setValue(null);
		try {
			form.saveEntry();
			check(e.warps.get(2).isUnset(), "Save with the destination fields cleared leaves the warp unset");
			try {
				e.assembleData();
				System.out.println("  FAIL: a zone saved a warp whose destination was never chosen");
				fails++;
			} catch (IllegalStateException ex) {
				System.out.println("  ok: and the zone still refuses to save it");
			}
		} catch (RuntimeException ex) {
			System.out.println("  FAIL: Save with the destination fields cleared threw " + ex);
			fails++;
		}
		form.setWarp(2);
		form.removeEntry();

		//Save straight after a reload, with no entry selected. warpIndex is
		//kept across loads, so a stale one from the previous zone would write
		//this zone's warp into whatever slot was selected in the last one.
		form.loadFromEntities(e);
		form.saveEntry();
		check(e.warps.size() == 2 && e.warps.get(0) == a, "Save before anything is selected writes nothing");

		//a destination that no longer exists
		ZoneEntities.Warp dangling = new ZoneEntities.Warp();
		dangling.targetZone = zonePnl.zones.length;
		dangling.targetWarpId = 0;
		e.warps.add(dangling);
		e.warpCount++;
		try {
			form.loadFromEntities(e);
			form.setWarp(2);
			check(entry(form, 2).contains("(missing)"), "a target past the zone table is named, not indexed: " + entry(form, 2));
			check(((JComboBox<?>) field(form, "tgtZone")).getSelectedIndex() == -1, "and nothing is selected for it");
		} catch (RuntimeException ex) {
			System.out.println("  FAIL: opening a zone with a dangling warp threw " + ex);
			fails++;
		}
	}

	/**
	 * Every SwingWorker in src must call get() in done(). A worker that does
	 * not discards whatever doInBackground threw, and six of them did exactly
	 * that: the progress dialog closed and the panel carried on. A pack that
	 * failed half-way still ran its completion callback - the one that deploys
	 * to the emulator - a region that failed to write still let the zone
	 * switch go on, and a map that failed to load left the previous map's
	 * regions on screen. Scanning the whole tree, not one panel, is what stops
	 * the next worker being added without one.
	 */
	static void workerErrorsSurface(File src) throws Exception {
		File root = new File(src, "ctrmap");
		if (!root.isDirectory()) {
			System.out.println("  skip: no source at " + root);
			return;
		}
		List<String> silent = new ArrayList<>();
		int workers = silentWorkers(root, silent);
		check(workers >= 10, workers + " done() bodies found under " + root);
		check(silent.isEmpty(), "every done() calls get(); missing at " + silent);
	}

	/** Comments are stripped first, so a get() mentioned in one does not count. */
	static int silentWorkers(File dir, List<String> silent) throws Exception {
		int n = 0;
		File[] files = dir.listFiles();
		if (files == null) {
			return 0;
		}
		for (File f : files) {
			if (f.isDirectory()) {
				n += silentWorkers(f, silent);
				continue;
			}
			if (!f.getName().endsWith(".java")) {
				continue;
			}
			String text = SourceSeamTest.stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			for (Matcher m = DONE.matcher(text); m.find();) {
				int open = text.indexOf('{', m.start()), end = open, depth = 0;
				do {
					char ch = text.charAt(end++);
					depth += ch == '{' ? 1 : ch == '}' ? -1 : 0;
				} while (depth > 0);
				n++;
				if (!text.substring(open, end).contains("get()")) {
					silent.add(f.getName() + ":" + text.substring(0, m.start()).split("\n", -1).length);
				}
			}
		}
		return n;
	}

	static File temp(byte[] bytes) throws Exception {
		File f = File.createTempFile("ctrmap_guard", ".bin");
		f.deleteOnExit();
		Files.write(f.toPath(), bytes);
		return f;
	}

	static Object field(Object o, String name) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	static String entry(WarpEditForm form, int index) throws Exception {
		return ((JComboBox<?>) field(form, "entryBox")).getItemAt(index).toString();
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
