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
import static ctrmap.formats.tilemap.PaintedMaterials.*;

/**
 * The painter's the tilemap's per-tile tuple.
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
public final class PaintedTiles {

	private PaintedTiles() {
	}

	// ---- tilemap ----------------------------------------------------------

	/** Composite tilemap: the donor's movement bytes with only the touched
	 *  tiles' tuples overwritten. An unusable donor degrades to a full build. */
	static byte[] buildTilemapComposite(byte[] donorTilemap, TilePalette[][] grid, boolean[][] touched) {
		if (donorTilemap == null || donorTilemap.length < 4 + DIM * DIM * 4
				|| (donorTilemap[0] & 0xFF) != DIM || (donorTilemap[2] & 0xFF) != DIM) {
			return buildTilemap(grid);
		}
		byte[] out = donorTilemap.clone();
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				if (!touched[ty][tx]) {
					continue;
				}
				TilePalette t = grid[ty][tx];
				int[] tuple = (t == null ? TilePalette.VOID : t).tuple;
				int off = 4 + (ty * DIM + tx) * 4;
				out[off] = (byte) tuple[0];
				out[off + 1] = (byte) tuple[1];
				out[off + 2] = (byte) tuple[2];
				out[off + 3] = (byte) tuple[3];
			}
		}
		return out;
	}

	static byte[] buildTilemap(TilePalette[][] grid) {
		byte[] out = new byte[6528];
		out[0] = (byte) DIM;
		out[2] = (byte) DIM;
		for (int ty = 0; ty < DIM; ty++) {
			for (int tx = 0; tx < DIM; tx++) {
				TilePalette t = grid[ty][tx];
				int[] tuple = (t == null ? TilePalette.VOID : t).tuple;
				int off = 4 + (ty * DIM + tx) * 4;
				out[off] = (byte) tuple[0];
				out[off + 1] = (byte) tuple[1];
				out[off + 2] = (byte) tuple[2];
				out[off + 3] = (byte) tuple[3];
			}
		}
		return out;
	}
}
