package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.gamedef.ArchiveType;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE TEST WORLD, BUILT (F138).
 *
 * <p>The in-game test world TESTING.md sends the owner to first was built by hand once, on 2026-08-30, packed
 * into one dump, and nothing could build it again: measured 2026-10-08, 518 of 642 commits came after it, so
 * walking it tested that day's editor. This builds it - every time, from the retail archives, through the
 * editor's own operations (zone clone, the painter's Apply, the NPC tool's Add, the warp linker) - and writes
 * only the files it changed as a mod folder, with the commit it was built from beside them.
 *
 * <p>FROM RETAIL, OR SAID. Each archive is taken from the first source whose copy is the retail one by its
 * SHA-256 ({@link #RETAIL}); a source can be a game folder or the decrypted cartridge image itself. With no
 * retail copy anywhere the build refuses, naming the archive - unless it is told {@code --not-vanilla}, and
 * then the stamp says NOT CANONICAL and which archives were not retail, so a world built over an edited game
 * can never be taken for the canonical one.
 *
 * <p>Usage: java ctrmap.tests.TestWorldBuilder &lt;out folder&gt; &lt;source&gt; [&lt;source&gt; ...] [--not-vanilla]
 */
public final class TestWorldBuilder {

	/**
	 * The retail archives the world is cut from: archive, SHA-256, length. MEASURED 2026-10-10 by reading the
	 * RomFS (level 3, 655 files) of the decrypted Omega Ruby cartridge image, 1,898,569,728 bytes; the eight of
	 * them RomFS_original_garcs holds are identical to it byte for byte, so two independent copies agree.
	 */
	static final String[][] RETAIL = {
		{"ZONE_DATA", "6fe152dffba1da2f6e8898906d535ffce0bb0af85693cfd89cae3220e9b7255b", "1042380"},
		{"AREA_DATA", "69b4e37eef3fa72d6965eca0eb241285a2d8e6b4d9fb97e53d9cf8e41162255c", "29406084"},
		{"FIELD_DATA", "31a3335c4356b550e407017de9abd51c8b8ec7d71695eec0842d3f5082541109", "108419976"},
		{"MAP_MATRIX", "019a4fbc076bf0ee38fcabc672bec999f421afad686ab872b3374fdbc174c9bf", "224696"},
		{"GAMETEXT", "e1b2a19aba46696d400bd2adf2d5394415b6db89d90ff2ddf448b6975912cfc6", "2958512"},
		{"BUILDING_MODELS", "44c79ffea5fa365dcaf655e66983aec1a6161109e3d19098c76eb26a401213ee", "3452356"},
		{"NPC_REGISTRIES", "5c1950b73d14eed82db4007774981c21a26514757c01b6377bd321fe4b705a00", "41824"},
		{"MOVE_MODELS", "06c21576ecf38180a456180fbe7baa58f529ca5fd57bf89130e90fe67ce230b6", "22538408"},
		{"STORYTEXT", "19379a35e3f2b3120567c0a580b59ab6ed390a86ce1913c5fbb71977278f20a7", "1653648"},
		{"TRAINER_DATA", "424fc3fa67221fc5987879dcbf7d41b6deb030b6970e2e7b8fbbe885c7ba4c28", "41856"},
		{"TRAINER_CLASS", "7db13fa7f0dba4caeb1d08685add572794be7bef886e1f8f77eaae49a3ab7e98", "10144"},
		{"TRAINER_POKE", "9c257bab9e4fbc1194ecc18b099d92e42b753295a0a5dc73b2b520d50afab04b", "36672"},
		{"SHARED_SCRIPTS", "3b9a5a975c4d17571222719a17db66774557696401fee315e57049a258582200", "503244"},
	};

	/** The location the hub, the buildings zone and the water zone are cloned from: real scenery to paint over. */
	static final String DONOR_LOCATION = "Route 102";

	private TestWorldBuilder() {
	}

	/** What one build did: each step's own account, the slots it used, and where the world went. */
	static final class Built {
		final List<String> said = new ArrayList<>();
		final Map<String, Integer> slots = new LinkedHashMap<>();
		final List<String> notRetail = new ArrayList<>();
		/** Each archive's digest as it was taken from its source, by archive name. */
		final Map<String, String> taken = new LinkedHashMap<>();
		/** The RomFS paths the build changed, which are the world. */
		final List<String> changed = new ArrayList<>();
		/** Everything the NPC tool said or asked while the NPCs were added. */
		final List<String> npcToolSaid = new ArrayList<>();
		/** The trainer ids the challenge battles. */
		int[] trainers;
		File out;
		File game;

		void say(String what) {
			said.add(what);
			System.out.println("  " + what);
		}
	}

	public static void main(String[] args) throws Exception {
		List<String> words = new ArrayList<>(java.util.Arrays.asList(args));
		boolean notVanilla = words.remove("--not-vanilla");
		for (String w : words) {
			if (w.startsWith("-")) {
				System.out.println("REFUSING: " + w + " is not a flag this reads (--not-vanilla) - nothing was built");
				System.exit(2);
			}
		}
		if (words.size() < 2) {
			System.out.println("Usage: java ctrmap.tests.TestWorldBuilder <out folder> <source> [<source> ...] [--not-vanilla]");
			System.exit(2);
		}
		File out = new File(words.get(0));
		List<File> sources = new ArrayList<>();
		for (String s : words.subList(1, words.size())) {
			sources.add(new File(s));
		}
		Built b = build(out, sources, notVanilla);
		System.out.println("built into " + b.out + (b.notRetail.isEmpty() ? "" : " - NOT CANONICAL: " + b.notRetail));
	}

	/** Builds the world into {@code out}, which must not exist yet. */
	static Built build(File out, List<File> sources, boolean notVanilla) throws Exception {
		if (out.exists()) {
			throw new IOException(out + " exists: a build writes into a folder of its own, never over one");
		}
		Built b = new Built();
		b.out = out;
		File work = new File(out, "work");
		b.game = new File(work, "game");
		File ws = new File(work, "ws");
		takeRetail(b, sources, notVanilla);
		openGame(b.game, ws);
		int donor = donor(b);
		b.slots.put(WORLD, firstFreeSlot(b));
		ctrmap.WorkspaceOps.cloneWithOwnMap(Workspace.session(), donor, b.slots.get(WORLD), OPS, () -> { });
		b.say("zone " + donor + " cloned into slot " + b.slots.get(WORLD) + " with its own map");
		pack(b, "the clone");
		paint(b);
		int[] trainers = trainers(b);
		b.trainers = trainers;
		npcs(b, trainers);
		encounters(b, donor);
		interiorPatch(b);
		warps(b);
		emit(b);
		return b;
	}

	// ---------------------------------------------------------------- the output

	/** The mod folder: the changed archives at their RomFS paths, as an emulator's mod loader reads them. */
	static final String ROMFS = "romfs";
	/** What the world was built from and by, beside it. */
	static final String STAMP = "BUILT.txt";

	/**
	 * Writes every archive the build changed - and only those - into out/romfs at its RomFS path, and the stamp:
	 * the code that built it (build.ps1's stamp, the tree's digest included), whether every source was retail,
	 * the slots and trainers taken, each file's digest, and every step's own account.
	 */
	static void emit(Built b) throws IOException {
		java.util.Properties built = buildStamp();
		File romfs = new File(b.out, ROMFS);
		StringBuilder files = new StringBuilder();
		java.util.Set<String> known = new java.util.HashSet<>();
		for (String[] row : RETAIL) {
			String rel = Workspace.getArchivePath(ArchiveType.valueOf(row[0]), GameType.ORAS);
			known.add(new File(b.game.getAbsolutePath() + rel).getCanonicalPath());
			byte[] now = Files.readAllBytes(new File(b.game.getAbsolutePath() + rel).toPath());
			String sha = sha256(now);
			if (sha.equals(b.taken.get(row[0]))) {
				continue;
			}
			File dst = new File(romfs.getAbsolutePath() + rel);
			dst.getParentFile().mkdirs();
			Files.write(dst.toPath(), now);
			b.changed.add(rel.substring(1));
			files.append(rel.substring(1)).append(' ').append(sha).append(' ').append(now.length).append(LF);
		}
		//NOTHING ELSE CHANGED: a file in the game the table does not name - other than detection's empty sound
		//stand-in - is something the build wrote that the world would not ship, and a world missing a file is
		//a world that crashes
		File sound = new File(b.game, ctrmap.gamedef.GameProfile.of(GameType.ORAS).archivePath(ArchiveType.SOUND_BCSAR));
		known.add(sound.getCanonicalPath());
		List<String> stray = new ArrayList<>();
		java.util.Deque<File> walk = new java.util.ArrayDeque<>();
		walk.add(b.game);
		while (!walk.isEmpty()) {
			File[] here = walk.pop().listFiles();
			for (File f : here == null ? new File[0] : here) {
				if (f.isDirectory()) {
					walk.add(f);
				} else if (!known.contains(f.getCanonicalPath())) {
					stray.add(f.getPath());
				}
			}
		}
		if (!stray.isEmpty()) {
			throw new IOException("the build wrote files no retail archive names, which the world would not ship: " + stray);
		}
		StringBuilder st = new StringBuilder();
		st.append("CTRMap test world (F138) - built by ctrmap.tests.TestWorldBuilder").append(LF);
		st.append("commit ").append(built.getProperty("sha")).append(LF);
		st.append("source digest ").append(built.getProperty("src")).append(LF);
		st.append("built ").append(java.time.Instant.now()).append(LF);
		st.append(b.notRetail.isEmpty() ? "CANONICAL: every archive was cut from the retail one" + LF
				: "NOT CANONICAL - not retail: " + b.notRetail + LF);
		st.append("slots ").append(b.slots).append(LF);
		st.append("files ").append(b.changed.size()).append(LF).append(files);
		st.append("steps").append(LF);
		for (String s : b.said) {
			st.append("  ").append(s.replace("\n", " | ")).append(LF);
		}
		Files.write(new File(b.out, STAMP).toPath(), st.toString().getBytes(StandardCharsets.UTF_8));
		b.say("wrote " + b.changed.size() + " changed archive(s) into " + romfs + ": " + b.changed);
	}

	/**
	 * build.ps1's stamp on the classes this runs from: the commit and the tree's digest. A world must say which
	 * code built it, so none - classes no build.ps1 signed - refuses.
	 */
	static java.util.Properties buildStamp() throws IOException {
		File root;
		try {
			root = new File(TestWorldBuilder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (java.net.URISyntaxException | RuntimeException ex) {
			throw new IOException("where these classes were loaded from cannot be told, so neither can the code that built them", ex);
		}
		File stamp = new File(root, ".built-by-build-ps1");
		if (!stamp.isFile()) {
			throw new IOException(root + " carries no build.ps1 stamp, so the world could not say which code built it");
		}
		java.util.Properties p = new java.util.Properties();
		try (java.io.Reader r = Files.newBufferedReader(stamp.toPath(), StandardCharsets.UTF_8)) {
			p.load(r);
		}
		if (p.getProperty("sha") == null || p.getProperty("src") == null) {
			throw new IOException(stamp + " names no commit or no source digest");
		}
		return p;
	}

	/**
	 * The world's wild Pokemon: the donor's table, through the encounter editor's own read and Save - what its
	 * "Copy from zone" does. A clone does not carry it (measured 2026-10-10: the cloned zone's table was not
	 * Route 102's), and the old guide promised Route 102's encounters in the tall grass all the same.
	 */
	static void encounters(Built b, int donor) throws Exception {
		WorkspaceSession s = Workspace.session();
		ctrmap.formats.encounters.EncounterTable table = ctrmap.humaninterface.EncounterEditDialog.readEncounters(s, donor);
		if (table.isEmpty()) {
			throw new IllegalStateException("zone " + donor + " has no wild Pokemon to give the world");
		}
		String said = ctrmap.humaninterface.EncounterEditDialog.saveEncounters(s, b.slots.get(WORLD), table, OPS);
		b.say("wild Pokemon: zone " + donor + "'s table saved for zone " + b.slots.get(WORLD) + " - " + said.replace("\n", " | "));
		pack(b, "the encounters");
	}

	// ---------------------------------------------------------------- the interior and the way in

	/** The 3x2 path patch composite-painted onto the cloned Pokemon Center's floor: local tiles x0, y0 to x1, y1. */
	static final int[] PATCH = {21, 22, 23, 23};

	/** Edit in place on retail geometry: a small patch on the interior's floor, the rest of the room kept. */
	static void interiorPatch(Built b) throws Exception {
		Integer interior = b.slots.get(INTERIOR);
		if (interior == null) {
			throw new IllegalStateException("the Pokemon Center's door was not given a private interior, so there is no room to patch");
		}
		open(interior);
		Paint p = new Paint().rect(PATCH[0], PATCH[1], PATCH[2], PATCH[3], ctrmap.formats.tilemap.TilePalette.PATH, 0);
		Host host = new Host(b);
		String report = ctrmap.MapPainter.applyToZone(loaded, interior, p.grid, p.height, p.ramp,
				ctrmap.formats.tilemap.TerrainLighting.daytime(), false, new ArrayList<ctrmap.MapPainter.Placed>(), p.touched, host);
		b.say("interior patch: " + report.replace("\n", " | "));
		if (!host.errors.isEmpty()) {
			throw new IllegalStateException("the interior's Apply said something went wrong: " + host.errors);
		}
	}

	/** The rival's house in Littleroot: the first door a new game walks through, redirected into the world. */
	static final int RIVAL_HOUSE = 225;
	static final String RIVAL_HOUSE_LOCATION = "Littleroot Town";
	/** Where the world's arrival mat lies, in its cell: the way back to Littleroot. */
	static final java.awt.Point MAT_AT = new java.awt.Point(19, 31);

	/**
	 * The way in and out: an arrival mat in the world, shaped as the rival's house's own exit mat, and every
	 * door into that house sent to it - found in the data, each said - with the mat leading back out of the
	 * first of them.
	 */
	static void warps(Built b) throws Exception {
		WorkspaceSession s = Workspace.session();
		int world = b.slots.get(WORLD);
		byte[] houseHdr = ctrmap.formats.containers.ContainerBytes.subfile(ctrmap.ZoneTables.zoneNow(s, RIVAL_HOUSE), 0);
		String houseName = ctrmap.formats.text.LocationNames.getLocName(s, ctrmap.formats.zone.ZoneHeader.parentMapOf(houseHdr));
		if (!RIVAL_HOUSE_LOCATION.equals(houseName)) {
			throw new IllegalStateException("zone " + RIVAL_HOUSE + " is named " + houseName + ", not " + RIVAL_HOUSE_LOCATION
					+ " - this is not the game the world's way in was measured on");
		}
		List<int[]> doors = new ArrayList<>();
		int zones = ctrmap.ZoneTables.zoneCount(s.getArchive(ArchiveType.ZONE_DATA));
		for (int z = 0; z < zones; z++) {
			if (z == RIVAL_HOUSE) {
				continue;
			}
			List<ctrmap.formats.zone.ZoneEntities.Warp> ws = entities(s, z).warps;
			for (int w = 0; w < ws.size(); w++) {
				if (ws.get(w).targetZone == RIVAL_HOUSE) {
					doors.add(new int[]{z, w});
				}
			}
		}
		if (doors.isEmpty()) {
			throw new IllegalStateException("no warp in the game leads into zone " + RIVAL_HOUSE);
		}
		ctrmap.formats.zone.ZoneEntities.Warp exit = null;
		for (ctrmap.formats.zone.ZoneEntities.Warp w : entities(s, RIVAL_HOUSE).warps) {
			if (w.targetZone == doors.get(0)[0] && w.targetWarpId == doors.get(0)[1]) {
				exit = w;
			}
		}
		if (exit == null) {
			throw new IllegalStateException("zone " + RIVAL_HOUSE + " has no exit back to zone " + doors.get(0)[0] + " warp "
					+ doors.get(0)[1] + ", so the mat has no retail shape to take");
		}
		open(world);
		ctrmap.formats.zone.Zone z = loaded.open();
		int[] cell = ctrmap.MapPainter.firstRegionCell(loaded);
		java.awt.Point at = global(cell, MAT_AT);
		byte[] collision = ctrmap.formats.containers.ContainerBytes.subfile(Files.readAllBytes(
				Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, cell[0]).toPath()), 2);
		ctrmap.formats.zone.ZoneEntities.Warp mat = new ctrmap.formats.zone.ZoneEntities.Warp();
		mat.targetZone = exit.targetZone;
		mat.targetWarpId = exit.targetWarpId;
		mat.directionality = exit.directionality;
		mat.faceDirection = exit.faceDirection;
		mat.transitionType = exit.transitionType;
		mat.coordinateType = exit.coordinateType;
		mat.w = exit.w;
		mat.h = exit.h;
		mat.u14 = exit.u14;
		mat.u16 = exit.u16;
		mat.x = ctrmap.formats.zone.WorldGrid.tileCentre(at.x);
		mat.y = ctrmap.formats.zone.WorldGrid.tileCentre(at.y);
		//THE GROUND UNDER IT, OR NO MAT: the sampler answers NaN where no floor lies, and Math.round(NaN) is 0 - a
		//height nobody measured, written as though one had been
		float ground = ctrmap.formats.tilemap.PaintedHeights.sampleBaseY(collision)[MAT_AT.y][MAT_AT.x];
		if (Float.isNaN(ground)) {
			throw new IllegalStateException("no floor lies under the mat's tile " + MAT_AT + " in region " + cell[0]
					+ "'s collision, so the mat has no height to stand at");
		}
		mat.z = Math.round(ground);
		z.entities.warps.add(mat);
		z.entities.modified = true;
		int matIndex = z.entities.warps.size() - 1;
		b.say("arrival mat: warp " + matIndex + " of zone " + world + " at " + MAT_AT + ", height " + mat.z
				+ ", shaped as zone " + RIVAL_HOUSE + "'s exit mat; saved " + z.store());
		b.say(ctrmap.WorkspaceOps.link(s, world, matIndex, doors.get(0)[0], doors.get(0)[1], true, OPS, () -> { }).replace("\n", " | "));
		for (int[] d : doors.subList(1, doors.size())) {
			b.say(ctrmap.WorkspaceOps.link(s, d[0], d[1], world, matIndex, false, OPS, () -> { }).replace("\n", " | "));
		}
		b.say("doors into zone " + RIVAL_HOUSE + " found: " + doors.size() + " - " + describe(doors) + "; each now leads to the mat");
		pack(b, "the warps");
	}

	static ctrmap.formats.zone.ZoneEntities entities(WorkspaceSession s, int zone) throws IOException {
		byte[] ent = ctrmap.formats.containers.ContainerBytes.subfile(ctrmap.ZoneTables.zoneNow(s, zone), 1);
		if (ent == null) {
			throw new IOException("zone " + zone + "'s entities do not read");
		}
		return new ctrmap.formats.zone.ZoneEntities(ent);
	}

	static String describe(List<int[]> doors) {
		StringBuilder sb = new StringBuilder();
		for (int[] d : doors) {
			sb.append(sb.length() == 0 ? "" : ", ").append("zone ").append(d[0]).append(" warp ").append(d[1]);
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------- trainers and NPCs

	/** The battle challenge's lineup: name, class taken from a retail trainer, and party (species, level). */
	static final Object[][] LINEUP = {
		{"Newbie Nick", new int[][]{{263, 3}}},
		{"Rookie Rita", new int[][]{{261, 5}}},
		{"Boss Bruno", new int[][]{{265, 4}, {276, 6}}},
	};

	/** The retail trainer whose class, AI and money rate the lineup takes: Calvin, Route 102's first. */
	static final int CLASS_FROM = 7;

	/**
	 * Authors the challenge's three trainers through the trainer editor's Save, into entries nothing uses:
	 * a blank name, and no NPC record in any zone that battles it (script 3000 + id, or 5000 + id for a
	 * partner). Chosen from the data, lowest first, and said - never a number written here.
	 */
	static int[] trainers(Built b) throws Exception {
		WorkspaceSession s = Workspace.session();
		int namesEntry = s.profile().textIndex(ctrmap.gamedef.GameProfile.TextIndex.TRAINER_NAMES);
		List<String> names = ctrmap.formats.text.GFMessageFile.parse(s.getArchive(ArchiveType.GAMETEXT)
				.getDecompressedEntry(namesEntry), false);
		java.util.Set<Integer> battled = new java.util.HashSet<>();
		int zones = ctrmap.ZoneTables.zoneCount(s.getArchive(ArchiveType.ZONE_DATA));
		for (int i = 0; i < zones; i++) {
			byte[] ent = ctrmap.formats.containers.ContainerBytes.subfile(ctrmap.ZoneTables.zoneNow(s, i), 1);
			if (ent == null) {
				throw new IOException("zone " + i + "'s entities do not read, so whether it battles a trainer cannot be told");
			}
			for (ctrmap.formats.zone.ZoneEntities.NPC n : new ctrmap.formats.zone.ZoneEntities(ent).npcs) {
				battled.add(n.script);
			}
		}
		GARC td = s.getArchive(ArchiveType.TRAINER_DATA);
		GARC tp = s.getArchive(ArchiveType.TRAINER_POKE);
		ctrmap.formats.trainers.TrainerEntry model = ctrmap.formats.trainers.TrainerEntry.read(
				td.getDecompressedEntry(CLASS_FROM), tp.getDecompressedEntry(CLASS_FROM));
		int[] ids = new int[LINEUP.length];
		int found = 0;
		for (int tid = 1; tid < Math.min(names.size(), td.length) && found < ids.length; tid++) {
			//BLANK AS THE TEXT READS IT: an empty line comes back as the placeholder "[~ id]" - retail 51, 52 and 61
			//read "[~ 51]", "[~ 52]", "[~ 61]" (measured 2026-10-10) - never as an empty string
			String name = names.get(tid).trim();
			boolean blank = name.isEmpty() || name.equals("[~ " + tid + "]");
			if (blank && !battled.contains(ctrmap.formats.scripts.NpcTemplates.TRAINER_SCRIPT_BASE + tid)
					&& !battled.contains(ctrmap.formats.scripts.NpcTemplates.TRAINER_PAIR_SCRIPT_BASE + tid)) {
				ids[found++] = tid;
			}
		}
		if (found < ids.length) {
			throw new IllegalStateException("only " + found + " trainer entries are blank and battled by no NPC; the challenge needs "
					+ ids.length);
		}
		for (int k = 0; k < ids.length; k++) {
			ctrmap.formats.trainers.TrainerEntry t = ctrmap.formats.trainers.TrainerEntry.read(
					td.getDecompressedEntry(ids[k]), tp.getDecompressedEntry(ids[k]));
			t.classId = model.classId;
			t.battleType = 0;
			t.aiFlags = model.aiFlags;
			t.moneyRate = model.moneyRate;
			t.party.clear();
			for (int[] mon : (int[][]) LINEUP[k][1]) {
				ctrmap.formats.trainers.TrainerEntry.PartyMon m = new ctrmap.formats.trainers.TrainerEntry.PartyMon();
				m.species = mon[0];
				m.level = mon[1];
				t.party.add(m);
			}
			ctrmap.humaninterface.TrainerEditDialog.save(ids[k], t, names.get(ids[k]), (String) LINEUP[k][0], s, OPS);
			b.say("trainer " + ids[k] + " (blank, battled by no NPC) authored as " + LINEUP[k][0] + ", class " + t.classId
					+ " (trainer " + CLASS_FROM + "'s), " + t.party.size() + " Pokemon");
		}
		return ids;
	}

	/** Where the NPCs stand, in the world's cell, and the model they wear. */
	static final java.awt.Point TALKER_AT = new java.awt.Point(17, 17), CHALLENGER_AT = new java.awt.Point(21, 17);
	static final int NPC_MODEL = 218;

	/** The talking NPC and the battle challenge, through the NPC tool's Add, then the zone saved. */
	static void npcs(Built b, int[] trainers) throws Exception {
		int zone = b.slots.get(WORLD);
		open(zone);
		ctrmap.formats.zone.Zone z = loaded.open();
		z.s.decompressThis();
		int[] cell = ctrmap.MapPainter.firstRegionCell(loaded);
		ctrmap.humaninterface.NPCEditForm form = new ctrmap.humaninterface.NPCEditForm(loaded,
				new ctrmap.humaninterface.tools.ToolSelection(), new Redraws(), new RecordingNavi(), new RecordingCentre(),
				new RecordingZoneSaver(), new RecordingZoneSaver(), TestWindow.mTileMapPanel(), pane -> { },
				UndoHistoryTest.overOneNumber(new int[]{0}));
		List<String> said = ctrmap.Ui.record(javax.swing.JOptionPane.OK_OPTION, javax.swing.JOptionPane.OK_OPTION);
		try {
			form.loadFromEntities(z.entities, null);
			ctrmap.formats.scripts.GFLPawnScript donor = ctrmap.formats.scripts.ZoneScriptAnalyzer.findMsgWrapper(z.s) != null
					? null : ctrmap.formats.scripts.MsgWrapperInjector.donorFrom(Workspace.session());
			ctrmap.formats.text.GFMessageFile msg = new ctrmap.formats.text.GFMessageFile(Files.readAllBytes(
					Workspace.getWorkspaceFile(ArchiveType.STORYTEXT, z.header.textID).toPath()));
			ctrmap.formats.zone.ZoneEntities.NPC talker = form.addTalker(z, msg,
					"I was put here by the test world builder." + LF + "The one beside me battles you three times in a row.",
					NPC_MODEL, donor, global(cell, TALKER_AT));
			if (talker == null) {
				throw new IllegalStateException("the talking NPC was not added: " + said);
			}
			b.say("talking NPC: script " + talker.script + " at " + TALKER_AT + (donor == null ? "" : ", message routine transplanted"));
			ctrmap.humaninterface.ChallengeInput in = new ctrmap.humaninterface.ChallengeInput();
			for (int tid : trainers) {
				in.trainerIds.add(tid);
			}
			in.model = NPC_MODEL;
			in.milestone = trainers.length;
			in.intro = "Win three battles in a row for a bonus!";
			in.win = "You won! Talk to me again for the next one.";
			in.lose = "You lost - your streak starts over.";
			ctrmap.formats.zone.ZoneEntities.NPC challenger = form.addChallenge(z, in, global(cell, CHALLENGER_AT));
			if (challenger == null) {
				throw new IllegalStateException("the battle challenge was not added: " + said);
			}
			b.say("battle challenge: script " + challenger.script + " at " + CHALLENGER_AT + ", trainers "
					+ java.util.Arrays.toString(trainers) + ", " + in.bpPerWin + " BP a win, " + in.milestoneBonus
					+ " more at a streak of " + in.milestone + ", streak in work variable 0x" + in.streakWorkHex);
		} finally {
			ctrmap.Ui.stopRecording();
			form.disposePreviews();
		}
		b.npcToolSaid.addAll(said);
		if (!said.isEmpty()) {
			b.say("the NPC tool said: " + String.join(" | ", said).replace("\n", " "));
		}
		b.say("zone " + zone + " saved: " + z.store());
		pack(b, "the NPCs");
	}

	/** A tile of the world's cell as a tile of the zone's whole map; {@code cell} is {region, x, y}. */
	static java.awt.Point global(int[] cell, java.awt.Point local) {
		return new java.awt.Point(cell[1] * DIM + local.x, cell[2] * DIM + local.y);
	}

	/** Packs the workspace into the scratch game, where it stands, saying what the pack warned of. */
	static void pack(Built b, String after) throws IOException {
		List<String> warned = Workspace.packArchives((percent, what) -> { });
		b.say("packed after " + after + (warned.isEmpty() ? "" : " - it warned: " + warned));
	}

	// ---------------------------------------------------------------- the map

	static final int DIM = ctrmap.MapPainter.DIM;
	/** The painted square: tiles FROM..TO on both axes; the cell's rim outside it stays the donor's scenery. */
	static final int FROM = 4, TO = 35;

	/** What the world's map cell is painted with: brush, level and ramp per tile, and which tiles the Apply rebuilds. */
	static final class Paint {
		final ctrmap.formats.tilemap.TilePalette[][] grid = new ctrmap.formats.tilemap.TilePalette[DIM][DIM];
		final int[][] height = new int[DIM][DIM];
		final int[][] ramp = new int[DIM][DIM];
		final boolean[][] touched = new boolean[DIM][DIM];

		Paint() {
			for (int y = 0; y < DIM; y++) {
				java.util.Arrays.fill(grid[y], ctrmap.formats.tilemap.TilePalette.GRASS);
				java.util.Arrays.fill(ramp[y], ctrmap.formats.tilemap.PaintedGeometry.NO_RAMP);
			}
		}

		/** Paints x0..x1, y0..y1 inclusive with {@code brush} at {@code level}, and touches them. */
		Paint rect(int x0, int y0, int x1, int y1, ctrmap.formats.tilemap.TilePalette brush, int level) {
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					grid[y][x] = brush;
					height[y][x] = level;
					touched[y][x] = true;
				}
			}
			return this;
		}
	}

	/**
	 * THE LAYOUT. One map cell, 40 tiles square: a path plaza over tiles 4-35 ringed two deep with rock, so
	 * nothing walks off onto the donor's scenery; every station is one brush or one feature, so what breaks
	 * says which. Coordinates are the cell's tiles, x east and y south.
	 */
	static Paint layout() {
		Paint p = new Paint();
		p.rect(FROM, FROM, TO, TO, ctrmap.formats.tilemap.TilePalette.PATH, 0);
		for (int y = FROM; y <= TO; y++) {
			for (int x = FROM; x <= TO; x++) {
				if (x <= FROM + 1 || x >= TO - 1 || y <= FROM + 1 || y >= TO - 1) {
					p.grid[y][x] = ctrmap.formats.tilemap.TilePalette.ROCK;
				}
			}
		}
		//WEST: grass you meet wild Pokemon in, a bike rail, deep sand
		p.rect(7, 7, 12, 11, ctrmap.formats.tilemap.TilePalette.TALL_GRASS, 0);
		p.rect(7, 13, 8, 18, ctrmap.formats.tilemap.TilePalette.BIKE_RAIL, 0);
		p.rect(10, 13, 12, 18, ctrmap.formats.tilemap.TilePalette.DEEP_SAND, 0);
		//NORTH: a plateau one level up - a ramp in the middle of its south edge, a ledge row either side of
		//it, and stairs on its east edge
		p.rect(14, 7, 25, 12, ctrmap.formats.tilemap.TilePalette.GRASS, 1);
		p.rect(14, 12, 18, 12, ctrmap.formats.tilemap.TilePalette.LEDGE_S, 1);
		p.rect(21, 12, 25, 12, ctrmap.formats.tilemap.TilePalette.LEDGE_S, 1);
		p.rect(19, 12, 20, 12, ctrmap.formats.tilemap.TilePalette.PATH, 1);
		p.ramp[12][19] = 2;
		p.ramp[12][20] = 2;
		p.rect(26, 9, 26, 10, ctrmap.formats.tilemap.TilePalette.STAIRS_W, 1);
		//EAST: one column of the brushes a route has no material for - imported from other maps
		p.rect(29, 7, 32, 9, ctrmap.formats.tilemap.TilePalette.CAVE, 0);
		p.rect(29, 11, 32, 13, ctrmap.formats.tilemap.TilePalette.INDOOR, 0);
		p.rect(29, 15, 32, 17, ctrmap.formats.tilemap.TilePalette.WALKWAY, 0);
		p.rect(29, 19, 32, 23, ctrmap.formats.tilemap.TilePalette.ICE, 0);
		p.rect(29, 25, 32, 27, ctrmap.formats.tilemap.TilePalette.SAND, 0);
		//SOUTH OF THE PLAZA: a pool, a waterfall on its south rim
		p.rect(14, 20, 25, 26, ctrmap.formats.tilemap.TilePalette.WATER, 0);
		p.rect(18, 27, 20, 27, ctrmap.formats.tilemap.TilePalette.WATERFALL, 0);
		return p;
	}

	/** A line break as the sign text box holds one. */
	static final String LF = String.valueOf((char) 10);

	/** The plaza's signs, by tile, and what each says. */
	static final Map<String, String> SIGNS = new LinkedHashMap<>();

	static {
		SIGNS.put("16,15", "TEST WORLD - built by CTRMap's test world builder." + LF + "Every station is one brush or feature.");
		SIGNS.put("23,15", "The plateau: walk up the ramp." + LF + "The ledges either side drop you back down.");
	}

	/** What is placed on the plaza: the Pokemon Center (its door cloned a private interior), signs, decor. */
	static List<ctrmap.MapPainter.Placed> placed() {
		List<ctrmap.MapPainter.Placed> out = new ArrayList<>();
		out.add(new ctrmap.MapPainter.Placed(entry("POKECENTER", "Pokemon Center"), 7, 22));
		for (String at : SIGNS.keySet()) {
			String[] xy = at.split(",");
			out.add(new ctrmap.MapPainter.Placed(entry("SIGN", "Sign post"), Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
		}
		out.add(new ctrmap.MapPainter.Placed(entry("FENCE", "Iron fence (4-tile run)"), 13, 29));
		out.add(new ctrmap.MapPainter.Placed(entry("TREE", "Fir tree"), 26, 28));
		return out;
	}

	static ctrmap.formats.h3d.BuildingCatalog.Entry entry(String kind, String name) {
		for (ctrmap.formats.h3d.BuildingCatalog.Entry e : ctrmap.formats.h3d.BuildingCatalog.entries()) {
			if (kind.equals(e.kind) && name.equals(e.name)) {
				return e;
			}
		}
		throw new IllegalStateException("the building catalogue has no " + kind + " named " + name);
	}

	/** The zone owner the painter reads and the panel the editor's tools save through, as the window builds them. */
	static ctrmap.LoadedZone loaded;

	static void open(int zoneIndex) throws Exception {
		loaded.open(zoneIndex, new ctrmap.formats.zone.Zone(new ctrmap.formats.containers.ZO(Workspace.getWorkspaceFile(
				ArchiveType.ZONE_DATA, zoneIndex), Workspace.session()), Workspace.game()));
	}

	/** Paints the world: the ripple first, as the Map Builder offers it, then one Apply of the whole layout. */
	static void paint(Built b) throws Exception {
		int zone = b.slots.get(WORLD);
		loaded = new ctrmap.LoadedZone();
		TestWindow.mZonePnl(new ctrmap.humaninterface.ZoneLoadingPanel(loaded, new ctrmap.humaninterface.tools.ToolSelection(),
				new RecordingEditors(), new ZoneEditorsSpy(), new RecordingNavi(), () -> { }, () -> { },
				UndoHistoryTest.overOneNumber(new int[]{0}), OPS));
		open(zone);
		if (ctrmap.formats.propdata.PropDatabase.get(Workspace.session()) == null) {
			throw new IllegalStateException("the prop database did not build, and the painter places props from it");
		}
		Host host = new Host(b);
		ctrmap.AreaForker.ForkResult own = host.ownArea(loaded, zone, "adding the water animation");
		if (own == null) {
			throw new IllegalStateException("the world's zone could not be given its own area: " + host.said);
		}
		try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Water ripple in area " + own.newArea, Workspace.session())) {
			int cells = ctrmap.MapPainter.enableWaterScroll(loaded, own.newArea);
			unit.commit();
			b.say("water ripple: the sea-scroll animation bound for " + cells + " map cell(s) of area " + own.newArea);
		}
		pack(b, "the area fork and the ripple");
		open(zone);
		Paint p = layout();
		String report = ctrmap.MapPainter.applyToZone(loaded, zone, p.grid, p.height, p.ramp,
				ctrmap.formats.tilemap.TerrainLighting.daytime(), true, placed(), p.touched, host);
		b.say("Apply: " + report.replace("\n", " | "));
		if (!host.errors.isEmpty()) {
			throw new IllegalStateException("the Apply said something went wrong: " + host.errors);
		}
	}

	/**
	 * The painter's questions, answered by the layout: the zone gets its own area through the same prompt the
	 * window shows (answered through the dialog seam), doors clone private interiors, signs take their text.
	 */
	static final class Host implements ctrmap.MapPainter.Host {

		final Built b;
		final List<String> said = new ArrayList<>();
		final List<String> errors = new ArrayList<>();

		Host(Built b) {
			this.b = b;
		}

		@Override
		public ctrmap.AreaForker.ForkResult ownArea(ctrmap.LoadedZone loaded, int zoneIndex, String why) {
			List<String> asked = ctrmap.Ui.record("Give this zone its own area");
			try {
				return ctrmap.humaninterface.AreaForkPrompt.ensurePrivate(loaded, null, zoneIndex, why, OPS, () -> { });
			} finally {
				ctrmap.Ui.stopRecording();
				said.addAll(asked);
			}
		}

		@Override
		public ctrmap.MapPainter.DoorWiring doorWiring(int enterable) {
			return ctrmap.MapPainter.DoorWiring.CLONE;
		}

		@Override
		public boolean transplantSignRoutine(int instructions) {
			return true;
		}

		@Override
		public ctrmap.MapPainter.SignText signText(ctrmap.MapPainter.Placed sign) {
			String text = SIGNS.get(sign.tx + "," + sign.ty);
			return text == null ? null : new ctrmap.MapPainter.SignText(text, 0);
		}

		@Override
		public void tell(String text, String title) {
			b.say(title + ": " + text.replace("\n", " | "));
		}

		@Override
		public void error(String text, String title) {
			errors.add(title + ": " + text);
			b.say("ERROR " + title + ": " + text.replace("\n", " | "));
		}

		@Override
		public void slotTaken(int slot) {
			b.slots.put(INTERIOR, slot);
			b.say("the Pokemon Center's private interior went into slot " + slot);
		}

		@Override
		public void pack(int zoneIndex, String report) {
			try {
				TestWorldBuilder.pack(b, "the Apply");
			} catch (IOException ex) {
				throw new java.io.UncheckedIOException(ex);
			}
		}
	}

	/** The world's outdoor zone, and the private interior its Pokemon Center door is wired to. */
	static final String WORLD = "world", INTERIOR = "interior";

	/** The history the editor's operations keep their changes in; the build keeps nothing to undo. */
	static final ctrmap.humaninterface.SaveHistory OPS = new ctrmap.humaninterface.SaveHistory(
			() -> java.util.Collections.<Object>emptyList());

	/** The first zone whose location name is {@link #DONOR_LOCATION}. */
	static int donor(Built b) throws IOException {
		WorkspaceSession s = Workspace.session();
		int zones = ctrmap.ZoneTables.zoneCount(s.getArchive(ArchiveType.ZONE_DATA));
		for (int i = 0; i < zones; i++) {
			byte[] hdr = ctrmap.formats.containers.ContainerBytes.subfile(ctrmap.ZoneTables.zoneNow(s, i), 0);
			if (hdr != null && hdr.length >= 0x1E && DONOR_LOCATION.equals(ctrmap.formats.text.LocationNames
					.getLocName(s, ctrmap.formats.zone.ZoneHeader.parentMapOf(hdr)))) {
				b.say("donor: zone " + i + ", the first named " + DONOR_LOCATION);
				return i;
			}
		}
		throw new IOException("no zone is named " + DONOR_LOCATION + " - the world is cloned from its scenery");
	}

	/**
	 * The slot the world's zone takes: the first the painter would give a private interior
	 * ({@link ctrmap.MapPainter#interiorSlots}) - an unnamed placeholder nothing reaches - so the world and
	 * its interior are held to one rule, and the interior takes the next. On retail there are two.
	 */
	static int firstFreeSlot(Built b) {
		List<Integer> free = ctrmap.MapPainter.interiorSlots(-1);
		if (free.size() < 2) {
			throw new IllegalStateException("the world needs two free placeholder slots - its zone and its Pokemon Center's"
					+ " interior - and this game has " + free);
		}
		b.say("slots free for the world: " + free + " - the zone takes the first, the interior the next");
		return free.get(0);
	}

	// ---------------------------------------------------------------- the sources

	/** Copies each archive the world reads into the scratch game, from the first source holding the retail one. */
	static void takeRetail(Built b, List<File> sources, boolean notVanilla) throws IOException {
		for (String[] row : RETAIL) {
			ArchiveType type = ArchiveType.valueOf(row[0]);
			String rel = Workspace.getArchivePath(type, GameType.ORAS);
			byte[] chosen = null;
			String from = null;
			byte[] firstPresent = null;
			String firstFrom = null;
			List<String> seen = new ArrayList<>();
			for (File src : sources) {
				byte[] bytes = read(src, rel);
				if (bytes == null) {
					seen.add(src + ": absent");
					continue;
				}
				String sha = sha256(bytes);
				if (firstPresent == null) {
					firstPresent = bytes;
					firstFrom = src.toString();
				}
				if (sha.equals(row[1]) && bytes.length == Long.parseLong(row[2])) {
					chosen = bytes;
					from = src.toString();
					break;
				}
				seen.add(src + ": " + sha.substring(0, 12) + ", " + bytes.length + " bytes");
			}
			if (chosen == null) {
				if (!notVanilla || firstPresent == null) {
					throw new IOException("no source holds the retail " + type + " (" + rel + ", "
							+ row[1].substring(0, 12) + ", " + row[2] + " bytes): " + seen
							+ (firstPresent == null ? "" : ". Give the decrypted cartridge image as a source, or build"
							+ " with --not-vanilla and the world is stamped NOT CANONICAL"));
				}
				chosen = firstPresent;
				from = firstFrom;
				b.notRetail.add(type + " from " + firstFrom);
			}
			File dst = new File(b.game.getAbsolutePath() + rel);
			dst.getParentFile().mkdirs();
			Files.write(dst.toPath(), chosen);
			b.taken.put(row[0], sha256(chosen));
			b.say(type + " from " + from + (b.notRetail.isEmpty() || !b.notRetail.get(b.notRetail.size() - 1)
					.startsWith(type + " ") ? " (retail)" : " (NOT retail)"));
		}
	}

	/** One archive's bytes from a game folder or a decrypted cartridge image; null when it has none. */
	static byte[] read(File source, String rel) throws IOException {
		if (source.isDirectory()) {
			File f = new File(source.getAbsolutePath() + rel);
			return f.isFile() ? Files.readAllBytes(f.toPath()) : null;
		}
		if (source.isFile()) {
			try (CartridgeImage image = new CartridgeImage(source)) {
				return image.file(rel.startsWith("/") ? rel.substring(1) : rel);
			}
		}
		return null;
	}

	static String sha256(byte[] bytes) {
		try {
			StringBuilder sb = new StringBuilder();
			for (byte x : MessageDigest.getInstance("SHA-256").digest(bytes)) {
				sb.append(String.format("%02x", x & 0xFF));
			}
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required", ex);
		}
	}

	/**
	 * A decrypted 3DS cartridge image's RomFS, read only: NCSD, its first partition's NCCH, the IVFC level 3
	 * directory and file tables. An encrypted image is refused, in words.
	 */
	static final class CartridgeImage implements AutoCloseable {

		private static final int MEDIA = 0x200;
		private final RandomAccessFile f;
		private final byte[] dirMeta;
		private final byte[] fileMeta;
		private final long data;

		CartridgeImage(File image) throws IOException {
			f = new RandomAccessFile(image, "r");
			try {
				byte[] head = at(0, 0x200);
				if (!"NCSD".equals(new String(head, 0x100, 4, StandardCharsets.US_ASCII))) {
					throw new IOException(image + " is no cartridge image: no NCSD at 0x100");
				}
				long part0 = u32(head, 0x120) * MEDIA;
				byte[] ncch = at(part0, 0x200);
				if (!"NCCH".equals(new String(ncch, 0x100, 4, StandardCharsets.US_ASCII))) {
					throw new IOException(image + "'s first partition is no NCCH");
				}
				if ((ncch[0x188 + 7] & 0x4) == 0) {
					throw new IOException(image + " is encrypted: decrypt it first, this reads only plain RomFS");
				}
				long romfs = part0 + u32(ncch, 0x1B0) * MEDIA;
				byte[] ivfc = at(romfs, 0x60);
				if (!"IVFC".equals(new String(ivfc, 0, 4, StandardCharsets.US_ASCII))) {
					throw new IOException(image + ": no IVFC header where its RomFS starts");
				}
				long align = 1L << u32(ivfc, 0x4C);
				long l3 = romfs + ((0x60 + u32(ivfc, 0x08) + align - 1) / align) * align;
				byte[] h = at(l3, 0x28);
				if (u32(h, 0) != 0x28) {
					throw new IOException(image + ": its RomFS level 3 header is not 0x28 bytes long");
				}
				dirMeta = at(l3 + u32(h, 0x0C), (int) u32(h, 0x10));
				fileMeta = at(l3 + u32(h, 0x1C), (int) u32(h, 0x20));
				data = l3 + u32(h, 0x24);
			} catch (IOException | RuntimeException ex) {
				f.close();
				throw ex;
			}
		}

		/** The bytes of a RomFS path such as "a/0/1/3"; null when the RomFS has no such file. */
		byte[] file(String path) throws IOException {
			String[] parts = path.split("/");
			int dir = 0;
			for (int i = 0; i < parts.length - 1; i++) {
				dir = child(dir, parts[i]);
				if (dir < 0) {
					return null;
				}
			}
			for (long e = u32(dirMeta, dir + 0x0C); e != 0xFFFFFFFFL; e = u32(fileMeta, (int) e + 0x04)) {
				int fe = (int) e;
				if (parts[parts.length - 1].equals(name(fileMeta, fe + 0x20, (int) u32(fileMeta, fe + 0x1C)))) {
					long size = u64(fileMeta, fe + 0x10);
					if (size > Integer.MAX_VALUE) {
						throw new IOException(path + " is " + size + " bytes, more than this reads");
					}
					return at(data + u64(fileMeta, fe + 0x08), (int) size);
				}
			}
			return null;
		}

		private int child(int dir, String want) {
			for (long c = u32(dirMeta, dir + 0x08); c != 0xFFFFFFFFL; c = u32(dirMeta, (int) c + 0x04)) {
				int ce = (int) c;
				if (want.equals(name(dirMeta, ce + 0x18, (int) u32(dirMeta, ce + 0x14)))) {
					return ce;
				}
			}
			return -1;
		}

		private static String name(byte[] b, int at, int len) {
			return new String(b, at, len, StandardCharsets.UTF_16LE);
		}

		private byte[] at(long off, int n) throws IOException {
			byte[] b = new byte[n];
			f.seek(off);
			f.readFully(b);
			return b;
		}

		private static long u32(byte[] b, int o) {
			return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
		}

		private static long u64(byte[] b, int o) {
			return u32(b, o) | u32(b, o + 4) << 32;
		}

		@Override
		public void close() throws IOException {
			f.close();
		}
	}

	// ---------------------------------------------------------------- the game

	/** Opens the scratch game as the live workspace, the way the application opens one. */
	static void openGame(File game, File ws) throws IOException {
		Workspace.reset();
		Workspace.GAMEDIR_PATH = game.getAbsolutePath();
		Workspace.WORKSPACE_PATH = ws.getAbsolutePath();
		ws.mkdirs();
		//detection looks for the game's own sound archive; an empty stand-in is enough, and the world never ships it
		File sound = new File(game, ctrmap.gamedef.GameProfile.of(GameType.ORAS).archivePath(ArchiveType.SOUND_BCSAR));
		sound.getParentFile().mkdirs();
		Files.write(sound.toPath(), new byte[0]);
		WorkspaceSession session;
		try {
			session = WorkspaceSession.open(ws, game);
		} catch (WorkspaceSession.OpenFailed ex) {
			throw new IOException("the scratch game did not open: " + ex.problems(), ex);
		}
		session.prepareDirectories();
		Workspace.install(session);
		TestWindow.onWorkspaceOpened(session);
		Workspace.snapshotOriginals();
	}
}
