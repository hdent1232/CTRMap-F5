package ctrmap.formats.h3d;

import ctrmap.formats.gfcollision.GfColl;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds BLANK map-region content - the "new empty map" primitive. Starting
 * from a template region (normally the zone's own current region, so the area's
 * textures are guaranteed present), it produces the four content subfiles of a
 * flat, walkable, empty canvas:
 * <ul>
 * <li>model: the chosen GROUND mesh becomes a flat 720x720 plane (a 10x10 quad
 *     grid with tiled UVs and up normals; vertex colors inherit the donor
 *     surface), every other editable mesh collapses to a degenerate
 *     triangle - same materials, so the file stays area-consistent;</li>
 * <li>collision: two triangles covering the full frame at Y=0, built by the
 *     retail-exact {@link GfColl} writer;</li>
 * <li>tilemap: all walkable with a one-tile blocked border;</li>
 * <li>props: empty.</li>
 * </ul>
 * All coordinates use the measured region frame: center origin, -360..360,
 * 18 units per tile.
 */
public class RegionFactory {

	/** The blank content for one region, ready for GR.storeFile. */
	public static class BlankContent {

		public byte[] model;
		public byte[] collision;
		public byte[] tilemap;
		public byte[] props;
		/** Painted tiles that had no ground under them and took a walkable
		 *  neighbour's, counted by the composite builder that did it, so what
		 *  Apply reports cannot drift from what was actually built. */
		public int borrowedGround;
	}

	/**
	 * Builds blank content from a template region's subfiles.
	 *
	 * @param templateModel the template's map model (subfile 1)
	 * @param groundMesh    the mesh whose material becomes the ground plane
	 */
	/**
	 * Blanks a region container in place: flat ground, no collision, void tiles,
	 * no props, and every extra layer emptied. Answers false and writes NOTHING
	 * when the region has no editable map model.
	 *
	 * <p>WHY THIS IS ONE METHOD. These four subfile numbers plus the extra-layer
	 * rules were written out inside Blank map canvas, and the zone append now
	 * needs exactly the same thing for the padding zones it creates. Two copies of
	 * "subfile 1 is the model, 2 is the collision, 0 is the tilemap, 3 is the
	 * props, and 7..10 are the extra layers when the container is long enough"
	 * is two places to get the numbering wrong.
	 *
	 * <p>IT REFUSES BY ANSWERING, NOT BY THROWING, and the caller decides. The
	 * append calls this AFTER the region copies are already staged and persisted,
	 * so a throw here would leave exactly the half-finished append that
	 * ZoneAppender promises never to leave. A region that cannot be blanked stays
	 * as the copy it already is - still the appended zone's OWN region, which is
	 * the property that matters - and the caller can say so.
	 *
	 * @param preferredGroundMesh the mesh number to prefer, or -1 to let each
	 *        region choose its own. A mesh number picked from ONE region's model
	 *        does not necessarily name the ground in its neighbours.
	 */
	public static boolean blankRegionFiles(ctrmap.formats.containers.GR gr, int preferredGroundMesh) {
		byte[] template = gr.getFile(1);
		if (!BchMapModel.isMapModel(template)) {
			return false;
		}
		int gm;
		BlankContent bc;
		try {
			BchMapModel tm = new BchMapModel(template);
			gm = ctrmap.formats.tilemap.PaintedMaterials.groundMeshOr(tm, preferredGroundMesh);
			bc = blank(template, gm);
		} catch (RuntimeException notBlankable) {
			//a model this factory cannot rebuild - left as it is, and said so
			return false;
		}
		//EVERY WRITE IS ANSWERED FOR, and the answer is no longer a boolean: a write
		//the container could not make - a locked file, a full disk - throws with the
		//file named. These eight answers used to be dropped on the floor, so a region
		//that half-blanked and then failed reported itself blanked, and the caller went
		//on to tell the user the slot was empty while it held whatever survived. The
		//"answers by returning false" contract above is about a model this factory
		//CANNOT REBUILD, decided before anything is written; past this line the region
		//is already being rewritten, so the answer for a failure there is the same no:
		//half a blanked region is not blanked, and the caller says the zone was kept.
		try {
			gr.storeFile(1, bc.model);
			gr.storeFile(2, bc.collision);
			gr.storeFile(0, bc.tilemap);
			gr.storeFile(3, bc.props);
			//extra layers (multi-layer templates): blank them out entirely
			if (gr.len >= 9) {
				gr.storeFile(7, voidTilemap());
				gr.storeFile(gr.len >= 11 ? 9 : 8, emptyCollision());
				if (gr.len >= 11) {
					gr.storeFile(8, voidTilemap());
					gr.storeFile(10, emptyCollision());
				}
			}
		} catch (RuntimeException notWritten) {
			//ANSWERED, NOT THROWN, which is this method's contract and the reason the
			//append can call it: a spare that could not be emptied keeps the copy it
			//already has, and the caller reports the zone as kept.
			return false;
		}
		return true;
	}

	public static BlankContent blank(byte[] templateModel, int groundMesh) {
		BlankContent out = new BlankContent();
		out.model = blankModel(templateModel, groundMesh);
		out.collision = blankCollision();
		out.tilemap = blankTilemap();
		out.props = new byte[]{0, 0, 0, 0}; //u32 prop count = 0
		return out;
	}

	/** The flat-plane model: ground mesh -> grid plane, other meshes -> degenerate. */
	public static byte[] blankModel(byte[] templateModel, int groundMesh) {
		BchMapModel model = new BchMapModel(templateModel);
		List<BchMapModel.MeshGeom> geom = model.geometry();
		if (groundMesh < 0 || groundMesh >= geom.size() || !geom.get(groundMesh).posOk) {
			throw new IllegalArgumentException("ground mesh " + groundMesh + " is not editable");
		}
		byte[] current = templateModel;
		for (int mi = 0; mi < geom.size(); mi++) {
			BchMapModel m = new BchMapModel(current);
			BchMapModel.MeshGeom g = m.geometry().get(mi);
			if (!g.posOk) {
				continue; //exotic-format mesh - leave untouched (tiny decorations)
			}
			if (mi == groundMesh) {
				MapModelObj.ObjMesh plane = buildPlane();
				byte[] vtx = MapModelObjImporter.buildVertexBytes(m, g, plane);
				current = m.setMeshGeometry(mi, vtx, plane.triangles);
			} else {
				//degenerate keep-alive triangle from the mesh's own first vertex
				byte[] vtx = new byte[g.stride];
				System.arraycopy(m.raw, g.vtxAbs, vtx, 0, g.stride);
				current = m.setMeshGeometry(mi, vtx, new int[]{0, 0, 0});
			}
		}
		return current;
	}

	/** A 10x10 quad grid over the region frame, tiled UVs, up normals. */
	static MapModelObj.ObjMesh buildPlane() {
		MapModelObj.ObjMesh om = new MapModelObj.ObjMesh();
		int n = 10; //quads per side
		int side = n + 1;
		om.positions = new float[side * side][];
		om.uvs = new float[side * side][];
		om.normals = new float[side * side][];
		for (int gy = 0; gy < side; gy++) {
			for (int gx = 0; gx < side; gx++) {
				int v = gy * side + gx;
				float x = -360f + gx * (720f / n);
				float z = -360f + gy * (720f / n);
				om.positions[v] = new float[]{x, 0f, z};
				//one texture repeat per 2 tiles (36 units) - a sane ground default
				om.uvs[v] = new float[]{(x + 360f) / 36f, (z + 360f) / 36f};
				om.normals[v] = new float[]{0f, 1f, 0f};
			}
		}
		List<Integer> tris = new ArrayList<>();
		for (int gy = 0; gy < n; gy++) {
			for (int gx = 0; gx < n; gx++) {
				int a = gy * side + gx, b = a + 1, c = a + side, d = c + 1;
				//winding matched to the dominant retail convention (negative XZ area)
				tris.add(a);
				tris.add(c);
				tris.add(b);
				tris.add(b);
				tris.add(c);
				tris.add(d);
			}
		}
		om.triangles = new int[tris.size()];
		for (int i = 0; i < tris.size(); i++) {
			om.triangles[i] = tris.get(i);
		}
		return om;
	}

	/** Flat full-frame collision at Y=0 (two triangles, retail-exact writer). */
	public static byte[] blankCollision() {
		List<float[]> tris = new ArrayList<>();
		tris.add(new float[]{-360, 0, -360, -360, 0, 360, 360, 0, -360});
		tris.add(new float[]{360, 0, -360, -360, 0, 360, 360, 0, 360});
		return GfColl.build(tris, null);
	}

	/** An all-blocked tilemap (for blanked-out extra layers), retail-padded size. */
	public static byte[] voidTilemap() {
		byte[] out = new byte[6528];
		out[0] = 40;
		out[2] = 40;
		for (int t = 0; t < 1600; t++) {
			int off = 4 + t * 4;
			out[off] = 0x21;
			out[off + 3] = 0x01;
		}
		return out;
	}

	/** A collision subfile with no triangles (extra layers of a blank canvas). */
	public static byte[] emptyCollision() {
		return GfColl.build(new ArrayList<float[]>(), null);
	}

	/** All-walkable tilemap with a one-tile blocked border, retail-padded size. */
	public static byte[] blankTilemap() {
		byte[] out = new byte[6528];
		out[0] = 40;
		out[2] = 40;
		for (int y = 0; y < 40; y++) {
			for (int x = 0; x < 40; x++) {
				int off = 4 + (y * 40 + x) * 4;
				boolean border = x == 0 || y == 0 || x == 39 || y == 39;
				if (border) {
					out[off] = 0x21;
					out[off + 3] = 0x01; //void/blocked tuple 21 00 00 01
				} else {
					out[off] = 0x20;     //standard walkable tuple 20 00 00 00
				}
			}
		}
		return out;
	}
}
