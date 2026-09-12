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
 * answers with bytes, and the dialog above it only draws.
 *
 * <p>It is also the only way this can be guarded. A preview is a picture, and a
 * suite cannot look at one - but it can check that the bytes handed to the view
 * came from the zone that was asked for, which is the thing that actually goes
 * wrong.
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

	/** What there is to draw for one zone, and what to say about it. */
	public static final class Shot {

		public final int zoneIndex;
		/** The FieldData region the model came from, or -1 when there is none. */
		public final int region;
		/** The zone's area id, for the textures - -1 when the zone could not be read. */
		public final int area;
		/** The map matrix the zone names, or -1. */
		public final int matrix;
		/** The region's map model, or null when there is nothing to draw. */
		public final byte[] model;
		/** The textures that model is painted with; never null, possibly empty. */
		public final List<H3DTexture> textures;
		/** What the user is told. Never empty, whether it worked or not. */
		public final String note;

		Shot(int zoneIndex, int region, int area, int matrix, byte[] model,
				List<H3DTexture> textures, String note) {
			this.zoneIndex = zoneIndex;
			this.region = region;
			this.area = area;
			this.matrix = matrix;
			this.model = model;
			this.textures = textures == null ? Collections.<H3DTexture>emptyList() : textures;
			this.note = note;
		}

		/** True when there is geometry to draw. */
		public boolean drawable() {
			return model != null && model.length > 0;
		}
	}

	private static Shot nothing(int zone, int region, int area, int matrix, String why) {
		return new Shot(zone, region, area, matrix, null, null, why);
	}

	/**
	 * Reads one zone's first map region, ready to hand to {@link MapPreview3D}.
	 *
	 * <p>The FIRST region, not all of them: a multi-region map would need the
	 * matrix stitched together, which is what opening the zone is for. One
	 * region is enough to tell a town from a route from a cave, which is the
	 * question a browser answers.
	 */
	public static Shot of(WorkspaceSession ws, int zoneIndex) {
		if (ws == null) {
			return nothing(zoneIndex, -1, -1, -1, "No game is open, so there is nothing to preview.");
		}
		int area = -1, matrix = -1, region = -1;
		try {
			File zf = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
			if (zf == null || !zf.isFile()) {
				return nothing(zoneIndex, region, area, matrix,
						"Zone " + zoneIndex + " could not be read out of the workspace.");
			}
			byte[] zo = Files.readAllBytes(zf.toPath());
			area = ZoneResource.AREA.idIn(zo);
			matrix = ZoneResource.MAP.idIn(zo);
			File mf = ws.getWorkspaceFile(ArchiveType.MAP_MATRIX, matrix);
			if (mf == null || !mf.isFile()) {
				return nothing(zoneIndex, region, area, matrix,
						"Zone " + zoneIndex + " names map " + matrix + ", which is not in the workspace.");
			}
			region = MapMatrix.firstRegionId(Files.readAllBytes(mf.toPath()));
			if (region < 0) {
				return nothing(zoneIndex, region, area, matrix,
						"Zone " + zoneIndex + "'s map (" + matrix + ") names no region - an empty slot.");
			}
			File rf = ws.getWorkspaceFile(ArchiveType.FIELD_DATA, region);
			if (rf == null || !rf.isFile()) {
				return nothing(zoneIndex, region, area, matrix,
						"Zone " + zoneIndex + " uses region " + region + ", which is not in the workspace.");
			}
			byte[] model = new GR(rf, ws).getFile(1);
			if (model == null || model.length == 0) {
				return nothing(zoneIndex, region, area, matrix,
						"Region " + region + " has no map model - nothing to draw.");
			}
			List<H3DTexture> tex = new ArrayList<>();
			try {
				//THE SAME LOADER THE BUILDING PALETTE USES, cached there, rather than a
				//second copy of "which subfiles of an area hold its texture packs"
				tex.addAll(BuildingPaletteDialog.donorTextures(area));
			} catch (Exception noTextures) {
				//an untextured preview is still a shape, and still tells a town from a cave
			}
			return new Shot(zoneIndex, region, area, matrix, model, tex,
					"Zone " + zoneIndex + " - area " + area + ", map " + matrix + ", region " + region
					+ (tex.isEmpty() ? " (no textures found - shape only)" : ""));
		} catch (Exception ex) {
			return nothing(zoneIndex, region, area, matrix,
					"Zone " + zoneIndex + " could not be previewed: " + ex);
		}
	}
}
