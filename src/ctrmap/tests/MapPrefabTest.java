package ctrmap.tests;

import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.MapPrefab;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Prefab pipeline validation on real regions (sampled): a tile box is cut out
 * of region A and stamped back (self-stamp at a shifted anchor - every material
 * guaranteed present) plus into a different region (cross-stamp - missing
 * materials allowed, stamped pieces verified):
 * - extraction: pieces carry full strides + local tris + collision + tiles;
 * - stamping: appended geometry lands EXACTLY at anchor+relative positions,
 *   result re-parses clean in BOTH parsers (BchMapModel + the render parser);
 * - collision stamp: exact triangle-count growth, rebuilt file valid;
 * - save/load: byte-faithful prefab file round-trip, skinning included, and
 *   the warning the loading session gets about the faces the cut left out;
 * - and what the Geometry tool SAYS a stamp did: how many of the prefab's
 *   pieces landed out of how many, which is the only place a fragment differs
 *   from a whole building on screen.
 *
 * <p>The skinning flag is the one field the file does NOT store: the load
 * re-reads it from the embedded donor. Nothing noticed when that re-read was
 * turned off, and a prefab that comes back claiming nothing is skinned is a
 * prefab {@link ctrmap.tools.BuildingHarvester} will file in the catalogue -
 * accepting them once let fifteen skinned cuts through, and each placed as a
 * few triangles under a full-size invisible wall.
 *
 * Usage: java ctrmap.tests.MapPrefabTest <path-to-a039-garc> [sampleStep]
 */
public class MapPrefabTest {

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		int step = args.length > 1 ? Integer.parseInt(args[1]) : 30;
		File scratch = Scratch.dir("ctrmap_prefab_test");
		GARC garc = new GARC(garcFile);
		int tested = 0, selfOk = 0, crossOk = 0, crossSkipped = 0, roundtripOk = 0, failures = 0, emptyBox = 0;
		int skinnedSeen = 0;
		boolean warnedSeen = false, silentSeen = false;
		for (int i = 0; i < garc.length; i += step) {
			try {
				GR grA = tempGR(scratch, garc, i);
				if (grA == null || !BchMapModel.isMapModel(grA.getFile(1))) {
					continue;
				}
				MapPrefab p;
				try {
					p = MapPrefab.extract(grA, 12, 12, 27, 27, "test");
				} catch (IllegalStateException crossingOnly) {
					//faces across the box, none inside: a refusal, not a prefab
					p = null;
				}
				if (p == null) {
					emptyBox++;
					continue;
				}
				tested++;

				// save/load round-trip
				File pf = new File(scratch, "p" + i + ".ctrprefab");
				p.save(pf);
				MapPrefab p2 = MapPrefab.load(pf);
				if (p2.pieces.size() != p.pieces.size() || p2.collTris.size() != p.collTris.size()
						|| !java.util.Arrays.equals(p2.pieces.get(0).vertexBytes, p.pieces.get(0).vertexBytes)) {
					throw new IllegalStateException("save/load mismatch");
				}
				//the skinning flag is not written to the file - the load re-reads
				//it from the embedded donor, and a prefab that comes back with it
				//cleared is one the harvester will accept and the catalogue will
				//place as an invisible wall
				for (int pi = 0; pi < p.pieces.size(); pi++) {
					boolean cut = p.pieces.get(pi).skinned, back = p2.pieces.get(pi).skinned;
					if (cut != back) {
						throw new IllegalStateException("save/load lost the skinning of piece " + pi
								+ " (" + p.pieces.get(pi).material + "): the cut says " + cut
								+ ", the reloaded file says " + back);
					}
					skinnedSeen += cut ? 1 : 0;
				}
				roundtripOk++;
				//and what the prefab FILE tells the session that opens it
				if (p.facesDropped > 0 && !warnedSeen) {
					warnedSeen = true;
					loadWarnsAboutTheCut(pf, p, true);
				} else if (p.facesDropped == 0 && !silentSeen) {
					silentSeen = true;
					loadWarnsAboutTheCut(pf, p, false);
				}

				// SELF-stamp at tile (2,2): all materials must match; verify positions
				verifyStamp(p, grA.getFile(1), 2, 2, 50f, true, "self");
				byte[] collA = grA.len > 2 ? grA.getFile(2) : null;
				if (collA != null && GfColl.isColl(collA) && !p.collTris.isEmpty()) {
					GfColl before = new GfColl(collA);
					MapPrefab.StampResult cr = new MapPrefab.StampResult();
					p.stampCollision(cr, collA, 2, 2, 50f);
					GfColl after = new GfColl(cr.newColl);
					if (cr.collTrisAdded != p.collTris.size()
							|| after.uniqueTris.size() != before.uniqueTris.size() + p.collTris.size()) {
						throw new IllegalStateException("self coll stamp: expected +" + p.collTris.size()
								+ " tris, reported +" + cr.collTrisAdded + ", got +" + (after.uniqueTris.size() - before.uniqueTris.size()));
					}
				}
				selfOk++;

				// CROSS-stamp into the next testable region
				GR grB = null;
				for (int j = i + 1; j < Math.min(i + 12, garc.length) && grB == null; j++) {
					GR cand = tempGR(scratch, garc, j);
					if (cand != null && BchMapModel.isMapModel(cand.getFile(1))) {
						grB = cand;
					}
				}
				if (grB != null) {
					MapPrefab.StampResult r = verifyStamp(p, grB.getFile(1), 4, 4, 0f, false, "cross");
					//v2: the full path injects missing materials - EVERY piece must land
					//unless injection legitimately refused (e.g. skinned donor)
					if (r.stamped.size() == p.pieces.size()) {
						crossOk++;
						if (!r.newMaterials.isEmpty()) {
							//injected materials: re-verify the model against the strict oracle
							java.util.List<String> v = ctrmap.formats.h3d.BchModelVerifier.verify(r.newModel);
							BchMapModel rm = new BchMapModel(r.newModel);
							if (!v.isEmpty() && rm.auxDicts.isEmpty()) {
								throw new IllegalStateException("cross inject: verifier " + v);
							}
						}
					} else {
						crossSkipped++;
						System.out.println("  note region " + i + " cross: " + r.missingMaterials);
					}
				}
			} catch (RuntimeException ex) {
				failures++;
				System.out.println("FAIL region " + i + ": " + ex.getMessage());
				if (ex instanceof IndexOutOfBoundsException) {
					ex.printStackTrace();
				}
				if (failures > 8) {
					break;
				}
			}
		}
		failures += stampSaysWhatLanded(scratch, garc);
		//a round-trip over cuts that are all unskinned would prove nothing about
		//the flag, so the sample must have met at least one skinned piece
		if (skinnedSeen == 0 && roundtripOk > 0) {
			failures++;
			System.out.println("FAIL fixture: no sampled cut carried a skinned piece, so the"
					+ " save/load skinning check above asserted nothing - lower the sample step");
		}
		if (roundtripOk > 0 && !(warnedSeen && silentSeen)) {
			failures++;
			System.out.println("FAIL fixture: the sample had no cut that dropped faces (" + warnedSeen
					+ ") or none that dropped none (" + silentSeen + "), so the load warning was"
					+ " only half checked - lower the sample step");
		}
		failures += loadWarnFails;
		System.out.println("\nMapPrefab: tested=" + tested + " (emptyBox=" + emptyBox + ")  self=" + selfOk
				+ "  cross=" + crossOk + " (no-shared-mats " + crossSkipped + ")  saveload=" + roundtripOk
				+ " (skinned pieces round-tripped " + skinnedSeen + ")  failures=" + failures);
		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (failures > 0) {
			System.exit(1);
		}
	}

	static int loadWarnFails = 0;

	/**
	 * A prefab is opened again in a session that never saw the cut, so the file
	 * has to say what the cut left out. The editor warns on load - and only
	 * when there is something to warn about; a warning on every prefab is a
	 * warning nobody reads.
	 *
	 * <p>That line sits behind a file chooser, where no suite could reach it:
	 * deleting it, or firing it on the prefabs that lost nothing, left the
	 * whole battery green. It is driven here through the load method the
	 * chooser calls, with {@link ctrmap.Ui} collecting what the user was told.
	 */
	static void loadWarnsAboutTheCut(File pf, MapPrefab p, boolean expectWarning) throws Exception {
		List<String> said = ctrmap.Ui.record();
		try {
			ctrmap.humaninterface.GeoEditForm.loadPrefabFile(null, pf);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		String only = said.isEmpty() ? "" : said.get(0);
		boolean ok = expectWarning
				? said.size() == 1 && only.contains(p.facesDropped + " face(s) crossing the selection edge were left out")
				: said.isEmpty();
		if (!ok) {
			loadWarnFails++;
			System.out.println("FAIL loading " + pf.getName() + " (" + p.facesDropped + " face(s) dropped): "
					+ (expectWarning ? "the warning naming them was not given" : "warned about nothing")
					+ " - said " + said);
		}
	}

	/**
	 * The Geometry tool's own account of a stamp: how many of the prefab's
	 * pieces landed, out of how many.
	 *
	 * <p>A stamp is partial whenever the target region has no material for one
	 * of the pieces and injection cannot supply it - every skinned donor, which
	 * region 490 is throughout. What lands then is a fragment, and it lands
	 * inside the same footprint the whole thing would have, so the map looks
	 * edited exactly as it would have if the stamp had worked. "Stamped 3/36
	 * pieces (skipped: 33 piece(s), see log)" is the only place that difference
	 * is stated; a complete stamp says "36/36" and nothing about skipping. With
	 * the line deleted, or writing the same thing both times, a fragment and a
	 * building read the same, and the missing pieces are found later as an
	 * invisible wall in the emulator.
	 *
	 * <p>Both are asserted through the form, not through StampResult, because
	 * StampResult was always countable - it is the status line that nothing
	 * could see, sitting behind a modal confirm and a file chooser.
	 */
	static int stampSaysWhatLanded(File scratch, GARC garc) throws Exception {
		GR target = tempGR(scratch, garc, 1);
		GR skinned = garc.length > 490 ? tempGR(scratch, garc, 490) : null;
		if (target == null || skinned == null || !BchMapModel.isMapModel(target.getFile(1))) {
			System.out.println("  skip stamp report: regions 1 and 490 are not both in this dump");
			return 0;
		}
		MapPrefab whole = MapPrefab.extract(target, 12, 12, 27, 27, "self cut");
		MapPrefab fragment = MapPrefab.extract(skinned, 8, 9, 19, 21, "skinned donor");
		if (whole == null || fragment == null) {
			System.out.println("FAIL stamp report: fixture cuts did not come out (" + whole + ", " + fragment + ")");
			return 1;
		}
		int fails = 0;
		//region 1's own cut, back into region 1: every material is already there
		String complete = stampInto(target, whole);
		if (!(complete.contains("Stamped " + whole.pieces.size() + "/" + whole.pieces.size() + " pieces")
				&& !complete.contains("skipped") && complete.contains("(unsaved)"))) {
			System.out.println("FAIL stamp report: a complete stamp of " + whole.pieces.size()
					+ " pieces did not say so, or claimed something was skipped: " + complete);
			fails++;
		} else {
			System.out.println("  ok: a complete stamp says every piece landed, and that it is unsaved: " + complete);
		}
		//region 490's cut into region 1: skinned pieces cannot be injected
		String partial = stampInto(target, fragment);
		java.util.regex.Matcher m = java.util.regex.Pattern.compile(
				"Stamped (\\d+)/(\\d+) pieces").matcher(partial);
		int landed = -1, of = -1;
		if (m.find()) {
			landed = Integer.parseInt(m.group(1));
			of = Integer.parseInt(m.group(2));
		}
		if (!(landed > 0 && of == fragment.pieces.size() && landed < of
				&& partial.contains("(skipped: " + (of - landed) + " piece(s), see log)"))) {
			System.out.println("FAIL stamp report: a stamp that lost pieces of " + fragment.pieces.size()
					+ " did not say how many landed and how many were skipped: " + partial);
			fails++;
		} else {
			System.out.println("  ok: a partial stamp says " + landed + " of " + of
					+ " landed and names the shortfall: " + partial);
		}
		if (fails == 0 && complete.equals(partial)) {
			System.out.println("FAIL stamp report: a fragment and a whole building read identically: " + partial);
			fails++;
		}
		return fails;
	}

	/**
	 * Drives the Geometry tool over one region: select tiles (2,2)-(5,5), stamp
	 * the prefab there, and hand back what the status line says. A fresh form
	 * and panel each time, so one stamp never lands on another's model.
	 */
	private static String stampInto(GR region, MapPrefab p) throws Exception {
		ctrmap.humaninterface.TileMapPanel panel = new ctrmap.humaninterface.TileMapPanel();
		panel.mainGR = region;
		ctrmap.CtrmapMainframe.mTileMapPanel = panel;
		ctrmap.CtrmapMainframe.mZonePnl = null;
		ctrmap.humaninterface.GeoEditForm form = new ctrmap.humaninterface.GeoEditForm();
		form.setSelection(2, 2, 5, 5);
		form.stampHere(p);
		java.lang.reflect.Field f = ctrmap.humaninterface.GeoEditForm.class.getDeclaredField("status");
		f.setAccessible(true);
		return ((javax.swing.JLabel) f.get(form)).getText();
	}

	/** Stamps and verifies every stamped piece's geometry position-exactly. */
	private static MapPrefab.StampResult verifyStamp(MapPrefab p, byte[] targetModel,
			int tileX, int tileY, float dy, boolean requireAll, String label) {
		MapPrefab.StampResult r = p.stampGeometry(targetModel, tileX, tileY, dy);
		if (requireAll && !r.missingMaterials.isEmpty()) {
			throw new IllegalStateException(label + ": unexpected missing materials " + r.missingMaterials);
		}
		if (r.stamped.isEmpty()) {
			return r;
		}
		BchMapModel re = new BchMapModel(r.newModel);
		if (!re.validate().isEmpty()) {
			throw new IllegalStateException(label + ": stamped model re-parse problems " + re.validate());
		}
		//render-parser acceptance (the second oracle)
		ctrmap.formats.h3d.BCHFile render = new ctrmap.formats.h3d.BCHFile(r.newModel);
		if (render.errorlevel != 0 || render.models.isEmpty()) {
			throw new IllegalStateException(label + ": render parser rejected the stamped model");
		}
		//geometry landing check: the stamper RECORDS where each piece landed
		//(final material name + vertex base) - verify against those records
		float ax = tileX * 18f - 360f, az = tileY * 18f - 360f;
		for (int pi = 0; pi < p.pieces.size(); pi++) {
			MapPrefab.Piece piece = p.pieces.get(pi);
			MapPrefab.Landing l = pi < r.landings.size() ? r.landings.get(pi) : null;
			if (l == null) {
				continue; //missing-material/inject-refused skip
			}
			//Use the mesh the stamper RECORDED. Re-deriving it from the material
			//name finds the FIRST mesh carrying that name, which need not be the
			//one the piece went into - region 540 carries wall_top01_r on four
			//meshes, three of them at stride 36 and one at 32.
			int mesh = l.meshIndex;
			if (mesh < 0 || mesh >= re.meshCount) {
				throw new IllegalStateException(label + ": landed mesh for '" + l.material + "' not recorded");
			}
			float[][] pos = re.getVertexPositions(mesh);
			if (pos.length < l.base + l.count) {
				throw new IllegalStateException(label + ": mesh for '" + l.material + "' smaller than landing");
			}
			for (int v = 0; v < l.count; v++) {
				float relX = getF(piece.vertexBytes, v * piece.stride + piece.posOffset);
				float relY = getF(piece.vertexBytes, v * piece.stride + piece.posOffset + 4);
				float relZ = getF(piece.vertexBytes, v * piece.stride + piece.posOffset + 8);
				float[] got = pos[l.base + v];
				if (Math.abs(got[0] - (relX + ax)) > 1e-3f || Math.abs(got[1] - (relY + dy)) > 1e-3f
						|| Math.abs(got[2] - (relZ + az)) > 1e-3f) {
					throw new IllegalStateException(label + ": piece " + pi + " landed at wrong position (v" + v + ")");
				}
			}
		}
		return r;
	}

	private static GR tempGR(File scratch, GARC garc, int index) throws Exception {
		byte[] entry = garc.getDecompressedEntry(index);
		if (entry == null || entry.length < 8) {
			return null;
		}
		File f = new File(scratch, "gr" + index + ".bin");
		try (FileOutputStream fos = new FileOutputStream(f)) {
			fos.write(entry);
		}
		return new GR(f);
	}

	private static float getF(byte[] b, int o) {
		return Float.intBitsToFloat((b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24));
	}
}
