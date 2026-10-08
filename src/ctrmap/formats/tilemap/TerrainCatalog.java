package ctrmap.formats.tilemap;

import ctrmap.formats.GameCache;
import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.GR;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BchModelAppender;
import ctrmap.formats.h3d.BuildingCatalog;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets EVERY terrain brush paint on ANY map. A map's own model only carries the
 * materials its own scenery needed - an indoor mall has no sand, a cave has no
 * grass - and painting with a missing material used to fall back silently to
 * whatever mesh was biggest (a wall, in Mauville's case). Instead, the brush's
 * material is IMPORTED: cut from a curated retail donor and appended into the
 * target model, the same machinery that gives a stamped building its materials.
 *
 * <p>The donor table ({@code oras_terrain.tsv}) is generated and verified by
 * {@link ctrmap.tools.TerrainDonorHarvester}: every row is a real retail
 * terrain material that appends cleanly into indoor, cave and route targets.
 * Metadata only - the geometry is cut from the user's own dump at paint time.
 *
 * <p>The injected material is named {@code ctr_<hint>} so the painter's
 * existing name-based resolver finds it with no special casing, and injection
 * is idempotent: a map that already has (or has been given) the material is
 * returned untouched.
 *
 * <p>Every cut is made from the game the caller HANDS in, through
 * {@link BuildingCatalog}; a null game means "nothing to cut from yet" and is
 * answered quietly with the model unchanged, which is what a build made
 * before any workspace exists gets. And nothing here shows a person anything:
 * this class used to open an error dialog from seven places, from inside the
 * format layer, where no suite could see it and no caller could decide what
 * to do about it. A real failure - a snapshot that will not parse, a table
 * that will not read - is now THROWN with its reason, and the caller that
 * owns a window reports it. The caches are per handed game: a second game
 * gets its own measurements, not the first game's.
 */
public class TerrainCatalog {

	/**
	 * What was measured from a game's pristine copy, kept in that game's own store (F218). These
	 * were two static maps emptied whenever a game other than the last one was handed, so two
	 * games took turns throwing each other's measurements away; and a cache keyed by nothing
	 * before that handed the second game the first's answers.
	 */
	private static final GameCache.Key<Map<String, float[]>> UV_SCALES = new GameCache.Key<>("terrain donor UV scales");
	private static final GameCache.Key<Map<String, List<String>>> DONOR_TEXTURES
			= new GameCache.Key<>("terrain donor textures");

	/**
	 * The donor table to read, which is normally the one built into the jar.
	 *
	 * <p>A map's look is decided almost entirely by this table - which rock its
	 * cliffs use, what its water is made of - and different maps want different
	 * answers. A volcanic cave needs WATER to be molten lava and CLIFF to be
	 * black rock with glowing cracks; a meadow emphatically does not. Pointing
	 * {@code -Dterrain=<file>} at another table lets each design carry its own
	 * palette instead of the two fighting over one global file.
	 */
	private static java.io.InputStream openCatalog() throws java.io.IOException {
		String override = System.getProperty("terrain");
		if (override != null && new java.io.File(override).isFile()) {
			return new java.io.FileInputStream(override);
		}
		try {
			return ctrmap.resources.ResourceAccess.getStream("oras_terrain.tsv");
		} catch (IllegalStateException missing) {
			throw new java.io.IOException(ctrmap.util.Bytes.reason(missing), missing);
		}
	}

	public static class Donor {

		public TilePalette brush;
		public int donorRegion;
		public int donorArea;
		public int donorMesh;
		public String material;
		public String injectName;
		/**
		 * Which horizontal bands of the texture the cliff face should use.
		 *
		 * <p>Cliff textures are not interchangeable: one is stone edge to edge,
		 * the next bakes a grass strip into its top and blank white into its
		 * bottom half, and a third runs the bands the other way up. Aiming the
		 * face at the wrong band is what made a cliff come out washed-out pale.
		 * <p>Measured off vanilla's chip-cliff atlas family (gake_hono_01,
		 * gake_entotsu, gake_01_02, gake_01_01, d112r0103_gake1 - identical in
		 * all five): one 18-unit elevation step is a lip bevel from vLip to
		 * vMid, a sheer wall from vMid to vWall, and a foot bevel from vWall to
		 * vFoot. The tiny gaps between the bands are authored guard seams
		 * between sub-strips; keep them. Optional columns 7-10 of the row.
		 */
		public float vLip = 0.2483f, vMid = 0.2015f, vWall = 0.0485f, vFoot = 0.0031f;
	}

	/**
	 * The UV scale the DONOR mesh was authored at, for a material this catalog
	 * injected - or null when the name is not one of ours, or the donor cannot
	 * be read.
	 *
	 * <p>{@link #ensureMaterial} keeps the donor's material and blanks its
	 * geometry to a single vertex, since the painter supplies the tiles. That
	 * leaves nothing for the painter's own UV measurement to work from, so every
	 * imported brush fell back to a fixed default of 1/36 while retail
	 * world-projected ground is authored around 1/72 - a 2x texture-scale error
	 * on precisely the brushes the editor adds, and the reason imported
	 * boardwalk planks came out twice the size of the retail ones beside them.
	 *
	 * <p>Measured lazily from the pristine snapshot of the handed game and
	 * cached; a null result is cached too, so a donor with nothing to measure
	 * costs one attempt rather than one per painted tile.
	 *
	 * @param files the game whose snapshot the donor is cut from; null, or a
	 * game with no snapshot, answers null and caches nothing
	 * @throws IllegalStateException when the donor region cannot be read or
	 * parsed: tiles painted with the brush would come out at the wrong size,
	 * and the caller that owns a window says so
	 */
	public static synchronized float[] donorUvScale(GameFiles files, String injectName) {
		if (injectName == null || files == null) {
			return null;
		}
		Map<String, float[]> uvScaleCache = files.cache().get(UV_SCALES, java.util.HashMap::new);
		if (uvScaleCache.containsKey(injectName)) {
			return uvScaleCache.get(injectName);
		}
		if (!BuildingCatalog.canCutDonor(files)) {
			//too early to measure anything; NOT cached, so the first call made
			//with a game handed still gets a real answer
			return null;
		}
		float[] out = null;
		for (Donor d : donors().values()) {
			if (!injectName.equals(d.injectName)) {
				continue;
			}
			try {
				GR gr = BuildingCatalog.pristineRegion(files, d.donorRegion);
				if (gr != null) {
					byte[] dm = gr.getFile(1);
					if (BchMapModel.isMapModel(dm)) {
						BchMapModel m = new BchMapModel(dm);
						if (d.donorMesh >= 0 && d.donorMesh < m.meshCount) {
							//the donor mesh has real geometry, so this measures
							//rather than recursing back into this method
							out = PaintedMaterials.measureUvScale(files, m, m.geometry().get(d.donorMesh));
						}
					}
				}
			} catch (Exception ex) {
				throw new IllegalStateException("Could not measure the donor texture scale for \"" + injectName
						+ "\" in the pristine copy of region " + d.donorRegion + ": " + ctrmap.util.Bytes.reason(ex), ex);
			}
			break;
		}
		uvScaleCache.put(injectName, out);
		return out;
	}

	/** What an import needs from the donor's AREA to render in the target. */
	public static class ImportResult {

		public byte[] model;
		public boolean injected;
		public int donorArea = -1;
		public final List<String> texturesNeeded = new ArrayList<>();
	}

	/**
	 * The table, read whole once for the run (F093). The brushes, the CLIFF row and the LAVA_CHURN
	 * row were three caches, each filled by its own read of the file when first asked: a table
	 * swapped between the first ask and the last gave a map its brushes from one design and its
	 * cliff or churn from another. They are three views of this one read now.
	 */
	private static Table table;

	/** One read of the table: every row by name, and the brush rows by brush. */
	private static final class Table {

		final Map<String, Donor> rows;
		final Map<TilePalette, Donor> brushes;

		Table(Map<String, Donor> rows) {
			this.rows = java.util.Collections.unmodifiableMap(rows);
			Map<TilePalette, Donor> b = new LinkedHashMap<>();
			for (Donor d : rows.values()) {
				if (d.brush != null) {
					b.put(d.brush, d);
				}
			}
			this.brushes = java.util.Collections.unmodifiableMap(b);
		}
	}

	/**
	 * The table, read on first ask.
	 *
	 * @param lost what cannot be done without it, for the refusal's words
	 * @throws IllegalStateException when the table cannot be read, naming the reason; nothing is
	 * kept then, so a repaired table is read next time
	 */
	private static synchronized Table table(String lost) {
		if (table == null) {
			try {
				table = new Table(rows());
			} catch (Exception ex) {
				throw new IllegalStateException("The terrain donor table could not be read, so " + lost + ": "
						+ ctrmap.util.Bytes.reason(ex), ex);
			}
		}
		return table;
	}

	/**
	 * The brush donors, by brush.
	 *
	 * @throws IllegalStateException when the table cannot be read, naming the reason
	 */
	public static Map<TilePalette, Donor> donors() {
		return table("no brush can be given to a map that lacks its material").brushes;
	}

	/** The table's rows that are not brushes: read by name, passed over by {@link #donors()}. */
	private static final java.util.Set<String> NOT_BRUSHES = new java.util.HashSet<>(
			java.util.Arrays.asList("CLIFF", "LAVA_CHURN"));

	/**
	 * Every row of the table by its leading name, in file order; a brush row's donor carries
	 * its brush. STRICT (F211): a row of the wrong width, a number that is not one, a name this
	 * build has no brush for, or a name given twice THROWS, naming the line. The brush reader
	 * swallowed each of those and dropped the row - a brush that then could not be given to
	 * any map, with nothing said - and the two named-row readers skipped a short row and
	 * answered "no such row". A missing table is refused too: it ships inside the build.
	 */
	private static Map<String, Donor> rows() throws java.io.IOException {
		Map<String, Donor> out = new LinkedHashMap<>();
		try (InputStream in = openCatalog()) {
			java.io.BufferedReader lines = new java.io.BufferedReader(
					new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
			int lineNo = 0;
			for (String raw; (raw = lines.readLine()) != null;) {
				lineNo++;
				String line = raw.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] f = line.split("\t");
				try {
					//row is: NAME region area mesh material injectName [vLip vMid vWall vFoot]
					if (f.length != 6 && f.length != 10) {
						throw new IllegalArgumentException(f.length + " fields; a row has 6, or 10 with the cliff bands");
					}
					if (out.containsKey(f[0])) {
						throw new IllegalArgumentException("the name is given twice");
					}
					Donor d = new Donor();
					d.brush = NOT_BRUSHES.contains(f[0]) ? null : TilePalette.valueOf(f[0]);
					d.donorRegion = Integer.parseInt(f[1]);
					d.donorArea = Integer.parseInt(f[2]);
					d.donorMesh = Integer.parseInt(f[3]);
					d.material = f[4];
					d.injectName = f[5];
					if (f.length == 10) {
						d.vLip = Float.parseFloat(f[6]);
						d.vMid = Float.parseFloat(f[7]);
						d.vWall = Float.parseFloat(f[8]);
						d.vFoot = Float.parseFloat(f[9]);
					}
					out.put(f[0], d);
				} catch (IllegalArgumentException ex) {
					throw new java.io.IOException("line " + lineNo + " (" + f[0] + "): " + ctrmap.util.Bytes.reason(ex), ex);
				}
			}
		}
		return out;
	}

	/**
	 * The donor for generated CLIFF FACES, or null when the table has no CLIFF
	 * row. Kept apart from {@link #donors()} because a cliff is not a brush -
	 * nobody paints with it, the painter raises it between two elevations.
	 *
	 * <p>Worth having because a map's cliff is otherwise whatever its own region
	 * happens to carry, and on most outdoor routes that is an orange chip_gake:
	 * measured mean colour 130/59/40 on gake_hono_02 and 107/63/42 on
	 * chip_jump_gake, against 65/55/21 for the earth-brown d112r0103_gake2 that
	 * actually reads as a cut hillside.
	 */
	public static Donor cliffDonor() {
		return table("generated cliffs would keep whatever rock the map already has").rows.get("CLIFF");
	}

	/**
	 * The catalogue's LAVA_CHURN row: vanilla's additive molten overlay.
	 *
	 * <p>Vanilla lava is three meshes, not one - an opaque base plate, a churn
	 * plate exactly 2.00 units above it, and a rim ribbon - and the churn plate
	 * is ADDITIVE (srcAlpha/add/one, depth-write off). That additive layer is
	 * the whole reason retail lava glows instead of sitting there as a flat
	 * orange rectangle, which is exactly what ours was doing.
	 *
	 * <p>Rather than author blend state by hand, the row names one of retail's
	 * own churn materials and imports it whole, blend mode and all.
	 */
	public static Donor churnDonor() {
		return table("lava would keep no molten overlay").rows.get("LAVA_CHURN");
	}

	/**
	 * Gives a model the catalogue's lava-churn overlay material, cut from the
	 * handed game, if it does not already carry it. See {@link #ensureCliffMaterial}.
	 */
	public static ImportResult ensureChurnMaterial(GameFiles files, byte[] model) {
		return ensureNamedMaterial(files, model, churnDonor());
	}

	/**
	 * Gives a model the catalog's cliff material, cut from the handed game, if
	 * it does not already carry it. Same machinery as {@link #ensureMaterial}:
	 * the donor's geometry is thrown away and the painter fills the empty mesh
	 * with the cliff quads it raises between elevations.
	 *
	 * @param files the game whose snapshot the donor is cut from; null, or a
	 * game with no snapshot, returns the model unchanged and says nothing
	 * @throws IllegalStateException when there was a snapshot to cut from and
	 * the import failed, naming the material, the region and the reason
	 */
	public static ImportResult ensureCliffMaterial(GameFiles files, byte[] model) {
		return ensureNamedMaterial(files, model, cliffDonor());
	}

	private static ImportResult ensureNamedMaterial(GameFiles files, byte[] model, Donor d) {
		ImportResult r = new ImportResult();
		r.model = model;
		if (d == null) {
			return r; //the catalogue has no CLIFF row: nothing to import
		}
		if (!BuildingCatalog.canCutDonor(files)) {
			//no game handed, or none with a snapshot, so there is nothing to
			//cut the donor out of. Asking anyway is what printed "cliff import
			//failed" on every single build; the painter's own fallback covers it.
			return r;
		}
		try {
			BchMapModel probe = new BchMapModel(model);
			if (probe.meshWithMaterial(d.injectName) >= 0) {
				//already imported - but its textures still belong to the
				//donor's area, which this model cannot vouch for
				r.donorArea = d.donorArea;
				r.texturesNeeded.addAll(donorTextures(files, d));
				return r;
			}
			cutFromPristine(files, model, d, r);
		} catch (Exception ex) {
			//thrown, not shown: the map would otherwise keep the material it
			//already had, which may be the wrong rock, and the caller that owns
			//a window is the one to say so
			throw new IllegalStateException("Could not import the terrain material \"" + d.injectName
					+ "\" from the pristine copy of region " + d.donorRegion + ": " + ctrmap.util.Bytes.reason(ex), ex);
		}
		return r;
	}

	/**
	 * Gives {@code model} a real material for {@code brush} when it has none.
	 * Returns the (possibly unchanged) model plus what the caller must carry
	 * from the donor's area. Never throws for a missing donor - the painter
	 * simply keeps its old fallback behaviour then.
	 *
	 * <p>A model that ALREADY carries the donor's material still reports that
	 * donor's textures. They live in the area, which this model cannot vouch
	 * for: after an Apply whose carry was refused, the material is on disk and
	 * the texture is not, and an early return that said "nothing needed" made
	 * the retry paint the same white floor and call it done.
	 *
	 * @param files the game whose snapshot the donor is cut from; null, or a
	 * game with no snapshot, returns the model unchanged and says nothing
	 * @throws IllegalStateException when the import failed, naming the brush
	 * and the reason: painting would otherwise fall back to a material the map
	 * already has, and the caller that owns a window is the one to say so
	 */
	public static ImportResult ensureMaterial(GameFiles files, byte[] model, TilePalette brush) {
		ImportResult r = new ImportResult();
		r.model = model;
		try {
			BchMapModel probe = new BchMapModel(model);
			Donor d = donors().get(brush);
			//already imported by an earlier paint? (idempotent by exact name)
			//Checked BEFORE the has-a-material test, which the imported material
			//itself satisfies - the two are only distinguishable by name.
			if (d != null && probe.meshWithMaterial(d.injectName) >= 0) {
				r.donorArea = d.donorArea;
				r.texturesNeeded.addAll(donorTextures(files, d));
				return r;
			}
			if (PaintedMaterials.hasMaterialFor(probe, brush)) {
				return r; //the map paints this brush with its OWN material - nothing to carry
			}
			if (d == null) {
				return r;
			}
			if (!BuildingCatalog.canCutDonor(files)) {
				return r; //no snapshot to cut from yet - see ensureNamedMaterial
			}
			cutFromPristine(files, model, d, r);
		} catch (Exception ex) {
			throw new IllegalStateException("Could not import a " + brush + " material into this map: "
					+ ctrmap.util.Bytes.reason(ex), ex);
		}
		return r;
	}

	/**
	 * Cuts {@code d}'s material out of the pristine copy and appends it to {@code model} as an
	 * empty mesh, recording the merged model and what it needs in {@code r}. The one copy of
	 * this sequence: the cliff and brush importers each carried their own until F092, and the
	 * two had already drifted apart in what they said about it.
	 *
	 * <p>CALLED ONLY ONCE THERE IS A SNAPSHOT TO CUT FROM, so every failure here is THROWN:
	 * returning the model unchanged would paint on whatever material the map already has, which
	 * both importers promise not to do. With no snapshot, the callers return before this.
	 */
	private static void cutFromPristine(GameFiles files, byte[] model, Donor d, ImportResult r) throws Exception {
		GR donorGr = BuildingCatalog.pristineRegion(files, d.donorRegion);
		if (donorGr == null) {
			throw new IllegalStateException("the pristine copy holds no region " + d.donorRegion);
		}
		byte[] donorModel = donorGr.getFile(1);
		if (!BchMapModel.isMapModel(donorModel)) {
			throw new IllegalStateException("the model in region " + d.donorRegion + " of the pristine copy is not"
					+ " a map model");
		}
		byte[] merged = BchModelAppender.append(model, donorModel, d.donorMesh, d.injectName);
		BchMapModel mm = new BchMapModel(merged);
		//the append brings the donor's own terrain along - blank it, we only
		//wanted the material; the painter fills it with the user's tiles.
		//The new mesh is NOT necessarily the last one: the appender inserts
		//it in render-layer order, shifting the meshes after it. Find it by
		//name, or we would blank an innocent mesh and leave a slab of the
		//donor's map floating in this one.
		int newMesh = mm.meshWithMaterial(d.injectName);
		if (newMesh < 0) {
			throw new IllegalStateException("the append produced no mesh named " + d.injectName);
		}
		BchMapModel.MeshGeom g = mm.geometry().get(newMesh);
		byte[] one = new byte[g.stride];
		System.arraycopy(mm.raw, g.vtxAbs, one, 0, g.stride);
		merged = mm.setMeshGeometry(newMesh, one, new int[]{0, 0, 0});
		if (!new BchMapModel(merged).validate().isEmpty()) {
			//never ship a model that stopped parsing - and never pretend it was not asked for
			throw new IllegalStateException("the merged model failed validation: "
					+ new BchMapModel(merged).validate().get(0));
		}
		r.model = merged;
		r.injected = true;
		r.donorArea = d.donorArea;
		r.texturesNeeded.addAll(textureNamesOf(new BchMapModel(donorModel), d.donorMesh));
	}

	/**
	 * The textures a donor's material references, read from the handed game's
	 * snapshot and cached per game: a caller that only wants to know what to
	 * carry must not pay for a region read every time.
	 *
	 * @param files the game whose snapshot is read; null, or a game with no
	 * snapshot, answers an empty list and caches nothing
	 * @throws IllegalStateException when the donor region cannot be read:
	 * painted tiles using the material would come out white, and the caller
	 * that owns a window says so
	 */
	public static synchronized List<String> donorTextures(GameFiles files, Donor d) {
		if (files == null) {
			return new ArrayList<>();   //no game, no snapshot: nothing to read and nothing kept
		}
		Map<String, List<String>> donorTextureCache = files.cache().get(DONOR_TEXTURES, LinkedHashMap::new);
		String key = d.donorRegion + ":" + d.donorMesh;
		List<String> cached = donorTextureCache.get(key);
		if (cached != null) {
			return cached;
		}
		List<String> names = new ArrayList<>();
		if (!BuildingCatalog.canCutDonor(files)) {
			return names; //not cached - a later call with a game handed may do better
		}
		try {
			GR donorGr = BuildingCatalog.pristineRegion(files, d.donorRegion);
			byte[] donorModel = donorGr == null ? null : donorGr.getFile(1);
			if (donorModel != null && BchMapModel.isMapModel(donorModel)) {
				names = textureNamesOf(new BchMapModel(donorModel), d.donorMesh);
			}
		} catch (Exception ex) {
			throw new IllegalStateException("Could not read the textures the \"" + d.injectName + "\" material needs"
					+ " from the pristine copy of region " + d.donorRegion + ": " + ctrmap.util.Bytes.reason(ex), ex);
		}
		donorTextureCache.put(key, names);
		return names;
	}

	/**
	 * The texture names a donor mesh's material references. THROWS when the header cannot be
	 * read (F211): a failure part way answered the names read so far, the import carried fewer
	 * textures than the material draws with, and the tiles came out white. Both callers already
	 * turn a throw into words.
	 */
	static List<String> textureNamesOf(BchMapModel m, int meshIndex) {
		return m.getMaterialTextureNames(m.getMeshMaterialIndex(meshIndex));
	}
}
