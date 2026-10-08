package ctrmap.tests;

import ctrmap.formats.containers.AD;
import ctrmap.formats.propdata.ADPropRegistry;
import ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An area's prop registry keeps what it does not model, its order and its entries, and says when
 * it cannot (F084, F095).
 *
 * <p>WHY THIS SUITE EXISTS. The registry editor rebuilt an edited entry from defaults and copied
 * back only the fields it shows, so every edit reset the 36 bytes nobody models; the entry's
 * equals ignored those bytes and answered true for null and for anything else, so the change check
 * could not see it; the edit was a remove-then-put that moved the entry to the end; New entry put
 * every new entry under the key -1, so a second click replaced the first; write() forgot it was
 * modified before it wrote; a short read kept half a registry, and a reference held twice was
 * overwritten and then written back one entry short. And one of the editor's fields committed a
 * Long under a Short cast (F095).
 *
 * <p>ORDER: needs no dump and no display; writes only under {@link Scratch}. The editor itself is
 * a form over a 3D preview no suite here can make, so its half is read in its source.
 *
 * Usage: java ctrmap.tests.PropRegistryTest
 */
public class PropRegistryTest {

	static int fails = 0;
	static final int ENTRY = 80;

	public static void main(String[] args) throws Exception {
		File dir = Scratch.dir("ctrmap_propreg");
		FakeGameFiles game = new FakeGameFiles();

		System.out.println("--- a registry is read and written back byte for byte");
		byte[] three = registry(entry(5, 5, 0x11), entry(9, 9, 0x22), entry(7, 3, 0x33));
		ADPropRegistry reg = new ADPropRegistry(area(dir, "three", three, game));
		check(new ArrayList<>(reg.entries.keySet()).equals(Arrays.asList(5, 9, 7)), "control: three entries, in file order " + reg.entries.keySet());
		check(Arrays.equals(reg.serialize(), three), "and serialized they are the bytes they were read from");

		System.out.println("--- an entry is edited on a copy that keeps what nobody models (F084)");
		ADPropRegistryEntry nine = reg.entries.get(9);
		ADPropRegistryEntry edit = nine.copy();
		edit.model = 44;
		check(Arrays.equals(edit.u2, nine.u2) && edit.u2 != nine.u2 && edit.u2[0] == 0x22,
				"the copy carries the entry's own 36 unmodelled bytes, in an array of its own");
		check(nine.model == 9, "and editing the copy leaves the entry as it was");
		ADPropRegistryEntry sameButU2 = nine.copy();
		sameButU2.u2[5] ^= 1;
		check(!nine.equals(sameButU2), "an entry differing only in those bytes is not equal to it");
		check(!nine.equals(null) && !nine.equals("an entry"), "nor is null, nor something else");
		check(nine.equals(nine.copy()) && nine.hashCode() == nine.copy().hashCode(), "a copy is equal, with the same hash");
		check(new ADPropRegistryEntry().evtAnimations1[3] == -1, "a new entry's fourth event animation is FFFF like the other three");

		System.out.println("--- an edit keeps the entry's place, and a reference stays one entry's (F084)");
		edit.reference = 12;
		reg.replace(9, edit);
		check(new ArrayList<>(reg.entries.keySet()).equals(Arrays.asList(5, 12, 7)),
				"the edited entry is written where it was, not at the end: " + reg.entries.keySet());
		ADPropRegistryEntry clash = reg.entries.get(5).copy();
		clash.reference = 7;
		try {
			reg.replace(5, clash);
			check(false, "an edit onto another entry's reference is refused");
		} catch (IllegalArgumentException said) {
			check(true, "an edit onto another entry's reference is refused: " + said.getMessage());
		}

		System.out.println("--- two new entries are two entries (F084)");
		int a = reg.register(new ADPropRegistryEntry(), 0);
		int b = reg.register(new ADPropRegistryEntry(), 0);
		check(a != b && reg.entries.containsKey(a) && reg.entries.containsKey(b) && reg.entries.size() == 5,
				"each gets a free reference of its own (" + a + ", " + b + "), and both are kept: " + reg.entries.keySet());
		check(reg.register(new ADPropRegistryEntry(), 5) == 6, "one asked for at a taken reference gets the next free one");

		System.out.println("--- a registry that cannot be written back whole is not written (F084)");
		byte[] twice = registry(entry(5, 5, 0x11), entry(5, 6, 0x22));
		AD twiceArea = area(dir, "twice", twice, game);
		ADPropRegistry dup = new ADPropRegistry(twiceArea);
		dup.modified = true;
		try {
			dup.write();
			check(false, "a registry that held a reference twice refuses to be written back one entry short");
		} catch (IllegalStateException said) {
			check(said.getMessage().contains("more than once"),
					"a registry that held a reference twice refuses to be written back one entry short: " + said.getMessage());
		}
		check(dup.modified && Arrays.equals(new AD(twiceArea.getOriginFile(), game).getFile(0), twice),
				"and it is still marked changed, its area untouched");
		try {
			new ADPropRegistry(area(dir, "short", Arrays.copyOf(three, three.length - 10), game));
			check(false, "a registry cut short is refused");
		} catch (IllegalStateException said) {
			check(said.getMessage().contains("ends before"), "a registry cut short is refused, in words: " + said.getMessage());
		}
		AD lost = area(dir, "lost", three, game);
		ADPropRegistry gone = new ADPropRegistry(lost);
		gone.modified = true;
		File where = lost.getOriginFile();
		Files.delete(where.toPath());
		where.mkdirs();
		Files.write(new File(where, "in the way").toPath(), new byte[1]);
		try {
			gone.write();
			check(false, "a write that fails is said");
		} catch (RuntimeException said) {
			check(true, "a write that fails is said: " + said.getMessage());
		}
		check(gone.modified, "and the registry still knows it holds an edit nobody saved");

		System.out.println("--- the editor and the painter use all of it (F084, F095)");
		String editor = source("src/ctrmap/humaninterface/ADPropRegistryEditor.java");
		String save = CollisionReadTest.body(editor, "public boolean saveEntry(boolean dialog)");
		String add = CollisionReadTest.body(editor, "private void btnNewEntryActionPerformed(");
		check(save != null && save.contains("Ui.askToKeep") && save.indexOf("public ", 1) < 0
				&& add != null && add.contains("model.addElement") && add.indexOf("private void", 1) < 0,
				"control: saveEntry and New entry are found, each whole and alone");
		check(save != null && save.contains("e.copy()") && !save.contains("new ADPropRegistry.ADPropRegistryEntry()"),
				"saveEntry edits a copy of the entry, not one built from defaults");
		check(save != null && save.contains("reg.replace(") && !save.contains("entries.remove("),
				"and puts it back in its own place");
		check(save != null && !save.contains("(Short)") && !save.contains("(Integer)"),
				"and reads every field as a Number, never through a cast to the class one field commits");
		check(add != null && add.contains("reg.register(") && !add.contains("put(-1"), "New entry registers a free reference");
		String painter = source("src/ctrmap/humaninterface/TilePainterForm.java");
		String stage = CollisionReadTest.body(painter, "void stageRegistry()");
		check(stage != null && stage.contains("serialize()") && !stage.contains(".write(dos)"),
				"the map painter stages the registry's own bytes, not a second copy of its layout");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** One 80-byte entry: reference, model, the rest zero but for u2, which is {@code mark} throughout. */
	static byte[] entry(int reference, int model, int mark) {
		byte[] e = new byte[ENTRY];
		e[0] = (byte) reference;
		e[1] = (byte) (reference >> 8);
		e[2] = (byte) model;
		e[3] = (byte) (model >> 8);
		Arrays.fill(e, ENTRY - 0x24, ENTRY, (byte) mark);
		return e;
	}

	static byte[] registry(byte[]... entries) {
		byte[] r = new byte[4 + entries.length * ENTRY];
		r[0] = (byte) entries.length;
		for (int i = 0; i < entries.length; i++) {
			System.arraycopy(entries[i], 0, r, 4 + i * ENTRY, ENTRY);
		}
		return r;
	}

	static AD area(File dir, String name, byte[] registry, FakeGameFiles game) throws Exception {
		File f = new File(dir, name);
		Files.write(f.toPath(), HandedGameTest.container("AD", registry, new byte[4]));
		return new AD(f, game);
	}

	static String source(String path) throws Exception {
		return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
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
