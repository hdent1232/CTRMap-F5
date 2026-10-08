package ctrmap.formats.gfcollision;

import com.jogamp.opengl.GL2;
import ctrmap.formats.containers.GR;
import ctrmap.formats.Triangle;
import ctrmap.util.Bytes;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.List;

/**
 * The collision editor's model of one region's walkable mesh: the sixteen buckets of GR
 * subfile 2, each holding the triangles first seen in it.
 *
 * <p>READ THROUGH {@link GfColl}, THE ONE COLLISION PARSER (F058, F061). This class used to
 * parse the same bytes with a reader of its own: a bad magic, a length past the end or a
 * missing "term" was a line on a console the shipped build does not have, input that ran out
 * part-way left the buckets part-filled, and the region opened and saved normally - writing
 * whatever part had been read over its real collision. It also threw away every repeated
 * triangle, where the spec-exact reader keeps the ones the source mesh genuinely repeats, so a
 * region opened and saved through the collision editor was not the retail-exact rebuild.
 *
 * <p>A region whose collision cannot be read is {@link #unreadable}: it holds no triangles,
 * reads every height as unknown, and refuses to be written, so nothing can save over the bytes
 * it could not read.
 */
public class GRCollisionFile {

	private final GR mapFile;
	/** The parsed subfile whose constants a write carries; null for a collision made from scratch. */
	private final GfColl template;
	/** Why this region's collision could not be read, in words; null when it was read. */
	public final String unreadable;

	public GRCollisionMesh[] meshes = new GRCollisionMesh[16]; //GR is limited to exactly 16 meshes, nothing more, nothing less

	public boolean modified = false;

	private GRCollisionFile(GR mapFile, GfColl template, String unreadable) {
		this.mapFile = mapFile;
		this.template = template;
		this.unreadable = unreadable;
		for (int i = 0; i < 16; i++) {
			meshes[i] = new GRCollisionMesh();
		}
		if (template != null) {
			for (int k = 0; k < template.uniqueTris.size(); k++) {
				float[] t = template.uniqueTris.get(k);
				meshes[template.uniqueTriBucket.get(k)].tris.add(new Triangle(
						new float[]{t[0], t[3], t[6]}, new float[]{t[1], t[4], t[7]}, new float[]{t[2], t[5], t[8]}));
			}
		}
	}

	/**
	 * The region's collision, read strictly.
	 *
	 * @throws IllegalStateException saying what is wrong, when subfile 2 is not a whole collision
	 */
	public static GRCollisionFile read(GR region) {
		try {
			return new GRCollisionFile(region, new GfColl(region.getFile(2)), null);
		} catch (RuntimeException damaged) {
			throw new IllegalStateException("This region's collision (subfile 2) cannot be read, so it was not"
					+ " opened and nothing will be saved over it: " + Bytes.reason(damaged), damaged);
		}
	}

	/**
	 * The region's collision, or - when it cannot be read - an {@link #unreadable} one saying
	 * why, so that one damaged region costs the editor that region's collision and not the
	 * whole map.
	 */
	public static GRCollisionFile readOrRefuse(GR region) {
		try {
			return read(region);
		} catch (IllegalStateException refused) {
			return new GRCollisionFile(region, null, ctrmap.util.Bytes.reason(refused));
		}
	}

	/** A new collision for a region being built: two triangles spanning the whole region. */
	public static GRCollisionFile blank(GR region) {
		GRCollisionFile made = new GRCollisionFile(region, null, null);
		made.meshes[0].tris.add(new Triangle(new float[]{-360f, -360f, 360f}, new float[3], new float[]{360f, -360f, -360f}));
		made.meshes[0].tris.add(new Triangle(new float[]{-360f, 360f, 360f}, new float[3], new float[]{360f, -360f, 360f}));
		made.modified = true;
		return made;
	}

	/**
	 * The ground height under (x, y); NaN - unknown - for a collision that was not read, and where no
	 * triangle lies under the point. That answered 0 (F246): ground at 0 and no ground at all read the
	 * same, so a quad stood on it sat at 0 and an NPC placed over a hole dropped there.
	 */
	public float getHeightAtPoint(float x, float y) {
		if (unreadable != null) {
			return Float.NaN;
		}
		//every triangle sits in one bucket here, so checking them all checks each once
		for (int i = 0; i < meshes.length; i++) {
			for (int j = 0; j < meshes[i].tris.size(); j++) {
				Polygon triPoly = meshes[i].tris.get(j).getAWTPoly();
				if (triPoly.contains(x, y)) {
					Triangle tri = meshes[i].tris.get(j);
					float top1 = (tri.getX(1) - tri.getX(0)) * (tri.getY(2) - tri.getY(0)) - (tri.getX(2) - tri.getX(0)) * (tri.getY(1) - tri.getY(0));
					float bot1 = (tri.getX(1) - tri.getX(0)) * (tri.getZ(2) - tri.getZ(0)) - (tri.getX(2) - tri.getX(0)) * (tri.getZ(1) - tri.getZ(0));
					float top2 = (tri.getZ(1) - tri.getZ(0)) * (tri.getY(2) - tri.getY(0)) - (tri.getZ(2) - tri.getZ(0)) * (tri.getY(1) - tri.getY(0));

					float result = tri.getY(0) + (top1 / bot1) * (y - tri.getZ(0)) - (top2 / bot1) * (x - tri.getX(0));
					return result;
				}
			}
		}
		return Float.NaN;
	}

	/**
	 * Writes the collision back through the RETAIL-EXACT builder ({@link GfColl}), carrying
	 * the constants of the subfile it was read from: the in-memory triangle set - each triangle
	 * once in the bucket it was first seen in, with the repeats the source genuinely holds - is
	 * re-bucketed and re-bounded with the algorithms that reproduce the retail bytes on every
	 * collision file in the game. The old in-class bounds/bucketing math was PROVEN game-invalid
	 * (bounds mismatch retail in 846/857 regions, bucketing misses whole-cell triangles in
	 * 853/857 - guaranteed height-lookup holes) and must never be used for writes again.
	 *
	 * @throws IllegalStateException for an {@link #unreadable} collision, which is never written
	 * over the bytes it could not read
	 */
	public void write() {
		if (unreadable != null) {
			throw new IllegalStateException(unreadable);
		}
		List<float[]> tris = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			for (int j = 0; j < meshes[i].tris.size(); j++) {
				Triangle t = meshes[i].tris.get(j);
				tris.add(new float[]{
					t.getX(0), t.getY(0), t.getZ(0),
					t.getX(1), t.getY(1), t.getZ(1),
					t.getX(2), t.getY(2), t.getZ(2)
				});
			}
		}
		mapFile.storeFile(2, GfColl.build(tris, template));
	}

	public void render(GL2 gl) {
		for (int i = 0; i < 16; i++) {
			meshes[i].render(gl);
		}
	}
}
