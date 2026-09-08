package ctrmap.formats.tilemap;

import ctrmap.formats.GameFiles;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.MapModelObj;
import ctrmap.formats.h3d.MapModelObjImporter;
import ctrmap.formats.h3d.RegionFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static ctrmap.formats.LittleEndian.f32;
import static ctrmap.formats.tilemap.PaintedRegionBuilder.*;
import static ctrmap.formats.tilemap.PaintedCliffMesh.*;
import static ctrmap.formats.tilemap.PaintedCollision.*;
import static ctrmap.formats.tilemap.PaintedFloorMesh.*;
import static ctrmap.formats.tilemap.PaintedHeights.*;
import static ctrmap.formats.tilemap.PaintedTiles.*;

/**
 * The painter's which donor mesh and UV scale each terrain gets.
 *
 * <p>One stage of {@link PaintedRegionBuilder}, which was 3750 lines and 106
 * methods in one file. Every method here is exactly the one that was there,
 * moved and not rewritten: the split is about which file you open to read a
 * stage, not about what the pipeline does. The constants and the other stages
 * are imported statically for the same reason - a body that called a helper
 * still calls the same helper.
 *
 * <p>What this is NOT is decoupling. PaintedRegionBuilder was never a god
 * object by coupling: it is pure, it holds no state, and it has six callers.
 * It was simply too long to read.
 */
public final class PaintedMaterials {

	private PaintedMaterials() {
	}

	/** True if the tileset donor carries a grass-edge material (so edge strips are available). */
	public static boolean donorSupportsEdges(byte[] donorModel) {
		return resolveEdgeMesh(new BchMapModel(donorModel)) >= 0;
	}

	/** True when this map has a REAL material for the brush (not the fallback).
	 *  {@link TerrainCatalog} imports one when it does not. */
	public static boolean hasMaterialFor(BchMapModel probe, TilePalette brush) {
		return resolveMesh(probe, brush, -1) >= 0;
	}

	// ---- material resolution + UV scale -----------------------------------

	/**
	 * How many texture repeats a mesh's own geometry puts on one world unit, as
	 * {u-per-x, v-per-z} - read off the donor rather than assumed, so a brush
	 * paints at the density the map it came from was authored at.
	 *
	 * <p>Measured by regression on the mesh's own vertices: the UV span divided
	 * by the world span, in each axis independently. A mesh flatter than a world
	 * unit in an axis has no span to divide by and takes the default there.
	 *
	 * <p>Both fallbacks matter and they are different. A mesh with no readable
	 * float UV attribute has nothing to measure. A mesh this editor imported has
	 * nothing LEFT to measure - {@link TerrainCatalog} keeps the material and
	 * throws the donor's geometry away - and that one must ask the catalog for
	 * the donor's scale instead of taking the default, or every imported brush
	 * paints at twice retail density.
	 *
	 * @param files the game whose pristine snapshot holds the donor an imported
	 *         material's scale is measured from; null means no donor can be
	 *         read and the default stands
	 * @return two positive scales, each already passed through
	 *         {@link #clampScale}; never null
	 */
	static float[] measureUvScale(GameFiles files, BchMapModel model, BchMapModel.MeshGeom g) {
		BchMapModel.MeshAttr uv = model.findAttr(g.meshIndex, 4);
		float def = 1f / 36f;
		if (uv == null || uv.type != 3 || g.vertexCount < 3) {
			//A material this editor imported arrives blanked to a single vertex -
			//TerrainCatalog keeps the material and throws the donor's geometry
			//away, so there is nothing left here to measure. Every imported
			//brush therefore painted at the 1/36 default, while retail
			//world-projected ground is authored at about 1/72: a consistent 2x
			//texture-scale error on exactly the brushes the editor adds. The
			//donor still knows its own scale, so ask the catalog for it.
			float[] donor = TerrainCatalog.donorUvScale(files,
					model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex)));
			return donor != null ? donor : new float[]{def, def};
		}
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
		float[][] pos = model.getVertexPositions(g.meshIndex);
		for (int v = 0; v < g.vertexCount; v++) {
			int at = g.vtxAbs + v * g.stride + uv.offset;
			float u = f32(model.raw, at), vv = f32(model.raw, at + 4);
			minX = Math.min(minX, pos[v][0]);
			maxX = Math.max(maxX, pos[v][0]);
			minZ = Math.min(minZ, pos[v][2]);
			maxZ = Math.max(maxZ, pos[v][2]);
			minU = Math.min(minU, u);
			maxU = Math.max(maxU, u);
			minV = Math.min(minV, vv);
			maxV = Math.max(maxV, vv);
		}
		float sx = maxX - minX > 1f ? Math.abs(maxU - minU) / (maxX - minX) : def;
		float sz = maxZ - minZ > 1f ? Math.abs(maxV - minV) / (maxZ - minZ) : def;
		return new float[]{clampScale(sx, def), clampScale(sz, def)};
	}

	/**
	 * Keeps a measured UV scale inside the range a ground texture can actually
	 * be authored at.
	 *
	 * <p>A measurement is a ratio of two spans and either can be wrong: a
	 * degenerate mesh gives zero or NaN, and a mesh whose UVs are not a world
	 * projection at all (an atlas lookup, say) gives something enormous. Above
	 * one repeat per world unit the floor is noise, so that is refused outright
	 * and the default used. Below 1/720 - one repeat per forty tiles - the floor
	 * is one flat colour, so that is raised rather than refused: an unusually
	 * coarse texture is still a texture, but a span of nothing is not a
	 * measurement.
	 */
	static float clampScale(float s, float def) {
		if (!(s > 0) || Float.isNaN(s) || s > 1f) {
			return def;
		}
		return Math.max(s, 1f / 720f);
	}

	/**
	 * The mesh a brush paints with: the first material whose name matches one of
	 * the brush's hints, skipping edge overlays and sprite atlases - and, for
	 * ground brushes, skipping anything that is not actually a surface.
	 *
	 * <p>That last filter matters more than it looks. The hints are matched by
	 * substring and ROCK's include {@code gake}, which is Japanese for CLIFF, so
	 * on a normal outdoor route "rock" matched the map's own cliff face:
	 * {@code chip_gake_sea} on Route 101, {@code r105_chip_rock_c} on Route 103.
	 * Painting rock ground then laid vertical cliff art flat on the floor. It
	 * looked like a bad donor row in the terrain table, but the table was never
	 * consulted - {@code ensureMaterial} returns early whenever the map already
	 * has a matching material, and 294 regions matched a cliff this way. Most of
	 * them held a perfectly good flat rock mesh a little further down the list.
	 *
	 * <p>Cliffs pass {@code wantSurface = false}: {@link #resolveCliffMesh} looks
	 * up the same ROCK brush and genuinely wants the vertical material.
	 */
	static int resolveMesh(BchMapModel model, TilePalette t, int fallback) {
		return resolveMesh(model, t, fallback, true);
	}

	/** The mesh a ground brush resolves to natively, or -1 when nothing matches
	 *  and the brush must import a donor instead. Exposed for the corpus sweep
	 *  in {@link ctrmap.tests.GroundResolveTest}. */
	public static int resolvedGroundMesh(BchMapModel model, TilePalette t) {
		return resolveMesh(model, t, -1, true);
	}

	/** @see #flatFraction */
	public static double meshFlatness(BchMapModel model, int meshIndex) {
		return flatFraction(model, meshIndex);
	}

	/** {@link #measureUvScale} with no game handed: an imported material's donor cannot be read, so it measures at the default. */
	public static float[] uvScaleOf(BchMapModel model, int meshIndex) {
		return uvScaleOf(null, model, meshIndex);
	}

	/** @see #measureUvScale */
	public static float[] uvScaleOf(GameFiles files, BchMapModel model, int meshIndex) {
		return measureUvScale(files, model, model.geometry().get(meshIndex));
	}

	/**
	 * The search behind {@link #resolveMesh(BchMapModel, TilePalette, int)},
	 * with the surface filter made explicit.
	 *
	 * <p>Hints are tried IN ORDER and the first mesh matching the current hint
	 * wins, so a palette's hint list is a preference ranking, not a set. Within
	 * one hint the meshes are scanned in model order; a mesh with no readable
	 * positions, an edge overlay, or a sprite atlas is never a candidate.
	 *
	 * @param wantSurface true to also reject anything standing up (see the
	 *                    caller's note on ROCK matching {@code gake}); false
	 *                    when the vertical material is the one wanted
	 * @param fallback    returned when no hint matches anything
	 */
	static int resolveMesh(BchMapModel model, TilePalette t, int fallback, boolean wantSurface) {
		for (String hint : t.matHints) {
			for (BchMapModel.MeshGeom g : model.geometry()) {
				if (!g.posOk) {
					continue;
				}
				String name = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
				if (name == null || isEdgeMaterial(name) || isSpriteMaterial(name)
						|| !name.toLowerCase().contains(hint)) {
					continue;
				}
				double flat = flatFraction(model, g.meshIndex);
				if (wantSurface && flat >= 0 && flat < MIN_GROUND_FLATNESS) {
					continue; //a cliff face, not ground - keep looking
				}
				return g.meshIndex;
			}
		}
		return fallback;
	}

	/**
	 * Plan-view area over true surface area for a whole mesh: 1.0 for a flat
	 * floor, ~0 for a vertical wall. Unlike {@link #upFacingArea} this is a
	 * ratio, so it compares meshes of wildly different sizes.
	 *
	 * <p>Returns -1 when the mesh has no measurable surface at all, which is NOT
	 * the same answer as 0. A freshly imported brush material arrives as an
	 * empty placeholder - {@link TerrainCatalog#ensureMaterial} blanks it to a
	 * single vertex, precisely so the painter can fill it - and scoring that as
	 * "perfectly vertical" made the resolver reject the material it had just
	 * imported, for eleven brushes on the indoor test map alone. An unmeasurable
	 * mesh has to be given the benefit of the doubt; the filter exists to reject
	 * cliffs it can see, not geometry it cannot.
	 */
	static double flatFraction(BchMapModel model, int meshIndex) {
		try {
			float[][] pos = model.getVertexPositions(meshIndex);
			int[] tris = model.getTriangles(meshIndex);
			double plan = 0, total = 0;
			for (int t = 0; t + 2 < tris.length; t += 3) {
				int a = tris[t], b = tris[t + 1], c = tris[t + 2];
				if (a >= pos.length || b >= pos.length || c >= pos.length) {
					continue;
				}
				double ux = pos[b][0] - pos[a][0], uy = pos[b][1] - pos[a][1], uz = pos[b][2] - pos[a][2];
				double vx = pos[c][0] - pos[a][0], vy = pos[c][1] - pos[a][1], vz = pos[c][2] - pos[a][2];
				double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
				total += Math.sqrt(nx * nx + ny * ny + nz * nz);
				plan += Math.abs(ny);
			}
			return total > 0 ? plan / total : -1;
		} catch (RuntimeException ex) {
			return -1;
		}
	}

	/**
	 * True when this material is a sprite/decal, not a surface you can stand on.
	 *
	 * <p>A material NAME cannot distinguish a tiling ground texture from a sprite
	 * atlas, and the brush hints match by substring, so the painter used to floor
	 * a map with whatever happened to match. On Route 102 "rock" matched
	 * {@code chip_jump_gake} - the jump-down ledge sprite, 43% opaque - and
	 * "path" matched {@code chip_wood_b}, the decoration atlas of bushes and
	 * flowers, 34% opaque. Painted tiles then sampled empty texels and drew
	 * nothing, over retail ground the compositor had already clipped away: the
	 * player stood in a black hole. Rejecting these sends the brush to
	 * {@link TerrainCatalog}, which imports a measured-opaque donor instead.
	 */
	static boolean isSpriteMaterial(String name) {
		return spriteMaterials().contains(name);
	}

	/**
	 * The set of material names measured to be see-through, read once from the
	 * harvested ground-material table and cached for the life of the process.
	 *
	 * <p>Synchronized because the cache is a static field and the painter runs
	 * off the event thread; without it two callers can both find it null and
	 * both build it, and one of them can hand out a half-filled set.
	 *
	 * <p>A missing or unreadable table is not fatal - it yields an empty set, so
	 * nothing is rejected as a sprite and the painter behaves as it did before
	 * the table existed. The failure is said on stderr rather than swallowed,
	 * because "no material is a sprite" and "the table did not load" produce
	 * identical geometry and only the message tells them apart.
	 */
	static synchronized Set<String> spriteMaterials() {
		if (spriteMaterials != null) {
			return spriteMaterials;
		}
		spriteMaterials = new HashSet<>();
		try (java.io.InputStream in = PaintedRegionBuilder.class.getClassLoader()
				.getResourceAsStream("ctrmap/resources/oras_ground_materials.tsv")) {
			if (in != null) {
				java.util.Scanner sc = new java.util.Scanner(in, "UTF-8");
				while (sc.hasNextLine()) {
					String line = sc.nextLine().trim();
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}
					String[] f = line.split("\t");
					if (f.length < 4) {
						continue;
					}
					try {
						//measured bimodal: 638 of 788 materials are 100% opaque and
						//everything below 95% is a sprite, decal or atlas
						if (Double.parseDouble(f[3]) < 95.0) {
							spriteMaterials.add(f[0]);
						}
					} catch (NumberFormatException ignore) {
					}
				}
			}
		} catch (Exception ex) {
			System.err.println("PaintedRegionBuilder: ground-material table unavailable: " + ex);
		}
		return spriteMaterials;
	}

	/**
	 * True when a triangle is a piece of SURFACE - something laid flat that you
	 * could stand on - rather than something standing up.
	 *
	 * <p>Both tests matter. Facing up alone would keep a canopy billboard that
	 * happens to lie flat; having no height alone would keep a flat wall panel.
	 * A floor is both at once.
	 */
	static boolean isSurface(float[][] pos, int a, int b, int c) {
		float ux = pos[b][0] - pos[a][0], uy = pos[b][1] - pos[a][1], uz = pos[b][2] - pos[a][2];
		float vx = pos[c][0] - pos[a][0], vy = pos[c][1] - pos[a][1], vz = pos[c][2] - pos[a][2];
		float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1e-6f) {
			return false;
		}
		float minY = Math.min(pos[a][1], Math.min(pos[b][1], pos[c][1]));
		float maxY = Math.max(pos[a][1], Math.max(pos[b][1], pos[c][1]));
		return Math.abs(ny) / len > 0.8f && maxY - minY < 1.0f;
	}

	/** True when a triangle's footprint centre lies inside the painted area. */
	static boolean centreInRegion(float[][] xz, List<float[]> rects) {
		float cx = (xz[0][0] + xz[1][0] + xz[2][0]) / 3f;
		float cz = (xz[0][1] + xz[1][1] + xz[2][1]) / 3f;
		for (float[] r : rects) {
			if (cx > r[0] && cx < r[2] && cz > r[1] && cz < r[3]) {
				return true;
			}
		}
		return false;
	}

	/** The cliff material mesh (gake/cliff/rock), or the rock/ground fallback.
	 *  Sprite materials are skipped: in ORAS the "gake" cliff is usually a
	 *  see-through ledge strip, and a wall built from it is an invisible wall. */
	public static int resolveCliffMesh(BchMapModel model, int fallback) {
		//An imported cliff material wins over the map's own. Most outdoor routes
		//carry an orange chip_gake and nothing better, so without this the
		//colour of every generated cliff is decided by whichever region the map
		//happened to be painted from.
		TerrainCatalog.Donor cd = TerrainCatalog.cliffDonor();
		if (cd != null) {
			for (int i = 0; i < model.meshCount; i++) {
				if (cd.injectName.equals(model.getMaterialName(model.getMeshMaterialIndex(i)))) {
					return i;
				}
			}
		}
		for (String hint : new String[]{"gake", "cliff", "chip_rock", "rock", "iwa"}) {
			for (BchMapModel.MeshGeom g : model.geometry()) {
				if (!g.posOk) {
					continue;
				}
				String name = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
				if (name != null && !isEdgeMaterial(name) && !isSpriteMaterial(name)
						&& name.toLowerCase().contains(hint)) {
					return g.meshIndex;
				}
			}
		}
		//the map's own cliff material is a sprite (or it has none): a rock brush
		//imported by TerrainCatalog is a real opaque wall, so prefer that.
		//wantSurface=false - this is a WALL, so the flatness filter that keeps
		//ground brushes off cliff faces must not run in reverse here
		return resolveMesh(model, TilePalette.ROCK, fallback, false);
	}

	/** The grass-edge overlay mesh (chip_kusa_edge / chip_grass_edge), or -1. */
	public static int resolveEdgeMesh(BchMapModel model) {
		for (String hint : new String[]{"kusa_edge", "grass_edge", "edge_tex", "_edge"}) {
			for (BchMapModel.MeshGeom g : model.geometry()) {
				if (!g.posOk) {
					continue;
				}
				String name = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
				if (name == null) {
					continue;
				}
				String lm = name.toLowerCase();
				//The last hint is a bare "_edge", broad enough to catch a
				//shoreline: wave_edge matched it, so every seam between grass
				//and a sand path got drawn with a water material - whose
				//texture the zone's area does not even carry, so the strips
				//came out solid white. A white outline round every path is the
				//result. A seam between two kinds of ground is never a wave.
				if (lm.contains("wave") || lm.contains("sea") || lm.contains("mizu")
						|| lm.contains("water") || lm.contains("taki")
						|| lm.contains("enkei") || lm.contains("umi")) {
					continue;
				}
				if (lm.contains(hint)) {
					return g.meshIndex;
				}
			}
		}
		//Better no edge strips than strips drawn with the wrong material: an
		//absent softening reads as a plain seam, a wrong one reads as damage.
		return -1;
	}

	static boolean isEdgeMaterial(String name) {
		String n = name.toLowerCase();
		return n.contains("_edge") || n.contains("edge_tex");
	}

	/** The map's main GROUND mesh: the largest FLOOR-facing surface. Scored by
	 *  up-facing triangle area, not raw triangle count - the biggest mesh of an
	 *  indoor map is usually a wall, and painting the floor with a wall
	 *  material is how "sand" ended up looking like grey plaster. */
	public static int defaultGroundMesh(BchMapModel model) {
		int best = -1, bestFallback = -1;
		double bestArea = -1;
		long bestTris = -1;
		for (BchMapModel.MeshGeom g : model.geometry()) {
			if (!g.posOk) {
				continue;
			}
			String name = model.getMaterialName(model.getMeshMaterialIndex(g.meshIndex));
			if (name != null && isEdgeMaterial(name)) {
				continue; // never treat the thin edge overlay as the ground
			}
			int tris = model.getTriangles(g.meshIndex).length;
			if (tris > bestTris) {
				bestTris = tris;
				bestFallback = g.meshIndex;
			}
			double up = upFacingArea(model, g.meshIndex);
			if (up > bestArea) {
				bestArea = up;
				best = g.meshIndex;
			}
		}
		return bestArea > 0 ? best : bestFallback;
	}

	/**
	 * The mesh to lay a floor on in {@code model}, honouring {@code preferred}
	 * when that mesh exists here and can be read, and falling back to
	 * {@link #defaultGroundMesh} when it cannot.
	 *
	 * <p>The fallback is the whole point. A zone's map is several regions and
	 * they do NOT share a mesh numbering, so a mesh index chosen while looking
	 * at one region is a guess about all the others - and a guess that misses
	 * has to land on that region's ground, not on whatever happens to have the
	 * most triangles. Blank map canvas used to pick by raw triangle count here,
	 * which is exactly the heuristic {@link #defaultGroundMesh} was written to
	 * replace: indoors the biggest mesh is usually a wall, so the new floor came
	 * out textured like plaster.
	 *
	 * @param preferred the mesh the user picked, or any value at all - out of
	 *                  range and unreadable are both answered by the fallback
	 * @return a mesh index, or -1 if the model has no readable geometry
	 */
	/**
	 * The order to OFFER a map's meshes in when asking the user which one is
	 * the ground: the ground first, then everything else biggest first.
	 *
	 * <p>Biggest-first on its own is a good browsing order - doors, windows and
	 * tree parts sink to the bottom instead of crowding the top - and it was
	 * being used to answer a different question as well: whatever came first
	 * was labelled the map's ground and pre-selected. Over the first 400 retail
	 * regions those are not the same mesh 312 times, so the label was wrong more
	 * often than right and a user who accepted the default floored their new map
	 * in a fence.
	 *
	 * <p>Putting {@link #defaultGroundMesh} at the front costs the browsing
	 * order nothing - it moves exactly one entry - and makes the first entry an
	 * answer to the question actually being asked.
	 *
	 * @return every mesh with readable positions, each exactly once; empty if
	 *         the model has none
	 */
	public static int[] groundFirstMeshOrder(BchMapModel model) {
		List<int[]> bySize = new ArrayList<>();   //{meshIndex, triangles}
		for (BchMapModel.MeshGeom g : model.geometry()) {
			if (g.posOk) {
				bySize.add(new int[]{g.meshIndex, model.getTriangles(g.meshIndex).length});
			}
		}
		bySize.sort((a, b) -> b[1] - a[1]);
		//defaultGroundMesh skips edge overlays, so on a map that is nothing but
		//those it answers -1 and there is no entry to promote
		int ground = defaultGroundMesh(model);
		boolean hasGround = false;
		for (int[] e : bySize) {
			hasGround |= e[0] == ground;
		}
		int[] out = new int[bySize.size()];
		int at = 0;
		if (hasGround) {
			out[at++] = ground;
		}
		for (int[] e : bySize) {
			if (hasGround && e[0] == ground) {
				continue;
			}
			out[at++] = e[0];
		}
		return out;
	}

	public static int groundMeshOr(BchMapModel model, int preferred) {
		if (preferred >= 0 && preferred < model.meshCount) {
			List<BchMapModel.MeshGeom> g = model.geometry();
			if (preferred < g.size() && g.get(preferred).posOk) {
				return preferred;
			}
		}
		return defaultGroundMesh(model);
	}

	/** Plan-view area of a mesh's up-facing triangles (a floor scores high, a wall ~0). */
	static double upFacingArea(BchMapModel model, int meshIndex) {
		try {
			float[][] pos = model.getVertexPositions(meshIndex);
			int[] tris = model.getTriangles(meshIndex);
			double area = 0;
			for (int t = 0; t + 2 < tris.length; t += 3) {
				int a = tris[t], b = tris[t + 1], c = tris[t + 2];
				if (a >= pos.length || b >= pos.length || c >= pos.length) {
					continue;
				}
				//|cross(ab, ac).y| / 2 = the triangle's shadow on the ground
				double ux = pos[b][0] - pos[a][0], uz = pos[b][2] - pos[a][2];
				double vx = pos[c][0] - pos[a][0], vz = pos[c][2] - pos[a][2];
				area += Math.abs(ux * vz - vx * uz) * 0.5;
			}
			return area;
		} catch (RuntimeException ex) {
			return 0;
		}
	}

	static float dist(float ax, float az, float bx, float bz) {
		float dx = bx - ax, dz = bz - az;
		return (float) Math.sqrt(dx * dx + dz * dz);
	}
}
