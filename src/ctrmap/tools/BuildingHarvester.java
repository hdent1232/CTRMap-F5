package ctrmap.tools;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.MapPrefab;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.formats.zone.ZoneHeader;
import ctrmap.formats.containers.GR;
import ctrmap.formats.text.GFMessageFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE ASSET HARVESTER: sweeps every retail map region and mines every
 * standalone baked structure - houses, domes, fountains, lamps, stair blocks,
 * trees, rocks - into catalog entries the Building palette can place.
 *
 * <p>ORAS bakes virtually all scenery into region geometry (the prop system
 * holds little beyond animated doors), so comprehensive asset coverage IS this
 * sweep. Detection: per-mesh connected components of faces elevated above the
 * collision-sampled ground, merged across meshes by footprint overlap -
 * measured to reproduce the hand-curated catalog's tile boxes exactly.
 * Dedup: rotation-canonical geometry signatures collapse the same asset
 * across its hundreds of appearances. Every emitted entry passes the same
 * gate as the curated 48: cut from pristine, EVERY piece stamps onto a
 * painted-grass region, model validates.
 *
 * <p>Emits {@code oras_buildings_auto.tsv} - metadata only (donor region +
 * tile box), no game assets, same columns as the curated TSV plus
 * location/count/signature trailers.
 *
 * Usage: java ctrmap.tools.BuildingHarvester &lt;pristineRomfsDir&gt; &lt;outTsv&gt;
 */
public class BuildingHarvester {

	static final float TILE = 18f, ORIGIN = -360f;
	static final int DIM = 40;
	static final float ELEV = 4f;       //face counts as "structure" this far above ground
	static final int MIN_FACES = 20;
	static final float MIN_YSPAN = 5f;
	public static final int TERRAIN_TILES = 20; //components this wide are terrain, not assets

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("Usage: java ctrmap.tools.BuildingHarvester <pristineRomfsDir> [outTsv]");
			return;
		}
		String romfs = args[0];
		String out = args.length > 1 ? args[1] : "src/ctrmap/resources/oras_buildings_auto.tsv";
		ctrmap.Workspace.GameType game = ctrmap.Workspace.GameType.ORAS;
		GARC gr = new GARC(new File(romfs + ctrmap.Workspace.getArchivePath(ctrmap.Workspace.ArchiveType.FIELD_DATA, game)));
		GARC zo = new GARC(new File(romfs + ctrmap.Workspace.getArchivePath(ctrmap.Workspace.ArchiveType.ZONE_DATA, game)));
		GARC mm = new GARC(new File(romfs + ctrmap.Workspace.getArchivePath(ctrmap.Workspace.ArchiveType.MAP_MATRIX, game)));
		GARC gt = new GARC(new File(romfs + ctrmap.Workspace.getArchivePath(ctrmap.Workspace.ArchiveType.GAMETEXT, game)));
		List<String> locNames = GFMessageFile.getStrings(gt.getDecompressedEntry(90));

		//region -> {areaId, locationName} via the base zones' headers
		String[] regionLoc = new String[gr.length];
		int[] regionArea = new int[gr.length];
		java.util.Arrays.fill(regionArea, -1);
		int zones = Math.min(536, zo.length - 2);
		for (int z = 0; z < zones; z++) {
			try {
				byte[] c = zo.getDecompressedEntry(z);
				byte[] hdr = sub(c, 0);
				ZoneHeader h = new ZoneHeader(hdr, ctrmap.Workspace.GameType.ORAS);
				byte[] mat = mm.getDecompressedEntry(h.mapmatrixID);
				int sub0 = le32(mat, 4);
				int w = u16(mat, sub0 + 4), ht = u16(mat, sub0 + 6);
				String loc = h.parentMap >= 0 && h.parentMap < locNames.size() ? locNames.get(h.parentMap) : "";
				if (loc == null || loc.isEmpty()) {
					loc = "Area " + h.areadataID;
				}
				for (int k = 0; k < w * ht; k++) {
					int id = u16(mat, sub0 + 8 + k * 2);
					if (id != 0xFFFF && id < regionLoc.length && regionArea[id] < 0) {
						regionArea[id] = h.areadataID;
						regionLoc[id] = loc;
					}
				}
			} catch (Exception ignore) {
			}
		}

		//sweep: detect structures per region
		Map<Long, Cand> bySig = new LinkedHashMap<>();
		int detected = 0, terrain = 0, edge = 0, regionsScanned = 0;
		for (int r = 0; r < gr.length; r++) {
			if (regionArea[r] < 0) {
				continue; //not reachable from any base zone
			}
			byte[] rc = gr.getDecompressedEntry(r);
			byte[] modelB = sub(rc, 1);
			byte[] collB = sub(rc, 2);
			if (modelB == null || !BchMapModel.isMapModel(modelB)) {
				continue;
			}
			regionsScanned++;
			try {
				List<Comp> comps = detect(modelB, collB);
				for (Comp c : comps) {
					detected++;
					if (c.terrain) {
						terrain++;
						continue;
					}
					if (c.minX < ORIGIN + 2 || c.minZ < ORIGIN + 2 || c.maxX > -ORIGIN - 2 || c.maxZ > -ORIGIN - 2) {
						edge++;
						continue;
					}
					long sig = c.signature();
					Cand prev = bySig.get(sig);
					if (prev == null) {
						Cand n = new Cand();
						n.region = r;
						n.area = regionArea[r];
						n.loc = regionLoc[r];
						n.comp = c;
						n.count = 1;
						bySig.put(sig, n);
					} else {
						prev.count++;
					}
				}
			} catch (RuntimeException ignore) {
			}
		}
		System.out.println("sweep: " + regionsScanned + " regions, " + detected + " structures ("
				+ terrain + " terrain, " + edge + " edge-straddling), " + bySig.size() + " unique");

		//verify every candidate through the curated-catalog gate
		byte[] grassDonor = sub(gr.getDecompressedEntry(1), 1); //Route 101 tileset
		TilePalette[][] grass = new TilePalette[DIM][DIM];
		for (TilePalette[] row : grass) {
			java.util.Arrays.fill(row, TilePalette.GRASS);
		}
		RegionFactory.BlankContent base = PaintedRegionBuilder.build(grassDonor, grass, null, null, TerrainLighting.daytime(), false);
		File tmpDir = new File(System.getProperty("java.io.tmpdir"), "ctrmap_harvest");
		tmpDir.mkdirs();

		List<String> rows = new ArrayList<>();
		Map<String, Integer> nameSeq = new HashMap<>();
		int verified = 0, dropped = 0;
		long t0 = System.currentTimeMillis();
		for (Map.Entry<Long, Cand> en : bySig.entrySet()) {
			Cand c = en.getValue();
			try {
				File tmp = new File(tmpDir, "reg_" + c.region);
				if (!tmp.exists()) {
					try (FileOutputStream fo = new FileOutputStream(tmp)) {
						fo.write(gr.getDecompressedEntry(c.region));
					}
				}
				GR reg = new GR(tmp);
				MapPrefab p = MapPrefab.extract(reg, c.comp.tx0, c.comp.ty0, c.comp.tx1, c.comp.ty1, "harvest");
				if (p == null) {
					dropped++;
					continue;
				}
				MapPrefab.StampResult sr = p.stampGeometry(base.model, 3, 3, -c.comp.baseY);
				//every piece, and none that lands only where its material already
				//exists: accepting "at least one piece" let fifteen cuts of skinned
				//regions through on the strength of the grass base's sea foam, and
				//they placed as a few triangles under a full-size invisible wall
				boolean whole = sr.missingMaterials.isEmpty();
				for (MapPrefab.Piece pc : p.pieces) {
					whole &= !pc.skinned;
				}
				if (!whole) {
					dropped++;
					continue;
				}
				if (!new BchMapModel(sr.newModel).validate().isEmpty()) {
					dropped++;
					continue;
				}
				verified++;
				String cat = c.comp.category();
				String hint = c.comp.hint();
				String baseName = (c.loc == null ? "?" : c.loc) + " " + hint;
				int seq = nameSeq.merge(baseName, 1, Integer::sum);
				String name = baseName + (seq > 1 ? " " + seq : "");
				rows.add(cat + "\t" + name + "\t" + c.region + "\t" + c.area + "\t"
						+ c.comp.tx0 + "\t" + c.comp.ty0 + "\t" + c.comp.tx1 + "\t" + c.comp.ty1 + "\t"
						+ c.comp.baseY + "\t-1\t-1\t-\t-1\t-1\t"
						+ (c.loc == null ? "" : c.loc) + "\t" + c.count + "\t" + Long.toHexString(en.getKey()));
			} catch (Exception ex) {
				dropped++;
			}
			if ((verified + dropped) % 500 == 0) {
				System.out.println("verify: " + verified + " ok, " + dropped + " dropped, "
						+ ((System.currentTimeMillis() - t0) / 1000) + "s");
			}
		}
		System.out.println("verified " + verified + " entries (" + dropped + " dropped) in "
				+ ((System.currentTimeMillis() - t0) / 1000) + "s");

		try (PrintWriter w = new PrintWriter(out, "UTF-8")) {
			w.println("# AUTO-HARVESTED building/decor catalog - generated by ctrmap.tools.BuildingHarvester.");
			w.println("# Metadata only (donor region + tile box); geometry cuts from the user's pristine dump.");
			w.println("# Every row passed the cut->stamp->validate gate. Columns = curated TSV + location, retailCount, signature.");
			for (String row : rows) {
				w.println(row);
			}
		}
		System.out.println("wrote " + rows.size() + " entries -> " + out);
	}

	// ---- detection ---------------------------------------------------------

	public static final class Comp {

		public float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
		public float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		public float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		public int tx0, ty0, tx1, ty1, baseY;
		public boolean terrain;
		final List<float[]> faces = new ArrayList<>();   //9 coords each
		final List<String> faceMats = new ArrayList<>();
		final Map<String, Integer> matFaces = new HashMap<>();

		void addFace(float[] f, String mat) {
			faces.add(f);
			faceMats.add(mat);
			matFaces.merge(mat, 1, Integer::sum);
			for (int v = 0; v < 3; v++) {
				minX = Math.min(minX, f[v * 3]);
				maxX = Math.max(maxX, f[v * 3]);
				minY = Math.min(minY, f[v * 3 + 1]);
				maxY = Math.max(maxY, f[v * 3 + 1]);
				minZ = Math.min(minZ, f[v * 3 + 2]);
				maxZ = Math.max(maxZ, f[v * 3 + 2]);
			}
		}

		void absorb(Comp o) {
			for (int i = 0; i < o.faces.size(); i++) {
				addFace(o.faces.get(i), o.faceMats.get(i));
			}
		}

		void computeTiles() {
			tx0 = Math.max(0, (int) Math.floor((minX - ORIGIN) / TILE));
			ty0 = Math.max(0, (int) Math.floor((minZ - ORIGIN) / TILE));
			tx1 = Math.min(DIM - 1, (int) Math.floor((maxX - ORIGIN - 0.01f) / TILE));
			ty1 = Math.min(DIM - 1, (int) Math.floor((maxZ - ORIGIN - 0.01f) / TILE));
		}

		public int tilesW() {
			return tx1 - tx0 + 1;
		}

		public int tilesH() {
			return ty1 - ty0 + 1;
		}

		/** Rotation-canonical, order-independent geometry signature. */
		long signature() {
			long best = Long.MAX_VALUE;
			float w = maxX - minX, d = maxZ - minZ;
			for (int rot = 0; rot < 4; rot++) {
				long sum = 0;
				for (int i = 0; i < faces.size(); i++) {
					String mat = faceMats.get(i);
					String ml = mat.toLowerCase();
					if (ml.contains("shadow") || ml.contains("kage")) {
						continue;
					}
					float[] f = faces.get(i);
					long h = 0xcbf29ce484222325L;
					for (int ci = 0; ci < mat.length(); ci++) {
						h = (h ^ mat.charAt(ci)) * 0x100000001b3L;
					}
					for (int v = 0; v < 3; v++) {
						float dx = f[v * 3] - minX, dy = f[v * 3 + 1] - minY, dz = f[v * 3 + 2] - minZ;
						float rx, rz;
						switch (rot) {
							case 1: rx = dz; rz = w - dx; break;
							case 2: rx = w - dx; rz = d - dz; break;
							case 3: rx = d - dz; rz = dx; break;
							default: rx = dx; rz = dz; break;
						}
						h = (h ^ Math.round(rx * 2)) * 0x100000001b3L;
						h = (h ^ Math.round(dy * 2)) * 0x100000001b3L;
						h = (h ^ Math.round(rz * 2)) * 0x100000001b3L;
					}
					sum += h;
				}
				if (sum < best) {
					best = sum;
				}
			}
			return best;
		}

		/** This component's triangles per material name - what its geometry is made of. */
		public Map<String, Integer> materialTriangles() {
			return java.util.Collections.unmodifiableMap(matFaces);
		}

		/** Triangles of this component's materials that belong to the family. */
		int facesOf(String[] family) {
			int n = 0;
			for (Map.Entry<String, Integer> e : matFaces.entrySet()) {
				if (familyOf(e.getKey()) == family) {
					n += e.getValue();
				}
			}
			return n;
		}

		/** Triangles of the biggest single material no family recognises. */
		int biggestUnnamed() {
			int most = 0;
			for (Map.Entry<String, Integer> e : matFaces.entrySet()) {
				if (familyOf(e.getKey()) == null && e.getValue() > most) {
					most = e.getValue();
				}
			}
			return most;
		}

		/**
		 * What this component is: the keyword family owning the MOST of its
		 * triangles, and only when that beats the biggest single part no family
		 * recognises. It used to be the first family in the list that matched
		 * anything at all, so the smallest recognisable piece named the whole
		 * cut: "Littleroot Town lamp" is a furnished room of 4,733 triangles
		 * whose only family material is 12 triangles of lamp glass, against 924
		 * of bookshelf alone - and 127 catalogue entries were filed under a
		 * family another family outweighed. A component nothing recognises is
		 * named for its size, as it always was. A tie keeps the earlier family.
		 */
		public String hint() {
			int bestFaces = 0;
			String[] best = null;
			for (String[] family : HINT_FAMILIES) {
				int faces = facesOf(family);
				if (faces > bestFaces) {
					bestFaces = faces;
					best = family;
				}
			}
			if (best != null && bestFaces > biggestUnnamed()) {
				return best[0];
			}
			return tilesW() <= 2 && tilesH() <= 2 ? "decor" : "structure";
		}

		String category() {
			return categoryOf(hint());
		}
	}

	/**
	 * What a component is called, by the words its materials use: {hint, then
	 * the keywords that suggest it}. The order is the tie-break order.
	 */
	public static final String[][] HINT_FAMILIES = {
		{"tree", "platan", "tree", "happa", "leaf", "yashi"},
		{"sign", "kanban"},
		{"fence", "saku", "fence"},
		{"lamp", "lamp", "light", "toudai"},
		{"stairs", "kaidan", "step"},
		{"bridge", "hashi", "bridge"},
		{"building", "pokecen", "friend", "shop", "gym", "yane", "roof", "kabe", "mado", "door", "house"},
		{"rock", "iwa", "rock", "gake", "ishi"}
	};

	/** The family a material name belongs to - the first that claims it - or null. */
	public static String[] familyOf(String material) {
		String ml = material.toLowerCase();
		for (String[] family : HINT_FAMILIES) {
			for (int k = 1; k < family.length; k++) {
				if (ml.contains(family[k])) {
					return family;
				}
			}
		}
		return null;
	}

	/** The catalogue kind a hint is filed under. */
	public static String categoryOf(String hint) {
		switch (hint) {
			case "tree": return "A_TREE";
			case "sign": return "A_SIGN";
			case "fence": return "A_FENCE";
			case "lamp": return "A_LAMP";
			case "stairs": return "A_STAIRS";
			case "bridge": return "A_BRIDGE";
			case "building": return "A_BUILDING";
			case "rock": return "A_ROCK";
			case "decor": return "A_DECOR";
			default: return "A_STRUCT";
		}
	}

	static final class Cand {

		int region, area, count;
		String loc;
		Comp comp;
	}

	/** Per-mesh connected components of elevated faces, merged across meshes. */
	public static List<Comp> detect(byte[] modelB, byte[] collB) {
		BchMapModel m = new BchMapModel(modelB);
		float[][] ground = groundGrid(collB);

		List<Comp> comps = new ArrayList<>();
		for (BchMapModel.MeshGeom g : m.geometry()) {
			if (!g.posOk) {
				continue;
			}
			String mat = m.getMaterialName(m.getMeshMaterialIndex(g.meshIndex));
			if (mat == null) {
				mat = "?";
			}
			float[][] pos = m.getVertexPositions(g.meshIndex);
			int[] tris = m.getTriangles(g.meshIndex);
			//union-find over quantized vertex keys of elevated faces
			Map<Long, Integer> vroot = new HashMap<>();
			int[] parent = new int[tris.length / 3 + pos.length];
			for (int i = 0; i < parent.length; i++) {
				parent[i] = i;
			}
			List<int[]> elevated = new ArrayList<>(); //{a,b,c} vertex indices
			for (int t = 0; t + 2 < tris.length; t += 3) {
				int a = tris[t], b = tris[t + 1], c = tris[t + 2];
				if (a >= pos.length || b >= pos.length || c >= pos.length) {
					continue;
				}
				float cx = (pos[a][0] + pos[b][0] + pos[c][0]) / 3f;
				float cy = (pos[a][1] + pos[b][1] + pos[c][1]) / 3f;
				float cz = (pos[a][2] + pos[b][2] + pos[c][2]) / 3f;
				float gY = groundAt(ground, cx, cz);
				if (Float.isNaN(gY) || cy <= gY + ELEV) {
					continue;
				}
				elevated.add(new int[]{a, b, c});
			}
			//union by shared quantized vertex position
			int fi = 0;
			for (int[] f : elevated) {
				int faceNode = fi++;
				for (int v : f) {
					long key = (Math.round(pos[v][0] * 8) & 0x1FFFFFL)
							| ((Math.round(pos[v][1] * 8) & 0x1FFFFFL) << 21)
							| ((Math.round(pos[v][2] * 8) & 0x1FFFFFL) << 42);
					Integer prev = vroot.get(key);
					if (prev == null) {
						vroot.put(key, faceNode);
					} else {
						union(parent, faceNode, prev);
					}
				}
			}
			Map<Integer, Comp> byRoot = new HashMap<>();
			fi = 0;
			for (int[] f : elevated) {
				int root = find(parent, fi++);
				Comp c = byRoot.get(root);
				if (c == null) {
					c = new Comp();
					byRoot.put(root, c);
				}
				c.addFace(new float[]{pos[f[0]][0], pos[f[0]][1], pos[f[0]][2],
					pos[f[1]][0], pos[f[1]][1], pos[f[1]][2],
					pos[f[2]][0], pos[f[2]][1], pos[f[2]][2]}, mat);
			}
			comps.addAll(byRoot.values());
		}
		for (Comp c : comps) {
			c.computeTiles();
			c.terrain = c.tilesW() > TERRAIN_TILES || c.tilesH() > TERRAIN_TILES;
		}
		//cross-mesh merge: footprint overlap >= 50% of the smaller, Y ranges near
		boolean changed = true;
		while (changed) {
			changed = false;
			outer:
			for (int i = 0; i < comps.size(); i++) {
				Comp a = comps.get(i);
				if (a.terrain) {
					continue;
				}
				for (int j = i + 1; j < comps.size(); j++) {
					Comp b = comps.get(j);
					if (b.terrain) {
						continue;
					}
					if (overlaps(a, b)) {
						a.absorb(b);
						a.computeTiles();
						a.terrain = a.tilesW() > TERRAIN_TILES || a.tilesH() > TERRAIN_TILES;
						comps.remove(j);
						changed = true;
						break outer;
					}
				}
			}
		}
		//satellite absorption: a piece whose tile box sits inside another's
		//1-tile-dilated box belongs to it (porches, awnings)
		changed = true;
		while (changed) {
			changed = false;
			outer2:
			for (int i = 0; i < comps.size(); i++) {
				Comp a = comps.get(i);
				if (a.terrain) {
					continue;
				}
				for (int j = 0; j < comps.size(); j++) {
					if (i == j) {
						continue;
					}
					Comp b = comps.get(j);
					if (b.terrain) {
						continue;
					}
					if (b.tx0 >= a.tx0 - 1 && b.ty0 >= a.ty0 - 1 && b.tx1 <= a.tx1 + 1 && b.ty1 <= a.ty1 + 1
							&& b.faces.size() < a.faces.size()) {
						a.absorb(b);
						a.computeTiles();
						//the box just grew - a component now terrain-sized stops being
						//an asset here as it does after a cross-mesh merge, or a fence
						//keeps swallowing satellites until it is a 22x17 slab of road
						a.terrain = a.tilesW() > TERRAIN_TILES || a.tilesH() > TERRAIN_TILES;
						comps.remove(j);
						changed = true;
						break outer2;
					}
				}
			}
		}
		//final filter + baseY
		List<Comp> out = new ArrayList<>();
		for (Comp c : comps) {
			if (c.terrain) {
				out.add(c); //kept for the terrain count only
				continue;
			}
			if (c.faces.size() < MIN_FACES || (c.maxY - c.minY) < MIN_YSPAN) {
				continue;
			}
			//the structure's own footing: of the ground under its box, the sample
			//nearest its lowest face that the structure actually reaches - no more
			//than half a step below that face (a buried base, a sloped tile) and
			//no higher than its top (a sunken floor, a wall running down a cliff
			//edge). The lowest ground under the whole box gave a cliff-top lamp
			//with its base at 153 a baseY of 0 - the sea at the foot of the cliff -
			//and stamped it 153 units into the air. A structure reaching no ground
			//at all (a bridge over a chasm, a treehouse) stands on its lowest face.
			float footing = c.minY, nearest = Float.MAX_VALUE;
			for (int ty = c.ty0; ty <= c.ty1; ty++) {
				for (int tx = c.tx0; tx <= c.tx1; tx++) {
					float gY = ground[ty][tx];
					if (!Float.isNaN(gY) && gY >= c.minY - 9 && gY <= c.maxY && Math.abs(gY - c.minY) < nearest) {
						nearest = Math.abs(gY - c.minY);
						footing = gY;
					}
				}
			}
			c.baseY = Math.round(footing);
			out.add(c);
		}
		return out;
	}

	static boolean overlaps(Comp a, Comp b) {
		float ox = Math.min(a.maxX, b.maxX) - Math.max(a.minX, b.minX);
		float oz = Math.min(a.maxZ, b.maxZ) - Math.max(a.minZ, b.minZ);
		if (ox <= 0 || oz <= 0) {
			return false;
		}
		float areaA = (a.maxX - a.minX) * (a.maxZ - a.minZ);
		float areaB = (b.maxX - b.minX) * (b.maxZ - b.minZ);
		float smaller = Math.max(1f, Math.min(areaA, areaB));
		if (ox * oz < smaller * 0.5f) {
			return false;
		}
		return a.minY <= b.maxY + 2 && b.minY <= a.maxY + 2;
	}

	static float[][] groundGrid(byte[] collB) {
		float[][] g = new float[DIM][DIM];
		for (float[] row : g) {
			java.util.Arrays.fill(row, Float.NaN);
		}
		if (collB == null || !GfColl.isColl(collB)) {
			return g;
		}
		GfColl coll;
		try {
			coll = new GfColl(collB);
		} catch (RuntimeException ex) {
			return g;
		}
		for (float[] t : coll.uniqueTris) {
			for (int v = 0; v < 3; v++) {
				int tx = (int) Math.floor((t[v * 3] - ORIGIN) / TILE);
				int ty = (int) Math.floor((t[v * 3 + 2] - ORIGIN) / TILE);
				if (tx >= 0 && ty >= 0 && tx < DIM && ty < DIM) {
					float y = t[v * 3 + 1];
					if (Float.isNaN(g[ty][tx]) || y < g[ty][tx]) {
						g[ty][tx] = y;
					}
				}
			}
		}
		//fill unsampled tiles from neighbours (a few passes)
		for (int pass = 0; pass < 4; pass++) {
			for (int y = 0; y < DIM; y++) {
				for (int x = 0; x < DIM; x++) {
					if (!Float.isNaN(g[y][x])) {
						continue;
					}
					float sum = 0;
					int n = 0;
					if (x > 0 && !Float.isNaN(g[y][x - 1])) {
						sum += g[y][x - 1];
						n++;
					}
					if (y > 0 && !Float.isNaN(g[y - 1][x])) {
						sum += g[y - 1][x];
						n++;
					}
					if (x < DIM - 1 && !Float.isNaN(g[y][x + 1])) {
						sum += g[y][x + 1];
						n++;
					}
					if (y < DIM - 1 && !Float.isNaN(g[y + 1][x])) {
						sum += g[y + 1][x];
						n++;
					}
					if (n > 0) {
						g[y][x] = sum / n;
					}
				}
			}
		}
		return g;
	}

	static float groundAt(float[][] g, float x, float z) {
		int tx = (int) Math.floor((x - ORIGIN) / TILE);
		int ty = (int) Math.floor((z - ORIGIN) / TILE);
		if (tx < 0 || ty < 0 || tx >= DIM || ty >= DIM) {
			return Float.NaN;
		}
		return g[ty][tx];
	}

	static int find(int[] p, int i) {
		while (p[i] != i) {
			p[i] = p[p[i]];
			i = p[i];
		}
		return i;
	}

	static void union(int[] p, int a, int b) {
		p[find(p, a)] = find(p, b);
	}

	static byte[] sub(byte[] c, int i) {
		if (c == null || c.length < 8) {
			return null;
		}
		int count = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= count) {
			return null;
		}
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		if (o0 < 0 || o1 > c.length || o1 < o0) {
			return null;
		}
		return java.util.Arrays.copyOfRange(c, o0, o1);
	}

	static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
