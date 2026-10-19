package ctrmap.formats.tilemap;

import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.MapModelObjImporter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static ctrmap.formats.tilemap.PaintedGeometry.CLIFF_PULL;
import static ctrmap.formats.tilemap.PaintedGeometry.CliffEdge;
import static ctrmap.formats.tilemap.PaintedGeometry.ORIGIN;
import static ctrmap.formats.tilemap.PaintedGeometry.Quad;
import static ctrmap.formats.tilemap.PaintedGeometry.STEP;
import static ctrmap.formats.tilemap.PaintedGeometry.TILE;
import static ctrmap.formats.tilemap.PaintedGeometry.fixWindingTowards;
import static ctrmap.formats.tilemap.PaintedGeometry.lowCorner;
import static ctrmap.formats.tilemap.PaintedGeometry.roundOutline;
import static ctrmap.formats.tilemap.PaintedGeometry.swapCorners12;

/**
 * The painter's stage for the vertical walls where a tile is higher than its neighbour, and the chains they form.
 *
 * <p>One stage of {@link PaintedRegionBuilder}, which was 3750 lines and 106
 * methods in one file. The split first moved every method unchanged and left
 * each stage importing the builder and every other stage whole, one class
 * cycle however many files it was in. It is a split now (F090): what the
 * stages share - the frame, the constants, the quad and edge types, flipping a
 * quad and rounding an outline - is {@link PaintedGeometry}, which needs no
 * stage; no stage needs the builder that calls it; and each file imports by
 * name exactly the members it uses, so its imports are its edges.
 * PackageCycleTest refuses a class cycle it has not recorded, and
 * SourceDebrisTest a wildcard import.
 */
public final class PaintedCliffMesh {

	private PaintedCliffMesh() {
	}

	/**
	 * One tile edge that needs a wall, as a directed segment with an outward
	 * normal - the unit {@link #emitCliffStrips} chains together.
	 *
	 * <p>The direction is not arbitrary: every edge runs so that the high ground
	 * is on its left and the outward normal points down the drop. That is what
	 * makes head-to-tail chaining meaningful (an edge's end is the next edge's
	 * start all the way round a plateau) and what lets the mitre at a shared
	 * vertex be the sum of the normals meeting there.
	 *
	 * @param dir which side of the tile the wall stands on: 0 east, 1 west,
	 *            2 south, 3 north
	 * @param yb  the bottom of the fall (the neighbour's surface)
	 * @param yt  the top of the fall (this tile's surface)
	 */
	static CliffEdge cliffEdge(int tx, int ty, int dir, float yb, float yt) {
		return cliffEdge(tx, ty, dir, yb, yt, -1, yt);
	}

	/**
	 * The same, for the edge of a ramp going down {@code rd} (-1: not a ramp) whose slope lands at
	 * {@code yLo}: the top at each end is the ramp's floor at that corner - {@code yt} on the high
	 * edge, the foot on the low one, never below the fall's bottom - so the face along a ramp's side
	 * falls with it, and on its back edge stays level.
	 */
	static CliffEdge cliffEdge(int tx, int ty, int dir, float yb, float yt, int rd, float yLo) {
		float x0 = tx * TILE + ORIGIN, x1 = x0 + TILE;
		float z0 = ty * TILE + ORIGIN, z1 = z0 + TILE;
		CliffEdge e = new CliffEdge();
		switch (dir) {
			case 0: e.ax = x1; e.az = z0; e.bx = x1; e.bz = z1; e.nx = 1; e.nz = 0; break;
			case 1: e.ax = x0; e.az = z1; e.bx = x0; e.bz = z0; e.nx = -1; e.nz = 0; break;
			case 2: e.ax = x1; e.az = z1; e.bx = x0; e.bz = z1; e.nx = 0; e.nz = 1; break;
			default: e.ax = x0; e.az = z0; e.bx = x1; e.bz = z0; e.nx = 0; e.nz = -1; break;
		}
		e.yTopA = lowCorner(rd, e.ax == x1 ? 1 : 0, e.az == z1 ? 1 : 0) ? Math.max(yLo, yb) : yt;
		e.yTopB = lowCorner(rd, e.bx == x1 ? 1 : 0, e.bz == z1 ? 1 : 0) ? Math.max(yLo, yb) : yt;
		e.yBot = yb;
		return e;
	}

	/**
	 * Turns loose per-tile cliff edges into CONTINUOUS strips that follow the
	 * contour, with mitred corners.
	 *
	 * <p>Emitting a quad per tile edge is what made painted elevation look like
	 * stacked blocks. Each tile produced its own free-standing parallelogram, so
	 * wherever the contour stepped, two of them met at an unmitred right angle
	 * and the whole slope read as a staircase of separate slabs. Tilting them
	 * (the 43-degree slope) only tilted each slab; recolouring them only
	 * recoloured each slab. Retail elevation is quantised to tiles exactly like
	 * this, and its cliffs still read as hillsides, because the cliff is one
	 * strip running along the boundary rather than a row of unrelated pieces.
	 *
	 * <p>So: chain the edges head-to-tail, give every chain vertex a single
	 * outward direction (the average of the segments meeting there, lengthened
	 * so the mitre keeps its width), and sweep the shoulder and face bands along
	 * the chain. Neighbouring segments then share their corner vertices and the
	 * seams disappear.
	 */
	static void emitCliffStrips(List<CliffEdge> edges, Map<Integer, List<Quad>> quadsByMesh, int cliffMesh) {
		//The outward direction at a corner has to be agreed on by EVERY wall
		//meeting there, not just by the ones inside a single strip. A strip stops
		//where the next wall turns or steps too far to join it, so where a
		//one-level drop meets a two-level drop the corner can be shared by two
		//different strips - and each
		//used to mitre it alone, sending its foot straight out along its own
		//normal. At a right angle those two directions are perpendicular, so
		//the feet ended up a whole face-width apart and left a wedge of sky
		//between them, right through the map.
		//Summing the normals of every edge that touches a point, across all
		//strips, gives one shared bisector, and the walls meet on it.
		//Slot 0,1 accumulate the shared bisector. Slot 2 records the DEEPEST
		//drop meeting at the point, because agreeing on the direction is only
		//half of it: a wall's foot reaches outward in proportion to its own
		//fall, so where a one-step wall meets a two-step wall their feet stop
		//at different distances along the same line and leave a slit. The
		//shallower wall is stretched out to meet the deeper one below.
		Map<String, float[]> corner = new LinkedHashMap<>();
		for (CliffEdge e : edges) {
			for (String k : new String[]{e.startKey(), e.endKey()}) {
				float[] acc = corner.computeIfAbsent(k, x -> new float[3]);
				acc[0] += e.nx;
				acc[1] += e.nz;
				acc[2] = Math.max(acc[2], e.topAt(k) - e.yBot);
			}
		}

		//Where each open strip's END PROFILE lies, in world coordinates, keyed
		//by the corner it stands on. Two strips meeting at a corner have the
		//same foot and the same bisector but tops 36 units apart in height, so
		//the region between their two profiles is a lens of nothing - and that
		//lens is what has been showing as sky. Recording the profiles lets it
		//be filled directly, without moving a single vertex of either wall.
		Map<String, List<float[][]>> caps = new LinkedHashMap<>();

		List<Quad> out = quadsByMesh.computeIfAbsent(cliffMesh, k -> new ArrayList<>());

		//ONE pool of edges, chained purely by where they touch - not split by the
		//step they belong to. Splitting was the root of the whole class of
		//defect: a cliff whose height changes along its length was cut into a
		//separate strip per step, each smoothed, mitred and capped in isolation,
		//and the seams between them are what produced every sliver, wedge and -
		//where a ramp corridor multiplied the count - the spray of disconnected
		//shards lying across the route. A wall that changes height is still one
		//wall, so it is built as one, and the height is carried per vertex.
		Map<String, List<CliffEdge>> byStart = new LinkedHashMap<>();
		for (CliffEdge e : edges) {
			byStart.computeIfAbsent(e.startKey(), k -> new ArrayList<>()).add(e);
		}
		Set<CliffEdge> used = new java.util.LinkedHashSet<>();
		for (CliffEdge seed : edges) {
			if (used.contains(seed)) {
				continue;
			}
			List<CliffEdge> chain = new ArrayList<>();
			CliffEdge cur = seed;
			while (cur != null && !used.contains(cur)) {
				used.add(cur);
				chain.add(cur);
				//Several edges can start where this one ends - a T-junction, or
				//a corner where a terrace meets a step. Continue along the one
				//that turns least and changes height least, so a wall follows
				//its own contour instead of jumping onto a different feature.
				List<CliffEdge> cands = byStart.get(cur.endKey());
				CliffEdge best = null;
				float bestScore = -Float.MAX_VALUE;
				if (cands != null) {
					float dx = cur.bx - cur.ax, dz = cur.bz - cur.az;
					float dl = (float) Math.hypot(dx, dz);
					if (dl > 1e-4f) {
						dx /= dl;
						dz /= dl;
					}
					for (CliffEdge c : cands) {
						if (used.contains(c)) {
							continue;
						}
						float ex = c.bx - c.ax, ez = c.bz - c.az;
						float el = (float) Math.hypot(ex, ez);
						if (el > 1e-4f) {
							ex /= el;
							ez /= el;
						}
						float straight = dx * ex + dz * ez;
						//A strip may run THROUGH a change of step, but only a
						//modest one. Letting it join anything that happened to
						//start at the same point let a 72-to-36 wall continue
						//into a 36-to-0 wall at right angles, and averaging the
						//heights across that join stretched a long diagonal
						//blade between them. One step of change, and no
						//doubling back on itself.
						//Both read where the two meet: this one's end, that one's start.
						float stepChange = Math.abs((c.yTopA - c.yBot) - (cur.yTopB - cur.yBot));
						if (stepChange > STEP * 1.01f || straight < -0.1f) {
							continue;
						}
						//and the tops must line up: a wall cannot continue into
						//one that starts at a different height
						if (Math.abs(c.yTopA - cur.yTopB) > STEP * 1.01f) {
							continue;
						}
						float score = straight - stepChange / 72f * 1.5f;
						if (score > bestScore) {
							bestScore = score;
							best = c;
						}
					}
				}
				cur = best;
			}
			emitChain(chain, out, corner, caps);
		}

		//Fill the lens between end profiles that share a corner. Sorted by top
		//height, each neighbouring pair bounds a planar sliver lying in the
		//corner's own bisector plane, so it is stitched straight onto the
		//profile points both walls already use - nothing moves, no vertex is
		//displaced, and there is no new seam to go wrong. Emitted with both
		//windings because the patch is edge-on to that plane and the corners of
		//a map face four different ways.
		for (Map.Entry<String, List<float[][]>> en : caps.entrySet()) {
			List<float[][]> prof = en.getValue();
			if (prof.size() < 2) {
				continue;
			}
			prof.sort((a, b) -> Float.compare(b[0][1], a[0][1]));
			for (int i = 0; i + 1 < prof.size(); i++) {
				float[][] a = prof.get(i), b = prof.get(i + 1);
				//Only fill between profiles that genuinely sit against each
				//other. Where a corridor cuts through, several strips end on
				//one point with feet a tile or more apart, and stitching those
				//together spans the gap with a blade of rock lying across the
				//route rather than closing a seam.
				float apart = (float) Math.hypot(a[2][0] - b[2][0], a[2][2] - b[2][2]);
				if (apart > TILE * 0.9f) {
					continue;
				}
				for (int band = 0; band < 2; band++) {
					float[] at = a[band], bt = b[band], ab = a[band + 1], bb = b[band + 1];
					if (same(at, bt) && same(ab, bb)) {
						continue;   //identical profiles: there is no lens to fill
					}
					out.add(gusset(at, bt, ab, bb, false));
					out.add(gusset(at, bt, ab, bb, true));
				}
			}
		}
	}

	static boolean same(float[] p, float[] q) {
		return Math.abs(p[0] - q[0]) < 0.05f && Math.abs(p[1] - q[1]) < 0.05f
				&& Math.abs(p[2] - q[2]) < 0.05f;
	}

	/**
	 * One patch across the gap between two walls' end profiles at a shared
	 * corner. Built only from points the walls themselves already use.
	 */
	static Quad gusset(float[] tl, float[] tr, float[] bl, float[] br, boolean flip) {
		Quad q = new Quad();
		q.pos[0] = tl.clone();
		q.pos[1] = tr.clone();
		q.pos[2] = bl.clone();
		q.pos[3] = br.clone();
		//stay in the rock band of the cliff texture, so a glimpse of the patch
		//reads as more of the same wall
		float h = Math.max(Math.abs(tl[1] - bl[1]), 1f) / 56f;
		q.uv[0] = new float[]{0f, 0.30f};
		q.uv[1] = new float[]{0.18f, 0.30f};
		q.uv[2] = new float[]{0f, 0.30f + h};
		q.uv[3] = new float[]{0.18f, 0.30f + h};
		for (int c = 0; c < 4; c++) {
			q.nrm[c] = new float[]{0f, 1f, 0f};
			q.ao[c] = c < 2 ? 0.78f : 0.60f;
		}
		if (flip) {
			swapCorners12(q);
		}
		return q;
	}

	/**
	 * How far out a wall's foot reaches for a given fall, matching the shoulder
	 * and face proportions used when the strip is built (and the same one-tile
	 * cap, so a tall cliff stands steeper rather than overshooting the terrace
	 * below it).
	 */
	static float reach(float drop) {
		return Math.min(TILE, drop * 0.77f);
	}

	/**
	 * Builds the geometry for ONE continuous run of cliff: a chain of tile edges
	 * already joined head-to-tail by {@link #emitCliffStrips}, turned into a
	 * rounded, stepped, textured wall.
	 *
	 * <p>Read top to bottom it is the shape of a wall. The per-vertex phases
	 * are each their own method, so each can be read - and checked - with
	 * only its own inputs in view, and every phase carries its reasoning
	 * where it happens; what follows is only the order and why the order is
	 * that.
	 *
	 * <ol>
	 * <li><b>Polyline.</b> The chain's n edges give n+1 vertices. A chain whose
	 *     last edge ends where the first began is a closed LOOP, and loops are
	 *     treated differently at every step below, because they have no ends.</li>
	 * <li><b>Round the outline</b> ({@link PaintedGeometry#roundOutline}: three smoothing
	 *     passes, then a cap on how far any vertex may travel). Elevation is
	 *     quantised to whole tiles, so the raw contour is a staircase of right
	 *     angles; smoothing is what makes it read as a hillside. The travel cap
	 *     is what stops a one-tile path being smoothed out of existence from
	 *     both sides at once. The ends of an open chain are pinned so
	 *     neighbouring chains still meet.</li>
	 * <li><b>Outward direction per vertex</b> ({@link #mitres}) - the mitre.
	 *     Preferring the bisector every wall touching that point agrees on is
	 *     what closes a corner shared between two strips; falling back to this
	 *     chain's own two edges is what stops a ramp corridor (where a dozen
	 *     edges face every which way and the sum cancels) turning into a spray
	 *     of spikes.</li>
	 * <li><b>Height per vertex</b> ({@link #vertexHeights}), top and bottom,
	 *     averaged where two edges of different depth meet - and every
	 *     proportion derived from it ({@link #faceReach}): the mid-height, and
	 *     how far the shoulder and the face reach out. This is what lets ONE
	 *     strip run through a change of step instead of stopping dead at it and
	 *     starting again with a seam down the join. If no vertex has any fall
	 *     at all, there is nothing to build and it returns here.</li>
	 * <li><b>Emit, segment by segment.</b> A flat COLLAR at the top bridges the
	 *     rounded outline back to the tile boundary the floor actually ends on -
	 *     without it, rounding tears holes straight through the map. Then the
	 *     face itself as a STACK of 18-unit steps, three quads each (lip bevel,
	 *     sheer wall, foot bevel), because vanilla never lets a cliff face cross
	 *     a multiple of 18 and one tall quad reads as flat plastic. The texture
	 *     restarts every step; u runs along the contour by arc length.</li>
	 * </ol>
	 *
	 * @param chain  the edges, head-to-tail; the drop may change by one level at a join
	 * @param out    receives the quads (collar and face alike)
	 * @param corner per-vertex accumulated outward normals and deepest drop,
	 *               shared across strips so corners between them close; may be null
	 * @param caps   receives this strip's end profiles, keyed by vertex, so a
	 *               genuine corner between two separate walls can be capped;
	 *               null, or skipped entirely, for a loop, which has no ends
	 */
	static void emitChain(List<CliffEdge> chain, List<Quad> out,
			Map<String, float[]> corner, Map<String, List<float[][]>> caps) {
		if (chain.isEmpty()) {
			return;
		}
		int n = chain.size();
		boolean loop = chain.get(0).startKey().equals(chain.get(n - 1).endKey());
		//vertices of the polyline: each edge's start, plus the last edge's end
		int vc = n + 1;
		float[] px = new float[vc], pz = new float[vc];
		for (int i = 0; i < n; i++) {
			px[i] = chain.get(i).ax;
			pz[i] = chain.get(i).az;
		}
		px[n] = chain.get(n - 1).bx;
		pz[n] = chain.get(n - 1).bz;

		float[][] rounded = roundOutline(px, pz, loop, CLIFF_PULL);
		float[] smoothX = rounded[0], smoothZ = rounded[1];
		float[][] mitre = mitres(chain, loop, corner);
		float[] mx = mitre[0], mz = mitre[1];
		float[][] heights = vertexHeights(chain, loop);
		float[] vyt = heights[0], vyb = heights[1];
		float[][] reach = faceReach(chain, loop, vyt, vyb);
		if (reach == null) {
			return;
		}
		float[] vyMid = reach[0], vRunS = reach[1], vRunT = reach[2];

		//Stretch the mitre at a corner shared with a DEEPER wall so the two feet
		//meet. Far less of this is needed now that a strip carries its own
		//height changes, but a genuine corner between two separate walls still
		//exists and still has to close.
		if (corner != null) {
			for (int i = 0; i < vc; i++) {
				float[] acc = corner.get(vertexKey(chain, i));
				if (acc == null || acc.length < 3 || vRunT[i] <= 0.01f
						|| acc[2] <= (vyt[i] - vyb[i]) + 0.01f) {
					continue;
				}
				float scale = Math.min(1.6f, reach(acc[2]) / vRunT[i]);
				if (scale > 1f) {
					mx[i] *= scale;
					mz[i] *= scale;
				}
			}
		}
		float bury = 2.0f;
		//v addresses the texture's bands directly, and this atlas runs the other
		//way up: measured over c108_gake_01, grey rock occupies V 0.00-0.19,
		//green grass V 0.25-0.44, and everything below V 0.50 is blank white.
		//Running the face 0.10 -> 0.92 therefore started it in rock, dragged it
		//through the grass strip and finished in pure white, which is why the
		//cliff came out pale and washed out. So V DESCENDS down the face: grass
		//at the lip, rock all the way down.
		//Which bands of the rock texture the face uses comes from the palette,
		//because cliff textures are not interchangeable: one is stone edge to
		//edge, another bakes a grass strip on top and blank white below, a third
		//runs its bands the other way up. Hardcoding one range meant every new
		//rock came out washed out until the numbers were changed by hand.
		TerrainCatalog.Donor cliffCat = TerrainCatalog.cliffDonor();
		final float V_LIP = cliffCat != null ? cliffCat.vLip : 0.2483f;
		final float V_MID = cliffCat != null ? cliffCat.vMid : 0.2015f;
		final float V_WALL = cliffCat != null ? cliffCat.vWall : 0.0485f;
		final float V_FOOT = cliffCat != null ? cliffCat.vFoot : 0.0031f;
		//u tiles along the contour once per 36 world units - exactly half a
		//repeat per 18-unit tile. Measured over eleven vanilla cliff regions:
		//the modal bin is 36.00 with an area-weighted median of 36.5-37.0. The
		//old 1/56 (and the comment claiming 0.32 repeats per tile) stretched
		//every cobble sideways.
		final float U_PER_UNIT = 1f / 36f;

		//Record this strip's end profiles, so a genuine corner between two
		//separate walls can still be capped.
		if (caps != null && !loop) {
			for (int endIdx : new int[]{0, vc - 1}) {
				String vk = vertexKey(chain, endIdx);
				caps.computeIfAbsent(vk, k -> new ArrayList<>()).add(new float[][]{
					{smoothX[endIdx], vyt[endIdx], smoothZ[endIdx]},
					{smoothX[endIdx] + mx[endIdx] * vRunS[endIdx], vyMid[endIdx],
						smoothZ[endIdx] + mz[endIdx] * vRunS[endIdx]},
					{smoothX[endIdx] + mx[endIdx] * vRunT[endIdx], vyb[endIdx] - bury,
						smoothZ[endIdx] + mz[endIdx] * vRunT[endIdx]},
				});
			}
		}

		float u = 0f;
		for (int i = 0; i < n; i++) {
			int j = i + 1;
			float segLen = (float) Math.hypot(px[j] - px[i], pz[j] - pz[i]);
			float enx = chain.get(i).nx, enz = chain.get(i).nz;

			//The collar: a flat ring at the top, from the tile boundary the floor
			//ends on out to wherever the rounded outline went. Rounding moves the
			//top edge both ways, and the outward half used to tear holes because
			//the floor stopped short of the cliff; the collar covers that span
			//whichever way it went, a hair below the floor so the two do not
			//fight over the same pixels.
			//A vanilla cliff carries an up-facing lip band about 7.5 units wide
			//along its top edge before the terrace floor starts; that is what
			//makes the edge read as rolled-over rock rather than a paper-thin
			//cut. Measured across the Fiery Path and Cave of Origin cliffs.
			final float COLLAR_IN = 2.5f, COLLAR_OUT = 7.5f;
			//Vanilla's clifftop lip is CLIFF material, not ground - measured as an
			//up-facing band about 7.5 units wide along every cliff top. Sending
			//it to the ground mesh put it in whatever the DONOR's default ground
			//happened to be, which is where the thick brown borders around every
			//platform came from: a band of some other map's dirt laid round grey
			//rock. It belongs in the cliff mesh, sampling the cliff's own lip
			//band, so it reads as the rock rolling over its edge.
			out.add(flatQuadV(
					px[i] - mx[i] * COLLAR_IN, pz[i] - mz[i] * COLLAR_IN,
					px[j] - mx[j] * COLLAR_IN, pz[j] - mz[j] * COLLAR_IN,
					smoothX[i] + mx[i] * COLLAR_OUT, smoothZ[i] + mz[i] * COLLAR_OUT,
					smoothX[j] + mx[j] * COLLAR_OUT, smoothZ[j] + mz[j] * COLLAR_OUT,
					vyt[i] - 0.02f, vyt[j] - 0.02f,
					u * U_PER_UNIT, (u + segLen) * U_PER_UNIT, V_LIP, V_MID, 0.86f));

			//A CLIFF IS A STACK OF 18-UNIT STEPS, not one tall slab.
			//
			//Vanilla never lets a cliff face cross a multiple of 18: measured
			//over five lava regions, 0.00% of cliff area does. Each step is
			//three quads - a lip bevel of 2.25, a sheer wall of 13.5, a foot
			//bevel of 2.25 - and together they lean the face back 13.5 units
			//horizontally per step. The texture restarts every step, running
			//vLip -> vMid -> vWall -> vFoot, about a quarter of the atlas.
			//
			//Mapping one V span across the whole drop instead, as this did,
			//smeared the cobbles into horizontal bands however the numbers were
			//tuned - and building the face as a single quad is what made the
			//rock read as flat plastic rather than stacked stone.
			final float STEP_LIP = 2.25f, STEP_WALL = 13.5f, STEP_FOOT = 2.25f;
			final float RUN_LIP = 4.4f, RUN_WALL = 4.5f, RUN_FOOT = 3.4f;
			final float RUN_STEP = RUN_LIP + RUN_WALL + RUN_FOOT;

			float dropI = Math.max(0f, vyt[i] - vyb[i]);
			float dropJ = Math.max(0f, vyt[j] - vyb[j]);
			int steps = (int) Math.ceil(Math.max(dropI, dropJ) / STEP - 0.001f);
			steps = Math.max(1, Math.min(steps, 8));

			for (int st = 0; st < steps; st++) {
				//this step's slice of each end's drop, clamped at the foot so a
				//drop that is not a whole number of levels still lands flush
				float tI = Math.max(vyb[i], vyt[i] - st * STEP);
				float bI = Math.max(vyb[i], vyt[i] - (st + 1) * STEP);
				float tJ = Math.max(vyb[j], vyt[j] - st * STEP);
				float bJ = Math.max(vyb[j], vyt[j] - (st + 1) * STEP);
				float hI = tI - bI, hJ = tJ - bJ;
				if (hI < 0.01f && hJ < 0.01f) {
					continue;
				}
				//a partial step keeps the same proportions, just shorter
				float fI = hI / STEP, fJ = hJ / STEP;
				float oI = st * RUN_STEP, oJ = st * RUN_STEP;

				float lipBotI = tI - STEP_LIP * fI, lipBotJ = tJ - STEP_LIP * fJ;
				float wallBotI = lipBotI - STEP_WALL * fI, wallBotJ = lipBotJ - STEP_WALL * fJ;
				float footBotI = bI - (st == steps - 1 ? bury : 0f);
				float footBotJ = bJ - (st == steps - 1 ? bury : 0f);

				out.add(stripVar(
						smoothX[i], smoothZ[i], mx[i], mz[i], tI, lipBotI,
						oI, oI + RUN_LIP * fI,
						smoothX[j], smoothZ[j], mx[j], mz[j], tJ, lipBotJ,
						oJ, oJ + RUN_LIP * fJ, enx, enz,
						u * U_PER_UNIT, (u + segLen) * U_PER_UNIT,
						V_LIP, V_MID, 0.80f, 0.72f));
				out.add(stripVar(
						smoothX[i], smoothZ[i], mx[i], mz[i], lipBotI, wallBotI,
						oI + RUN_LIP * fI, oI + (RUN_LIP + RUN_WALL) * fI,
						smoothX[j], smoothZ[j], mx[j], mz[j], lipBotJ, wallBotJ,
						oJ + RUN_LIP * fJ, oJ + (RUN_LIP + RUN_WALL) * fJ, enx, enz,
						u * U_PER_UNIT, (u + segLen) * U_PER_UNIT,
						V_MID, V_WALL, 0.72f, 0.56f));
				out.add(stripVar(
						smoothX[i], smoothZ[i], mx[i], mz[i], wallBotI, footBotI,
						oI + (RUN_LIP + RUN_WALL) * fI, oI + RUN_STEP * fI,
						smoothX[j], smoothZ[j], mx[j], mz[j], wallBotJ, footBotJ,
						oJ + (RUN_LIP + RUN_WALL) * fJ, oJ + RUN_STEP * fJ, enx, enz,
						u * U_PER_UNIT, (u + segLen) * U_PER_UNIT,
						V_WALL, V_FOOT, 0.56f, 0.44f));
			}
			u += segLen;
		}
	}

	//---- the per-vertex phases of a chain -------------------------------------
	//A chain of n edges is a polyline of n+1 vertices. Vertex i sits between
	//the edge before it and the edge after it; on a loop those wrap, on an
	//open chain the two end vertices reuse their own single edge.

	/** The edge ending at vertex i of the polyline. */
	static CliffEdge edgeBefore(List<CliffEdge> chain, int i, boolean loop) {
		int n = chain.size();
		return i == 0 ? (loop ? chain.get(n - 1) : chain.get(0)) : chain.get(i - 1);
	}

	/** The edge starting at vertex i of the polyline. */
	static CliffEdge edgeAfter(List<CliffEdge> chain, int i, boolean loop) {
		int n = chain.size();
		return i >= n ? (loop ? chain.get(0) : chain.get(n - 1)) : chain.get(i);
	}

	/** The key vertex i has in the corner and cap maps. */
	static String vertexKey(List<CliffEdge> chain, int i) {
		int n = chain.size();
		return i < n ? chain.get(i).startKey() : chain.get(n - 1).endKey();
	}

	/**
	 * The outward direction at each vertex: the mitre of the segments meeting
	 * there, lengthened so a mitred corner keeps the same face width as a
	 * straight run, and capped so it cannot grow a blade.
	 *
	 * @param corner the shared bisectors, keyed by vertex; may be null
	 * @return {mx, mz} per vertex
	 */
	static float[][] mitres(List<CliffEdge> chain, boolean loop, Map<String, float[]> corner) {
		int vc = chain.size() + 1;
		float[] mx = new float[vc], mz = new float[vc];
		for (int i = 0; i < vc; i++) {
			CliffEdge prev = edgeBefore(chain, i, loop);
			CliffEdge next = edgeAfter(chain, i, loop);
			//Prefer the bisector agreed by every wall touching this point, so
			//that a corner shared with another strip closes. Fall back to this
			//chain's own two edges if the point is not in the map.
			float[] shared = corner == null ? null : corner.get(vertexKey(chain, i));
			float sx, sz;
			//The shared bisector is the sum of the outward normals of every
			//wall touching this point. That is meaningful at an ordinary
			//corner, where two or three walls broadly agree. It is meaningless
			//where a ramp corridor cuts through a cliff: half a dozen edges
			//meet there facing opposite ways, the sum cancels to nearly zero,
			//and normalising it yields an essentially random direction - which
			//the dot-clamp and the reach scale then stretch into a blade. The
			//cliff either side of every corridor was a spray of spikes.
			//When the walls disagree that badly, there is no shared bisector to
			//find; use this strip's own two edges.
			float slen = shared == null ? 0f : (float) Math.hypot(shared[0], shared[1]);
			if (shared != null && slen > 0.75f) {
				sx = shared[0];
				sz = shared[1];
			} else {
				sx = prev.nx + next.nx;
				sz = prev.nz + next.nz;
			}
			float len = (float) Math.sqrt(sx * sx + sz * sz);
			if (len < 1e-4f) {
				sx = next.nx;
				sz = next.nz;
				len = 1f;
			}
			sx /= len;
			sz /= len;
			//lengthen so a mitred corner keeps the same face width as a straight run
			//Cap how far a mitre may stretch. At 0.5 a corner reaches twice the
			//face width, which is a spike whenever the bisector is even
			//slightly off; 0.7 keeps corners closed without letting them grow
			//blades.
			float dot = Math.max(0.7f, sx * next.nx + sz * next.nz);
			mx[i] = sx / dot;
			mz[i] = sz / dot;
		}
		return new float[][]{mx, mz};
	}

	/**
	 * HEIGHT PER VERTEX. This is what lets one strip run through a change of
	 * step instead of ending at it. A vertex shared by two edges of different
	 * depth takes the average of the two, so the wall's top and bottom edges
	 * both slope through the transition - which is what a hillside corner
	 * actually looks like - rather than one wall stopping dead and another
	 * starting beside it with a seam down the join.
	 *
	 * @return {top, bottom} per vertex
	 */
	static float[][] vertexHeights(List<CliffEdge> chain, boolean loop) {
		int vc = chain.size() + 1;
		float[] vyt = new float[vc], vyb = new float[vc];
		for (int i = 0; i < vc; i++) {
			CliffEdge ea = edgeBefore(chain, i, loop);
			CliffEdge eb = edgeAfter(chain, i, loop);
			String at = vertexKey(chain, i);
			vyt[i] = (ea.topAt(at) + eb.topAt(at)) * 0.5f;
			vyb[i] = (ea.yBot + eb.yBot) * 0.5f;
		}
		return new float[][]{vyt, vyb};
	}

	/**
	 * Every proportion derived from a vertex's fall: the mid-height, and how
	 * far the shoulder and the face reach out.
	 *
	 * @return {mid, shoulderReach, faceReach} per vertex, or null when no
	 *         vertex has any fall at all - there is nothing to build
	 */
	static float[][] faceReach(List<CliffEdge> chain, boolean loop, float[] vyt, float[] vyb) {
		int vc = chain.size() + 1;
		float[] vyMid = new float[vc], vRunS = new float[vc], vRunT = new float[vc];
		boolean anyDrop = false;
		for (int i = 0; i < vc; i++) {
			float d = vyt[i] - vyb[i];
			if (d > 0.01f) {
				anyDrop = true;
			}
			//The face carries most of the fall AND most of the horizontal run,
			//so from above it is the face you see, not the shoulder.
			float rs = d * 0.22f;
			float rt = rs + d * 0.55f;
			//Never reach further than one tile out, however tall the drop: a
			//45-degree face on a 36-unit step overshoots the terrace below.
			if (rt > TILE) {
				float squeeze = TILE / rt;
				rs *= squeeze;
				rt *= squeeze;
			}
			CliffEdge ea = edgeBefore(chain, i, loop);
			CliffEdge eb = edgeAfter(chain, i, loop);
			if (ea.tight || eb.tight) {
				//a face that would lean over a route stands up instead
				rs = Math.min(rs, 1.2f);
				rt = Math.min(rt, 4.5f);
			}
			vyMid[i] = vyb[i] + d * 0.80f;
			vRunS[i] = rs;
			vRunT[i] = rt;
		}
		return anyDrop ? new float[][]{vyMid, vRunS, vRunT} : null;
	}

	/**
	 * One band of a contour strip, with its own height and its own outward
	 * reach at EACH end.
	 *
	 * <p>The scalar version could only describe a band of constant depth, which
	 * is why a wall had to be cut wherever its step changed - and every seam
	 * between those pieces was a defect waiting to happen. Letting the two ends
	 * differ is what allows one strip to run through the change.
	 */
	static Quad stripVar(
			float x0, float z0, float m0x, float m0z,
			float yTop0, float yBot0, float out0Top, float out0Bot,
			float x1, float z1, float m1x, float m1z,
			float yTop1, float yBot1, float out1Top, float out1Bot,
			float edgeNx, float edgeNz,
			float u0, float u1, float vTop, float vBot, float aoTop, float aoBot) {
		Quad q = new Quad();
		q.pos[0] = new float[]{x0 + m0x * out0Top, yTop0, z0 + m0z * out0Top};
		q.pos[1] = new float[]{x1 + m1x * out1Top, yTop1, z1 + m1z * out1Top};
		q.pos[2] = new float[]{x0 + m0x * out0Bot, yBot0, z0 + m0z * out0Bot};
		q.pos[3] = new float[]{x1 + m1x * out1Bot, yBot1, z1 + m1z * out1Bot};
		q.uv[0] = new float[]{u0, vTop};
		q.uv[1] = new float[]{u1, vTop};
		q.uv[2] = new float[]{u0, vBot};
		q.uv[3] = new float[]{u1, vBot};
		q.ao[0] = aoTop;
		q.ao[1] = aoTop;
		q.ao[2] = aoBot;
		q.ao[3] = aoBot;
		//lean averaged over the two ends, so lighting follows the slope
		float run = ((out0Bot - out0Top) + (out1Bot - out1Top)) * 0.5f;
		float rise = ((yTop0 - yBot0) + (yTop1 - yBot1)) * 0.5f;
		float hyp = (float) Math.hypot(run, rise);
		float upward = hyp < 1e-4f ? 0f : run / hyp;
		float horiz = hyp < 1e-4f ? 1f : rise / hyp;
		float ax2 = (m0x + m1x) * 0.5f, az2 = (m0z + m1z) * 0.5f;
		float al = (float) Math.hypot(ax2, az2);
		if (al > 1e-4f) {
			ax2 /= al;
			az2 /= al;
		}
		for (int c = 0; c < 4; c++) {
			q.nrm[c] = new float[]{ax2 * horiz, upward, az2 * horiz};
		}
		//Wind against the SEGMENT'S OWN outward direction, never the averaged
		//mitre: at a corner the two mitres are perpendicular and their average
		//points diagonally, so the sign comes down to rounding - and a cliff
		//face wound inward is culled, showing the background straight through.
		fixWindingTowards(q, edgeNx * horiz, upward, edgeNz * horiz);
		return q;
	}

	/**
	 * A horizontal quad whose two ends may sit at different heights, textured over
	 * the U and V range it is handed and wound to face up.
	 *
	 * <p>The clifftop collar is drawn with it, in the CLIFF mesh, over the V range of
	 * the cliff texture's own lip band (see the collar note in emitChain): sending
	 * the collar to the ground mesh put it in whatever the donor's default ground
	 * happened to be, which is where the thick brown borders round every platform
	 * came from.
	 */
	static Quad flatQuadV(float ax, float az, float bx, float bz,
			float cx, float cz, float dx, float dz, float yA, float yB,
			float u0, float u1, float v0, float v1, float ao) {
		Quad q = new Quad();
		q.pos[0] = new float[]{ax, yA, az};
		q.pos[1] = new float[]{bx, yB, bz};
		q.pos[2] = new float[]{cx, yA, cz};
		q.pos[3] = new float[]{dx, yB, dz};
		q.uv[0] = new float[]{u0, v0};
		q.uv[1] = new float[]{u1, v0};
		q.uv[2] = new float[]{u0, v1};
		q.uv[3] = new float[]{u1, v1};
		for (int c = 0; c < 4; c++) {
			q.nrm[c] = new float[]{0f, 1f, 0f};
			q.ao[c] = ao;
		}
		fixWindingTowards(q, 0f, 1f, 0f);
		return q;
	}

	/** Bakes tint x brightness x per-corner AO into each quad's 4 vertex colors. */
	static void bakeQuadLighting(BchMapModel model, BchMapModel.MeshGeom g, byte[] vtx, List<Quad> quads, TerrainLighting light) {
		BchMapModel.MeshAttr col = model.findAttr(g.meshIndex, 3);
		if (col == null) {
			return;
		}
		int compSize = col.size() / Math.max(1, col.elems);
		for (int i = 0; i < quads.size(); i++) {
			Quad q = quads.get(i);
			for (int c = 0; c < 4; c++) {
				int[] rgba = light.vertexColor(q.ao[c]);
				int base = (i * 4 + c) * g.stride + col.offset;
				for (int k = 0; k < col.elems; k++) {
					int o = base + k * compSize;
					//encode PER ATTRIBUTE FORMAT - a color attribute may be s8
					//(range 0..127, 1/127 scale), u8, s16 or float. Writing the
					//raw 0..255 value into an s8 attribute overflows to a
					//NEGATIVE byte, which the renderer shows as BLACK: measured
					//on 60% of retail regions, and the cause of paint coming out
					//as a dark square on maps like Mauville.
					float unit = (k < 4 ? rgba[k] : 255) / 255f;
					MapModelObjImporter.putComp(vtx, o, col.type, unit);
				}
			}
		}
	}
}
