package ctrmap.formats.tilemap;

import static ctrmap.formats.tilemap.PaintedGeometry.DIM;

/**
 * The painter's stage for the tilemap's per-tile tuple.
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
		byte[] out = new byte[Tilemap.REGION_BYTES];
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
