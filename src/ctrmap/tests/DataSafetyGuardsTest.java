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
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * <li>Every zone-loading SwingWorker must call get() in done(). One of the two
 *     in ZoneLoadingPanel did, with a comment saying why; the other dropped
 *     every exception on the floor.</li>
 * <li>A pack that cannot rewrite the archive must fail loudly. When the
 *     emulator, an antivirus scanner or Explorer held the archive open,
 *     packDirectory logged the IOException and returned normally: the
 *     progress bar filled, Deploy reported "nothing changed", the game showed
 *     the old map, and a half-written &lt;archive&gt;_new sat in the workspace.
 *     The user concluded the editor did not save.</li>
 * </ol>
 *
 * Usage: java ctrmap.tests.DataSafetyGuardsTest &lt;path-to-any-garc&gt; [pristine-dump-root]
 * The worker check reads src/ from the working directory.
 */
public class DataSafetyGuardsTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		String garcPath = args.length > 0 ? args[0] : "../RomFS_original_garcs/a/0/4/0";
		File dump = new File(args.length > 1 ? args[1] : "../RomFS_original_garcs");
		staleArchive(new File(garcPath));
		lockedArchive(new File(garcPath));
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
	 * A pack against an archive another process is holding must throw, leave
	 * the archive exactly as it was, leave no half-written copy behind, and
	 * keep the edit pending for the next pack.
	 */
	static void lockedArchive(File src) throws Exception {
		if (!src.isFile()) {
			System.out.println("  skip: no GARC at " + src);
			return;
		}
		File root = Files.createTempDirectory("ctrmap_lock").toFile();
		File archive = new File(new File(root, "game"), src.getName());
		archive.getParentFile().mkdirs();
		Files.copy(src.toPath(), archive.toPath());
		byte[] before = Files.readAllBytes(archive.toPath());
		Workspace.WORKSPACE_PATH = new File(root, "ws").getAbsolutePath();
		File dir = new File(Workspace.WORKSPACE_PATH, "mapmatrix");
		dir.mkdirs();

		//the user edits entry 3 and saves
		GARC g = new GARC(archive);
		byte[] edited = g.getDecompressedEntry(3).clone();
		edited[edited.length - 1] ^= 0x5A;
		File staged = new File(dir, "3");
		Files.write(staged.toPath(), edited);
		Workspace.addPersist(staged);

		//the emulator has the archive open
		try (RandomAccessFile holder = new RandomAccessFile(archive, "rw");
				FileLock lock = holder.getChannel().lock()) {
			try {
				g.packDirectory(dir);
				check(false, "a pack against a locked archive throws");
			} catch (Exception ex) {
				check(true, "a pack against a locked archive throws (" + ex.getMessage() + ")");
			}
		}
		check(Arrays.equals(before, Files.readAllBytes(archive.toPath())), "the archive on disk is untouched");
		check(!new File(Workspace.WORKSPACE_PATH, archive.getName() + "_new").exists(),
				"no half-written <archive>_new is left in the workspace");
		check(Workspace.persist_paths.contains(staged.getAbsolutePath()), "the edit is still pending");

		//and once the archive is released the same pack goes through
		g.packDirectory(dir);
		check(Arrays.equals(new GARC(archive).getDecompressedEntry(3), edited),
				"the pack goes through once the archive is released, carrying the edit");
		Workspace.persist_paths.remove(staged.getAbsolutePath());
		deleteTree(root);
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
	 * Every SwingWorker in ZoneLoadingPanel must call get() in done(). A worker
	 * that does not discards whatever doInBackground threw, and the zone-switch
	 * worker did exactly that: the progress dialog closed, the map tab came up,
	 * and the entity editors quietly kept the previous zone.
	 */
	static void workerErrorsSurface(File src) throws Exception {
		File panel = new File(src, "ctrmap/humaninterface/ZoneLoadingPanel.java");
		if (!panel.isFile()) {
			System.out.println("  skip: no source at " + panel);
			return;
		}
		String text = new String(Files.readAllBytes(panel.toPath()), StandardCharsets.UTF_8);
		int workers = 0;
		List<Integer> silent = new ArrayList<>();
		for (int at = text.indexOf("void done()"); at != -1; at = text.indexOf("void done()", at + 1)) {
			int open = text.indexOf('{', at), end = open, depth = 0;
			do {
				char ch = text.charAt(end++);
				depth += ch == '{' ? 1 : ch == '}' ? -1 : 0;
			} while (depth > 0);
			workers++;
			if (!text.substring(open, end).contains("get()")) {
				silent.add(text.substring(0, at).split("\n", -1).length);
			}
		}
		check(workers >= 2, workers + " workers found in ZoneLoadingPanel");
		check(silent.isEmpty(), "every done() calls get(); missing at line(s) " + silent);
	}

	static void deleteTree(File f) {
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				deleteTree(k);
			}
		}
		f.delete();
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
