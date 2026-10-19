package ctrmap.tests;

import ctrmap.util.LittleEndianDataOutputStream;
import ctrmap.formats.containers.AD;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.propdata.ADPropRegistry;
import ctrmap.formats.propdata.PropDatabase;
import ctrmap.formats.propdata.PropRegistration;
import ctrmap.gamedef.ArchiveType;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A prop is registered in an area one way, whichever button placed it (F107).
 *
 * <p>WHY THIS SUITE EXISTS. The prop palette, the prop form's registration of a typed model and the
 * map painter's doors each registered a prop their own way: one asked before growing the area's
 * texture pack and two did not; one checked the donor and the merged pack and two only that the
 * pack parsed; one told the prop database about the import and two left it behind the disk; one
 * zeroed the template's event bindings and two kept them; one fell back to a bare entry on a
 * template that would not read and the others failed.
 *
 * <p>ORDER: needs no dump and no display. A game is built here - a zone table, an area to register
 * into, a donor area holding the textures and the model's retail entry, three building models - and
 * the one registration is driven on it; the three callers are read in their source.
 *
 * Usage: java ctrmap.tests.PropRegistrationTest
 */
public class PropRegistrationTest {

	static int fails = 0;

	static final int TARGET = 7;
	static final int DONOR = 9;

	public static void main(String[] args) throws Exception {
		aPropIsImportedAndRegisteredOnce();
		aDeclinedImportWritesNothing();
		aSharedAreaIsRefusedBeforeAnythingIsWritten();
		aModelTheAreaHasIsNotRegisteredTwice();
		aModelWithNoEntryToCopyGetsABareOneAndSaysSo();
		theDonorIsReadFromDisk();
		aTextureNoAreaHoldsIsRefusedBeforeAnythingIsWritten();
		aStagedAreaTellsTheDatabaseOnlyWhenWritten();
		noFreeReferenceRefusesBeforeThePackGrows();
		aMergedPackIsVerifiedBeforeItIsWritten();
		everyCallerRegistersThroughTheOneWay();
		aRegistrationFromTheFormIsOneEdit();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	// ------------------------------------------------------------ the game

	/** Model 1's retail entry, with event bindings a door would carry. */
	static byte[] doorEntry() throws Exception {
		ADPropRegistry.ADPropRegistryEntry e = new ADPropRegistry.ADPropRegistryEntry();
		e.reference = 1;
		e.model = 1;
		e.eventScr1 = 5;
		e.eventScr2 = 6;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(out);
		e.write(dos);
		dos.close();
		return out.toByteArray();
	}

	/** A registry subfile holding these 0x50-byte entries. */
	static byte[] registry(byte[]... entries) {
		byte[] r = new byte[4 + entries.length * 0x50];
		MapResizeTest.putI32(r, 0, entries.length);
		for (int i = 0; i < entries.length; i++) {
			System.arraycopy(entries[i], 0, r, 4 + i * 0x50, 0x50);
		}
		return r;
	}

	/** An area container: registry, prop pack, nine empty subfiles, world pack. */
	static byte[] area(byte[] registry, byte[] propPack) {
		byte[][] subs = new byte[12][];
		Arrays.fill(subs, new byte[0]);
		subs[0] = registry;
		subs[1] = propPack;
		subs[11] = HandedGameTest.pack("tex_w");
		return HandedGameTest.container("AD", subs);
	}

	/**
	 * Zones on the given areas; area 7 holds tex_c and no entry; area 9 holds tex_a and tex_b and
	 * model 1's retail entry; models 1 "door01" and 2 "lamp01", the second registered nowhere.
	 */
	static FakeGameFiles game(int... zoneAreas) throws Exception {
		FakeGameFiles g = HandedGameTest.zoneGame(zoneAreas);
		byte[][] areas = new byte[10][];
		for (int i = 0; i < areas.length; i++) {
			areas[i] = HandedGameTest.container("AD", new byte[4], new byte[0]);
		}
		areas[TARGET] = area(registry(), HandedGameTest.pack("tex_c"));
		areas[DONOR] = area(registry(doorEntry()), HandedGameTest.pack("tex_a", "tex_b"));
		File ad = new File(g.root(), "areadata.garc");
		HandedGameTest.writeGarc(ad, areas);
		File bm = new File(g.root(), "buildingmodels.garc");
		HandedGameTest.writeGarc(bm, HandedGameTest.container("BM", HandedGameTest.bch(PropDatabase.DUMMY_MODEL_NAME, 1)),
				HandedGameTest.container("BM", HandedGameTest.bch("door01", 1)),
				HandedGameTest.container("BM", HandedGameTest.bch("lamp01", 1)));
		return g.open(ArchiveType.AREA_DATA, new GARC(ad)).open(ArchiveType.BUILDING_MODELS, new GARC(bm));
	}

	static PropDatabase db(FakeGameFiles g) {
		return PropDatabase.build(g.archive(ArchiveType.BUILDING_MODELS), g.archive(ArchiveType.AREA_DATA));
	}

	/** An area written at once, as the prop form's is. */
	static class Written implements PropRegistration.Area {

		final AD ad;
		final int id;
		final int zone;
		final ADPropRegistry reg;
		int changed;

		Written(FakeGameFiles g, int id, int zone) {
			this.ad = new AD(g.staged(ArchiveType.AREA_DATA, id), g);
			this.id = id;
			this.zone = zone;
			this.reg = new ADPropRegistry(ad);
		}

		@Override
		public int id() {
			return id;
		}

		@Override
		public int editingZone() {
			return zone;
		}

		@Override
		public byte[] file(int subfile) {
			return ad.getFile(subfile);
		}

		@Override
		public void put(int subfile, byte[] data) {
			ad.storeFile(subfile, data);
		}

		@Override
		public ADPropRegistry registry() {
			return reg;
		}

		@Override
		public void registryChanged() {
			changed++;
			reg.modified = true;
		}

		@Override
		public void whenWritten(Runnable then) {
			then.run();
		}
	}

	/** An area whose writes wait for a commit, as the painter's Apply's do. */
	static final class Staged extends Written {

		final List<Runnable> waiting = new ArrayList<>();

		Staged(FakeGameFiles g, int id, int zone) {
			super(g, id, zone);
		}

		@Override
		public void whenWritten(Runnable then) {
			waiting.add(then);
		}
	}

	/** A voice that answers as told and keeps what it was asked and told. */
	static final class Heard implements PropRegistration.Voice {

		final boolean answer;
		final List<String> asked = new ArrayList<>();
		final List<String> notes = new ArrayList<>();

		Heard(boolean answer) {
			this.answer = answer;
		}

		@Override
		public boolean importFrom(int donorArea, List<String> textures) {
			asked.add(donorArea + " " + textures);
			return answer;
		}

		@Override
		public void note(String line) {
			notes.add(line);
		}
	}

	static Set<String> names(String... n) {
		return new LinkedHashSet<>(Arrays.asList(n));
	}

	static byte[] onDisk(FakeGameFiles g, int area) throws Exception {
		return Files.readAllBytes(g.staged(ArchiveType.AREA_DATA, area).toPath());
	}

	// ------------------------------------------------------------ the one way

	static void aPropIsImportedAndRegisteredOnce() throws Exception {
		System.out.println("--- a prop's missing textures are imported and it is registered from its retail entry (F107)");
		FakeGameFiles g = game(TARGET, DONOR);
		PropDatabase db = db(g);
		check(PropDatabase.getTexturePackTextureNames(HandedGameTest.pack("tex_c")).equals(names("tex_c")),
				"control: a pack built here reads back its names");
		Written area = new Written(g, TARGET, 0);
		Heard voice = new Heard(true);
		PropRegistration.Registered r = PropRegistration.register(g, db, db.getModel(1), names("tex_a", "tex_b"), area, voice);
		check(r != null && !r.already && r.reference == 1, "registered under its model number: " + (r == null ? null : r.reference));
		check(voice.asked.equals(Arrays.asList(DONOR + " [tex_a, tex_b]")), "having asked once, naming the donor and the textures: " + voice.asked);
		Set<String> nowHeld = PropDatabase.getTexturePackTextureNames(new AD(g.staged(ArchiveType.AREA_DATA, TARGET), g).getFile(1));
		check(nowHeld.containsAll(names("tex_a", "tex_b", "tex_c")), "the area's pack holds them, and what it held: " + nowHeld);
		check(r != null && r.imported.size() == 2, "and they are handed back to be drawn with: " + (r == null ? 0 : r.imported.size()));
		ADPropRegistry.ADPropRegistryEntry e = area.reg.entries.get(1);
		check(e != null && e.model == 1 && e.eventScr1 == 5 && e.eventScr2 == 6,
				"the entry is the retail one, its event bindings kept: " + (e == null ? null : e.eventScr1 + "/" + e.eventScr2));
		check(area.changed == 1, "and the registry is marked changed, once");
		check(db.areaTextureNames.get(TARGET).containsAll(names("tex_a", "tex_b")),
				"the prop database is told the area holds them now: " + db.areaTextureNames.get(TARGET));
	}

	static void aDeclinedImportWritesNothing() throws Exception {
		System.out.println("--- an import the user declines writes nothing (F107)");
		FakeGameFiles g = game(TARGET, DONOR);
		PropDatabase db = db(g);
		byte[] before = onDisk(g, TARGET);
		Written area = new Written(g, TARGET, 0);
		Heard voice = new Heard(false);
		PropRegistration.Registered r = PropRegistration.register(g, db, db.getModel(1), names("tex_a"), area, voice);
		check(r == null && voice.asked.size() == 1, "declined, it answers nothing");
		check(Arrays.equals(before, onDisk(g, TARGET)) && g.edited().isEmpty() && area.reg.entries.isEmpty() && area.changed == 0,
				"and the area, its registry and the edit list are as they were");
		check(!db.areaTextureNames.get(TARGET).contains("tex_a"), "and the database was told nothing");
	}

	static void aSharedAreaIsRefusedBeforeAnythingIsWritten() throws Exception {
		System.out.println("--- an area other zones draw from is not grown (F107)");
		FakeGameFiles g = game(TARGET, TARGET, DONOR);
		PropDatabase db = db(g);
		byte[] before = onDisk(g, TARGET);
		Written area = new Written(g, TARGET, 0);
		String refused = null;
		try {
			PropRegistration.register(g, db, db.getModel(1), names("tex_a"), area, new Heard(true));
		} catch (RuntimeException ex) {
			refused = ex.getMessage();
		}
		check(refused != null && refused.contains("zone 1"), "refused, naming the other zone: " + refused);
		check(Arrays.equals(before, onDisk(g, TARGET)) && g.edited().isEmpty() && area.reg.entries.isEmpty(),
				"with nothing written and nothing registered");
	}

	static void aModelTheAreaHasIsNotRegisteredTwice() throws Exception {
		System.out.println("--- a model the area registers already is answered, not registered again (F107)");
		FakeGameFiles g = game(TARGET, DONOR);
		PropDatabase db = db(g);
		Written donor = new Written(g, DONOR, 1);
		Heard voice = new Heard(true);
		PropRegistration.Registered r = PropRegistration.register(g, db, db.getModel(1), names("tex_a"), donor, voice);
		check(r != null && r.already && r.reference == 1 && voice.asked.isEmpty() && donor.changed == 0 && g.edited().isEmpty(),
				"its reference is answered, nothing asked and nothing written");
	}

	static void aModelWithNoEntryToCopyGetsABareOneAndSaysSo() throws Exception {
		System.out.println("--- a model with no retail entry to copy gets a bare one, and says so (F107)");
		FakeGameFiles g = game(TARGET, DONOR);
		PropDatabase db = db(g);
		Written area = new Written(g, TARGET, 0);
		Heard voice = new Heard(true);
		PropRegistration.Registered r = PropRegistration.register(g, db, db.getModel(2), area, voice);
		ADPropRegistry.ADPropRegistryEntry e = r == null ? null : area.reg.entries.get(r.reference);
		check(e != null && e.model == 2 && e.eventScr1 == 0, "through the model's own textures, a bare entry is registered");
		check(voice.notes.size() == 1 && voice.notes.get(0).startsWith("lamp01 has no retail registry entry"),
				"and the user is told: " + voice.notes);

		FakeGameFiles g2 = game(TARGET, DONOR);
		PropDatabase db2 = db(g2);
		db2.getModel(1).template = new byte[4];
		Written area2 = new Written(g2, TARGET, 0);
		Heard voice2 = new Heard(true);
		PropRegistration.Registered r2 = null;
		try {
			r2 = PropRegistration.register(g2, db2, db2.getModel(1), names(), area2, voice2);
		} catch (RuntimeException ex) {
			voice2.notes.add("threw " + ex);
		}
		check(r2 != null && area2.reg.entries.get(r2.reference) != null && voice2.notes.size() == 1
				&& voice2.notes.get(0).contains("would not read"),
				"an entry that will not read is registered bare too, and said: " + voice2.notes);
	}

	/**
	 * A model drawing with a texture no area holds is refused before anything is written: placed without it, the area
	 * hardlocks the game when it loads.
	 *
	 * <p>Measured by the 2026-10-14 shared sweep (PropRegistration 151, dropped, SURVIVED): every model this suite
	 * registered had a donor for what it lacked. Dropped, the registration went on with no donor to import from.
	 */
	static void aTextureNoAreaHoldsIsRefusedBeforeAnythingIsWritten() throws Exception {
		System.out.println("--- a model drawing with a texture no area holds is refused before anything is written (F107)");
		FakeGameFiles g = game(TARGET, DONOR);
		PropDatabase db = db(g);
		byte[] before = onDisk(g, TARGET);
		Heard voice = new Heard(true);
		String refused;
		try {
			PropRegistration.Registered r = PropRegistration.register(g, db, db.getModel(1), names("tex_nowhere"),
					new Written(g, TARGET, 0), voice);
			refused = "it registered " + (r == null ? "nothing" : "reference " + r.reference);
		} catch (RuntimeException ex) {
			refused = ex.getMessage();
		}
		check(String.valueOf(refused).endsWith("draws with textures this area does not have [tex_nowhere], and no single area"
				+ " holds them all to import from. Placed without them it would hardlock the game when the area loads."),
				"a texture no area holds is refused, naming it and why: " + refused);
		check(Arrays.equals(before, onDisk(g, TARGET)) && g.edited().isEmpty() && voice.asked.isEmpty(),
				"with nothing written and nobody asked");
	}

	static void theDonorIsReadFromDisk() throws Exception {
		System.out.println("--- the donor is read as the workspace holds it, not as the database remembers it (F107)");
		FakeGameFiles g = game(TARGET, DONOR);
		PropDatabase db = db(g);
		db.registerImportedTextures(DONOR, names("tex_x"));
		byte[] before = onDisk(g, TARGET);
		String refused = null;
		try {
			PropRegistration.register(g, db, db.getModel(1), names("tex_x"), new Written(g, TARGET, 0), new Heard(true));
		} catch (RuntimeException ex) {
			refused = ex.getMessage();
		}
		check(refused != null && refused.contains("does not hold all of"), "a name the database has and the disk has not is refused: " + refused);
		check(Arrays.equals(before, onDisk(g, TARGET)) && g.edited().isEmpty(), "with nothing written");
	}

	static void aStagedAreaTellsTheDatabaseOnlyWhenWritten() throws Exception {
		System.out.println("--- a staged area tells the database of an import only once it is written (F107)");
		FakeGameFiles g = game(TARGET, DONOR);
		PropDatabase db = db(g);
		Staged area = new Staged(g, TARGET, 0);
		PropRegistration.register(g, db, db.getModel(1), names("tex_a"), area, new Heard(true));
		check(!db.areaTextureNames.get(TARGET).contains("tex_a") && area.waiting.size() == 1,
				"staged, the database does not hear of it yet");
		area.waiting.get(0).run();
		check(db.areaTextureNames.get(TARGET).contains("tex_a"), "and once written, it does");
	}

	static void noFreeReferenceRefusesBeforeThePackGrows() throws Exception {
		System.out.println("--- a registration that cannot finish writes nothing (F107)");
		FakeGameFiles g = game(TARGET, DONOR);
		PropDatabase db = db(g);
		Written area = new Written(g, TARGET, 0);
		for (int ref = 0xFFF0; ref <= 0xFFFF; ref++) {
			ADPropRegistry.ADPropRegistryEntry taken = new ADPropRegistry.ADPropRegistryEntry();
			taken.reference = ref;
			area.reg.entries.put(ref, taken);
		}
		PropDatabase.PropModel high = new PropDatabase.PropModel(0xFFF0);
		high.donorAreas.add(DONOR);
		byte[] before = onDisk(g, TARGET);
		String refused = null;
		try {
			PropRegistration.register(g, db, high, names("tex_a"), area, new Heard(true));
		} catch (RuntimeException ex) {
			refused = ex.getMessage();
		}
		check(refused != null && refused.contains("no prop reference is free"), "with no reference free it is refused: " + refused);
		check(Arrays.equals(before, onDisk(g, TARGET)) && g.edited().isEmpty(), "before the area's pack grew");
	}

	static void aMergedPackIsVerifiedBeforeItIsWritten() {
		System.out.println("--- a merged pack is read back and counted before it is written (F107)");
		check(PropRegistration.verified(HandedGameTest.pack("tex_a", "tex_c"), Arrays.asList("tex_a")).size() == 1,
				"control: a pack holding the import verifies");
		String lacks = null;
		try {
			PropRegistration.verified(HandedGameTest.pack("tex_a"), Arrays.asList("tex_a", "tex_b"));
		} catch (RuntimeException ex) {
			lacks = ex.getMessage();
		}
		check(lacks != null && lacks.contains("holds 1 of the 2"), "a pack missing an import is refused: " + lacks);
		String unread = null;
		try {
			PropRegistration.verified("BCH\0 not a pack at all".getBytes(StandardCharsets.US_ASCII), Arrays.asList("tex_a"));
		} catch (RuntimeException ex) {
			unread = String.valueOf(ex.getMessage());
		}
		check(unread != null && unread.contains("does not verify"), "and one that does not read is refused as unreadable: " + unread);
	}

	// ------------------------------------------------------------ the callers

	/**
	 * A registration from the prop form is ONE edit of the zone's history (F209): the textures written into
	 * the area and the registry entry it adds. The registry is saved into the same area file the textures went
	 * into, so the Undo is of the textures' SUBFILE: a save of the registry between them leaves it standing.
	 */
	static void aRegistrationFromTheFormIsOneEdit() throws Exception {
		System.out.println("--- a registration from the prop form is one edit of the zone's history, and outlives a save (F209)");
		FakeGameFiles g = game(TARGET, DONOR);
		PropDatabase db = db(g);
		AD ad = new AD(g.staged(ArchiveType.AREA_DATA, TARGET), g);
		ADPropRegistry reg = new ADPropRegistry(ad);
		Object scope = new Object();
		ctrmap.humaninterface.UndoHistory h = new ctrmap.humaninterface.UndoHistory(() -> java.util.Collections.singletonList(scope),
				() -> java.util.Collections.<ctrmap.humaninterface.UndoHistory.Document<?>>singletonList(
						ctrmap.humaninterface.ZoneDocuments.propRegistry(reg, () -> {
						})));
		h.settle();
		byte[] packBefore = ad.getFile(1);
		PropRegistration.Area area = new ctrmap.humaninterface.PropEditForm.ThroughHistory(h, ad, TARGET, 0, reg, db);
		PropRegistration.Registered r = PropRegistration.register(g, db, db.getModel(1), names("tex_a", "tex_b"), area, new Heard(true));
		check(r != null && h.settle() && "the area's prop textures and 1 more".equals(h.undoName()),
				"the textures written and the registry entry added are one edit: " + h.undoName());
		reg.write();   //Save props: the registry is stored into subfile 0 of the same area file
		check(h.undo() && Arrays.equals(ad.getFile(1), packBefore) && !reg.entries.containsKey(1),
				"Undo after the registry was saved takes back both: the pack as it was, the entry gone");
		check(!db.areaTextureNames.get(TARGET).contains("tex_a"),
				"and the database's names for the area are its pack's again: " + db.areaTextureNames.get(TARGET));
		check(h.redo() && PropDatabase.getTexturePackTextureNames(ad.getFile(1)).containsAll(names("tex_a", "tex_b"))
				&& reg.entries.containsKey(1), "and Redo puts both again");
	}

	static String source(String path) throws Exception {
		return SourceSeamTest.stripComments(new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8));
	}

	static void everyCallerRegistersThroughTheOneWay() throws Exception {
		System.out.println("--- every caller registers a prop through the one way (F107)");
		String pef = source("src/ctrmap/humaninterface/PropEditForm.java");
		String palette = CollisionReadTest.body(pef, "private void placeSelectedPaletteProp(");
		String typed = CollisionReadTest.body(pef, "private ADPropRegistry.ADPropRegistryEntry autoRegisterProp(");
		String here = CollisionReadTest.body(pef, "private PropRegistration.Registered registerHere(");
		check(palette != null && palette.contains("registerHere(pm, true)"), "the palette registers through it, asking");
		check(typed != null && typed.contains("registerHere(pm, false)"), "a typed model number through it, not asking");
		//the question is the form's named voice since 2026-10-15 (PropEditForm.FormVoice), asked by itself in
		//PropEditFormGuardsTest; read here as the one registerHere hands the registration
		String voice = CollisionReadTest.body(pef, "public boolean importFrom(int donorArea, List<String> textures) {");
		check(here != null && here.contains("PropRegistration.register(") && here.contains("new FormVoice(this, ask,")
				&& voice != null && voice.indexOf("refuseSharedArea(") >= 0
				&& voice.indexOf("refuseSharedArea(") < voice.indexOf("ctrmap.Ui.confirm("),
				"and the form's question refuses a shared area before it is asked");
		check(here != null && here.contains("new ThroughHistory(history,"),
				"and the prop form hands its registrations' area the zone's history, so each is an edit of it (F209)");
		String tpf =source("src/ctrmap/MapPainter.java");
		String door = CollisionReadTest.body(tpf, "public static int ensureDoorPropRegistered(");
		check(door != null && door.contains("PropRegistration.register("), "the painter's doors through it");
		String commit = CollisionReadTest.body(tpf, "void commit(");
		String waits = CollisionReadTest.body(tpf, "public void whenWritten(");
		check(waits != null && waits.contains("afterCommit.add(") && commit != null
				&& commit.indexOf("afterCommit") > commit.indexOf("storeFile("),
				"whose staged area tells the database only after the Apply writes it");

		//nothing else parses a registry template or imports into an area: the duplication cannot grow back
		Pattern parses = Pattern.compile("\\.template\\s*\\)|importIntoArea\\s*\\(|registerImportedTextures\\s*\\(");
		check(parses.matcher("x = new E(new In(new B(pm.template)));").find(), "control: a template parse is seen");
		List<String> found = new ArrayList<>();
		for (File f : CollisionReadTest.javaFiles(new File("src/ctrmap"), new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			Matcher m = parses.matcher(source(f.getPath()));
			while (m.find()) {
				found.add(f.getName());
			}
		}
		Set<String> where = new HashSet<>(found);
		where.remove("PropDatabase.java");      //registerImportedTextures' own declaration
		where.remove("BchTexturePack.java");    //importIntoArea's own declaration
		check(found.contains("PropRegistration.java") && where.equals(new HashSet<>(Arrays.asList("PropRegistration.java"))),
				"only the one way does it: " + found);
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
