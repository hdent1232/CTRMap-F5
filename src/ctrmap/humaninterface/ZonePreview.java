package ctrmap.humaninterface;

import ctrmap.WorkspaceSession;
import ctrmap.ZoneResource;
import ctrmap.formats.containers.GR;
import ctrmap.formats.h3d.texturing.H3DTexture;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One zone's map, decoded far enough to draw - without loading the zone.
 *
 * <p>WHY THIS IS A CLASS AND NOT A LISTENER. Picking a zone in the Zone Loader
 * loads it: its entities, its script, its editors, its undo history. That is the
 * right thing when you mean to work on it and the wrong thing when you are
 * looking for it, and the owner asked for a preview precisely because finding a
 * zone meant loading each candidate in turn. So the decode is a function that
 * answers with bytes, and the panel above it only draws.
 *
 * <p>WHAT "THE MAP OF ZONE N" MEANS, and what it used to mean here. A zone names
 * a map matrix, and a matrix is not one zone's map: 25 retail matrices are named
 * by more than one zone and 139 zones sit on a shared one. This class used to
 * answer with {@code MapMatrix.firstRegionId} - the first filled cell in reading
 * order - which is whichever zone owns the top-left corner. Measured over the
 * retail dump, 39 of the 61 zones whose matrix carries an ownership grid were
 * answered with a region belonging to a DIFFERENT zone: Mossdeep City was shown
 * region 96, which belongs to Route 125. The owner previewed three cities,
 * recognised none of them, and said so.
 *
 * <p>It also answered with ONE region for a zone that averages seven, so even a
 * correct answer was a corner tile. Both are the same mistake made twice - the
 * map of a zone is every region that zone owns - and both are fixed by asking
 * the matrix's own zone grid which cells belong to the zone that asked.
 *
 * <p>It is also the only way this can be guarded. A preview is a picture, and a
 * suite cannot look at one - but it can check that the regions handed to the
 * view are the ones the game assigns to that zone, which is the thing that
 * actually goes wrong.
 *
 * <p>IT IS HANDED ITS SESSION rather than fetching the open one. Not style:
 * WorkspaceSessionTest holds a falling ceiling on how many production files reach
 * those statics, and a new class that reached them would raise it - which that
 * suite refuses, correctly, and did. Handing it in is also what lets a suite point
 * this at a scratch game rather than at whatever happens to be open.
 *
 * <p>IT ALWAYS ANSWERS, and always says something. A zone whose map cannot be
 * read gives a {@link Shot} with no model and a note explaining which part was
 * missing; the caller shows the note and clears the view. The alternative - a
 * null, or an exception into a listener - is a preview pane that silently keeps
 * showing the zone BEFORE the one you clicked, which is worse than blank because
 * it is wrong rather than absent.
 */
public final class ZonePreview {

	private ZonePreview() {
	}

	/**
	 * How many regions of one zone will be read.
	 *
	 * <p>The largest retail zone owns 23, so this reads every one of them; it is
	 * here so a damaged or hand-edited matrix claiming hundreds of cells cannot
	 * turn arrowing down a list into a disk storm.
	 */
	public static final int MOST_REGIONS = 32;

	/** What there is to draw for one zone, and what to say about it. */
	public static final class Shot {

		public final int zoneIndex;
		/** The zone's FIRST owned region, or -1 when there is none. */
		public final int region;
		/** The zone's area id, for the textures - -1 when the zone could not be read. */
		public final int area;
		/** The map matrix the zone names, or -1. */
		public final int matrix;
		/** The first region's map model, or null when there is nothing to draw. */
		public final byte[] model;
		/** Every owned region as {region id, matrix column, matrix row}; never null. */
		public final int[][] cells;
		/** The model bytes of each cell, aligned with {@link #cells}; never null. */
		public final List<byte[]> models;
		/** The textures those models are painted with; never null, possibly empty. */
		public final List<H3DTexture> textures;
		/** What the user is told. Never empty, whether it worked or not. */
		public final String note;

		Shot(int zoneIndex, int region, int area, int matrix, byte[] model, int[][] cells,
				List<byte[]> models, List<H3DTexture> textures, String note) {
			this.zoneIndex = zoneIndex;
			this.region = region;
			this.area = area;
			this.matrix = matrix;
			this.model = model;
			this.cells = cells == null ? new int[0][] : cells;
			this.models = models == null ? Collections.<byte[]>emptyList() : models;
			this.textures = textures == null ? Collections.<H3DTexture>emptyList() : textures;
			this.note = note;
		}

		/** True when there is geometry to draw. */
		public boolean drawable() {
			return model != null && model.length > 0;
		}

		/** How many regions of this zone are being drawn. */
		public int drawnCount() {
			return models.size();
		}

		/** The matrix columns of the drawn regions, for the view's layout. */
		public int[] columns() {
			int[] out = new int[models.size()];
			for (int i = 0; i < out.length && i < cells.length; i++) {
				out[i] = cells[i][1];
			}
			return out;
		}

		/** The matrix rows of the drawn regions, for the view's layout. */
		public int[] rows() {
			int[] out = new int[models.size()];
			for (int i = 0; i < out.length && i < cells.length; i++) {
				out[i] = cells[i][2];
			}
			return out;
		}
	}

	private static Shot nothing(int zone, int region, int area, int matrix, String why) {
		return new Shot(zone, region, area, matrix, null, null, null, null, why);
	}

	/**
	 * Reads every region the zone owns, ready to hand to {@link MapPreview3D}.
	 *
	 * <p>Owned, not "in the matrix": see the class comment. A zone on a matrix
	 * with no ownership grid owns all of it, which is the right answer for the
	 * 477 zones that do not share.
	 */
	public static Shot of(WorkspaceSession ws, int zoneIndex) {
		if (ws == null) {
			return nothing(zoneIndex, -1, -1, -1, "No game is open, so there is nothing to preview.");
		}
		int area = -1, matrix = -1;
		try {
			File zf = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
			if (zf == null || !zf.isFile()) {
				return nothing(zoneIndex, -1, area, matrix,
						"Zone " + zoneIndex + " could not be read out of the workspace.");
			}
			byte[] zo = Files.readAllBytes(zf.toPath());
			area = ZoneResource.AREA.idIn(zo);
			matrix = ZoneResource.MAP.idIn(zo);
			File mf = ws.getWorkspaceFile(ArchiveType.MAP_MATRIX, matrix);
			if (mf == null || !mf.isFile()) {
				return nothing(zoneIndex, -1, area, matrix,
						"Zone " + zoneIndex + " names map " + matrix + ", which is not in the workspace.");
			}
			byte[] mm = Files.readAllBytes(mf.toPath());
			int[][] owned = MapMatrix.regionsOwnedBy(mm, zoneIndex);
			if (owned.length == 0) {
				return nothing(zoneIndex, -1, area, matrix,
						"Zone " + zoneIndex + "'s map (" + matrix + ") gives it no regions of its own"
						+ " - an empty slot, or a matrix that hands every cell to another zone.");
			}
			List<int[]> got = new ArrayList<>();
			List<byte[]> models = new ArrayList<>();
			List<String> missing = new ArrayList<>();
			for (int i = 0; i < owned.length && got.size() < MOST_REGIONS; i++) {
				int id = owned[i][0];
				File rf = ws.getWorkspaceFile(ArchiveType.FIELD_DATA, id);
				if (rf == null || !rf.isFile()) {
					missing.add(String.valueOf(id));
					continue;
				}
				byte[] model = new GR(rf, ws).getFile(1);
				if (model == null || model.length == 0) {
					missing.add(String.valueOf(id));
					continue;
				}
				got.add(owned[i]);
				models.add(model);
			}
			if (models.isEmpty()) {
				return nothing(zoneIndex, owned[0][0], area, matrix,
						"Zone " + zoneIndex + " owns " + owned.length + " region(s) and none of them"
						+ " has a map model - nothing to draw.");
			}
			List<H3DTexture> tex = new ArrayList<>();
			try {
				//THE SAME LOADER THE BUILDING PALETTE USES, cached there, rather than a
				//second copy of "which subfiles of an area hold its texture packs"
				tex.addAll(BuildingPaletteDialog.donorTextures(area));
			} catch (Exception noTextures) {
				//an untextured preview is still a shape, and still tells a town from a cave
			}
			StringBuilder said = new StringBuilder();
			said.append("Zone ").append(zoneIndex).append(" - area ").append(area)
					.append(", map ").append(matrix).append(", ")
					.append(models.size() == 1 ? "region " + got.get(0)[0]
							: models.size() + " regions");
			if (owned.length > models.size()) {
				said.append(" (").append(owned.length - models.size()).append(" missing: ")
						.append(join(missing)).append(")");
			}
			if (tex.isEmpty()) {
				said.append(" - no textures found, shape only");
			}
			return new Shot(zoneIndex, got.get(0)[0], area, matrix, models.get(0),
					got.toArray(new int[got.size()][]), models, tex, said.toString());
		} catch (Exception ex) {
			return nothing(zoneIndex, -1, area, matrix,
					"Zone " + zoneIndex + " could not be previewed: " + ex);
		}
	}

	private static String join(List<String> what) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < what.size() && i < 6; i++) {
			sb.append(i == 0 ? "" : ", ").append(what.get(i));
		}
		if (what.size() > 6) {
			sb.append(", ...");
		}
		return sb.toString();
	}
}
