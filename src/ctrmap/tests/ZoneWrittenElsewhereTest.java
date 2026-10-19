package ctrmap.tests;

import ctrmap.formats.containers.ZO;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/**
 * A zone another action wrote is never written back over (F234).
 *
 * <p>WHY THIS SUITE EXISTS. The zone table reads every zone once. Empty zone, Rename, Connect
 * zones, the painter's door wiring and an interior clone write a zone FILE through a container
 * of their own, and the table's copy - the one the editor opens and saves - went on holding what
 * it read before. Opening that zone showed the old warps; saving it wrote the old header,
 * entities and script back: a rename undone by the next save, a link cut off, an injected script
 * put back as it was, because the script was written on every save whatever had happened to it.
 * The container half (two objects over one file splicing with stale offsets) is
 * ContainerWriteSafetyTest's; this is the content half.
 *
 * <p>ORDER: needs no dump. The zone is the minimal one HandedGameTest builds, written to a
 * scratch file; "another action" is a second container over the same file, which is exactly what
 * each of those actions is.
 *
 * Usage: java ctrmap.tests.ZoneWrittenElsewhereTest [src root]
 */
public class ZoneWrittenElsewhereTest {

	static int fails = 0;
	static final ContainerWriteSafetyTest.Files2 FILES = new ContainerWriteSafetyTest.Files2();

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		aHeaderAnotherActionWroteIsNotWrittenBack();
		aMassEditNamesTheZoneThatRefused();
		entitiesAnotherActionWroteAreNotWrittenBack();
		theScriptIsWrittenOnlyWhenItChanged();
		theEditorReadsTheZoneAgain(root);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A scratch zone file holding {@code entities} as subfile 1, and the table's copy of it. */
	static File zoneFile(String name, byte[] entities) throws Exception {
		File f = new File(Scratch.dir(name), "0");
		byte[] script = HandedGameTest.amx();
		Files.write(f.toPath(), HandedGameTest.container("ZO", new byte[0x38], entities, script));
		return f;
	}

	/** The minimal zone's entity data: no records, then its own script. */
	static byte[] noRecords() {
		byte[] script = HandedGameTest.amx();
		byte[] e = new byte[12 + script.length];
		MapResizeTest.putI32(e, 0, 8);
		System.arraycopy(script, 0, e, 12, script.length);
		return e;
	}

	/** The same with one warp, which is what Connect zones and the painter's doors add. */
	static byte[] oneWarp() {
		byte[] script = HandedGameTest.amx();
		byte[] e = new byte[12 + 0x18 + script.length];
		MapResizeTest.putI32(e, 0, 8 + 0x18);
		e[6] = 1;                                    //the warp count
		System.arraycopy(script, 0, e, 12 + 0x18, script.length);
		return e;
	}

	static String refusal(Zone z) {
		try {
			z.store();
			return "nothing was refused";
		} catch (IllegalStateException refused) {
			return refused.getMessage();
		}
	}

	static void aHeaderAnotherActionWroteIsNotWrittenBack() throws Exception {
		System.out.println("--- a header another action wrote is not written back (F234: Rename)");
		File f = zoneFile("ctrmap_zwe_header", noRecords());
		Zone table = new Zone(new ZO(f, FILES), GameType.ORAS);
		check(table.changedOnDisk().isEmpty() && table.changed().isEmpty(),
				"control: a zone just read has no changes of its own and none on disk");
		ZO other = new ZO(f, FILES);
		byte[] renamed = other.getFile(0).clone();
		MapResizeTest.putU16(renamed, 2, 7);         //what Rename does to a header, as far as this copy can tell
		other.storeFile(0, renamed);
		check(table.changedOnDisk().equals(Collections.singletonList("header")),
				"the table's copy sees its header changed on disk: " + table.changedOnDisk());
		check(!table.changed().contains(Zone.Part.HEADER),
				"and does not call that change its own - it did, comparing with the file, and offered to write the old header back");
		String quiet = refusal(table);
		check(quiet.equals("nothing was refused") && Arrays.equals(renamed, new ZO(f, FILES).getFile(0)),
				"a save with no edits of its own refuses nothing and leaves the other action's header on disk (" + quiet + ")");
		table.header.textID = table.header.textID + 1;
		String said = refusal(table);
		check(said.contains("changed on disk") && said.contains("header") && Arrays.equals(renamed, new ZO(f, FILES).getFile(0)),
				"an edit of its own to the same part is refused, nothing written, saying why: " + said);
		Zone again = table.reread();
		check(again.changedOnDisk().isEmpty() && again.header.areadataID == 7,
				"and reading the zone again shows what the file holds (area " + again.header.areadataID + ")");
	}

	/**
	 * A mass edit's store of a zone that refuses names the zone and throws - the throw is what takes back every zone
	 * the edit wrote before it (the 2026-10-14 shared sweep, ExtrasPanel 291, drop-throw, SURVIVED: no zone a suite's
	 * mass edit stored ever refused). Asked of the edits' one store, with a zone whose header another action wrote.
	 */
	static void aMassEditNamesTheZoneThatRefused() throws Exception {
		System.out.println("--- a mass edit's zone that refuses is named, and the edit stops there (F234)");
		File f = zoneFile("ctrmap_zwe_mass", noRecords());
		Zone zone = new Zone(new ZO(f, FILES), GameType.ORAS);
		ZO other = new ZO(f, FILES);
		byte[] renamed = other.getFile(0).clone();
		MapResizeTest.putU16(renamed, 2, 7);
		other.storeFile(0, renamed);
		zone.header.textID = zone.header.textID + 1;
		java.lang.reflect.Method store = ctrmap.humaninterface.ExtrasPanel.class.getDeclaredMethod("storeZone",
				Zone.class, int.class);
		store.setAccessible(true);
		String said = "nothing was refused";
		try {
			store.invoke(null, zone, 7);
		} catch (java.lang.reflect.InvocationTargetException thrown) {
			said = String.valueOf(thrown.getCause());
		}
		check(said.startsWith("java.lang.IllegalStateException: zone 7 was not saved: ") && said.contains("changed on disk")
				&& Arrays.equals(renamed, new ZO(f, FILES).getFile(0)),
				"the refusal names the zone and why, and the other action's header stays on disk: " + said);
	}

	static void entitiesAnotherActionWroteAreNotWrittenBack() throws Exception {
		System.out.println("--- entities another action wrote are not written back (F234: Empty zone, Connect zones)");
		check(new ZoneEntities(oneWarp()).warps.size() == 1 && new ZoneEntities(noRecords()).warps.isEmpty(),
				"control: the two entity blobs read as one warp and as none");
		File f = zoneFile("ctrmap_zwe_empty", oneWarp());
		Zone table = new Zone(new ZO(f, FILES), GameType.ORAS);
		new ZO(f, FILES).storeFile(1, noRecords());  //Empty zone, through its own container
		String quiet = refusal(table);
		check(quiet.equals("nothing was refused") && new ZoneEntities(new ZO(f, FILES).getFile(1)).warps.isEmpty(),
				"a save with no edits of its own refuses nothing and leaves the zone empty (" + quiet + ")");
		table.entities.warps.get(0).targetZone = 12;
		table.entities.modified = true;
		String said = refusal(table);
		check(said.contains("changed on disk") && said.contains("entities")
				&& new ZoneEntities(new ZO(f, FILES).getFile(1)).warps.isEmpty(),
				"a save of entities edited over the stale copy is refused - it put the emptied warp back - saying why: " + said);
		check(table.discardEntities() && table.changedOnDisk().isEmpty() && table.entities.warps.isEmpty(),
				"and Discard is the way out: it shows what the file holds, and leaves nothing to conflict");

		File g = zoneFile("ctrmap_zwe_link", noRecords());
		Zone other = new Zone(new ZO(g, FILES), GameType.ORAS);
		new ZO(g, FILES).storeFile(1, oneWarp());     //Connect zones writes the far end
		check(other.changedOnDisk().equals(Collections.singletonList("entities")) && other.entities.warps.isEmpty(),
				"the far end's table copy still holds no warp, and knows its file changed");
		check(other.reread().entities.warps.size() == 1, "reading it again - what opening it now does - shows the link");
	}

	static void theScriptIsWrittenOnlyWhenItChanged() throws Exception {
		System.out.println("--- the script is written only when it changed (F234)");
		File f = zoneFile("ctrmap_zwe_script", noRecords());
		Zone table = new Zone(new ZO(f, FILES), GameType.ORAS);
		byte[] injected = new ZO(f, FILES).getFile(2).clone();
		MapResizeTest.putI32(injected, 28, 0x3C);       //another script: its main entry moved
		new ZO(f, FILES).storeFile(2, injected);
		check(!Arrays.equals(injected, HandedGameTest.amx()), "control: the other action's script differs from the one read");
		String quiet = refusal(table);
		check(quiet.equals("nothing was refused") && Arrays.equals(injected, new ZO(f, FILES).getFile(2)),
				"a save with no script edits refuses nothing and leaves the other action's script - it was written back"
				+ " on every save (" + quiet + ")");
	}

	static void theEditorReadsTheZoneAgain(File root) throws Exception {
		System.out.println("--- the editor opens what the file holds, and reopens after the actions (F234)");
		String zp = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/humaninterface/ZoneLoadingPanel.java")));
		int stale = zp.indexOf("slot.changedOnDisk().isEmpty() ? slot : slot.reread()");
		int replaced = zp.indexOf("loadedZone.replace(opening, z);");
		int opened = zp.indexOf("loadedZone.open(opening, z);");
		check(stale >= 0 && replaced >= 0 && opened > replaced,
				"opening a zone reads its slot again when the file changed, and the table holds the new read before it opens");
		String mf = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/CtrmapMainframe.java")));
		String empty = PropEditFormGuardsTest.body(mf, "private void emptyZoneAction(");
		String rename = PropEditFormGuardsTest.body(mf, "private void renameZoneAction(");
		String connect = PropEditFormGuardsTest.body(mf, "private void connectZonesAction(");
		check(empty != null && empty.indexOf("reopenIfWritten();") > empty.indexOf("ZoneManager.clearZone(")
				&& rename != null && rename.indexOf("reopenIfWritten();") > rename.indexOf("ZoneManager.renameZone(game, idx, name)")
				&& connect != null && connect.contains("reopenIfWritten();"),
				"and Empty zone, Rename and Connect zones each reopen the open zone after writing");
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
