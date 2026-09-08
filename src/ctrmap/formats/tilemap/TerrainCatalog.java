package ctrmap.formats.tilemap;

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
import java.util.Scanner;

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
	 * The game the two caches below were filled from. Identity, not equality:
	 * two sessions over one folder are two games, and a cache keyed by nothing
	 * handed the second game the first's answers.
	 */
	private static GameFiles cachedFor;

	/** Empties the caches when a different game is handed than the one they were filled from. */
	private static synchronized void cachesFor(GameFiles files) {
		if (files != cachedFor) {
			uvScaleCache.clear();
			donorTextureCache.clear();
			cachedFor = files;
		}
	}

	/** What a report says about an exception: its message, or the exception itself when it gave none. */
	private static String reason(Throwable ex) {
		String m = ex.getMessage();
		return m == null || m.trim().isEmpty() ? ex.toString() : m;
	}

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
		return TerrainCatalog.class.getClassLoader()
				.getResourceAsStream("ctrmap/resources/oras_terrain.tsv");
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

	private static final java.util.Map<String, float[]> uvScaleCache = new java.util.HashMap<>();

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
		if (injectName == null) {
			return null;
		}
		cachesFor(files);
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
							out = PaintedRegionBuilder.measureUvScale(files, m, m.geometry().get(d.donorMesh));
						}
					}
				}
			} catch (Exception ex) {
				throw new IllegalStateException("Could not measure the donor texture scale for \"" + injectName
						+ "\" in the pristine copy of region " + d.donorRegion + ": " + reason(ex), ex);
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

	private static Map<TilePalette, Donor> donors;

	/**
	 * The brush donors, read once from the table.
	 *
	 * @throws IllegalStateException when the table cannot be read, naming the
	 * reason; nothing is cached then, so a repaired table is read next time
	 */
	public static synchronized Map<TilePalette, Donor> donors() {
		if (donors != null) {
			return donors;
		}
		Map<TilePalette, Donor> read = new LinkedHashMap<>();
		try (InputStream in = openCatalog()) {
			if (in == null) {
				donors = read;
				return donors;
			}
			Scanner sc = new Scanner(in, "UTF-8");
			while (sc.hasNextLine()) {
				String line = sc.nextLine().trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] f = line.split("\t");
				if (f.length < 6) {
					continue;
				}
				try {
					Donor d = new Donor();
					d.brush = TilePalette.valueOf(f[0]);
					d.donorRegion = Integer.parseInt(f[1]);
					d.donorArea = Integer.parseInt(f[2]);
					d.donorMesh = Integer.parseInt(f[3]);
				//row is: BRUSH region area mesh material injectName [vLip vMid vFoot]
				if (f.length > 9) {
					d.vLip = Float.parseFloat(f[6]);
					d.vMid = Float.parseFloat(f[7]);
					d.vWall = Float.parseFloat(f[8]);
					d.vFoot = Float.parseFloat(f[9]);
				}
					d.material = f[4];
					d.injectName = f[5];
					read.put(d.brush, d);
				} catch (IllegalArgumentException ignore) {
				}
			}
		} catch (Exception ex) {
			throw new IllegalStateException("The terrain donor table could not be read, so no brush can be"
					+ " given to a map that lacks its material: " + reason(ex), ex);
		}
		donors = read;
		return donors;
	}

	/** True when this brush can be given to a map that lacks its material. */
	public static boolean canImport(TilePalette brush) {
		return donors().containsKey(brush);
	}

	private static Donor cliffDonor;
	private static boolean cliffLoaded;

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
	public static synchronized Donor cliffDonor() {
		if (cliffLoaded) {
			return cliffDonor;
		}
		try (InputStream in = openCatalog()) {
			if (in == null) {
				cliffLoaded = true;
				return null;
			}
			Scanner sc = new Scanner(in, "UTF-8");
			while (sc.hasNextLine()) {
				String line = sc.nextLine().trim();
				if (!line.startsWith("CLIFF\t")) {
					continue;
				}
				String[] f = line.split("\t");
				if (f.length < 6) {
					continue;
				}
				Donor d = new Donor();
				d.brush = null;
				d.donorRegion = Integer.parseInt(f[1]);
				d.donorArea = Integer.parseInt(f[2]);
				d.donorMesh = Integer.parseInt(f[3]);
				//row is: BRUSH region area mesh material injectName [vLip vMid vFoot]
				if (f.length > 9) {
					d.vLip = Float.parseFloat(f[6]);
					d.vMid = Float.parseFloat(f[7]);
					d.vWall = Float.parseFloat(f[8]);
					d.vFoot = Float.parseFloat(f[9]);
				}
				d.material = f[4];
				d.injectName = f[5];
				cliffDonor = d;
				break;
			}
		} catch (Exception ex) {
			//not marked loaded: a repaired table is read next time
			throw new IllegalStateException("The CLIFF row of the terrain donor table could not be read;"
					+ " generated cliffs would keep whatever rock the map already has: " + reason(ex), ex);
		}
		cliffLoaded = true;
		return cliffDonor;
	}

	/** Why the cliff import gave up, when -Dcliffdebug is set. */
	private static void say(String why) {
		if (System.getProperty("cliffdebug") != null) {
			System.out.println("  cliff import: " + why);
		}
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
	private static Donor churnDonor;
	private static boolean churnLoaded;

	public static synchronized Donor churnDonor() {
		if (!churnLoaded) {
			//marked loaded only once the row was read: a table that throws is read again next time
			churnDonor = rowNamed("LAVA_CHURN");
			churnLoaded = true;
		}
		return churnDonor;
	}

	/**
	 * One non-brush row of the table, read by its leading keyword.
	 *
	 * @throws IllegalStateException when the table cannot be read
	 */
	private static Donor rowNamed(String key) {
		try (InputStream in = openCatalog()) {
			if (in == null) {
				return null;
			}
			Scanner sc = new Scanner(in, "UTF-8");
			while (sc.hasNextLine()) {
				String line = sc.nextLine().trim();
				if (!line.startsWith(key + "	")) {
					continue;
				}
				String[] f = line.split("	");
				if (f.length < 6) {
					continue;
				}
				Donor d = new Donor();
				d.donorRegion = Integer.parseInt(f[1]);
				d.donorArea = Integer.parseInt(f[2]);
				d.donorMesh = Integer.parseInt(f[3]);
				d.material = f[4];
				d.injectName = f[5];
				return d;
			}
		} catch (Exception ex) {
			throw new IllegalStateException("The " + key + " row of the terrain donor table could not be read: "
					+ reason(ex), ex);
		}
		return null;
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
			say("no CLIFF row in the catalogue");
			return r;
		}
		if (!BuildingCatalog.canCutDonor(files)) {
			//no game handed, or none with a snapshot, so there is nothing to
			//cut the donor out of. Asking anyway is what printed "cliff import
			//failed" on every single build; the painter's own fallback covers it.
			say("no pristine snapshot to cut " + d.injectName + " from");
			return r;
		}
		try {
			BchMapModel probe = new BchMapModel(model);
			for (int i = 0; i < probe.meshCount; i++) {
				if (d.injectName.equals(probe.getMaterialName(probe.getMeshMaterialIndex(i)))) {
					//already imported - but its textures still belong to the
					//donor's area, which this model cannot vouch for
					r.donorArea = d.donorArea;
					r.texturesNeeded.addAll(donorTextures(files, d));
					return r;
				}
			}
			GR donorGr = BuildingCatalog.pristineRegion(files, d.donorRegion);
			if (donorGr == null) {
				say("donor region " + d.donorRegion + " could not be opened");
				return r;
			}
			byte[] donorModel = donorGr.getFile(1);
			if (!BchMapModel.isMapModel(donorModel)) {
				say("donor region " + d.donorRegion + " subfile 1 is not a map model");
				return r;
			}
			byte[] merged = BchModelAppender.append(model, donorModel, d.donorMesh, d.injectName);
			BchMapModel mm = new BchMapModel(merged);
			int newMesh = -1;
			for (int i = 0; i < mm.meshCount; i++) {
				if (d.injectName.equals(mm.getMaterialName(mm.getMeshMaterialIndex(i)))) {
					newMesh = i;
					break;
				}
			}
			if (newMesh < 0) {
				say("append produced no mesh named " + d.injectName);
				return r;
			}
			BchMapModel.MeshGeom g = mm.geometry().get(newMesh);
			byte[] one = new byte[g.stride];
			System.arraycopy(mm.raw, g.vtxAbs, one, 0, g.stride);
			merged = mm.setMeshGeometry(newMesh, one, new int[]{0, 0, 0});
			if (!new BchMapModel(merged).validate().isEmpty()) {
				say("merged model failed validation: "
						+ new BchMapModel(merged).validate().get(0));
				return r;
			}
			r.model = merged;
			r.injected = true;
			r.donorArea = d.donorArea;
			r.texturesNeeded.addAll(textureNamesOf(new BchMapModel(donorModel), d.donorMesh));
		} catch (Exception ex) {
			//thrown, not shown: the map would otherwise keep the material it
			//already had, which may be the wrong rock, and the caller that owns
			//a window is the one to say so
			throw new IllegalStateException("Could not import the terrain material \"" + d.injectName
					+ "\" from the pristine copy of region " + d.donorRegion + ": " + reason(ex), ex);
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
			if (d != null) {
				for (int i = 0; i < probe.meshCount; i++) {
					String n = probe.getMaterialName(probe.getMeshMaterialIndex(i));
					if (d.injectName.equals(n)) {
						r.donorArea = d.donorArea;
						r.texturesNeeded.addAll(donorTextures(files, d));
						return r;
					}
				}
			}
			if (PaintedRegionBuilder.hasMaterialFor(probe, brush)) {
				return r; //the map paints this brush with its OWN material - nothing to carry
			}
			if (d == null) {
				return r;
			}
			if (!BuildingCatalog.canCutDonor(files)) {
				return r; //no snapshot to cut from yet - see ensureNamedMaterial
			}
			GR donorGr = BuildingCatalog.pristineRegion(files, d.donorRegion);
			if (donorGr == null) {
				return r;
			}
			byte[] donorModel = donorGr.getFile(1);
			if (!BchMapModel.isMapModel(donorModel)) {
				return r;
			}
			byte[] merged = BchModelAppender.append(model, donorModel, d.donorMesh, d.injectName);
			BchMapModel mm = new BchMapModel(merged);
			//the append brings the donor's own terrain along - blank it, we only
			//wanted the material; the painter fills it with the user's tiles.
			//The new mesh is NOT necessarily the last one: the appender inserts
			//it in render-layer order, shifting the meshes after it. Find it by
			//name, or we would blank an innocent mesh and leave a slab of the
			//donor's map floating in this one.
			int newMesh = -1;
			for (int i = 0; i < mm.meshCount; i++) {
				if (d.injectName.equals(mm.getMaterialName(mm.getMeshMaterialIndex(i)))) {
					newMesh = i;
					break;
				}
			}
			if (newMesh < 0) {
				return r; //the append did not produce the material we asked for
			}
			BchMapModel.MeshGeom g = mm.geometry().get(newMesh);
			byte[] one = new byte[g.stride];
			System.arraycopy(mm.raw, g.vtxAbs, one, 0, g.stride);
			merged = mm.setMeshGeometry(newMesh, one, new int[]{0, 0, 0});
			if (!new BchMapModel(merged).validate().isEmpty()) {
				return r; //never ship a model that stopped parsing
			}
			r.model = merged;
			r.injected = true;
			r.donorArea = d.donorArea;
			r.texturesNeeded.addAll(textureNamesOf(new BchMapModel(donorModel), d.donorMesh));
		} catch (Exception ex) {
			throw new IllegalStateException("Could not import a " + brush + " material into this map: "
					+ reason(ex), ex);
		}
		return r;
	}

	private static final Map<String, List<String>> donorTextureCache = new LinkedHashMap<>();

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
		cachesFor(files);
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
					+ " from the pristine copy of region " + d.donorRegion + ": " + reason(ex), ex);
		}
		donorTextureCache.put(key, names);
		return names;
	}

	/** The texture names a donor mesh's material references (header slots). */
	static List<String> textureNamesOf(BchMapModel m, int meshIndex) {
		List<String> out = new ArrayList<>();
		try {
			int matHdr = m.matValuesPtr + m.getMeshMaterialIndex(meshIndex) * 0x2C;
			for (int slot : new int[]{0x1C, 0x20, 0x24}) {
				int sp = m.ptr(matHdr + slot);
				if (sp > 0) {
					StringBuilder sb = new StringBuilder();
					for (int q = sp; q < m.raw.length && m.raw[q] != 0; q++) {
						sb.append((char) (m.raw[q] & 0xFF));
					}
					if (sb.length() > 0 && !out.contains(sb.toString())) {
						out.add(sb.toString());
					}
				}
			}
		} catch (RuntimeException ignore) {
		}
		return out;
	}
}
