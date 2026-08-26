package ctrmap.formats.h3d;

import ctrmap.formats.containers.GR;
import ctrmap.formats.gfcollision.GfColl;
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

/**
 * A map PREFAB - a reusable piece of map (a building, a bridge, a patch of
 * scenery) cut out of any region and stampable into any other. This is the
 * "build maps out of the game's own pieces" workflow.
 *
 * <p>A prefab carries all three data layers, in coordinates relative to its
 * anchor (the min corner of the tile box it was cut from):
 * <ul>
 * <li>per-mesh geometry pieces: FULL vertex strides (UVs/normals/colors ride
 *     along) + a local triangle list + the source material name;</li>
 * <li>the collision triangles inside the box;</li>
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
	public static final int VERSION = 1;

	public static class Piece {

		public String material;
		public int stride;
		public int posOffset;
		public byte[] vertexBytes;   // n * stride, positions RELATIVE to the prefab anchor
		public int[] triangles;      // local indices, 3 per face
	}

	public String name = "prefab";
	public int sourceRegion = -1;
	public int tilesW, tilesH;            // footprint in tiles
	public final List<Piece> pieces = new ArrayList<>();
	public final List<float[]> collTris = new ArrayList<>();   // float[9], anchor-relative
	public byte[][][] tiles;              // [w][h][4] tuples, or null

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
			Map<Integer, Integer> remap = new LinkedHashMap<>();
			List<Integer> localTris = new ArrayList<>();
			for (int t = 0; t + 2 < tris.length; t += 3) {
				if (in[tris[t]] && in[tris[t + 1]] && in[tris[t + 2]]) {
					for (int c = 0; c < 3; c++) {
						localTris.add(remap.computeIfAbsent(tris[t + c], k -> remap.size()));
					}
				}
			}
			if (remap.isEmpty()) {
				continue;
			}
			Piece piece = new Piece();
			piece.material = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
			piece.stride = g.stride;
			piece.posOffset = g.posOffset;
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
		if (p.pieces.isEmpty()) {
			return null;
		}

		//collision (layer 0; multi-layer regions contribute their extra layers too)
		for (int cs : collSubfiles(gr)) {
			byte[] cb = gr.getFile(cs);
			if (!GfColl.isColl(cb)) {
				continue;
			}
			GfColl coll = new GfColl(cb);
			for (float[] t : coll.uniqueTris) {
				boolean all = true;
				for (int v = 0; v < 3 && all; v++) {
					float x = t[v * 3], z = t[v * 3 + 2];
					all = x >= box.minX && x <= box.maxX && z >= box.minZ && z <= box.maxZ;
				}
				if (all) {
					float[] rel = t.clone();
					for (int v = 0; v < 3; v++) {
						rel[v * 3] -= ax;
						rel[v * 3 + 2] -= az;
					}
					p.collTris.add(rel);
				}
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

	// ---- stamping ---------------------------------------------------------

	/** Per-piece stamp outcome for user-facing reporting. */
	public static class StampResult {

		public final List<String> stamped = new ArrayList<>();
		public final List<String> missingMaterials = new ArrayList<>();
		public byte[] newModel;
		public byte[] newColl;          // layer-0 collision
		public int collTrisAdded;
		public int tilesStamped;
	}

	/**
	 * Stamps this prefab into a target region model at the given region-local
	 * tile anchor + height offset. Geometry goes into the target's mesh with
	 * the SAME material name (full vertex strides appended, positions rebased);
	 * pieces whose material the target lacks are reported in
	 * {@code missingMaterials} and skipped. Collision and tiles are the
	 * caller's follow-up via {@link #stampCollision} / {@link #stampTiles}
	 * (kept separate so the UI can preview/confirm).
	 */
	public StampResult stampGeometry(byte[] targetModel, int tileX, int tileY, float dy) {
		StampResult r = new StampResult();
		float ax = tileX * 18f - 360f, az = tileY * 18f - 360f;
		byte[] current = targetModel;
		for (Piece piece : pieces) {
			BchMapModel model = new BchMapModel(current);
			int target = findTargetMesh(model, piece);
			if (target < 0) {
				r.missingMaterials.add(piece.material);
				continue;
			}
			BchMapModel.MeshGeom g = model.geometry().get(target);
			int n = piece.vertexBytes.length / piece.stride;
			byte[] vtx = new byte[n * g.stride];
			System.arraycopy(piece.vertexBytes, 0, vtx, 0, vtx.length);
			for (int v = 0; v < n; v++) {
				int o = v * g.stride + g.posOffset;
				putF(vtx, o, getF(vtx, o) + ax);
				putF(vtx, o + 4, getF(vtx, o + 4) + dy);
				putF(vtx, o + 8, getF(vtx, o + 8) + az);
			}
			int base = g.vertexCount;
			int[] tris = new int[piece.triangles.length];
			for (int i = 0; i < tris.length; i++) {
				tris[i] = base + piece.triangles[i];
			}
			current = model.appendGeometry(target, vtx, tris);
			r.stamped.add(piece.material + " (" + n + " verts)");
		}
		r.newModel = current;
		return r;
	}

	/** Adds the prefab's collision at the anchor; returns the new layer-0 coll subfile. */
	public byte[] stampCollision(byte[] collBytes, int tileX, int tileY, float dy) {
		if (collTris.isEmpty() || !GfColl.isColl(collBytes)) {
			return collBytes;
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
		return GfColl.build(tris, c);
	}

	/** Stamps the footprint tuples into a Tilemap at the anchor; returns tiles written. */
	public int stampTiles(Tilemap tm, int tileX, int tileY) {
		if (tiles == null) {
			return 0;
		}
		int n = 0;
		for (int y = 0; y < tilesH; y++) {
			for (int x = 0; x < tilesW; x++) {
				int dx = tileX + x, dyt = tileY + y;
				if (dx >= 0 && dx < 40 && dyt >= 0 && dyt < 40) {
					tm.setTileData(dx, dyt, tiles[x][y]);
					n++;
				}
			}
		}
		return n;
	}

	/**
	 * The mesh a piece stamps into: same material name AND the same vertex
	 * layout (stride + position offset) - the same name can appear on several
	 * meshes with different layouts, and vertex bytes are copied whole-stride
	 * so the layout must match exactly.
	 */
	public static int findTargetMesh(BchMapModel model, Piece piece) {
		if (piece.material == null) {
			return -1;
		}
		List<BchMapModel.MeshGeom> geom = model.geometry();
		for (int m = 0; m < model.meshes.size(); m++) {
			if (!piece.material.equals(model.getMaterialName(model.getMeshMaterialIndex(m)))) {
				continue;
			}
			BchMapModel.MeshGeom g = geom.get(m);
			if (g.posOk && g.stride == piece.stride && g.posOffset == piece.posOffset) {
				return m;
			}
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
				p.pieces.add(piece);
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
