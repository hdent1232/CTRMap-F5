package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.formats.tilemap.TerrainCatalog;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.util.Arrays;

/**
 * A terrain material that cannot be imported from the pristine copy says so; it does not leave the
 * map on whatever material it already had (F092).
 *
 * <p>WHY THIS SUITE EXISTS. Both importers documented that a failed import throws, naming the
 * material and the reason, because painting would otherwise fall back to a material the map
 * already has. Four of their failure paths instead returned the model unchanged - a donor region
 * the snapshot did not hold, one whose model subfile was not a map model, an append that produced
 * no mesh of that name, a merged model that failed validation - and the cliff importer printed why
 * only when started with {@code -Dcliffdebug}. With no snapshot to cut from at all, saying nothing
 * is right, and stays right.
 *
 * <p>ORDER: needs no dump. The pristine snapshot here holds a FieldData archive whose every region
 * carries a model subfile that is not a map model; the model painted into is the bundled
 * DummyBCH3DModel.bch.
 *
 * Usage: java ctrmap.tests.MaterialImportTest
 */
public class MaterialImportTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		byte[] model = BchSectionsTest.resource("ctrmap/resources/DummyBCH3DModel.bch");
		Workspace.reset();
		try {
			WorkspaceSession bare = game("none", false);
			TerrainCatalog.ImportResult quiet = TerrainCatalog.ensureCliffMaterial(bare, model);
			check(quiet.model == model && !quiet.injected, "control: with no snapshot to cut from, the cliff import"
					+ " leaves the model as it is and says nothing");
			WorkspaceSession broken = game("broken", true);
			check(broken.pristine() != null, "control: the second game has a pristine snapshot");
			String said = refusal(() -> TerrainCatalog.ensureCliffMaterial(broken, model));
			check(said.contains("Could not import") && said.contains("not a map model"),
					"the cliff import whose donor region holds no map model refuses, saying why: " + said);
			//a donor region the snapshot does not hold at all (the 2026-10-14 shared sweep, TerrainCatalog 433,
			//drop-throw, SURVIVED: every snapshot here held every region, so the refusal was never reached)
			WorkspaceSession shortOne = game("short", 1);
			String missing = refusal(() -> TerrainCatalog.ensureCliffMaterial(shortOne, model));
			check(missing.contains("Could not import") && missing.contains("the pristine copy holds no region "),
					"a cliff donor region the snapshot does not hold is refused, naming it: " + missing);
			//WHAT THE APPENDER RETURNS, asked (the 2026-10-17 sweep, TerrainCatalog 450 and 458, dropped and negated: HOLES,
			//"no input reaches it short of breaking the appender"): appenders handed in through the seam, over a snapshot
			//whose regions hold a real map model
			WorkspaceSession modelled = game("modelled", 654, model);
			String asIs = refusal(() -> TerrainCatalog.ensureCliffMaterial(modelled, model, (m, donor, mesh, name) -> m));
			check(asIs.contains("Could not import") && asIs.contains("the append produced no mesh named ctr_gake"),
					"an append that names no mesh is refused, naming the material: " + asIs);
			String clean = refusal(() -> TerrainCatalog.ensureCliffMaterial(modelled, model,
					(m, donor, mesh, name) -> renamed(m, "Material", name)));
			check(!clean.contains("produced no mesh") && !clean.contains("failed validation"),
					"control: an append whose merge names the mesh and validates passes both checks: " + clean);
			String invalid = refusal(() -> TerrainCatalog.ensureCliffMaterial(modelled, model,
					(m, donor, mesh, name) -> culled(renamed(m, "Material", name))));
			check(invalid.contains("Could not import") && invalid.contains("the merged model failed validation: SubMeshCullings empty"),
					"and one whose merge does not validate is refused, saying why: " + invalid);
			ctrmap.formats.h3d.BchMapModel probe = new ctrmap.formats.h3d.BchMapModel(model);
			//the lookup both importers ask before cutting, and after (F092): each mesh's own material
			//finds the FIRST mesh drawing it, and a name the model lacks finds none
			java.util.List<String> misfound = new java.util.ArrayList<>();
			for (int i = 0; i < probe.meshCount; i++) {
				String name = probe.getMaterialName(probe.getMeshMaterialIndex(i));
				int first = -1;
				for (int j = 0; j < probe.meshCount && first < 0; j++) {
					first = name.equals(probe.getMaterialName(probe.getMeshMaterialIndex(j))) ? j : -1;
				}
				if (probe.meshWithMaterial(name) != first) {
					misfound.add(name + " at " + probe.meshWithMaterial(name) + ", not " + first);
				}
			}
			check(probe.meshCount > 0 && misfound.isEmpty() && probe.meshWithMaterial("no such material") == -1,
					"each of the model's " + probe.meshCount + " meshes is found by its material's name, and a name"
					+ " it lacks is not" + (misfound.isEmpty() ? "" : " - misfound: " + misfound));
			//the bundled model has one mesh, and the appender will not take it as a donor, so FIRST is
			//measured on a twin: a second mesh entry drawing the same material, which a lookup that
			//kept the last match would answer instead
			ctrmap.formats.h3d.BchMapModel twin = new ctrmap.formats.h3d.BchMapModel(model);
			twin.meshes.add(twin.meshes.get(0).clone());
			String drawn = twin.getMaterialName(twin.getMeshMaterialIndex(1));
			check(twin.meshes.size() == 2 && twin.meshWithMaterial(drawn) == 0,
					"of two meshes drawing " + drawn + ", the lookup answers the first: " + twin.meshWithMaterial(drawn));
			int refused = 0;
			int own = 0;
			java.util.List<String> quietFallback = new java.util.ArrayList<>();
			for (TilePalette brush : TerrainCatalog.donors().keySet()) {
				String r = refusal(() -> TerrainCatalog.ensureMaterial(broken, model, brush));
				if (r.contains("Could not import") && r.contains("not a map model")) {
					refused++;
				} else if (ctrmap.formats.tilemap.PaintedMaterials.hasMaterialFor(probe, brush)) {
					own++; //the map paints this brush with its own material: nothing to cut, nothing to say
				} else {
					quietFallback.add(brush + ": " + r);
				}
			}
			check(refused > 0 && quietFallback.isEmpty(), "every brush whose donor had to be cut refuses, naming why ("
					+ refused + " refused, " + own + " already the map's own)"
					+ (quietFallback.isEmpty() ? "" : " - these returned quietly: " + quietFallback));
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	interface Import {

		TerrainCatalog.ImportResult run() throws Exception;
	}

	/** What an import said: its refusal, or "returned" and whether it changed the model. */
	static String refusal(Import i) {
		try {
			TerrainCatalog.ImportResult r = i.run();
			return "returned (" + (r.injected ? "injected" : "unchanged") + ")";
		} catch (Exception ex) {
			return String.valueOf(ex.getMessage());
		}
	}

	/** A bare ORAS session; with a snapshot whose FieldData holds 654 regions with no map model in them. */
	static WorkspaceSession game(String name, boolean snapshot) throws Exception {
		return game(name, snapshot ? 654 : -1);
	}

	/** {@code model} with its one material named {@code to} instead of {@code from}: names of one length, written over. */
	static byte[] renamed(byte[] model, String from, String to) {
		byte[] was = (from + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		byte[] now = (to + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		if (was.length != now.length) {
			throw new IllegalArgumentException("a rename in place needs names of one length: " + from + ", " + to);
		}
		byte[] out = model.clone();
		int at = -1;
		for (int i = 0; i + was.length <= out.length; i++) {
			if (Arrays.equals(Arrays.copyOfRange(out, i, i + was.length), was)) {
				if (at >= 0) {
					throw new IllegalArgumentException(from + " is in the model more than once");
				}
				at = i;
			}
		}
		if (at < 0) {
			throw new IllegalArgumentException(from + " is not in the model");
		}
		System.arraycopy(now, 0, out, at, now.length);
		return out;
	}

	/** {@code model} with its model header's SubMeshCullings count set to one: it parses, and says it should be none. */
	static byte[] culled(byte[] model) {
		byte[] out = model.clone();
		int count = new ctrmap.formats.h3d.BchMapModel(model).modelPtr + 0x6C;
		out[count] = 1;
		return out;
	}

	/** A bare ORAS session; with a snapshot of {@code regions} regions with no map model in them, or none when -1. */
	static WorkspaceSession game(String name, int regionCount) throws Exception {
		return game(name, regionCount, null);
	}

	/** A bare ORAS session; with a snapshot of {@code regionCount} regions, each holding {@code model} - or no map model. */
	static WorkspaceSession game(String name, int regionCount, byte[] model) throws Exception {
		boolean snapshot = regionCount >= 0;
		File root = Scratch.dir("ctrmap_matimport_" + name);
		File ws = new File(root, "workspace");
		ws.mkdirs();
		WorkspaceSession s = Sessions.bare(ws, new File(root, "game"), GameType.ORAS);
		s.prepareDirectories();
		if (snapshot) {
			File snap = WorkspaceSession.originalSnapshotDir(ws);
			File garc = new File(snap.getAbsolutePath() + GameProfile.of(GameType.ORAS).archivePath(ArchiveType.FIELD_DATA));
			garc.getParentFile().mkdirs();
			byte[][] regions = new byte[regionCount][];
			Arrays.fill(regions, model == null ? MapResizeTest.region("not a map model")
					: MapResizeTest.container(0x4752, "a region".getBytes(java.nio.charset.StandardCharsets.US_ASCII), model));
			HandedGameTest.writeGarc(garc, regions);
		}
		Workspace.install(s);
		return s;
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
