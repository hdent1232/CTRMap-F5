package ctrmap.tests;

import ctrmap.formats.npcreg.NPCRegistry;
import ctrmap.formats.npcreg.NPCRegistry.NPCRegistryEntry;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * An area's NPC registry is written back as it was read - every entry, in order, and the bytes
 * after them - and an edit changes only what was edited (F081).
 *
 * <p>WHY THIS SUITE EXISTS. The registry was a HashMap by UID that every caller put into: it was
 * written in hash order, a UID held twice (retail carries UID-0 dummies) was dropped when read and
 * again when written, and the bytes after the last whole entry were lost. The editor rebuilt an
 * edited entry from defaults and copied back only what it shows, so eight fields took the defaults
 * on every edit, and equals left out exactly those eight; its Add put the new entry under UID 0,
 * over the dummy. A registry that could not be read was logged and stood in for by an empty one.
 *
 * <p>ORDER: needs no dump and no display; writes only under {@link Scratch}. The editor is a form
 * over a 3D preview no suite here can make, so its half is read in its source.
 *
 * Usage: java ctrmap.tests.NpcRegistryTest
 */
public class NpcRegistryTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		System.out.println("--- a registry is written back exactly as it was read (F081)");
		NPCRegistryEntry dummyA = HandedGameTest.npcEntry(0, 0);
		dummyA.u1 = 0;
		NPCRegistryEntry seven = HandedGameTest.npcEntry(7, 1);
		seven.u5 = 9;
		seven.u16 = 3;
		NPCRegistryEntry dummyB = HandedGameTest.npcEntry(0, 0);
		dummyB.u13 = 4;
		byte[] entries = HandedGameTest.registryBytes(dummyA, seven, dummyB);
		byte[] onDisk = Arrays.copyOf(entries, entries.length + 3);
		onDisk[onDisk.length - 1] = 0x5A;
		FakeGameFiles game = HandedGameTest.npcGame("hero", "rival").plant(ArchiveType.NPC_REGISTRIES, 3, onDisk);
		File f = game.staged(ArchiveType.NPC_REGISTRIES, 3);
		NPCRegistry reg = new NPCRegistry(f, game);
		check(reg.entries.keySet().equals(new java.util.HashSet<>(Arrays.asList(0, 7))),
				"control: two UIDs answer, the UID-0 dummy held twice (" + reg.entries.keySet() + ")");
		reg.modified = true;
		check(reg.store(), "a store is made");
		check(Arrays.equals(Files.readAllBytes(f.toPath()), onDisk),
				"and the file is the bytes it was read from: both dummies, in their order, and the 3 bytes after them");

		System.out.println("--- an edit changes only what was edited, where it was (F081)");
		NPCRegistryEntry e = reg.entries.get(7);
		NPCRegistryEntry edit = e.copy();
		edit.collW = 3;
		check(edit.u5 == 9 && edit.u16 == 3 && e.collW != 3, "the copy keeps the fields no form shows, and the entry is untouched");
		NPCRegistryEntry onlyU12 = e.copy();
		onlyU12.u12 = 1;
		check(!e.equals(onlyU12), "an entry differing only in a field no form shows is not equal to it");
		check(!e.equals(null) && !e.equals("an entry") && e.equals(e.copy()) && e.hashCode() == e.copy().hashCode(),
				"null and other things are not equal; a copy is, with the same hash");
		reg.replace(7, edit);
		reg.store();
		NPCRegistryEntry editedSeven = seven.copy();
		editedSeven.collW = 3;
		byte[] want = Arrays.copyOf(HandedGameTest.registryBytes(dummyA, editedSeven, dummyB), onDisk.length);
		want[want.length - 1] = 0x5A;
		check(Arrays.equals(Files.readAllBytes(f.toPath()), want),
				"stored, the edited entry is in its own place with only its width changed");
		NPCRegistryEntry fresh = new NPCRegistryEntry();
		check(fresh.u11 == 2 && fresh.u12 == 0, "a new entry's u11 is set and u12 is 0, the right way round (u11 "
				+ fresh.u11 + ", u12 " + fresh.u12 + ")");

		System.out.println("--- a new entry gets a UID of its own (F081)");
		int added = reg.register(new NPCRegistryEntry(), 1);
		check(added != 0 && added != 7 && reg.entries.get(0) != null && reg.entries.get(0).u13 == 4,
				"Add takes a free UID (" + added + ") and the UID-0 dummy is still there");
		int again = reg.register(new NPCRegistryEntry(), 1);
		int asked7 = reg.register(new NPCRegistryEntry(), 7);
		check(again != added && asked7 != 7 && reg.entries.get(7).collW == 3 && reg.entries.size() == 5,
				"a second Add takes another (" + again + "), and one asked for at a taken UID the next free one ("
				+ asked7 + "), with UID 7 still the edited entry: " + reg.entries.keySet());
		try {
			reg.entries.put(99, new NPCRegistryEntry());
			check(false, "the map by UID cannot be put into past the registry");
		} catch (UnsupportedOperationException readOnly) {
			check(true, "the map by UID cannot be put into past the registry");
		}
		reg.remove(0);
		check(reg.entries.get(0) != null && reg.entries.get(0).u13 == 0,
				"removing UID 0 removes the one it answered for, and the other dummy answers now");

		System.out.println("--- what cannot be read or written is said (F081)");
		try {
			new NPCRegistry(new File(f.getParentFile(), "not there"), game);
			check(false, "a registry that cannot be read is refused");
		} catch (IllegalStateException said) {
			check(said.getMessage().contains("could not be read"), "a registry that cannot be read is refused, in words: " + said.getMessage());
		}
		NPCRegistry lost = new NPCRegistry(f, game);
		lost.modified = true;
		byte[] before = Files.readAllBytes(f.toPath());
		Files.delete(f.toPath());
		f.mkdirs();
		Files.write(new File(f, "in the way").toPath(), new byte[1]);
		try {
			lost.store();
			check(false, "a store that fails is said");
		} catch (java.io.IOException | RuntimeException said) {
			check(true, "a store that fails is said: " + said);
		}
		check(lost.modified, "and the registry still knows it holds an edit nobody saved");
		check(before.length > 0, "control: there was a registry to lose");

		System.out.println("--- the editor and the NPC form go through the registry (F081)");
		String editor = source("src/ctrmap/humaninterface/NPCRegistryEditor.java");
		String save = CollisionReadTest.body(editor, "public boolean saveEntry(boolean dialog)");
		String add = CollisionReadTest.body(editor, "private void btnAddActionPerformed(");
		String rem = CollisionReadTest.body(editor, "private void btnRemoveActionPerformed(");
		check(save != null && save.contains("Ui.askToKeep") && save.indexOf("public ", 1) < 0
				&& add != null && add.contains("model.addElement") && add.indexOf("private void", 1) < 0
				&& rem != null && rem.contains("entryList.getSelectedIndex()") && rem.indexOf("private void", 1) < 0,
				"control: saveEntry, Add and Remove are found, each whole and alone");
		check(save != null && save.contains("e.copy()") && !save.contains("new NPCRegistry.NPCRegistryEntry()"),
				"saveEntry edits a copy of the entry, not one built from defaults");
		check(save != null && save.contains("reg.replace(") && !save.contains("entries.remove("), "and puts it back in its own place");
		check(add != null && add.contains("reg.register(") && add.contains("mapModel(") && add.contains("MAX_ENTRIES"),
				"Add registers a free UID, maps its model, and is capped by the registry's own limit");
		check(rem != null && rem.contains("reg.remove("), "Remove goes through the registry");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
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
