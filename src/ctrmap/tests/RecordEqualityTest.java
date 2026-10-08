package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.cameradata.CameraData;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.humaninterface.NPCEditForm;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A record that says what equal means says what its hash is, and no editor finds a record by
 * equality (F210).
 *
 * <p>WHY THIS SUITE EXISTS. Six format records overrode equals with no hashCode, and four of
 * them compared a hand-picked subset of their fields. Those equals were the editors' change
 * detectors AND their identity lookups: the camera and NPC forms found the slot to write with
 * indexOf, which answers the FIRST equal record - so after two New camera entries an edit to the
 * second was written over the first.
 *
 * <p>ORDER: needs no dump and no display. The first half reads the compiled classes, so a record
 * added later is held to it without being named here.
 *
 * Usage: java ctrmap.tests.RecordEqualityTest
 */
public class RecordEqualityTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			TreeSet<String> records = everyEqualsHasAHash();
			noEditorFindsARecordByEquality(records);
			theZoneRecordsCompareWhatTheyWrite();
			theNpcFormWritesTheNpcItShows();
		} finally {
			ctrmap.Ui.stopRecording();
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** The control: says what equal means and not what its hash is. */
	static final class EqualsWithoutHash {

		@Override
		public boolean equals(Object o) {
			return o instanceof EqualsWithoutHash;
		}
	}

	static boolean declaresEquals(Class<?> c) {
		try {
			c.getDeclaredMethod("equals", Object.class);
			return true;
		} catch (NoSuchMethodException no) {
			return false;
		}
	}

	static boolean declaresHash(Class<?> c) {
		try {
			c.getDeclaredMethod("hashCode");
			return true;
		} catch (NoSuchMethodException no) {
			return false;
		}
	}

	/** Every production class, read from the compiled tree this suite runs from. */
	static TreeSet<String> everyEqualsHasAHash() throws Exception {
		System.out.println("--- every class that says what equal means says what its hash is (F210)");
		check(declaresEquals(EqualsWithoutHash.class) && !declaresHash(EqualsWithoutHash.class),
				"control: a class with equals and no hashCode is seen as one");
		Path root = new File(CameraData.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toPath();
		List<Path> classes;
		try (Stream<Path> walk = Files.walk(root)) {
			classes = walk.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
		}
		int scanned = 0;
		List<String> unread = new ArrayList<>();
		List<String> noHash = new ArrayList<>();
		TreeSet<String> records = new TreeSet<>();
		for (Path p : classes) {
			String name = root.relativize(p).toString().replace(File.separatorChar, '.');
			name = name.substring(0, name.length() - ".class".length());
			if (!name.startsWith("ctrmap.") || name.startsWith("ctrmap.tests.")) {
				continue;
			}
			Class<?> c;
			try {
				c = Class.forName(name, false, RecordEqualityTest.class.getClassLoader());
			} catch (Throwable cannotLoad) {
				unread.add(name + ": " + cannotLoad);
				continue;
			}
			scanned++;
			if (declaresEquals(c)) {
				records.add(c.getSimpleName());
				if (!declaresHash(c)) {
					noHash.add(name);
				}
			}
		}
		check(unread.isEmpty(), "every compiled class could be read - one that could not is not one that passed: " + unread);
		check(scanned > 700 && records.contains("CameraData") && records.contains("NPC"),
				"control: " + scanned + " classes read, and the known records are among the " + records.size() + " found " + records);
		check(noHash.isEmpty(), "and every one of them declares hashCode: missing in " + noHash);
		return records;
	}

	/**
	 * No editor calls indexOf, lastIndexOf or contains on a list of records that compare by
	 * value: of two equal records it answers the first, whichever one is being edited.
	 */
	static void noEditorFindsARecordByEquality(TreeSet<String> records) throws Exception {
		System.out.println("--- no list of value records is searched by equality (F210)");
		String types = String.join("|", records);
		Pattern listOf = Pattern.compile("<\\s*(?:[\\w.]+\\.)?(?:" + types + ")\\s*>\\s+(\\w+)\\s*[=;]");
		String control = "public ArrayList<CameraData> camData;\n f.camData.set(f.camData.indexOf(cam), cam2);\n";
		check(!lookups(control, listOf, names(control, listOf)).isEmpty(),
				"control: a camera table searched with indexOf is seen");
		//A FIELD'S NAME IS EVERY FILE'S, A LOCAL'S ONLY ITS OWN: f.camData is searched from other files, but a
		//local list of records named 'out' made every 'out.contains(' in the tree a finding (F209's camera
		//document; InteriorWirer and BchMapModel search lists of ids and names)
		Pattern fieldOf = Pattern.compile("(?:public|private|protected)\\s+(?:static\\s+)?(?:final\\s+)?[\\w.]*<\\s*(?:[\\w.]+\\.)?(?:"
				+ types + ")\\s*>\\s+(\\w+)\\s*[=;]");
		String elsewhere = "List<String> out = new ArrayList<>();\n out.contains(name);\n";
		String local = "List<CameraData> out = new ArrayList<>();\n";
		check(lookups(elsewhere, listOf, union(names(local, fieldOf), names(elsewhere, listOf))).isEmpty()
				&& !lookups(elsewhere, listOf, names("private List<CameraData> out;\n", fieldOf)).isEmpty(),
				"control: a local list of records named out in one file leaves another file's out alone, a field does not");
		List<File> files = CollisionReadTest.javaFiles(new File("src/ctrmap"), new ArrayList<>());
		TreeSet<String> lists = new TreeSet<>();
		TreeSet<String> fields = new TreeSet<>();
		List<String> texts = new ArrayList<>();
		for (File f : files) {
			if (f.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			String t = SourceSeamTest.stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			texts.add(f.getName() + "\n" + t);
			lists.addAll(names(t, listOf));
			fields.addAll(names(t, fieldOf));
		}
		check(lists.contains("camData") && lists.contains("npcs") && fields.contains("camData"), "control: the camera and NPC lists are among the "
				+ lists.size() + " lists of records found " + lists + ", the camera table among the " + fields.size() + " fields");
		List<String> found = new ArrayList<>();
		for (String t : texts) {
			for (String hit : lookups(t, listOf, union(fields, names(t, listOf)))) {
				found.add(t.substring(0, t.indexOf('\n')) + ": " + hit);
			}
		}
		check(found.isEmpty() && files.size() > 300, "none is searched by equality in " + files.size() + " files: " + found);
	}

	static TreeSet<String> union(TreeSet<String> a, TreeSet<String> b) {
		TreeSet<String> out = new TreeSet<>(a);
		out.addAll(b);
		return out;
	}

	static TreeSet<String> names(String text, Pattern listOf) {
		TreeSet<String> out = new TreeSet<>();
		Matcher m = listOf.matcher(text);
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}

	static List<String> lookups(String text, Pattern listOf, TreeSet<String> lists) {
		List<String> out = new ArrayList<>();
		if (lists.isEmpty()) {
			return out;
		}
		Matcher m = Pattern.compile("\\b(?:" + String.join("|", lists) + ")\\s*\\.\\s*(?:indexOf|lastIndexOf|contains)\\s*\\([^)]*\\)").matcher(text);
		while (m.find()) {
			out.add(m.group());
		}
		return out;
	}

	static void theZoneRecordsCompareWhatTheyWrite() throws Exception {
		System.out.println("--- an NPC and a trigger compare the fields they write, and hash with them (F210)");
		List<String> wrong = new ArrayList<>();
		int moved = 0;
		for (Field fd : ZoneEntities.NPC.class.getFields()) {
			if (Modifier.isStatic(fd.getModifiers())) {
				continue;
			}
			ZoneEntities.NPC a = new ZoneEntities.NPC();
			ZoneEntities.NPC b = new ZoneEntities.NPC();
			if (fd.getType() == int.class) {
				fd.setInt(b, fd.getInt(b) + 3);
			} else if (fd.getType() == float.class) {
				fd.setFloat(b, fd.getFloat(b) + 3.5f);
			} else {
				continue;
			}
			moved++;
			boolean unwritten = fd.getName().equals("xFlags") || fd.getName().equals("yFlags") || fd.getName().startsWith("floatMotion");
			if (unwritten != a.equals(b)) {
				wrong.add(fd.getName() + (unwritten ? " is compared though nothing writes it" : " is not compared"));
			}
			if (a.equals(b) && a.hashCode() != b.hashCode()) {
				wrong.add(fd.getName() + " left them equal with different hashes");
			}
		}
		check(moved == 27 && wrong.isEmpty(), "each of the 27 NPC fields: the 23 written compare, the four that are not"
				+ " do not, and equal NPCs hash the same: " + wrong + " (" + moved + " moved)");
		ZoneEntities.Trigger t1 = new ZoneEntities.Trigger();
		ZoneEntities.Trigger t2 = new ZoneEntities.Trigger();
		check(t1.equals(t2) && t1.hashCode() == t2.hashCode(), "two new triggers are equal and hash the same");
		t2.u16val = 4;
		check(!t1.equals(t2), "and the last field is compared");
	}

	static void theNpcFormWritesTheNpcItShows() throws Exception {
		System.out.println("--- of two equal NPCs, the one shown is the one written (F210)");
		MapResizeTest.game("npctwins");
		byte[] amx = HandedGameTest.amx();
		byte[] bytes = new byte[12 + amx.length];
		MapResizeTest.putI32(bytes, 0, 8);
		System.arraycopy(amx, 0, bytes, 12, amx.length);
		ZoneEntities e = new ZoneEntities(bytes);
		//both uid 0: a zone an earlier delete left misnumbered, which the form offers to renumber
		//and the user may decline - the one way two NPCs in a zone can be equal
		for (int i = 0; i < 2; i++) {
			ZoneEntities.NPC n = new ZoneEntities.NPC();
			n.xTile = 4;
			n.yTile = 6;
			e.npcs.add(n);
		}
		check(e.npcs.get(0).equals(e.npcs.get(1)), "control: the two NPCs are equal");
		List<String> said = ctrmap.Ui.record();
		NPCEditForm form = new NPCEditForm(NpcEditFormGuardsTest.LOADED, NpcEditFormGuardsTest.TOOLS,
				NpcEditFormGuardsTest.REDRAW, NpcEditFormGuardsTest.NAVI, NpcEditFormGuardsTest.CENTRE,
				NpcEditFormGuardsTest.SAVER, NpcEditFormGuardsTest.SAVER, TestWindow.mTileMapPanel(), pane -> { }, UndoHistoryTest.overOneNumber(new int[]{0}));
		form.loadFromEntities(e, null);
		//as the NPC and warp tools do: two NPCs labelled by the same uid are one row to a combo
		//box, which selects the first equal label - and a zone in that state will not save
		form.showEntry(1);
		check(form.npcIndex == 1 && form.npc == e.npcs.get(1), "control: the form shows the second");
		((javax.swing.JFormattedTextField) MatrixEditFormGuardsTest.field(form, "x")).setValue(9);
		boolean saved = form.saveEntry();
		check(saved && e.npcs.get(1).xTile == 9 && e.npcs.get(0).xTile == 4,
				"an edit to the second lands on the second: x " + e.npcs.get(0).xTile + ", " + e.npcs.get(1).xTile
				+ " (asked " + said.size() + ")");
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
