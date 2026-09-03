package ctrmap.formats.h3d;

import ctrmap.formats.containers.GR;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.formats.tilemap.Tilemap;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A map PREFAB - a reusable piece of map (a building, a bridge, a patch of
 * scenery) cut out of any region and stampable into any other. This is the
 * "build maps out of the game's own pieces" workflow.
 *
 * <p>A prefab carries all three data layers, in coordinates relative to its
 * anchor (the min corner of the tile box it was cut from):
 * <ul>
 * <li>per-mesh geometry pieces: FULL vertex strides (UVs/normals/colors ride
 *     along) + a local triangle list + the source material name - faces
 *     crossing the box edge are left out, and counted;</li>
 * <li>the collision triangles inside the box, those crossing its edge clipped
 *     to it;</li>
 * <li>the 4-byte movement-tile tuples of the footprint.</li>
 * </ul>
 * Stamping appends each piece into the target region's mesh with the SAME
 * material name (so textures resolve). Pieces whose material the target lacks
 * are reported; injecting brand-new materials is the {@code BchModelAppender}
 * path layered on top once available.
 *
 * <p>File format (".ctrprefab", little-endian via DataOutput = big-endian
 * Java streams kept internal-only): magic CMPF, version, then the layers.
 */
public class MapPrefab {

	public static final int MAGIC = 0x434D5046; // CMPF
	public static final int VERSION = 2;

	public static class Piece {

		public String material;
		public int stride;
		public int posOffset;
		public byte[] vertexBytes;   // n * stride, positions RELATIVE to the prefab anchor
		public int[] triangles;      // local indices, 3 per face
		public int donorMeshIndex = -1;            // mesh in the embedded donor model (v2)
		public final List<String> textures = new ArrayList<>();  // texture names the material references (v2)
		public boolean skinned;      // the donor submesh is bone-dependent: it lands only where its material already exists (v2)
	}

	public String name = "prefab";
	public int sourceRegion = -1;
	public int donorArea = -1;            // the source region's AreaData id (v2, for texture carry)
	public byte[] donorModel;             // the source region's full model (v2, enables new-material stamping)
	public int tilesW, tilesH;            // footprint in tiles
	public final List<Piece> pieces = new ArrayList<>();
	public final List<float[]> collTris = new ArrayList<>();   // float[9], anchor-relative
	public byte[][][] tiles;              // [w][h][4] tuples, or null
	public int facesDropped;              // faces that crossed the box edge and were left out of the cut
	public final List<String> materialsLost = new ArrayList<>();   // materials with faces in the box but none fully inside

	// ---- extraction -------------------------------------------------------

	/**
	 * Cuts a prefab out of a region: every face fully inside the tile box
	 * (region-local tiles, inclusive), all layers. Returns null if the box
	 * contains no geometry.
	 */
	public static MapPrefab extract(GR gr, int tx0, int ty0, int tx1, int ty1, String name) {
		GeoBoxOps.Box box = GeoBoxOps.Box.ofTiles(tx0, ty0, tx1, ty1);
		byte[] modelBytes = gr.getFile(1);
		if (!BchMapModel.isMapModel(modelBytes)) {
			return null;
		}
		BchMapModel model = new BchMapModel(modelBytes);
		MapPrefab p = new MapPrefab();
		p.name = name;
		p.tilesW = Math.abs(tx1 - tx0) + 1;
		p.tilesH = Math.abs(ty1 - ty0) + 1;
		float ax = box.minX, az = box.minZ; // anchor = box min corner

		//per material, faces kept and faces that crossed the edge: a cut says
		//what it left out and names any material that vanished with it
		Map<String, int[]> perMaterial = new LinkedHashMap<>();
		for (BchMapModel.MeshGeom g : model.geometry()) {
			if (!g.posOk) {
				continue;
			}
			float[][] pos = model.getVertexPositions(g.meshIndex);
			int[] tris = model.getTriangles(g.meshIndex);
			boolean[] in = new boolean[pos.length];
			for (int v = 0; v < pos.length; v++) {
				in[v] = box.contains(pos[v]);
			}
			String material = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
			int[] faces = perMaterial.computeIfAbsent(material, k -> new int[2]);
			Map<Integer, Integer> remap = new LinkedHashMap<>();
			List<Integer> localTris = new ArrayList<>();
			for (int t = 0; t + 2 < tris.length; t += 3) {
				int inside = (in[tris[t]] ? 1 : 0) + (in[tris[t + 1]] ? 1 : 0) + (in[tris[t + 2]] ? 1 : 0);
				if (inside == 3) {
					for (int c = 0; c < 3; c++) {
						localTris.add(remap.computeIfAbsent(tris[t + c], k -> remap.size()));
					}
					faces[0]++;
				} else if (inside > 0) {
					faces[1]++;
				}
			}
			if (remap.isEmpty()) {
				continue;
			}
			Piece piece = new Piece();
			piece.material = material;
			piece.stride = g.stride;
			piece.posOffset = g.posOffset;
			piece.donorMeshIndex = g.meshIndex;
			piece.skinned = skinned(model, g.meshIndex);
			//texture names from the material header slots (+0x1C/+0x20/+0x24) - what
			//must exist in the target AREA's texture packs for the piece to render
			int matHdr = model.matValuesPtr + model.getMeshMaterialIndex(g.meshIndex) * 0x2C;
			for (int slot : new int[]{0x1C, 0x20, 0x24}) {
				int sp = model.ptr(matHdr + slot);
				if (sp > 0) {
					StringBuilder sb = new StringBuilder();
					for (int q = sp; q < model.raw.length && model.raw[q] != 0; q++) {
						sb.append((char) (model.raw[q] & 0xFF));
					}
					if (sb.length() > 0 && !piece.textures.contains(sb.toString())) {
						piece.textures.add(sb.toString());
					}
				}
			}
			piece.vertexBytes = new byte[remap.size() * g.stride];
			for (Map.Entry<Integer, Integer> e : remap.entrySet()) {
				int src = e.getKey(), dst = e.getValue();
				System.arraycopy(model.raw, g.vtxAbs + src * g.stride, piece.vertexBytes, dst * g.stride, g.stride);
				//re-anchor the position
				putF(piece.vertexBytes, dst * g.stride + g.posOffset, pos[src][0] - ax);
				putF(piece.vertexBytes, dst * g.stride + g.posOffset + 8, pos[src][2] - az);
			}
			piece.triangles = new int[localTris.size()];
			for (int i = 0; i < localTris.size(); i++) {
				piece.triangles[i] = localTris.get(i);
			}
			p.pieces.add(piece);
		}
		for (Map.Entry<String, int[]> e : perMaterial.entrySet()) {
			p.facesDropped += e.getValue()[1];
			if (e.getValue()[0] == 0 && e.getValue()[1] > 0) {
				p.materialsLost.add(e.getKey());
			}
		}
		if (p.pieces.isEmpty()) {
			return null;
		}
		//embed the donor model so pieces with materials the target lacks can be
		//stamped as brand-new material+mesh via BchModelAppender (v2 full path)
		p.donorModel = modelBytes;

		//collision (layer 0; multi-layer regions contribute their extra layers
		//too), clipped to the box. ORAS collision triangles are large, and a
		//narrow cut - a bridge, a flight of stairs - has every one of them
		//crossing its edge; keeping only whole triangles gave such cuts no
		//walkable surface at all.
		for (int cs : collSubfiles(gr)) {
			if (cs >= gr.len) {
				continue;
			}
			byte[] cb = gr.getFile(cs);
			if (!GfColl.isColl(cb)) {
				continue;
			}
			List<float[]> clipped = new ArrayList<>();
			for (float[] t : new GfColl(cb).uniqueTris) {
				clipToBox(t, box, clipped);
			}
			for (float[] t : clipped) {
				for (int v = 0; v < 3; v++) {
					t[v * 3] -= ax;
					t[v * 3 + 2] -= az;
				}
				p.collTris.add(t);
			}
		}

		//movement tiles (raw subfile-0 parse - no UI dependency)
		byte[] tmap = gr.getFile(0);
		int lx0 = Math.min(tx0, tx1), ly0 = Math.min(ty0, ty1);
		if (tmap != null && tmap.length >= 4 + 40 * 40 * 4) {
			p.tiles = new byte[p.tilesW][p.tilesH][];
			for (int y = 0; y < p.tilesH; y++) {
				for (int x = 0; x < p.tilesW; x++) {
					int off = 4 + ((ly0 + y) * 40 + (lx0 + x)) * 4;
					p.tiles[x][y] = new byte[]{tmap[off], tmap[off + 1], tmap[off + 2], tmap[off + 3]};
				}
			}
		}
		return p;
	}

	public static int[] collSubfiles(GR gr) {
		int count = gr.len;
		if (count >= 11) {
			return new int[]{2, 9, 10};
		}
		if (count >= 9) {
			return new int[]{2, 8};
		}
		return new int[]{2};
	}

	/**
	 * Clips one collision triangle to the box in XZ - Sutherland-Hodgman
	 * against its four edges, Y interpolated along every cut edge - and fans
	 * the surviving polygon into triangles. A triangle wholly inside comes
	 * back as itself; one wholly outside adds nothing.
	 */
	private static void clipToBox(float[] t, GeoBoxOps.Box box, List<float[]> out) {
		List<float[]> poly = new ArrayList<>();
		for (int v = 0; v < 3; v++) {
			poly.add(new float[]{t[v * 3], t[v * 3 + 1], t[v * 3 + 2]});
		}
		//{axis, limit, side}: keep where side * (coord - limit) >= 0
		float[][] edges = {{0, box.minX, 1}, {0, box.maxX, -1}, {2, box.minZ, 1}, {2, box.maxZ, -1}};
		for (float[] e : edges) {
			int axis = (int) e[0];
			List<float[]> next = new ArrayList<>();
			for (int i = 0; i < poly.size(); i++) {
				float[] a = poly.get(i), b = poly.get((i + 1) % poly.size());
				float da = e[2] * (a[axis] - e[1]), db = e[2] * (b[axis] - e[1]);
				if (da >= 0) {
					next.add(a);
				}
				if ((da >= 0) != (db >= 0)) {
					float s = da / (da - db);
					next.add(new float[]{a[0] + (b[0] - a[0]) * s, a[1] + (b[1] - a[1]) * s, a[2] + (b[2] - a[2]) * s});
				}
			}
			poly = next;
			if (poly.size() < 3) {
				return;
			}
		}
		for (int i = 1; i + 1 < poly.size(); i++) {
			float[] a = poly.get(0), b = poly.get(i), c = poly.get(i + 1);
			float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
			float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
			float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			if (nx * nx + ny * ny + nz * nz > 1e-8f) { //a sliver of no area is not a surface
				out.add(new float[]{a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]});
			}
		}
	}

	/** True when a mesh's submesh header names bones (+0 skinningMode, +2 nodeIdCount): BchModelAppender cannot inject it. */
	private static boolean skinned(BchMapModel m, int meshIndex) {
		int sub = m.meshes.get(meshIndex)[3];
		return (m.raw[sub] | m.raw[sub + 1] | m.raw[sub + 2] | m.raw[sub + 3]) != 0;
	}

	/** Triangles across every piece. */
	public int triangleCount() {
		int n = 0;
		for (Piece piece : pieces) {
			n += piece.triangles.length / 3;
		}
		return n;
	}

	/** {lowest, highest} vertex Y across every piece, in the donor's frame. */
	public float[] heightSpan() {
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		for (Piece piece : pieces) {
			for (int o = piece.posOffset + 4; o + 4 <= piece.vertexBytes.length; o += piece.stride) {
				float y = getF(piece.vertexBytes, o);
				lo = Math.min(lo, y);
				hi = Math.max(hi, y);
			}
		}
		return new float[]{lo, hi};
	}

	// ---- stamping ---------------------------------------------------------

	/** Where one piece's geometry landed in the final model (for verification). */
	public static class Landing {

		public String material;   // the FINAL material name (unique name if injected)
		public int meshIndex = -1; // the mesh it landed in - recorded, not re-derived:
		                           // one material can sit on several meshes at different
		                           // layouts, so looking it up again by name can find
		                           // the wrong one
		public int base;          // first vertex index of the piece within that mesh
		public int count;         // piece vertex count
	}

	/** Per-piece stamp outcome for user-facing reporting. */
	public static class StampResult {

		public final List<String> stamped = new ArrayList<>();
		public final List<String> missingMaterials = new ArrayList<>();
		/** Materials INJECTED into the target model (donor mesh+material append). */
		public final List<String> newMaterials = new ArrayList<>();
		/** Texture names the injected materials need in the target AREA's packs. */
		public final List<String> texturesNeeded = new ArrayList<>();
		/** Per-piece landing (parallel to the prefab's pieces; null = not stamped). */
		public final List<Landing> landings = new ArrayList<>();
		public byte[] newModel;
		public byte[] newColl;          // layer-0 collision
		public int collTrisAdded;
		public int tilesStamped;
		/** Footprint tuples NOT written because the user's own tile stays: behaviour -> count (see stampFootprint). */
		public final Map<String, Integer> tilesKept = new TreeMap<>();
		/** Every tuple a verbatim copy wrote, by behaviour (see stampTiles). */
		public final Map<String, Integer> tilesWritten = new TreeMap<>();
	}

	/**
	 * Stamps this prefab into a target region model at the given region-local
	 * tile anchor + height offset. Geometry goes into the target's mesh with
	 * the SAME material name (full vertex strides appended, positions rebased);
	 * pieces whose material the target lacks are reported in
	 * {@code missingMaterials} and skipped. Collision and tiles are the
	 * caller's follow-up via {@link #stampCollision} and {@link #stampTiles}
	 * or {@link #stampFootprint}, filling the same result (kept separate so
	 * the UI can preview/confirm).
	 */
	public StampResult stampGeometry(byte[] targetModel, int tileX, int tileY, float dy) {
		StampResult r = new StampResult();
		float ax = tileX * 18f - 360f, az = tileY * 18f - 360f;
		byte[] current = targetModel;
		//parsed once, not per piece: used to check that a fast-path target really
		//has the donor's vertex layout and not merely its stride
		BchMapModel donor = null;
		if (donorModel != null && BchMapModel.isMapModel(donorModel)) {
			try {
				donor = new BchMapModel(donorModel);
			} catch (RuntimeException ignore) {
			}
		}
		for (Piece piece : pieces) {
			BchMapModel model = new BchMapModel(current);
			int target = findTargetMesh(model, piece, donor);
			int n = piece.vertexBytes.length / piece.stride;
			//rebased vertex bytes (anchor + height applied to every position)
			byte[] vtx = piece.vertexBytes.clone();
			for (int v = 0; v < n; v++) {
				int o = v * piece.stride + piece.posOffset;
				putF(vtx, o, getF(vtx, o) + ax);
				putF(vtx, o + 4, getF(vtx, o + 4) + dy);
				putF(vtx, o + 8, getF(vtx, o + 8) + az);
			}
			if (target >= 0) {
				//FAST PATH: grow the existing mesh with the same material+layout
				BchMapModel.MeshGeom g = model.geometry().get(target);
				int base = g.vertexCount;
				int[] tris = new int[piece.triangles.length];
				for (int i = 0; i < tris.length; i++) {
					tris[i] = base + piece.triangles[i];
				}
				current = model.appendGeometry(target, vtx, tris);
				r.stamped.add(piece.material + " (" + n + " verts)");
				Landing l = new Landing();
				l.material = model.getMaterialName(model.getMeshMaterialIndex(target));
				l.meshIndex = target;
				l.base = base;
				l.count = n;
				r.landings.add(l);
			} else if (donorModel != null && piece.donorMeshIndex >= 0) {
				//FULL PATH: inject the donor's material+mesh (command config, params,
				//texture refs ride along), then swap its buffers for just the piece
				try {
					String matName = uniqueMaterialName(model, piece.material);
					byte[] injected = BchModelAppender.append(current, donorModel, piece.donorMeshIndex, matName);
					BchMapModel im = new BchMapModel(injected);
					int newMesh = findMeshByMaterialName(im, matName);
					if (newMesh < 0) {
						throw new IllegalStateException("appended mesh not found");
					}
					current = im.setMeshGeometry(newMesh, vtx, piece.triangles.clone());
					r.newMaterials.add(matName);
					for (String tex : piece.textures) {
						if (!r.texturesNeeded.contains(tex)) {
							r.texturesNeeded.add(tex);
						}
					}
					r.stamped.add(piece.material + " (" + n + " verts, new material " + matName + ")");
					Landing l = new Landing();
					l.material = matName;
					l.meshIndex = newMesh;
					l.base = 0;
					l.count = n;
					r.landings.add(l);
				} catch (RuntimeException ex) {
					r.missingMaterials.add(piece.material + " (inject failed: " + ex.getMessage() + ")");
					r.landings.add(null);
				}
			} else {
				r.missingMaterials.add(piece.material);
				r.landings.add(null);
			}
		}
		r.newModel = current;
		return r;
	}

	private String uniqueMaterialName(BchMapModel model, String base) {
		String name = base;
		int k = 2;
		while (findMeshByMaterialName(model, name) >= 0 || materialNameExists(model, name)) {
			name = base + "_p" + (k++);
		}
		return name;
	}

	private static boolean materialNameExists(BchMapModel model, String name) {
		for (int i = 0; i < model.matCount; i++) {
			if (name.equals(model.getMaterialName(i))) {
				return true;
			}
		}
		return false;
	}

	private static int findMeshByMaterialName(BchMapModel model, String material) {
		for (int m = 0; m < model.meshes.size(); m++) {
			if (material.equals(model.getMaterialName(model.getMeshMaterialIndex(m)))) {
				return m;
			}
		}
		return -1;
	}

	/**
	 * Adds the prefab's collision at the anchor: {@code r.newColl} is the new
	 * layer-0 coll subfile (the input itself when there is nothing to add) and
	 * {@code r.collTrisAdded} how many triangles it gained.
	 */
	public void stampCollision(StampResult r, byte[] collBytes, int tileX, int tileY, float dy) {
		r.newColl = collBytes;
		r.collTrisAdded = 0;
		if (collTris.isEmpty() || !GfColl.isColl(collBytes)) {
			return;
		}
		float ax = tileX * 18f - 360f, az = tileY * 18f - 360f;
		GfColl c = new GfColl(collBytes);
		List<float[]> tris = new ArrayList<>(c.uniqueTris);
		for (float[] t : collTris) {
			float[] n = t.clone();
			for (int v = 0; v < 3; v++) {
				n[v * 3] += ax;
				n[v * 3 + 1] += dy;
				n[v * 3 + 2] += az;
			}
			tris.add(n);
		}
		r.newColl = GfColl.build(tris, c);
		r.collTrisAdded = collTris.size();
	}

	/**
	 * Stamps every footprint tuple into a Tilemap at the anchor - the Geometry
	 * tool's explicit "also update movement tiles" choice; {@code r.tilesStamped}
	 * counts them.
	 */
	public void stampTiles(StampResult r, Tilemap tm, int tileX, int tileY) {
		r.tilesStamped = 0;
		if (tiles == null) {
			return;
		}
		for (int y = 0; y < tilesH; y++) {
			for (int x = 0; x < tilesW; x++) {
				int dx = tileX + x, dyt = tileY + y;
				if (dx >= 0 && dx < 40 && dyt >= 0 && dyt < 40) {
					tm.setTileData(dx, dyt, tiles[x][y]);
					r.tilesStamped++;
				}
			}
		}
	}

	/**
	 * Stamps the footprint over a raw tilemap subfile - the painter's own
	 * layer - without repainting the user's map. A donor tuple is written only
	 * where it is a wall, or at the building's door tile ({@code doorX,doorY}
	 * anchor-relative, -1 for none); everywhere else the tile the user painted
	 * stays, and what the donor would have made of it is counted by behaviour
	 * in {@code r.tilesKept}. Copying the footprint whole turned 366 painted
	 * path tiles into surf water under one Route 110 cut and wrote door tiles
	 * that no warp backed.
	 */
	public void stampFootprint(StampResult r, byte[] tilemap, int tileX, int tileY, int doorX, int doorY) {
		r.tilesStamped = 0;
		r.tilesKept.clear();
		if (tiles == null) {
			return;
		}
		for (int y = 0; y < tilesH; y++) {
			for (int x = 0; x < tilesW; x++) {
				int dx = tileX + x, dyt = tileY + y;
				if (dx < 0 || dx >= 40 || dyt < 0 || dyt >= 40) {
					continue;
				}
				String behaviour = TilePalette.behaviourOf(tiles[x][y]);
				if ("wall".equals(behaviour) || (x == doorX && y == doorY)) {
					System.arraycopy(tiles[x][y], 0, tilemap, 4 + (dyt * 40 + dx) * 4, 4);
					r.tilesStamped++;
				} else {
					r.tilesKept.merge(behaviour, 1, Integer::sum);
				}
			}
		}
	}

	/**
	 * The mesh a piece stamps into: same material name AND the same vertex
	 * layout (stride + position offset) - the same name can appear on several
	 * meshes with different layouts, and vertex bytes are copied whole-stride
	 * so the layout must match exactly.
	 */
	/**
	 * True when the target mesh has EXACTLY the donor mesh's vertex layout, so
	 * the piece's bytes can be copied into it whole-stride.
	 *
	 * <p>{@link #findTargetMesh} matches on material name, stride and position
	 * offset, which is not enough. PICA s8 and u8 are both one byte wide, so two
	 * meshes can agree on all three and still disagree about what the bytes
	 * mean. A u8 colour copied into an s8 attribute reads back negative and
	 * renders black - which is what happened to stamped fir trees - and worse
	 * combinations exist in the corpus, where a donor carrying a normal lands in
	 * a target buffering a UV, so the normal's floats are read as texture
	 * coordinates.
	 *
	 * <p>Returns true when there is no donor model to compare against (a v1
	 * prefab), preserving the old behaviour rather than refusing to stamp.
	 */
	private static boolean layoutMatches(BchMapModel donor, Piece piece, BchMapModel model, int target) {
		if (donor == null || piece.donorMeshIndex < 0 || piece.donorMeshIndex >= donor.meshCount) {
			return true;
		}
		try {
			List<BchMapModel.MeshAttr> a = donor.attributes(piece.donorMeshIndex);
			List<BchMapModel.MeshAttr> b = model.attributes(target);
			if (a == null || b == null || a.size() != b.size()) {
				return false;
			}
			for (int i = 0; i < a.size(); i++) {
				BchMapModel.MeshAttr x = a.get(i), y = b.get(i);
				if (x.name != y.name || x.type != y.type || x.elems != y.elems || x.offset != y.offset) {
					return false;
				}
			}
			return true;
		} catch (RuntimeException ex) {
			return false; //cannot prove they match - take the safe path
		}
	}

	public static int findTargetMesh(BchMapModel model, Piece piece) {
		return findTargetMesh(model, piece, null);
	}

	/**
	 * As above, but when a donor model is supplied the candidate must also carry
	 * the donor's exact vertex layout.
	 *
	 * <p>Several meshes in a map can share a material name, and taking the first
	 * one that merely agreed on stride was how colours ended up in the wrong
	 * format. Note that the search continues rather than giving up on the first
	 * candidate: a map that holds the same material at two layouts usually holds
	 * the right one further down the list, and rejecting the whole stamp on the
	 * first near-miss turned working stamps into hard failures - region 540's
	 * {@code wall06_r} sits on more than one mesh, and the full path could not
	 * take it because that donor submesh is skinned.
	 */
	public static int findTargetMesh(BchMapModel model, Piece piece, BchMapModel donor) {
		if (piece.material == null) {
			return -1;
		}
		List<BchMapModel.MeshGeom> geom = model.geometry();
		for (int m = 0; m < model.meshes.size(); m++) {
			if (!piece.material.equals(model.getMaterialName(model.getMeshMaterialIndex(m)))) {
				continue;
			}
			BchMapModel.MeshGeom g = geom.get(m);
			if (!(g.posOk && g.stride == piece.stride && g.posOffset == piece.posOffset)) {
				continue;
			}
			if (donor != null && !layoutMatches(donor, piece, model, m)) {
				continue; //same name and stride, different vertex format
			}
			return m;
		}
		return -1;
	}

	// ---- persistence ------------------------------------------------------

	public void save(File f) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream o = new DataOutputStream(baos);
		o.writeInt(MAGIC);
		o.writeInt(VERSION);
		o.writeUTF(name);
		o.writeInt(sourceRegion);
		o.writeInt(tilesW);
		o.writeInt(tilesH);
		o.writeInt(pieces.size());
		for (Piece p : pieces) {
			o.writeUTF(p.material == null ? "" : p.material);
			o.writeInt(p.stride);
			o.writeInt(p.posOffset);
			o.writeInt(p.vertexBytes.length);
			o.write(p.vertexBytes);
			o.writeInt(p.triangles.length);
			for (int t : p.triangles) {
				o.writeInt(t);
			}
			//v2 per-piece
			o.writeInt(p.donorMeshIndex);
			o.writeInt(p.textures.size());
			for (String t : p.textures) {
				o.writeUTF(t);
			}
		}
		//v2 donor payload (LZ11-compressed model, enables new-material stamping)
		o.writeInt(donorArea);
		if (donorModel != null) {
			byte[] packed = ctrmap.formats.garc.LZ11.compress(donorModel);
			o.writeInt(packed.length);
			o.write(packed);
		} else {
			o.writeInt(0);
		}
		o.writeInt(collTris.size());
		for (float[] t : collTris) {
			for (int i = 0; i < 9; i++) {
				o.writeFloat(t[i]);
			}
		}
		o.writeBoolean(tiles != null);
		if (tiles != null) {
			for (int y = 0; y < tilesH; y++) {
				for (int x = 0; x < tilesW; x++) {
					o.write(tiles[x][y]);
				}
			}
		}
		o.flush();
		try (FileOutputStream fos = new FileOutputStream(f)) {
			fos.write(baos.toByteArray());
		}
	}

	public static MapPrefab load(File f) throws IOException {
		try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("Not a CTRMap prefab file.");
			}
			int ver = in.readInt();
			if (ver > VERSION) {
				throw new IOException("Prefab version " + ver + " is newer than this CTRMap.");
			}
			MapPrefab p = new MapPrefab();
			p.name = in.readUTF();
			p.sourceRegion = in.readInt();
			p.tilesW = in.readInt();
			p.tilesH = in.readInt();
			int np = in.readInt();
			for (int i = 0; i < np; i++) {
				Piece piece = new Piece();
				piece.material = in.readUTF();
				piece.stride = in.readInt();
				piece.posOffset = in.readInt();
				piece.vertexBytes = new byte[in.readInt()];
				in.readFully(piece.vertexBytes);
				piece.triangles = new int[in.readInt()];
				for (int t = 0; t < piece.triangles.length; t++) {
					piece.triangles[t] = in.readInt();
				}
				if (ver >= 2) {
					piece.donorMeshIndex = in.readInt();
					int nt = in.readInt();
					for (int t = 0; t < nt; t++) {
						piece.textures.add(in.readUTF());
					}
				}
				p.pieces.add(piece);
			}
			if (ver >= 2) {
				//v2 donor payload sits between the pieces block and the collision block
				p.donorArea = in.readInt();
				int dl = in.readInt();
				if (dl > 0) {
					byte[] packed = new byte[dl];
					in.readFully(packed);
					p.donorModel = ctrmap.formats.garc.LZ11.decompress(packed);
				}
				//skinning is a fact of the embedded donor, not stored: re-read it
				if (p.donorModel != null && BchMapModel.isMapModel(p.donorModel)) {
					try {
						BchMapModel donor = new BchMapModel(p.donorModel);
						for (Piece piece : p.pieces) {
							piece.skinned = piece.donorMeshIndex >= 0 && piece.donorMeshIndex < donor.meshCount
									&& skinned(donor, piece.donorMeshIndex);
						}
					} catch (RuntimeException ignore) {
					}
				}
			}
			int nc = in.readInt();
			for (int i = 0; i < nc; i++) {
				float[] t = new float[9];
				for (int k = 0; k < 9; k++) {
					t[k] = in.readFloat();
				}
				p.collTris.add(t);
			}
			if (in.readBoolean()) {
				p.tiles = new byte[p.tilesW][p.tilesH][4];
				for (int y = 0; y < p.tilesH; y++) {
					for (int x = 0; x < p.tilesW; x++) {
						in.readFully(p.tiles[x][y]);
					}
				}
			}
			return p;
		}
	}

	private static float getF(byte[] b, int o) {
		return Float.intBitsToFloat((b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24));
	}

	private static void putF(byte[] b, int o, float f) {
		int v = Float.floatToIntBits(f);
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}
}
