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
 * <p>ORDER: needs no dump and no display; writes only under {@link Scratch}. Most of the editor's
 * half is read in its source; its UID refusal is asked of the form itself, built headless - the 3D
 * preview it holds stands without OpenGL (F077).
 *
 * Usage: java ctrmap.tests.NpcRegistryTest
 */
public class NpcRegistryTest {

	static {
		Headless.require();
	}

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

		System.out.println("--- the cap is kept at the one door every entry comes in by (census M.57)");
		NPCRegistry capped = new NPCRegistry(f, game);
		for (int uid = 2000; capped.entries.size() < NPCRegistry.MAX_ENTRIES; uid++) {
			capped.register(new NPCRegistryEntry(), uid);
		}
		check(capped.entries.size() == NPCRegistry.MAX_ENTRIES, "control: a registry filled to its cap (" + capped.entries.size() + ")");
		try {
			capped.register(new NPCRegistryEntry(), 9000);
			check(false, "an entry past the cap is refused by the registry itself");
		} catch (IllegalStateException refused) {
			check(refused.getMessage().contains("maximum of " + NPCRegistry.MAX_ENTRIES)
					&& capped.entries.size() == NPCRegistry.MAX_ENTRIES,
					"an entry past the cap is refused by the registry itself, naming the cap: " + refused.getMessage());
		}
		//three callers each checked the cap before calling, one against a literal 31 in the words it showed: no
		//production string states the cap as a number of its own
		java.util.regex.Pattern spelled = java.util.regex.Pattern.compile("\"[^\"\\n]*\\b" + NPCRegistry.MAX_ENTRIES
				+ "\\b[^\"\\n]*(?:NPC|registry|models)[^\"\\n]*\"|\"[^\"\\n]*(?:NPC|registry)[^\"\\n]*\\b" + NPCRegistry.MAX_ENTRIES + "\\b[^\"\\n]*\"");
		check(spelled.matcher("\"The registry's maximum capacity of 31 unique NPCs has been reached.\"").find()
				&& !spelled.matcher("\"maximum of \" + NPCRegistry.MAX_ENTRIES + \" NPCs\"").find(),
				"control: the scan sees the cap spelled in words and not the constant quoted");
		java.util.List<String> literal = new java.util.ArrayList<>();
		int scanned = 0;
		for (File src : CollisionReadTest.javaFiles(new File("src/ctrmap"), new java.util.ArrayList<>())) {
			if (src.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			scanned++;
			if (spelled.matcher(SourceSeamTest.stripComments(source(src.getPath()))).find()) {
				literal.add(src.getName());
			}
		}
		check(literal.isEmpty() && scanned > 100, "no production string spells the registry's cap as "
				+ NPCRegistry.MAX_ENTRIES + " (" + literal + " in " + scanned + " files)");

		System.out.println("--- a registry's model is decoded by the registry alone (census M.58)");
		//the NPC registry editor's preview, the prop registry editor's, the prop form's failsafe and the prop drawn
		//after it is registered each decoded the model themselves, from the global workspace - one with no
		//empty-model guard, one printing its failure to a console - beside the registries' guarded loaders
		//BY SHAPE, QUALIFIED OR NOT: the first cut matched the short names only, and a decode spelled
		//new ctrmap.formats.h3d.BCHFile(new ctrmap.formats.containers.MM(...)) walked past it
		java.util.regex.Pattern decode = java.util.regex.Pattern.compile(
				"new\\s+(?:[\\w.]*\\.)?BCHFile\\s*\\(\\s*new\\s+(?:[\\w.]*\\.)?(?:MM|BM)\\s*\\(");
		check(decode.matcher("BCHFile b = new BCHFile(new BM(f, Workspace.session()).getFile(0));").find()
				&& decode.matcher("x(new ctrmap.formats.h3d.BCHFile(new ctrmap.formats.containers.MM(f, s).getFile(0)))").find()
				&& !decode.matcher("BCHFile b = new BCHFile(bytes);").find(),
				"control: the scan sees a container model decoded by hand, its classes named short or in full");
		java.util.Map<String, Integer> decoders = new java.util.TreeMap<>();
		for (File src : CollisionReadTest.javaFiles(new File("src/ctrmap"), new java.util.ArrayList<>())) {
			if (src.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			java.util.regex.Matcher m = decode.matcher(SourceSeamTest.stripComments(source(src.getPath())));
			while (m.find()) {
				decoders.merge(src.getName(), 1, Integer::sum);
			}
		}
		check(decoders.toString().equals("{ADPropRegistry.java=1, NPCRegistry.java=1}"),
				"a MoveModels or BuildingModels model is decoded only by its registry: " + decoders);
		String preview = CollisionReadTest.body(source("src/ctrmap/humaninterface/NPCRegistryEditor.java"), "void showEntry(");
		check(preview != null && preview.contains("customH3DPreview1.loadModel(reg.loadFreshModel(entry.uid))"),
				"and the NPC registry editor previews an entry through its registry");

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
		//census M.77: with nothing selected Remove threw out of its listener, and the entry it removed stayed the one
		//shown, so moving the selection saved it back into a registry that no longer holds it
		int guarded = rem == null ? -1 : rem.indexOf("if (index < 0");
		int forgotten = rem == null ? -1 : rem.indexOf("e = null;");
		int removed = rem == null ? -1 : rem.indexOf("reg.remove(");
		check(guarded > 0 && removed > guarded, "Remove with nothing selected removes nothing (" + guarded + ", " + removed + ")");
		check(forgotten > 0 && removed > forgotten, "and the entry it removes stops being the one shown first ("
				+ forgotten + ", " + removed + ")");
		//census L.44: the shadow box with no entry shown dereferenced the null entry
		String shadow = CollisionReadTest.body(editor, "private void shadowActionPerformed(");
		int asks = shadow == null ? -1 : shadow.indexOf("if (e == null)");
		int reads = shadow == null ? -1 : shadow.indexOf("e.shadowEnabled");
		check(asks > 0 && reads > asks, "the shadow box asks for an entry before it reads one (" + asks + ", " + reads + ")");
		//census L.46: the ESPICA chooser opens in ESPICA's folder, not the tileset's
		String settings = source("src/ctrmap/humaninterface/WorkspaceSettings.java");
		String spica = CollisionReadTest.body(settings, "private void btnSetSpicaActionPerformed(");
		check(spica != null && spica.contains("new JFileChooser(new File(spicaField.getText())") && !spica.contains("tilesetPath"),
				"the ESPICA chooser opens in ESPICA's folder");

		System.out.println("--- the editor refuses a UID another entry holds, and only that");
		//the 2026-10-15 shared sweep, NPCRegistryEditor 102, negate-if, SURVIVED: the editor's half was read in its
		//source and no suite saved an entry through it. Inverted, an entry kept or moved to a free UID is refused as
		//taken, and one moved onto another entry's UID gets past the form and throws out of the registry, unsaid.
		FakeGameFiles two = HandedGameTest.npcGame("hero", "rival").plant(ArchiveType.NPC_REGISTRIES, 3, entries);
		NPCRegistry held = new NPCRegistry(two.staged(ArchiveType.NPC_REGISTRIES, 3), two);
		ctrmap.humaninterface.NPCRegistryEditor regedit = new ctrmap.humaninterface.NPCRegistryEditor();
		regedit.loadRegistry(held);
		regedit.showEntry(7);
		java.lang.reflect.Field box = ctrmap.humaninterface.NPCRegistryEditor.class.getDeclaredField("uid");
		box.setAccessible(true);
		javax.swing.JSpinner uidBox = (javax.swing.JSpinner) box.get(regedit);
		String taken = saved(regedit, uidBox, 0);
		check(taken.equals("false, [UID not unique: The specified UID is already registered. Please use another one.]")
				&& held.entries.keySet().equals(new java.util.HashSet<>(Arrays.asList(0, 7)))
				&& held.entries.get(0).model == 0,
				"an entry moved onto the UID another holds is refused, in words, and neither is written: " + taken + ", "
				+ held.entries.keySet());
		String kept = saved(regedit, uidBox, 7);
		check(kept.equals("true, []"), "an entry that keeps its own UID is not refused: " + kept);
		String moved = saved(regedit, uidBox, 8);
		check(moved.equals("true, []") && held.entries.keySet().equals(new java.util.HashSet<>(Arrays.asList(0, 8)))
				&& held.entries.get(8).model == 1,
				"and one moved to a free UID is saved there: " + moved + ", " + held.entries.keySet());
		//Add on a registry with room adds (the 2026-10-15 shared sweep, NPCRegistryEditor 362, negate-if, SURVIVED: the
		//editor's Add was read in its source only) - inverted, a registry with room was refused as full
		javax.swing.AbstractButton addButton = ItemEditTest.button(regedit, "Add");
		java.util.Set<Integer> held0 = new java.util.HashSet<>(held.entries.keySet());
		java.util.List<String> said = ctrmap.Ui.record();
		String pressed;
		try {
			addButton.doClick();
			pressed = "added";
		} catch (RuntimeException threw) {
			pressed = "threw " + threw;
		} finally {
			ctrmap.Ui.stopRecording();
		}
		java.util.Set<Integer> grew = new java.util.HashSet<>(held.entries.keySet());
		grew.removeAll(held0);
		check(pressed.equals("added") && said.isEmpty() && grew.size() == 1 && !grew.contains(0),
				"Add on a registry with room registers one entry under a free UID, and says nothing: " + pressed + ", "
				+ said + ", new " + grew);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** The registry editor's save with {@code uid} in its UID box: what it answered and what it said, or what it threw. */
	static String saved(ctrmap.humaninterface.NPCRegistryEditor editor, javax.swing.JSpinner uidBox, int uid) {
		uidBox.setValue(uid);
		java.util.List<String> said = ctrmap.Ui.record();
		try {
			return editor.saveEntry(false) + ", " + said;
		} catch (RuntimeException threw) {
			return "threw " + threw + ", " + said;
		} finally {
			ctrmap.Ui.stopRecording();
		}
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
