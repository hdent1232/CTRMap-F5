package ctrmap.formats.tilemap;

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
 */
public class TerrainCatalog {

	public static class Donor {

		public TilePalette brush;
		public int donorRegion;
		public int donorArea;
		public int donorMesh;
		public String material;
		public String injectName;
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
	 * <p>Measured lazily from the pristine dump and cached; a null result is
	 * cached too, so a missing snapshot costs one attempt rather than one per
	 * painted tile.
	 */
	public static synchronized float[] donorUvScale(String injectName) {
		if (injectName == null) {
			return null;
		}
		if (uvScaleCache.containsKey(injectName)) {
			return uvScaleCache.get(injectName);
		}
		float[] out = null;
		for (Donor d : donors().values()) {
			if (!injectName.equals(d.injectName)) {
				continue;
			}
			try {
				GR gr = BuildingCatalog.pristineRegion(d.donorRegion);
				if (gr != null) {
					byte[] dm = gr.getFile(1);
					if (BchMapModel.isMapModel(dm)) {
						BchMapModel m = new BchMapModel(dm);
						if (d.donorMesh >= 0 && d.donorMesh < m.meshCount) {
							//the donor mesh has real geometry, so this measures
							//rather than recursing back into this method
							out = PaintedRegionBuilder.measureUvScale(m, m.geometry().get(d.donorMesh));
						}
					}
				}
			} catch (Exception ex) {
				System.err.println("TerrainCatalog: could not measure donor scale for "
						+ injectName + ": " + ex);
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

	public static synchronized Map<TilePalette, Donor> donors() {
		if (donors != null) {
			return donors;
		}
		donors = new LinkedHashMap<>();
		try (InputStream in = TerrainCatalog.class.getClassLoader()
				.getResourceAsStream("ctrmap/resources/oras_terrain.tsv")) {
			if (in == null) {
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
					d.material = f[4];
					d.injectName = f[5];
					donors.put(d.brush, d);
				} catch (IllegalArgumentException ignore) {
				}
			}
		} catch (Exception ex) {
			System.err.println("TerrainCatalog: load failed: " + ex);
		}
		return donors;
	}

	/** True when this brush can be given to a map that lacks its material. */
	public static boolean canImport(TilePalette brush) {
		return donors().containsKey(brush);
	}

	/**
	 * Gives {@code model} a real material for {@code brush} when it has none.
	 * Returns the (possibly unchanged) model plus what the caller must carry
	 * from the donor's area. Never throws for a missing donor - the painter
	 * simply keeps its old fallback behaviour then.
	 */
	public static ImportResult ensureMaterial(byte[] model, TilePalette brush) {
		ImportResult r = new ImportResult();
		r.model = model;
		try {
			BchMapModel probe = new BchMapModel(model);
			if (PaintedRegionBuilder.hasMaterialFor(probe, brush)) {
				return r; //the map already paints this brush with a real material
			}
			Donor d = donors().get(brush);
			if (d == null) {
				return r;
			}
			//already imported by an earlier paint? (idempotent by exact name)
			for (int i = 0; i < probe.meshCount; i++) {
				String n = probe.getMaterialName(probe.getMeshMaterialIndex(i));
				if (d.injectName.equals(n)) {
					return r;
				}
			}
			GR donorGr = BuildingCatalog.pristineRegion(d.donorRegion);
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
			System.err.println("TerrainCatalog: import for " + brush + " failed: " + ex);
		}
		return r;
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
