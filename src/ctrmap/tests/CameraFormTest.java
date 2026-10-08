package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.cameradata.CameraCoordinates;
import ctrmap.formats.cameradata.CameraData;
import ctrmap.formats.cameradata.CameraDataFile;
import ctrmap.formats.containers.AD;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.CameraEditForm;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JSlider;

/**
 * The camera form writes what it was moved to and every other byte as it read it (F123, F127,
 * F097, the camera half of F210).
 *
 * <p>WHY THIS SUITE EXISTS. The form built the record it saved from its widgets alone, and
 * several cannot hold what the file does: the FOV slider is whole degrees, the motion box has
 * three rows, the neutral box is one tick over a byte, and no widget holds the unknowns. The
 * change detector rounded every float to a whole number. So a pitch moved by 0.4 was dropped, a
 * fractional FOV became whole on the next edit, an unnamed motion code became 7 on an entry
 * switch with no edit at all, and the record it did write went into the slot of the first EQUAL
 * camera. PROPERTIES.md says each edit form writes what it displayed and nothing else; until this
 * suite nothing saved a camera and compared the bytes.
 *
 * <p>ORDER: the record and form halves need no dump and no display - the form is the real one,
 * over a hand-built area. The last half reads every retail area's camera table and is skipped,
 * saying so, without the dump.
 *
 * Usage: java ctrmap.tests.CameraFormTest [dump dir, default ../RomFS_original_garcs]
 */
public class CameraFormTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		Workspace.reset();
		try {
			theRecordComparesWhatItWrites();
			keepTakesEveryMovedFieldAndNothingElse();
			theFormWritesWhatItWasMovedTo();
			aCameraReadInPiecesIsTheSameCamera();
			theCamerasAreADocumentOfTheZonesHistory();
			everyRetailCameraSurvivesBeingShown(dump);
		} finally {
			ctrmap.Ui.stopRecording();
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	// ------------------------------------------------------------ the record

	/**
	 * A camera is read whole however its bytes arrive (F221). Its first sixteen bytes were read with
	 * read(byte[]), which may hand over fewer than asked while more remain, and the count was dropped -
	 * every field after them was then read from the wrong place, and nothing said so.
	 */
	static void aCameraReadInPiecesIsTheSameCamera() throws Exception {
		System.out.println("--- a camera is read whole however its bytes arrive (F221)");
		CameraData made = new CameraData();
		made.unknownBytes[0] = 5;
		made.unknownBytes[15] = 6;
		made.coords1.FOV = 41f;
		byte[] b = made.bytes();
		CameraData whole = new CameraData(new ctrmap.util.LittleEndianDataInputStream(new java.io.ByteArrayInputStream(b)));
		check(whole.equals(made), "control: read from the whole array, the camera is the one written");
		java.io.InputStream trickle = new java.io.FilterInputStream(new java.io.ByteArrayInputStream(b)) {
			@Override
			public int read(byte[] buf, int off, int len) throws java.io.IOException {
				return super.read(buf, off, Math.min(len, 1));
			}
		};
		CameraData pieces = new CameraData(new ctrmap.util.LittleEndianDataInputStream(trickle));
		check(pieces.equals(made), "and read through a stream that hands over one byte at a time, it is the same camera");
	}


	static void theRecordComparesWhatItWrites() {
		System.out.println("--- a camera compares the bytes it writes (F123, F210)");
		CameraCoordinates a = new CameraCoordinates();
		CameraCoordinates b = new CameraCoordinates();
		a.pitch = 10f;
		b.pitch = 11f;
		check(!a.equals(b), "control: a whole unit apart reads as a change");
		b.pitch = 10.4f;
		check(!a.equals(b), "and so does 0.4 of one, in a field that offers two decimals");
		b.pitch = 10f;
		check(a.equals(b) && a.hashCode() == b.hashCode(), "the same floats are equal and hash the same");

		CameraData one = new CameraData();
		CameraData two = new CameraData();
		check(one.equals(two) && one.hashCode() == two.hashCode(), "two new cameras are equal and hash the same");
		two.unknownBytes[3] = 9;
		check(!one.equals(two), "and a change to a field no widget shows is a change");
		check(one.bytes().length == CameraData.SIZE && CameraData.SIZE == 100,
				"a record is the layout's " + CameraData.SIZE + " bytes, 0x64 as the table holds them");
		CameraData copy = two.copy();
		copy.coords1.FOV = 50f;
		check(two.coords1.FOV == 30f && copy.equals(copy.copy()), "a copy shares nothing with its original");
	}

	/**
	 * Every field a camera writes, found by reflection and placed by where its bytes land - so
	 * a field added later is in this list without anyone adding it.
	 */
	static TreeMap<Integer, Object[]> fieldsByOffset() throws Exception {
		List<Object[]> found = new ArrayList<>();
		for (Field fd : CameraData.class.getFields()) {
			if (Modifier.isStatic(fd.getModifiers())) {
				continue;
			}
			Class<?> t = fd.getType();
			if (t == CameraCoordinates.class) {
				for (Field cf : CameraCoordinates.class.getFields()) {
					if (!Modifier.isStatic(cf.getModifiers())) {
						found.add(new Object[]{fd.getName() + "." + cf.getName(), (Consumer<CameraData>) c -> {
							try {
								Object cc = fd.get(c);
								cf.setFloat(cc, cf.getFloat(cc) + 12.75f);
							} catch (IllegalAccessException ex) {
								throw new IllegalStateException(ex);
							}
						}});
					}
				}
			} else if (t == int.class || t == short.class || t == byte[].class) {
				found.add(new Object[]{fd.getName(), (Consumer<CameraData>) c -> {
					try {
						if (t == int.class) {
							fd.setInt(c, fd.getInt(c) ^ 0x5A);
						} else if (t == short.class) {
							fd.setShort(c, (short) (fd.getShort(c) ^ 0x1234));
						} else {
							byte[] v = ((byte[]) fd.get(c)).clone();
							Arrays.fill(v, (byte) 0x77);
							fd.set(c, v);
						}
					} catch (IllegalAccessException ex) {
						throw new IllegalStateException(ex);
					}
				}});
			} else {
				check(false, "the suite knows how to move every camera field: not " + fd.getName() + " (" + t + ")");
			}
		}
		TreeMap<Integer, Object[]> byOffset = new TreeMap<>();
		byte[] base = base().bytes();
		List<String> unwritten = new ArrayList<>();
		List<String> shared = new ArrayList<>();
		for (Object[] f : found) {
			CameraData c = base();
			move(f, c);
			byte[] moved = c.bytes();
			int at = -1;
			for (int i = 0; i < base.length && at < 0; i++) {
				if (base[i] != moved[i]) {
					at = i;
				}
			}
			if (at < 0) {
				unwritten.add((String) f[0]);
			} else if (byOffset.containsKey(at)) {
				shared.add(f[0] + " at " + at);
			} else {
				byOffset.put(at, f);
			}
		}
		check(unwritten.isEmpty() && shared.isEmpty(), "every field reaches the file, each at its own offset: unwritten "
				+ unwritten + ", sharing " + shared);
		return byOffset;
	}

	@SuppressWarnings("unchecked")
	static void move(Object[] field, CameraData c) {
		((Consumer<CameraData>) field[1]).accept(c);
	}

	/** A camera with nothing at a default, so a field moved by the suite always reads as moved. */
	static CameraData base() {
		CameraData c = new CameraData();
		Arrays.fill(c.unknownBytes, (byte) 3);
		c.unknownInt1 = 0x01020304;
		c.unknownFFFF = 0x0506;
		c.layer = 4;
		return c;
	}

	static void keepTakesEveryMovedFieldAndNothingElse() throws Exception {
		System.out.println("--- keep takes each field the form moved, and every other as read (F123, F127, F097)");
		TreeMap<Integer, Object[]> fields = fieldsByOffset();
		check(fields.size() == 31, "control: 31 fields found - 17 of the record's own and 7 in each set of coordinates ("
				+ fields.size() + ")");
		List<String> wrong = new ArrayList<>();
		Integer prev = null;
		for (Integer at : fields.keySet()) {
			Object[] f = fields.get(at);
			//MOVED: shown and raw agree, the form moved it
			CameraData raw = base();
			CameraData now = base();
			move(f, now);
			if (!Arrays.equals(CameraData.keep(raw, base(), now).bytes(), now.bytes())) {
				wrong.add(f[0] + " moved was not taken");
			}
			//KEPT: the file holds something the form cannot show, and the form was not moved
			CameraData odd = base();
			move(f, odd);
			if (!Arrays.equals(CameraData.keep(odd, base(), base()).bytes(), odd.bytes())) {
				wrong.add(f[0] + " unmoved was not kept");
			}
			//NEIGHBOURS: one moved beside one kept, each way round - what a layout that merged
			//two fields into one slice would get wrong
			if (prev != null) {
				Object[] p = fields.get(prev);
				CameraData r1 = base();
				move(p, r1);
				CameraData n1 = base();
				move(f, n1);
				CameraData want1 = base();
				move(p, want1);
				move(f, want1);
				if (!Arrays.equals(CameraData.keep(r1, base(), n1).bytes(), want1.bytes())) {
					wrong.add(p[0] + " kept beside " + f[0] + " moved");
				}
				CameraData r2 = base();
				move(f, r2);
				CameraData n2 = base();
				move(p, n2);
				if (!Arrays.equals(CameraData.keep(r2, base(), n2).bytes(), want1.bytes())) {
					wrong.add(f[0] + " kept beside " + p[0] + " moved");
				}
			}
			prev = at;
		}
		check(wrong.isEmpty(), "every field, alone and beside its neighbour, is taken when moved and kept when not: " + wrong);
	}

	// ------------------------------------------------------------ the form

	/** An area whose subfile 3 is this camera table, read through the real container. */
	static AD area(CameraData... cams) throws Exception {
		return area(new FakeGameFiles(), cams);
	}

	/** The same, in the game handed: an ORAS one unless said otherwise. */
	static AD area(FakeGameFiles fake, CameraData... cams) throws Exception {
		ByteArrayOutputStream table = new ByteArrayOutputStream();
		ctrmap.util.LittleEndianDataOutputStream into = new ctrmap.util.LittleEndianDataOutputStream(table);
		into.writeInt(cams.length);
		for (CameraData c : cams) {
			c.write(into);
		}
		into.close();
		fake.plant(ArchiveType.AREA_DATA, 0, HandedGameTest.container("AD", new byte[0], new byte[0], new byte[0], table.toByteArray()));
		return new AD(fake.staged(ArchiveType.AREA_DATA, 0), fake);
	}

	/** A camera with a value in every field the form shows inexactly or not at all. */
	static CameraData oddCamera() {
		CameraData c = base();
		c.coords1.FOV = 30.4f;
		c.coords2.FOV = -20f;
		c.coords1.pitch = 10f;
		c.movementDirection = 5;
		c.unknown01or03 = 0;
		c.transitionPeriod = 2000;
		c.isFirstEnabled = 3;
		c.isSecondEnabled = -1;
		c.acceptCoords1 = 2;
		c.unknown00 = 0x44;
		return c;
	}

	/**
	 * The camera table is a document of the zone's history (F209): a camera added, removed or moved is
	 * taken back by the same Undo as everything else, and the form shows the table again as it was put.
	 * The camera editor had no undo at all.
	 */
	static void theCamerasAreADocumentOfTheZonesHistory() throws Exception {
		System.out.println("--- the camera table is a document of the zone's history (F209)");
		MapResizeTest.game("camundo");
		int[] npc = {0};
		ctrmap.humaninterface.UndoHistory zone = UndoHistoryTest.overOneNumber(npc);
		String said = "(nothing was thrown)";
		try {
			new CameraEditForm(DataSafetyGuardsTest.REDRAW, null);
		} catch (RuntimeException refused) {
			said = refused.getClass().getSimpleName() + ": " + refused.getMessage();
		}
		check(said.startsWith("IllegalArgumentException") && said.contains("must be handed the zone's history"),
				"a camera form built without the zone's history is refused: " + said);
		CameraEditForm form = new CameraEditForm(DataSafetyGuardsTest.REDRAW, zone);
		JComboBox<?> entries = (JComboBox<?>) MatrixEditFormGuardsTest.field(form, "entryBox");
		form.loadFrom(area(oddCamera(), new CameraData()));
		zone.settle();
		check(!zone.canUndo() && form.f.camData.size() == 2, "control: a table loaded is noted, not an edit");
		((javax.swing.JButton) MatrixEditFormGuardsTest.field(form, "btnAdd")).doClick();
		check(zone.settle() && "the zone's cameras".equals(zone.undoName()) && form.f.camData.size() == 3,
				"a camera added is an edit of the zone's history: " + zone.undoName());
		byte[] first = form.f.camData.get(0).bytes();
		form.f.modified = false;   //saved: the undo must mark it changed again
		check(zone.undo() && form.f.camData.size() == 2 && entries.getItemCount() == 2,
				"Undo takes it away again, and the form lists two cameras (" + entries.getItemCount() + ")");
		check(Arrays.equals(form.f.camData.get(0).bytes(), first) && form.f.modified,
				"the cameras left are the ones there were, and the table is marked changed from what was saved");
		check(zone.redo() && form.f.camData.size() == 3 && entries.getItemCount() == 3, "Redo adds it again");
		form.f.camData.get(1).boundX1 = 77;   //a camera moved, as saveCamera writes it
		check(zone.settle() && zone.undo() && form.f.camData.get(1).boundX1 != 77,
				"a camera moved is an edit, and Undo puts it back where it was");
		form.unload();
		String refused = "(nothing was thrown)";
		try {
			zone.undo();
		} catch (IllegalStateException notOpen) {
			refused = notOpen.getMessage();
		}
		check(refused.contains("no longer open") && refused.contains("the zone's cameras"),
				"after the table is unloaded, an Undo reaching it is refused, naming it: " + refused);
	}

	static void theFormWritesWhatItWasMovedTo() throws Exception {
		System.out.println("--- the camera form writes what it was moved to and nothing else (F123, F127, F097, F210)");
		MapResizeTest.game("camform");
		List<String> said = ctrmap.Ui.record();
		CameraEditForm form = new CameraEditForm(DataSafetyGuardsTest.REDRAW, UndoHistoryTest.overOneNumber(new int[]{0}));
		JComboBox<?> entries = (JComboBox<?>) MatrixEditFormGuardsTest.field(form, "entryBox");
		CameraData odd = oddCamera();
		byte[] oddBytes = odd.bytes();
		form.loadFrom(area(odd, new CameraData()));
		check(form.cam != null && form.camIndex == 0 && said.isEmpty(), "the table loads, showing camera 0: " + said);
		JSlider fov1 = (JSlider) MatrixEditFormGuardsTest.field(form, "fov1Slider");
		check(fov1.getMinimum() == 1 && fov1.getMaximum() == 179, "the FOV slider offers an angle of view, 1 to 179: "
				+ fov1.getMinimum() + ".." + fov1.getMaximum());

		entries.setSelectedIndex(1);
		entries.setSelectedIndex(0);
		check(!form.f.modified && Arrays.equals(form.f.camData.get(0).bytes(), oddBytes),
				"switching away and back with no edit writes nothing: FOV 30.4 and -20, motion code 5, a 2000-frame"
				+ " transition, defaults 3/-1 and the unknowns all as read (modified " + form.f.modified + ")");

		((JFormattedTextField) MatrixEditFormGuardsTest.field(form, "pitch1")).setValue(10.4f);
		form.saveCamera();
		CameraData saved = form.f.camData.get(0);
		CameraData want = oddCamera();
		want.coords1.pitch = 10.4f;
		check(form.f.modified && Arrays.equals(saved.bytes(), want.bytes()),
				"a pitch moved by 0.4 is written exactly, and every other field as read: pitch " + saved.coords1.pitch
				+ ", FOV " + saved.coords1.FOV + ", motion " + saved.movementDirection + ", transition " + saved.transitionPeriod);

		fov1.setValue(45);
		form.saveCamera();
		check(form.f.camData.get(0).coords1.FOV == 45f && form.f.camData.get(0).coords2.FOV == -20f,
				"a FOV the slider was moved to is written, and the other set's as read");

		JComboBox<?> motion = (JComboBox<?>) MatrixEditFormGuardsTest.field(form, "motion");
		check(motion.getSelectedIndex() == -1, "a motion code with no row shows no row, not Static: row " + motion.getSelectedIndex());
		motion.setSelectedIndex(0);
		form.saveCamera();
		check(form.f.camData.get(0).movementDirection == 1 && form.f.camData.get(0).unknown01or03 == 1,
				"and a row the user picks is written with its dynamic marker");

		System.out.println("--- of two equal cameras, the one shown is the one written (F210)");
		form.loadFrom(area(new CameraData(), new CameraData()));
		entries.setSelectedIndex(1);
		((JFormattedTextField) MatrixEditFormGuardsTest.field(form, "yaw1")).setValue(33f);
		form.saveCamera();
		check(form.f.camData.get(1).coords1.yaw == 33f && form.f.camData.get(0).coords1.yaw == 0f,
				"an edit to the second of two new cameras lands on the second: yaws "
				+ form.f.camData.get(0).coords1.yaw + ", " + form.f.camData.get(1).coords1.yaw);

		System.out.println("--- a neutral tick is saved, with this game's value (F097)");
		javax.swing.JCheckBox neutral = (javax.swing.JCheckBox) MatrixEditFormGuardsTest.field(form, "neutralCheckbox");
		form.loadFrom(area(new CameraData(), new CameraData()));
		neutral.doClick(0);
		form.saveCamera();
		check(form.f.modified && form.f.camData.get(0).isNeutral == Workspace.profile().neutralCameraValue()
				&& form.f.camData.get(0).isNeutral == 1,
				"a neutral-only change is written, as ORAS's 1 when the area has no neutral camera to copy: "
				+ form.f.camData.get(0).isNeutral + ", modified " + form.f.modified);
		CameraData two = new CameraData();
		two.isNeutral = 2;
		form.loadFrom(area(new CameraData(), two));
		neutral.doClick(0);
		form.saveCamera();
		check(form.f.camData.get(0).isNeutral == 2, "and as the area's own neutral cameras hold it when it has one: "
				+ form.f.camData.get(0).isNeutral);

		//A GAME THAT RECORDS NO VALUE - ORAS in every other respect, so it has areas to hold the
		//table: the area's own game is asked, not the open workspace
		ctrmap.gamedef.GameProfile unrecorded = new ctrmap.gamedef.OrasProfile() {
			@Override
			public int neutralCameraValue() {
				return -1;
			}
		};
		form.loadFrom(area(new FakeGameFiles().profile(unrecorded), new CameraData(), new CameraData()));
		said.clear();
		neutral.doClick(0);
		form.saveCamera();
		check(!neutral.isSelected() && !form.f.modified && said.size() == 1 && said.get(0).contains("neutral camera"),
				"with no value known the tick is refused and said, and nothing is written: " + said);

		System.out.println("--- an emptied form offers nothing to add to (census M.73)");
		form.unload();
		javax.swing.JButton add = (javax.swing.JButton) MatrixEditFormGuardsTest.field(form, "btnAdd");
		Throwable thrown = null;
		try {
			MatrixEditFormGuardsTest.invoke(form, "btnAddActionPerformed");
		} catch (Throwable t) {
			thrown = t;
		}
		check(!add.isEnabled() && !neutral.isEnabled() && thrown == null,
				"Add and the neutral tick are disabled, and Add's handler does nothing: " + thrown);
	}

	// ------------------------------------------------------------ the dump

	/**
	 * Every retail area's cameras, shown and switched through with no edit, come back byte for
	 * byte - and every neutral camera holds ORAS's recorded value.
	 */
	static void everyRetailCameraSurvivesBeingShown(File dump) throws Exception {
		System.out.println("--- every retail camera is written back as read, and every neutral one holds 1");
		File garcFile = new File(dump, Workspace.getArchivePath(ArchiveType.AREA_DATA, GameType.ORAS));
		if (!garcFile.isFile()) {
			Skip.section("no dump at " + dump + " - UNKNOWN, not passed");
			return;
		}
		MapResizeTest.game("camdump");
		ctrmap.Ui.record();
		GARC garc = new GARC(garcFile);
		CameraEditForm form = new CameraEditForm(DataSafetyGuardsTest.REDRAW, UndoHistoryTest.overOneNumber(new int[]{0}));
		JComboBox<?> entries = (JComboBox<?>) MatrixEditFormGuardsTest.field(form, "entryBox");
		int areas = 0;
		int cameras = 0;
		TreeSet<Integer> neutralValues = new TreeSet<>();
		List<String> changed = new ArrayList<>();
		for (int i = 0; i < garc.length; i++) {
			byte[] entry = garc.getDecompressedEntry(i);
			if (entry == null) {
				continue;
			}
			File tmp = File.createTempFile("ctrmap_camform", ".bin");
			tmp.deleteOnExit();
			Files.write(tmp.toPath(), entry);
			FakeGameFiles fake = new FakeGameFiles();
			CameraDataFile cdf;
			try {
				cdf = new CameraDataFile(new AD(tmp, fake));
			} catch (RuntimeException notATable) {
				continue;
			}
			areas++;
			List<byte[]> before = new ArrayList<>();
			for (CameraData c : cdf.camData) {
				before.add(c.bytes());
				if (c.isNeutral != 0) {
					neutralValues.add(c.isNeutral);
				}
			}
			form.loadDataFile(cdf);
			for (int k = 0; k < cdf.camData.size(); k++) {
				entries.setSelectedIndex(k);
			}
			form.saveCamera();
			cameras += cdf.camData.size();
			for (int k = 0; k < cdf.camData.size(); k++) {
				if (!Arrays.equals(before.get(k), cdf.camData.get(k).bytes())) {
					changed.add("area " + i + " camera " + k);
				}
			}
			if (cdf.modified && changed.isEmpty()) {
				changed.add("area " + i + " marked modified");
			}
		}
		check(areas > 200 && cameras > 200, "control: " + areas + " areas and " + cameras + " cameras read");
		check(changed.isEmpty(), "every one shown and switched past with no edit comes back byte for byte: " + changed);
		check(neutralValues.isEmpty() || neutralValues.equals(new TreeSet<>(Arrays.asList(1))),
				"every neutral camera holds the profile's 1: " + neutralValues);
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
