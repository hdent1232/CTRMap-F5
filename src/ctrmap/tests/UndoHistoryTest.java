package ctrmap.tests;

import ctrmap.formats.tilemap.Tilemap;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.humaninterface.FlatCopy;
import ctrmap.humaninterface.UndoHistory;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * One undo model, watching documents rather than trusting editors to record (F209).
 *
 * <p>WHY THIS SUITE EXISTS. Undo was four unrelated mechanisms in four editors - a static tile history,
 * two private snapshot stacks and the item table's own - and none in nineteen others, and Ctrl+Z always
 * went to the tile history. The history here reads its documents around each user gesture, so what it
 * must get right is: a gesture's change is one edit, opening is not an edit, an undo puts back exactly
 * what was there (a half-made warp included), a history never puts an edit into a document that is no
 * longer open, and the gesture hook really runs where the event loop runs.
 *
 * <p>ORDER: needs no dump. The gesture section posts real mouse events through the AWT event queue.
 *
 * Usage: java ctrmap.tests.UndoHistoryTest
 */
public class UndoHistoryTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Headless.require();
		aGestureIsAnEdit();
		oneGestureAcrossDocumentsIsOneEdit();
		aWriteThatIsAnEditJoinsItsGesture();
		aPlaceThatRefusesToBeReadIsSaid();
		anEditorAddsItsOwnDocuments();
		aDocumentThatCannotSeeItselfIsRefused();
		aFormsWidgetsAreADocument();
		theRegistriesAndPropsComeBack();
		theHistoryKeepsTheNewestHundred();
		anotherScopeStartsAgain();
		anEditOnSomethingClosedIsRefused();
		theGestureHookRunsInTheEventLoop();
		ctrlZGoesToTheEditorBeingWorkedIn();
		aRecordIsCopiedExactly();
		theZonesEntitiesComeBack();
		theZonesScriptComesBack();
		aRegionsTilesComeBack();
		anotherZoneOrMapStartsAgain();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** What {@code made} was refused with, or what it did instead. */
	static String refusalOf(Runnable made) {
		try {
			made.run();
			return "(nothing was refused)";
		} catch (IllegalArgumentException refused) {
			return refused.getMessage();
		} catch (RuntimeException other) {
			return "not refused in words: " + other;
		}
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}

	/** A document holding one number, for the suites that need a history and nothing else. */
	static final class Number implements UndoHistory.Document<Integer> {

		final int[] value;
		final String name;
		int puts;

		Number(int[] value, String name) {
			this.value = value;
			this.name = name;
		}

		@Override
		public Object subject() {
			return value;
		}

		@Override
		public Integer take() {
			return value[0];
		}

		@Override
		public void put(Integer state) {
			value[0] = state;
			puts++;
		}

		@Override
		public boolean same(Integer a, Integer b) {
			return a.intValue() == b.intValue();
		}

		@Override
		public String what() {
			return name;
		}
	}

	/** A history over one number, its scope fixed: what the tool row and other suites drive. */
	static UndoHistory overOneNumber(int[] number) {
		final Number doc = new Number(number, "the number");
		final Object scope = new Object();
		UndoHistory h = new UndoHistory(() -> Collections.singletonList(scope),
				() -> Collections.<UndoHistory.Document<?>>singletonList(doc));
		h.settle();   //what it is now is where it starts
		return h;
	}

	/**
	 * A whole zone's documents, its entities and the map view's regions' tiles, as the window's history and
	 * the map view's together hold them - for the suites that build a zone's history in one call.
	 */
	static java.util.function.Supplier<List<UndoHistory.Document<?>>> zone(ctrmap.LoadedZone lz,
			java.util.function.Supplier<ctrmap.humaninterface.TileMapPanel> map, Runnable entitiesShown, Runnable mapShown) {
		java.util.function.Supplier<List<UndoHistory.Document<?>>> entities = ctrmap.humaninterface.ZoneDocuments.entities(lz, entitiesShown);
		java.util.function.Supplier<List<UndoHistory.Document<?>>> tiles = ctrmap.humaninterface.ZoneDocuments.tiles(map, mapShown);
		return () -> {
			List<UndoHistory.Document<?>> out = new ArrayList<>(entities.get());
			out.addAll(tiles.get());
			return out;
		};
	}

	static void aGestureIsAnEdit() {
		System.out.println("--- what a gesture changed is one edit; being opened is not one");
		int[] n = {3};
		UndoHistory h = overOneNumber(n);
		check(!h.canUndo() && !h.canRedo(), "control: a document seen for the first time is no edit");
		check(!h.settle() && !h.canUndo(), "and settling with nothing changed records nothing");
		n[0] = 4;
		check(h.settle() && h.canUndo() && "the number".equals(h.undoName()), "a change is an edit, named for what changed");
		check(h.undo() && n[0] == 3 && h.canRedo() && !h.canUndo(), "undo puts back what was there (" + n[0] + ")");
		check(!h.settle(), "and taking it back is not itself an edit");
		check(h.redo() && n[0] == 4, "redo puts it again (" + n[0] + ")");
		h.undo();
		n[0] = 9;
		h.settle();
		check(!h.canRedo() && h.canUndo(), "a new edit after an undo drops what could have been redone");
		n[0] = 11;
		check(h.undo() && n[0] == 9, "and an unrecorded change is the newest edit when Undo is pressed: it is the one taken back ("
				+ n[0] + ")");
	}

	static void oneGestureAcrossDocumentsIsOneEdit() {
		System.out.println("--- one gesture that changes two documents is one edit");
		final int[] a = {1};
		final int[] b = {2};
		final Number da = new Number(a, "the first");
		final Number db = new Number(b, "the second");
		final Object scope = new Object();
		UndoHistory h = new UndoHistory(() -> Collections.singletonList(scope), () -> Arrays.<UndoHistory.Document<?>>asList(da, db));
		h.settle();
		a[0] = 10;
		b[0] = 20;
		h.settle();
		check("the first and 1 more".equals(h.undoName()), "named for both: " + h.undoName());
		h.undo();
		check(a[0] == 1 && b[0] == 2 && !h.canUndo(), "and one undo takes both back (" + a[0] + ", " + b[0] + ")");
		a[0] = 5;
		h.settle();
		h.undo();
		check(db.puts == 1, "an edit to one document puts back that document only (the second was put " + db.puts + " time(s))");
	}

	/**
	 * A place whose read refuses - a container's subfile, which refuses in words now rather than answering null
	 * (census M.4) - is said with its reason and the history's own words: nothing was written.
	 */
	static void aPlaceThatRefusesToBeReadIsSaid() throws Exception {
		System.out.println("--- a place that refuses to be read is said, and nothing is written (census M.4)");
		final boolean[] wrote = {false};
		UndoHistory.Place refusing = new UndoHistory.Place() {
			@Override
			public byte[] read() {
				throw new IllegalStateException("planted: subfile 3 of 14 could not be read");
			}

			@Override
			public void write(byte[] data) {
				wrote[0] = true;
			}

			@Override
			public String what() {
				return "the area's camera table";
			}
		};
		Object scope = new Object();
		UndoHistory h = new UndoHistory(() -> Collections.singletonList(scope), () -> Collections.<UndoHistory.Document<?>>emptyList());
		String said = null;
		try {
			h.write(refusing, new byte[]{1});
		} catch (IllegalStateException refused) {
			said = refused.getMessage();
		}
		check(said != null && said.contains("the area's camera table could not be read (planted: subfile 3 of 14")
				&& said.contains("nothing was written"), "the refusal is said with its reason and that nothing was written: " + said);
		check(!wrote[0] && !h.canUndo(), "and nothing was written, and there is nothing to take back");
		//and one that reads before the write and not after it: written, and said to be one that cannot be taken back
		//(the 2026-10-15 shared sweep, UndoHistory 254, drop-throw, SURVIVED: every place a suite wrote read back)
		final boolean[] written = {false};
		UndoHistory.Place lost = new UndoHistory.Place() {
			@Override
			public byte[] read() {
				if (written[0]) {
					throw new IllegalStateException("planted: the file went with the write");
				}
				return new byte[]{0};
			}

			@Override
			public void write(byte[] data) {
				written[0] = true;
			}

			@Override
			public String what() {
				return "the area's camera table";
			}
		};
		String afterSaid;
		try {
			h.write(lost, new byte[]{1});
			afterSaid = "(nothing was said)";
		} catch (IllegalStateException refused) {
			afterSaid = refused.getMessage();
		}
		check(afterSaid.contains("the area's camera table was written and cannot be read back (planted: the file went")
				&& afterSaid.contains("so it cannot be taken back") && !h.canUndo(),
				"a write whose place cannot be read back is said to be one that cannot be taken back: " + afterSaid);
		//and a zone file place whose write the disk refuses says so and goes no further: not listed as an edit, not
		//reported written (the 2026-10-15 shared sweep, ZoneDocuments 81, drop-throw, SURVIVED: no suite wrote one that
		//failed). A folder with something in it stands where the file goes.
		java.io.File folder = Scratch.dir("ctrmap_zonedoc_unwritable");
		java.io.File inTheWay = new java.io.File(folder, "zone.bin");
		inTheWay.mkdir();
		java.nio.file.Files.write(new java.io.File(inTheWay, "held").toPath(), new byte[]{1});
		ctrmap.WorkspaceSession ws = new ctrmap.WorkspaceSession(Scratch.dir("ctrmap_zonedoc_ws"), folder,
				ctrmap.gamedef.GameType.ORAS, null);
		final boolean[] told = {false};
		UndoHistory.Place zoneFile = ctrmap.humaninterface.ZoneDocuments.file(inTheWay, "the zone's file", ws,
				() -> told[0] = true);
		String fileSaid;
		try {
			zoneFile.write(new byte[]{2});
			fileSaid = "(nothing was said)";
		} catch (IllegalStateException refused) {
			fileSaid = refused.getMessage();
		}
		check(fileSaid.startsWith("could not write zone.bin: ") && !told[0]
				&& !ws.persistPaths().contains(inTheWay.getAbsolutePath()),
				"a zone file the disk will not take is refused naming it, not listed as an edit, not reported written: "
				+ fileSaid + ", told " + told[0]);
	}

	/** Bytes held in memory as a place: a written file or subfile, for the suites with no workspace. */
	static final class Held implements UndoHistory.Place {

		byte[] bytes;
		final String name;

		Held(byte[] bytes, String name) {
			this.bytes = bytes;
			this.name = name;
		}

		@Override
		public byte[] read() {
			return bytes == null ? null : bytes.clone();
		}

		@Override
		public void write(byte[] data) {
			bytes = data.clone();
		}

		@Override
		public String what() {
			return name;
		}
	}

	/**
	 * A write that IS an edit - a prop's textures imported into its area, the story line an NPC wizard adds -
	 * is declared to the history and joined with what the same gesture changed in the documents (F209), so
	 * one Undo takes back a registration whole. It is put back only over what it left there: a save or
	 * another editor that wrote the place since is not overwritten.
	 */
	static void aWriteThatIsAnEditJoinsItsGesture() {
		System.out.println("--- a write that is an edit joins its gesture, and is put back only over what it left (F209)");
		int[] n = {1};
		UndoHistory h = overOneNumber(n);
		Held textures = new Held(new byte[]{1, 2}, "the area's prop textures");
		h.write(textures, new byte[]{1, 2, 3});
		n[0] = 2;   //the registry entry the same registration added
		check(h.settle() && "the area's prop textures and 1 more".equals(h.undoName()),
				"a write and the document its gesture changed are ONE edit, named for the write: " + h.undoName());
		check(h.undo() && n[0] == 1 && Arrays.equals(textures.bytes, new byte[]{1, 2}) && !h.canUndo(),
				"one Undo takes back both (" + n[0] + ", " + Arrays.toString(textures.bytes) + ")");
		check(h.redo() && n[0] == 2 && Arrays.equals(textures.bytes, new byte[]{1, 2, 3}), "and one Redo puts both again");
		textures.bytes = new byte[]{9};   //a save, or another editor, wrote it since
		String said = "(nothing was thrown)";
		try {
			h.undo();
		} catch (IllegalStateException refused) {
			said = refused.getMessage();
		}
		check(said.contains("has been written since") && n[0] == 2 && Arrays.equals(textures.bytes, new byte[]{9}) && h.canUndo(),
				"a place written since refuses the Undo, puts back nothing, and keeps the edit: " + said);

		int[] m = {5};
		UndoHistory g = overOneNumber(m);
		Held story = new Held(new byte[]{7}, "story text 12");
		g.write(story, new byte[]{7, 8});
		check(!g.settle() && "story text 12".equals(g.undoName()),
				"a write whose gesture changed no document is an edit of its own: " + g.undoName());
		check(g.undo() && Arrays.equals(story.bytes, new byte[]{7}), "and Undo puts its bytes back");

		int[] k = {0};
		UndoHistory f = overOneNumber(k);
		Held first = new Held(new byte[]{1}, "the first file");
		Held second = new Held(new byte[]{2}, "the second file");
		f.write(first, new byte[]{10});
		f.write(second, new byte[]{20});
		k[0] = 1;
		f.settle();
		first.bytes = new byte[]{99};   //the OLDER write's place was written since: it is taken back last
		try {
			f.undo();
		} catch (IllegalStateException expected) {
			//said by act() when pressed; here only what it left behind is asked
		}
		check(Arrays.equals(second.bytes, new byte[]{20}) && k[0] == 1,
				"two writes in one gesture are taken back all or none: the newer is on again when the older refuses ("
				+ Arrays.toString(second.bytes) + ")");
		first.bytes = new byte[]{10};
		check(f.undo() && Arrays.equals(first.bytes, new byte[]{1}) && Arrays.equals(second.bytes, new byte[]{2}) && k[0] == 0,
				"control: with nothing written since, both come back");
		second.bytes = new byte[]{77};   //the NEWER write's place was written since: it is put again last
		try {
			f.redo();
		} catch (IllegalStateException expected) {
			//as above
		}
		check(Arrays.equals(first.bytes, new byte[]{1}) && k[0] == 0,
				"and put again all or none: the older is off again when the newer refuses (" + Arrays.toString(first.bytes) + ")");

		final Object[] scope = {new Object()};
		UndoHistory moved = new UndoHistory(() -> java.util.Collections.singletonList(scope[0]),
				() -> java.util.Collections.<UndoHistory.Document<?>>emptyList());
		moved.settle();
		scope[0] = new Object();   //another workspace opened, and nothing settled since
		Held mass = new Held(new byte[]{4}, "a mass edit's file");
		moved.write(mass, new byte[]{5});
		check(moved.undo() && Arrays.equals(mass.bytes, new byte[]{4}),
				"an edit added after the scope moved, before any settle, is kept and taken back (" + Arrays.toString(mass.bytes) + ")");

		Held gone = new Held(null, "a file that is not there");
		String why = "(nothing was thrown)";
		try {
			f.write(gone, new byte[]{1});
		} catch (IllegalStateException refused) {
			why = refused.getMessage();
		}
		check(why.contains("could not be read") && gone.bytes == null,
				"a place that cannot be read is refused before anything is written: " + why);
	}

	/**
	 * An editor handed a history adds its own documents with watch (F209): the painter its canvas. Whoever
	 * built the history keeps no list of them, so an editor added later cannot be missing from one. Two
	 * documents over one object are one document - otherwise an edit to it would be recorded, and taken
	 * back, twice.
	 */
	static void anEditorAddsItsOwnDocuments() {
		System.out.println("--- an editor adds its own documents; two over one object are one");
		int[] n = {3};
		UndoHistory h = overOneNumber(n);
		int[] canvas = {7};
		final Number painted = new Number(canvas, "the canvas");
		h.watch(() -> Collections.<UndoHistory.Document<?>>singletonList(painted));
		check(!h.settle() && !h.canUndo(), "control: a document added is noted, not an edit");
		canvas[0] = 8;
		check(h.settle() && "the canvas".equals(h.undoName()), "a change to it is an edit of this history: " + h.undoName());
		check(h.undo() && canvas[0] == 7, "and Undo takes it back (" + canvas[0] + ")");
		final Number again = new Number(n, "the number again");
		h.watch(() -> Collections.<UndoHistory.Document<?>>singletonList(again));
		n[0] = 4;
		h.settle();
		check("the number".equals(h.undoName()), "a second document over the number is the same document, not one more part: "
				+ h.undoName());
		h.undo();
		check(n[0] == 3 && again.puts == 0, "so Undo puts the number back once (the second document was put " + again.puts
				+ " time(s))");
		String said = "(nothing was thrown)";
		try {
			h.watch(null);
		} catch (IllegalArgumentException refused) {
			said = refused.getMessage();
		}
		check(said.contains("handed no documents"), "watch refuses nothing to watch: " + said);
	}

	/**
	 * A document whose comparison calls two readings of itself different, with nothing changed, is refused
	 * the first time it is seen (F209). The collision editor's first document compared fresh arrays by
	 * identity: every settle recorded a phantom edit, and Undo took that back instead of the change.
	 */
	static void aDocumentThatCannotSeeItselfIsRefused() {
		System.out.println("--- a document that reads differently twice with nothing changed is refused, by name");
		final int[] n = {1};
		UndoHistory.Document<int[]> byIdentity = new UndoHistory.Document<int[]>() {
			@Override
			public Object subject() {
				return n;
			}

			@Override
			public int[] take() {
				return n.clone();
			}

			@Override
			public void put(int[] state) {
				n[0] = state[0];
			}

			@Override
			public boolean same(int[] a, int[] b) {
				return a == b;   //the mistake: two readings are two arrays
			}

			@Override
			public String what() {
				return "the counter";
			}
		};
		final Object scope = new Object();
		UndoHistory h = new UndoHistory(() -> Collections.singletonList(scope),
				() -> Collections.<UndoHistory.Document<?>>singletonList(byIdentity));
		String said = "(nothing was thrown)";
		try {
			h.settle();
		} catch (IllegalStateException refused) {
			said = refused.getMessage();
		}
		check(said.contains("the counter") && said.contains("reads differently twice"),
				"refused the first time it is seen, naming it: " + said);
		int[] m = {1};
		UndoHistory fine = overOneNumber(m);
		check(!fine.settle() && !fine.canUndo(), "control: a document that compares content is seen, and nothing is recorded");
	}

	/**
	 * What a form's widgets hold is a document (F209), for a form that writes them only when saved: a
	 * spinner, a formatted field, a text field, a tick box and a drop-down each put back as they were; a
	 * widget that holds no value is refused when the document is made, by name.
	 */
	static void aFormsWidgetsAreADocument() {
		System.out.println("--- what a form's widgets hold is a document, every kind of widget");
		javax.swing.JSpinner spin = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(3, 0, 99, 1));
		javax.swing.JFormattedTextField formatted = new javax.swing.JFormattedTextField(1.5f);
		javax.swing.JTextField text = new javax.swing.JTextField("north");
		javax.swing.JCheckBox tick = new javax.swing.JCheckBox("on", true);
		javax.swing.JComboBox<String> pick = new javax.swing.JComboBox<>(new String[]{"a", "b", "c"});
		pick.setSelectedIndex(1);
		final ctrmap.humaninterface.WidgetDocument doc = new ctrmap.humaninterface.WidgetDocument(spin, "the form",
				spin, formatted, text, tick, pick);
		//one with nothing it shows, no name, or no widgets is refused in words (the 2026-10-15 shared sweep,
		//WidgetDocument 37, drop-throw, SURVIVED: every one a suite built was whole)
		String noSubject = refusalOf(() -> new ctrmap.humaninterface.WidgetDocument(null, "the form", spin));
		String noName = refusalOf(() -> new ctrmap.humaninterface.WidgetDocument(spin, null, spin));
		String noWidgets = refusalOf(() -> new ctrmap.humaninterface.WidgetDocument(spin, "the form",
				(javax.swing.JComponent[]) null));
		//and a history handed no edit refuses it (the 2026-10-15 shared sweep, UndoHistory 223, dropped and swallowed,
		//SURVIVED): kept, a null edit was pushed, and Undo threw on it later with nothing to say what
		UndoHistory empty = new UndoHistory(() -> Collections.singletonList(new Object()),
				() -> Collections.<UndoHistory.Document<?>>emptyList());
		String noEdit = refusalOf(() -> empty.add(null));
		check("add was handed no edit".equals(noEdit) && !empty.canUndo(), "a history handed no edit refuses it, in words,"
				+ " and holds nothing to undo: " + noEdit);
		String needs = "a widget document needs what it shows, its name and its widgets";
		check(needs.equals(noSubject) && needs.equals(noName) && needs.equals(noWidgets),
				"a widget document with nothing it shows, no name or no widgets is refused, in words: " + noSubject + " / "
				+ noName + " / " + noWidgets);
		final Object scope = new Object();
		UndoHistory h = new UndoHistory(() -> Collections.singletonList(scope),
				() -> Collections.<UndoHistory.Document<?>>singletonList(doc));
		h.settle();
		spin.setValue(7);
		formatted.setValue(2.5f);
		text.setText("south");
		tick.setSelected(false);
		pick.setSelectedIndex(2);
		check(h.settle() && "the form".equals(h.undoName()), "five widgets changed in one gesture are one edit of the form");
		check(h.undo() && Integer.valueOf(3).equals(spin.getValue()) && Float.valueOf(1.5f).equals(formatted.getValue())
				&& "north".equals(text.getText()) && tick.isSelected() && pick.getSelectedIndex() == 1,
				"Undo puts every one back: " + spin.getValue() + ", " + formatted.getValue() + ", " + text.getText() + ", "
				+ tick.isSelected() + ", " + pick.getSelectedIndex());
		check(h.redo() && Integer.valueOf(7).equals(spin.getValue()) && !tick.isSelected() && pick.getSelectedIndex() == 2,
				"and Redo puts the change again");
		String said = "(nothing was thrown)";
		try {
			new ctrmap.humaninterface.WidgetDocument(spin, "the form", spin, new javax.swing.JButton("Save"));
		} catch (IllegalArgumentException refused) {
			said = refused.getMessage();
		}
		check(said.contains("JButton") && said.contains("holds no value"), "a button is refused, by kind: " + said);
	}

	/**
	 * The NPC registry, the area's prop registry and a region's prop placements come back (F209): an entry
	 * added, an entry or a prop changed in place - a nested animation table included - taken back, the
	 * records an editor holds kept where they can be, and each marked changed from what was saved.
	 */
	static void theRegistriesAndPropsComeBack() throws Exception {
		System.out.println("--- the NPC registry, the prop registry and a region's props come back");
		final int[] shown = {0};
		final Object scope = new Object();

		FakeGameFiles fake = HandedGameTest.npcGame("hero", "rival").plant(ctrmap.gamedef.ArchiveType.NPC_REGISTRIES, 3,
				HandedGameTest.registryBytes(HandedGameTest.npcEntry(7, 1)));
		final ctrmap.formats.npcreg.NPCRegistry npcs = new ctrmap.formats.npcreg.NPCRegistry(
				fake.staged(ctrmap.gamedef.ArchiveType.NPC_REGISTRIES, 3), fake);
		UndoHistory h = new UndoHistory(() -> Collections.singletonList(scope), () -> Collections.<UndoHistory.Document<?>>singletonList(
				ctrmap.humaninterface.ZoneDocuments.npcRegistry(npcs, () -> shown[0]++)));
		h.settle();
		int added = npcs.registerModel(0);
		check(h.settle() && "the NPC registry".equals(h.undoName()) && npcs.entries.size() == 2,
				"an NPC registry entry added is an edit: " + h.undoName() + ", " + npcs.entries.keySet());
		npcs.modified = false;   //saved
		check(h.undo() && npcs.entries.keySet().equals(new java.util.HashSet<>(Collections.singletonList(7))) && npcs.modified
				&& shown[0] == 1, "Undo takes it away, marks the registry changed and shows it again (" + npcs.entries.keySet() + ")");
		check(h.redo() && npcs.entries.containsKey(added), "and Redo adds it again, under the UID it had (" + added + ")");
		npcs.entries.get(7).shadowEnabled ^= 1;
		int shadow = npcs.entries.get(7).shadowEnabled;
		check(h.settle() && h.undo() && npcs.entries.get(7).shadowEnabled != shadow, "an entry changed in place is an edit, taken back");

		final ctrmap.formats.propdata.ADPropRegistry props = (ctrmap.formats.propdata.ADPropRegistry)
				RenderLoopTest.bare(ctrmap.formats.propdata.ADPropRegistry.class);
		props.entries = new java.util.LinkedHashMap<>();
		props.models = new java.util.HashMap<>();
		ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry door = new ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry();
		door.reference = 5;
		props.entries.put(5, door);
		UndoHistory p = new UndoHistory(() -> Collections.singletonList(scope), () -> Collections.<UndoHistory.Document<?>>singletonList(
				ctrmap.humaninterface.ZoneDocuments.propRegistry(props, () -> shown[0]++)));
		p.settle();
		short table = door.evtAnimations2[1][2];   //-1, the entry's own default: measured, not assumed
		door.evtAnimations2[1][2] = 9;   //an animation table, two deep, changed in place
		check(p.settle() && "the area's prop registry".equals(p.undoName()), "a prop registry entry's nested table changed is an edit: "
				+ p.undoName());
		check(p.undo() && door.evtAnimations2[1][2] == table && props.entries.get(5) == door && props.modified,
				"Undo puts the table back into the entry the editor holds, and marks the registry changed (table " + door.evtAnimations2[1][2]
				+ ", held " + (props.entries.get(5) == door) + ", marked " + props.modified + ")");
		props.entries.put(6, new ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry());
		check(p.settle() && p.undo() && !props.entries.containsKey(6) && props.entries.get(5) == door,
				"an entry added is taken away again, and the one held stays");

		final ctrmap.formats.propdata.GRPropData region = new ctrmap.formats.propdata.GRPropData();
		ctrmap.formats.propdata.GRProp lamp = new ctrmap.formats.propdata.GRProp();
		lamp.x = 18f;
		region.props.add(lamp);
		UndoHistory r = new UndoHistory(() -> Collections.singletonList(scope), () -> Collections.<UndoHistory.Document<?>>singletonList(
				ctrmap.humaninterface.ZoneDocuments.propPlacements(region, () -> shown[0]++)));
		r.settle();
		lamp.x = 36f;
		region.modified = false;
		check(r.settle() && "the region's props".equals(r.undoName()) && r.undo() && lamp.x == 18f && region.props.get(0) == lamp
				&& region.modified, "a prop moved is an edit, put back into the record the form holds, the region marked changed");
	}

	static void theHistoryKeepsTheNewestHundred() {
		System.out.println("--- the history keeps the newest " + UndoHistory.LIMIT + " edits");
		int[] n = {0};
		UndoHistory h = overOneNumber(n);
		for (int i = 1; i <= UndoHistory.LIMIT + 5; i++) {
			n[0] = i;
			h.settle();
		}
		int undone = 0;
		//BOUNDED: a history that recorded its own undos as edits would never run out, and a suite that
		//hangs says nothing - this one stops at twice the limit and fails in words instead
		while (h.canUndo() && undone < 2 * UndoHistory.LIMIT) {
			h.undo();
			undone++;
		}
		check(undone == UndoHistory.LIMIT && n[0] == 5, undone + " undone, back to " + n[0] + " - the oldest five went first");
	}

	static void anotherScopeStartsAgain() {
		System.out.println("--- a history is the history of one zone: another opened starts it again");
		final int[] n = {0};
		final Number doc = new Number(n, "the number");
		final Object[] open = {new Object()};
		final int[] told = {0};
		UndoHistory h = new UndoHistory(() -> Collections.singletonList(open[0]),
				() -> Collections.<UndoHistory.Document<?>>singletonList(doc));
		h.addListener(() -> told[0]++);
		h.settle();
		n[0] = 1;
		h.settle();
		check(h.canUndo(), "control: an edit made in the first");
		int before = told[0];
		open[0] = new Object();
		h.settle();
		check(!h.canUndo() && !h.canRedo() && told[0] > before, "another opened: nothing to undo, and the buttons were told");
		n[0] = 2;
		h.settle();
		check(h.undo() && n[0] == 1, "and the new one's edits are recorded from where it opened (" + n[0] + ")");
	}

	static void anEditOnSomethingClosedIsRefused() {
		System.out.println("--- an edit on something no longer open is refused, never put into it");
		final int[] n = {0};
		final Number doc = new Number(n, "region (1, 2)'s tiles");
		final List<UndoHistory.Document<?>> docs = new ArrayList<>();
		docs.add(doc);
		final Object scope = new Object();
		UndoHistory h = new UndoHistory(() -> Collections.singletonList(scope), () -> new ArrayList<>(docs));
		h.settle();
		n[0] = 6;
		h.settle();
		docs.clear();
		String said;
		try {
			h.undo();
			said = null;
		} catch (IllegalStateException refused) {
			said = refused.getMessage();
		}
		check(said != null && said.contains("no longer open") && said.contains("region (1, 2)'s tiles"),
				"refused, naming what is gone: " + said);
		check(n[0] == 6 && doc.puts == 0 && !h.canUndo(), "nothing was put into it, and the history starts again");
		List<String> shown = ctrmap.Ui.record();
		try {
			docs.add(doc);
			h.settle();   //seen again: from here its changes are edits
			n[0] = 7;
			h.settle();
			docs.clear();
			h.act(null, true);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(shown.size() == 1 && shown.get(0).contains("Nothing was undone"), "and pressed as a button, the refusal is said: " + shown);
	}

	static void theGestureHookRunsInTheEventLoop() throws Exception {
		System.out.println("--- the gesture hook settles where the event loop runs: before a press, after a release");
		final int[] n = {0};
		final UndoHistory h = overOneNumber(n);
		UndoHistory.settleAroundGestures(() -> Collections.singletonList(h));
		final JPanel target = new JPanel();
		target.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseReleased(MouseEvent e) {
				n[0] += 5;   //what an editor does when a drag ends
			}
		});
		n[0] = 1;   //changed outside any gesture: the press must not take it for the gesture's change
		post(target, MouseEvent.MOUSE_PRESSED);
		check(h.canUndo() && "the number".equals(h.undoName()), "the press settled what was there before it as its own edit");
		h.undo();
		check(n[0] == 0, "control: taking that back leaves the number where it was before (" + n[0] + ")");
		post(target, MouseEvent.MOUSE_PRESSED);
		post(target, MouseEvent.MOUSE_RELEASED);
		flush();
		check(n[0] == 5 && h.canUndo(), "the release's handler changed the number, and once it had run that was recorded");
		h.undo();
		check(n[0] == 0, "as one edit: one undo takes the whole gesture back (" + n[0] + ")");
	}

	static void post(JPanel target, int id) throws Exception {
		SwingUtilities.invokeAndWait(() -> target.dispatchEvent(new MouseEvent(target, id, System.currentTimeMillis(), 0, 1, 1, 1, false,
				MouseEvent.BUTTON1)));
	}

	static void flush() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
		});
	}

	static void ctrlZGoesToTheEditorBeingWorkedIn() {
		System.out.println("--- Ctrl+Z goes to the history of the editor around the focus, else the zone's");
		UndoHistory zone = overOneNumber(new int[]{0});
		UndoHistory table = overOneNumber(new int[]{0});
		JPanel window = new JPanel();
		JPanel editor = new JPanel();
		JPanel field = new JPanel();
		editor.add(field);
		window.add(editor);
		JPanel elsewhere = new JPanel();
		window.add(elsewhere);
		table.own(editor);
		check(UndoHistory.of(field, zone) == table, "inside an editor that owns a history: that one");
		check(UndoHistory.of(elsewhere, zone) == zone && UndoHistory.of(null, zone) == zone, "anywhere else, or nowhere: the zone's");
	}

	/** A flat record with an array, for the copier's known answers. */
	static final class Rec {

		int a;
		String s;
		byte[] bytes = new byte[2];
		Runnable shared;
	}

	/** A record that is not flat: the list would be shared by a copy. */
	static final class Nested {

		int a;
		List<Integer> inside = new ArrayList<>();
	}

	static void aRecordIsCopiedExactly() throws Exception {
		System.out.println("--- a record is copied field by field, its arrays its own");
		Rec r = new Rec();
		r.a = 70_000;   //past the boxed values Java caches: compared by identity, two copies would differ
		r.s = "x";
		r.bytes[1] = 9;
		r.shared = () -> {
		};
		Rec c = FlatCopy.copy(r);
		check(c != r && c.a == 70_000 && "x".equals(c.s) && c.bytes[1] == 9 && c.shared == r.shared && FlatCopy.same(r, c),
				"control: every field copied, the shared reference shared, and the two the same");
		c.bytes[1] = 1;
		check(r.bytes[1] == 9 && !FlatCopy.same(r, c), "the array is the copy's own: changing it leaves the original alone");
		ZoneEntities.Warp w = new ZoneEntities.Warp();
		w.targetZone = ZoneEntities.Warp.NO_TARGET;
		w.x = 18;
		ZoneEntities.Warp wc = FlatCopy.copy(w);
		check(wc.isUnset() && wc.x == 18, "a half-made warp copies as a half-made warp - the save would refuse it, an undo must not");
		List<Rec> live = new ArrayList<>(Arrays.asList(r, new Rec()));
		Rec keep = live.get(0);
		List<Rec> state = FlatCopy.copyAll(Collections.singletonList(r));
		state.get(0).a = 42;
		FlatCopy.putAll(state, live);
		check(live.size() == 1 && live.get(0) == keep && keep.a == 42,
				"putting a state back keeps the records an editor holds, and drops the ones the state does not have");
		String said = "(nothing was thrown)";
		try {
			FlatCopy.copy(new Nested());
		} catch (IllegalArgumentException refused) {
			said = refused.getMessage();
		}
		check(said.contains("not a flat record") && said.contains("inside"),
				"a record holding a list is refused, by field, because a copy would share it: " + said);
		//a record put back into one of another class (the 2026-10-14 shared sweep, FlatCopy 48, drop-throw, SURVIVED:
		//no suite handed it two classes)
		Nested other = new Nested();
		other.a = 5;
		String mixed = "(nothing was thrown)";
		try {
			FlatCopy.into(new Rec(), other);
		} catch (RuntimeException refused) {
			mixed = refused.getMessage();
		}
		check("a Rec cannot be copied into a Nested".equals(mixed) && other.a == 5,
				"a record is never copied into one of another class, which keeps what it held: " + mixed);
		int records = 0;
		for (Class<?> k : ZoneEntities.class.getDeclaredClasses()) {
			if (!k.isInterface() && !java.lang.reflect.Modifier.isAbstract(k.getModifiers())) {
				try {
					FlatCopy.same(FlatCopy.copy(RenderLoopTest.bare(k)), RenderLoopTest.bare(k));
					records++;
				} catch (Exception refused) {
					check(false, "the zone's " + k.getSimpleName() + " records cannot be copied for undo: " + refused);
				}
			}
		}
		check(records == 4, "every record kind the zone's entities hold is flat (" + records + " of 4 copied)");
		aFieldThatCannotBeReadOrWrittenIsSaid();
	}

	/** Refuses every write: a static final field, which set() refuses even made accessible. */
	static final class Constant {

		static final String FIXED = "fixed";
		private int hidden = 3;
	}

	/**
	 * A field FlatCopy cannot read or write is said, naming it (2026-10-24). Every field it copies is made accessible
	 * first, so the catch each of its loops kept was a line no record reached - the 2026-10-17 sweep recorded FlatCopy 57
	 * as a HOLE. Its loops read and write through one read and one write now, and those are handed what refuses: a
	 * private field never made accessible, and a static final field, which set() refuses whatever is done to it.
	 */
	static void aFieldThatCannotBeReadOrWrittenIsSaid() throws Exception {
		java.lang.reflect.Method read = FlatCopy.class.getDeclaredMethod("read", java.lang.reflect.Field.class, Object.class);
		java.lang.reflect.Method write = FlatCopy.class.getDeclaredMethod("write", java.lang.reflect.Field.class, Object.class,
				Object.class);
		read.setAccessible(true);
		write.setAccessible(true);
		java.lang.reflect.Field hidden = Constant.class.getDeclaredField("hidden");
		java.lang.reflect.Field fixed = Constant.class.getDeclaredField("FIXED");
		fixed.setAccessible(true);
		check(fixed.get(null).equals("fixed"), "control: the static final field reads once made accessible");
		String unread = said(read, hidden, new Constant());
		check(unread.contains("Constant.hidden could not be read"), "a field that cannot be read is said, naming it: " + unread);
		String unwritten = said(write, fixed, null, "other");
		check(unwritten.contains("Constant.FIXED could not be written") && "fixed".equals(fixed.get(null)),
				"and one that cannot be written is said, naming it, its value kept: " + unwritten);
	}

	/** What a FlatCopy helper said when handed {@code args}: its refusal's words, or that it said nothing. */
	static String said(java.lang.reflect.Method m, Object... args) {
		try {
			m.invoke(null, args);
			return "(nothing was thrown)";
		} catch (java.lang.reflect.InvocationTargetException thrown) {
			return String.valueOf(thrown.getCause().getMessage());
		} catch (IllegalAccessException cannot) {
			return "the helper could not be asked: " + cannot;
		}
	}

	static void theZonesEntitiesComeBack() throws Exception {
		System.out.println("--- the zone's entities: an NPC added, moved and removed is taken back exactly");
		ZoneEntities e = (ZoneEntities) RenderLoopTest.bare(ZoneEntities.class);
		e.furniture = new ArrayList<>();
		e.npcs = new ArrayList<>();
		e.warps = new ArrayList<>();
		e.triggers1 = new ArrayList<>();
		e.triggers2 = new ArrayList<>();
		ctrmap.LoadedZone lz = new ctrmap.LoadedZone();
		ctrmap.formats.zone.Zone zone = (ctrmap.formats.zone.Zone) RenderLoopTest.bare(ctrmap.formats.zone.Zone.class);
		zone.entities = e;
		lz.table(new ctrmap.formats.zone.Zone[]{zone});
		lz.open(0, zone);
		final int[] shown = {0};
		UndoHistory h = new UndoHistory(ctrmap.humaninterface.ZoneDocuments.scope(lz, () -> null),
				UndoHistoryTest.zone(lz, () -> null, () -> shown[0]++, () -> {
				}));
		h.settle();
		ZoneEntities.NPC npc = new ZoneEntities.NPC();
		npc.xTile = 4;
		e.npcs.add(npc);
		h.settle();
		npc.xTile = 9;
		h.settle();
		check(h.undo() && e.npcs.size() == 1 && e.npcs.get(0) == npc && npc.xTile == 4,
				"undo moves the NPC back, and it is still the record the form holds (x " + npc.xTile + ")");
		check(h.undo() && e.npcs.isEmpty() && shown[0] == 2 && e.modified,
				"undo again removes it; every put re-showed the entities and marked the zone modified (" + shown[0] + ")");
		check(h.redo() && e.npcs.size() == 1 && e.npcs.get(0).xTile == 4, "redo adds it back where it was added");
		e.modified = false;
		check(!h.settle() && h.canRedo(), "a save clearing the modified flag is no edit: the move is still there to redo");
	}

	/**
	 * The zone's script is a document of its history (F209): the NPC wizards replace it, the dialogue editor
	 * re-points it, the script panel edits it. It is read as the bytes it would be saved as, through a decoded
	 * copy, so a script only decompressed to be looked at is no edit.
	 */
	static void theZonesScriptComesBack() throws Exception {
		System.out.println("--- the zone's script: replaced, it is taken back; decompressed to be read, it is no edit (F209)");
		ctrmap.LoadedZone lz = new ctrmap.LoadedZone();
		ctrmap.formats.zone.Zone zone = (ctrmap.formats.zone.Zone) RenderLoopTest.bare(ctrmap.formats.zone.Zone.class);
		zone.s = new ctrmap.formats.scripts.GFLPawnScript(longCell());
		lz.table(new ctrmap.formats.zone.Zone[]{zone});
		lz.open(0, zone);
		final int[] shown = {0};
		UndoHistory h = new UndoHistory(ctrmap.humaninterface.ZoneDocuments.scope(lz, () -> null),
				ctrmap.humaninterface.ZoneDocuments.script(lz, () -> shown[0]++));
		h.settle();
		ctrmap.formats.scripts.GFLPawnScript original = zone.s;
		byte[] raw = zone.s.getScriptBytes();
		zone.s.decompressThis();
		check(!Arrays.equals(raw, zone.s.getScriptBytes()), "control: decompressing this script changes the bytes it would be saved as");
		check(!h.settle() && !h.canUndo(), "a script decompressed to be read is no edit");
		zone.s = new ctrmap.formats.scripts.GFLPawnScript(PawnHeaderTest.withOnePublic());
		check(h.settle() && "the zone's script".equals(h.undoName()), "a script replaced - an NPC wizard's commit - is an edit: " + h.undoName());
		check(h.undo() && zone.s != original && zone.s.publics.isEmpty() && shown[0] == 1,
				"and Undo puts the script back as it was, and shows it (" + shown[0] + ")");
		check(h.redo() && zone.s.publics.size() == 1, "Redo puts the replacement again");
	}

	/**
	 * A script whose one data cell is stored in two bytes - 0 as 80 00, a continuation byte the editor's
	 * compressor never writes - so decompressed and saved again its bytes change though nothing it says does.
	 */
	static byte[] longCell() {
		byte[] b = Arrays.copyOf(HandedGameTest.amx(), 0x40);
		java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0, 0x40).putInt(20, 0x40).putInt(24, 0x40);
		b[0x3C] = (byte) 0x80;
		return b;
	}

	/**
	 * A zone's history is the history of the zone open and the map grid shown, by the scope alone: another
	 * zone opened, or another grid of regions put in the map view, starts it again even when nothing calls
	 * reset - an edit made on one map cannot be put back on another.
	 */
	static void anotherZoneOrMapStartsAgain() throws Exception {
		System.out.println("--- another zone opened, or another map grid shown, starts the zone's history again");
		ctrmap.formats.zone.Zone[] zones = new ctrmap.formats.zone.Zone[2];
		for (int i = 0; i < 2; i++) {
			zones[i] = (ctrmap.formats.zone.Zone) RenderLoopTest.bare(ctrmap.formats.zone.Zone.class);
		}
		ctrmap.LoadedZone lz = new ctrmap.LoadedZone();
		lz.table(zones);
		lz.open(0, zones[0]);
		final ctrmap.humaninterface.TileMapPanel map = (ctrmap.humaninterface.TileMapPanel) RenderLoopTest.bare(
				ctrmap.humaninterface.TileMapPanel.class);
		Tilemap region = new Tilemap(null, 40, 40);
		map.tilemaps = new Tilemap[][]{{region}};
		UndoHistory h = new UndoHistory(ctrmap.humaninterface.ZoneDocuments.scope(lz, () -> map),
				UndoHistoryTest.zone(lz, () -> map, () -> {
				}, () -> {
				}));
		h.settle();
		region.setTileData(2, 2, new byte[]{1, 2, 3, 4});
		check(h.settle() && h.canUndo(), "control: a tile edit on the open map is an edit");
		map.tilemaps = new Tilemap[][]{{new Tilemap(null, 40, 40)}};
		h.settle();
		check(!h.canUndo(), "another grid in the map view: nothing to take back, though nobody called reset");
		region = map.tilemaps[0][0];
		region.setTileData(2, 2, new byte[]{1, 2, 3, 4});
		check(h.settle() && h.canUndo(), "control: an edit on the new grid is an edit");
		lz.open(1, zones[1]);
		h.settle();
		check(!h.canUndo(), "another zone opened: nothing to take back either");
	}

	static void aRegionsTilesComeBack() {
		System.out.println("--- a region's tiles: a stroke is taken back whole, and the map is drawn again");
		Tilemap region = new Tilemap(null, 40, 40);
		final TileMapPanelHolder holder = new TileMapPanelHolder(region);
		final int[] drawn = {0};
		final Object scope = new Object();
		UndoHistory h = new UndoHistory(() -> Collections.singletonList(scope),
				() -> Collections.<UndoHistory.Document<?>>singletonList(holder.tiles(() -> drawn[0]++)));
		h.settle();
		byte[] was = region.getTileData(3, 3).clone();
		region.setTileData(3, 3, new byte[]{1, 2, 3, 4});
		region.setTileData(4, 3, new byte[]{1, 2, 3, 4});
		h.settle();
		check(h.undo() && Arrays.equals(region.getTileData(3, 3), was) && Arrays.equals(region.getTileData(4, 3), was)
				&& drawn[0] == 1 && region.modified, "both tiles back, the map drawn again, the region marked modified");
		check(h.redo() && region.getTileData(4, 3)[3] == 4, "and redo puts the stroke again");
		region.modified = false;   //saved
		h.undo();
		check(region.modified, "an undo after a save leaves the region marked modified: it is not what was saved");
		h.redo();
		byte kept = region.rawTileData[5][5][0];
		region.rawTileData[5][5][0] = (byte) (kept + 1);   //written in place, as the tile form's spinners write it
		check(h.settle() && h.undo() && region.rawTileData[5][5][0] == kept,
				"a tile written in place is an edit, and undo puts the byte back: the state is the region's own copy ("
				+ region.rawTileData[5][5][0] + ")");
	}

	/** Reaches the package-private tiles document through the public factory, over one region. */
	static final class TileMapPanelHolder {

		final Tilemap region;

		TileMapPanelHolder(Tilemap region) {
			this.region = region;
		}

		UndoHistory.Document<?> tiles(Runnable shown) {
			ctrmap.humaninterface.TileMapPanel map;
			try {
				map = (ctrmap.humaninterface.TileMapPanel) RenderLoopTest.bare(ctrmap.humaninterface.TileMapPanel.class);
			} catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			map.tilemaps = new Tilemap[][]{{region}};
			return UndoHistoryTest.zone(new ctrmap.LoadedZone(), () -> map, () -> {
			}, shown).get().get(0);
		}
	}
}
