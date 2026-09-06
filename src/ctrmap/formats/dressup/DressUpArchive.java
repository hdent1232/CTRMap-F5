package ctrmap.formats.dressup;

import ctrmap.formats.garc.GARC;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the dress-up index sets inside a player-parts GARC and resolves the
 * two bases an editor needs: where that set's part models start in the archive
 * and where its textures start.
 *
 * <p>Both bases are MEASURED, never assumed. The part models of a set are a
 * run of consecutive subfiles that carry at least one model, and the run whose
 * length equals the set's own part count is that set's model block; the
 * textures then begin immediately after it, because the index counts texture
 * ids from the first subfile past the models. A set whose block cannot be
 * identified reports -1 for both, and a caller must treat that as absence, not
 * as zero.
 *
 * <p>One dependency worth knowing: a model subfile that {@link GARC}'s
 * compression sniff hands back still compressed would not look like a model
 * and would split the run. That does not happen in the archive measured here -
 * the 46 subfiles the sniff misses are all textures - and if it ever did, the
 * run length would stop matching the part count and the base would come back
 * -1 rather than come back wrong.
 */
public class DressUpArchive {

	/** One decoded set, with its place in the archive. */
	public static class Set {

		/** Subfile the container was found in. */
		public final int containerSubfile;
		public final DressUpIndex index;
		/** Subfile of part model 0, or -1 when the model block was not identified. */
		public final int modelBase;
		/** Subfile of texture id 0, or -1 when the model block was not identified. */
		public final int textureBase;

		Set(int containerSubfile, DressUpIndex index, int modelBase, int textureBase) {
			this.containerSubfile = containerSubfile;
			this.index = index;
			this.modelBase = modelBase;
			this.textureBase = textureBase;
		}

		/** Archive subfile of a part model, or -1 when the base is unknown. */
		public int modelSubfile(int partModel) {
			if (modelBase < 0 || partModel < 0 || partModel >= index.parts.length) {
				return -1;
			}
			return modelBase + partModel;
		}

		/** Archive subfile of a texture id, or -1 when the base is unknown. */
		public int textureSubfile(int textureId) {
			if (textureBase < 0 || textureId < 0) {
				return -1;
			}
			return textureBase + textureId;
		}
	}

	private final List<Set> sets = new ArrayList<>();

	/** Sets found, in archive order. Empty when the archive carries none. */
	public List<Set> sets() {
		return sets;
	}

	/**
	 * Scans a parts archive for its dress-up index containers.
	 *
	 * @param g the archive, opened with decompression enabled
	 */
	public DressUpArchive(GARC g) {
		int n = g.getEntryCount();
		boolean[] isModel = new boolean[n];
		List<int[]> containers = new ArrayList<>(); // {subfile}
		List<byte[]> blobs = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			byte[] d = entry(g, i);
			if (d == null || d.length < 16) {
				continue;
			}
			if (DressUpIndex.isContainer(d)) {
				containers.add(new int[]{i});
				blobs.add(d);
			} else if (modelCount(d) > 0) {
				isModel[i] = true;
			}
		}
		// maximal runs of consecutive model subfiles
		List<int[]> runs = new ArrayList<>(); // {start, length}
		int i = 0;
		while (i < n) {
			if (!isModel[i]) {
				i++;
				continue;
			}
			int s = i;
			while (i < n && isModel[i]) {
				i++;
			}
			runs.add(new int[]{s, i - s});
		}
		boolean[] taken = new boolean[runs.size()];
		for (int c = 0; c < containers.size(); c++) {
			DressUpIndex idx;
			try {
				idx = DressUpIndex.read(blobs.get(c));
			} catch (IllegalArgumentException ex) {
				continue; // a container that does not decode is not silently a set
			}
			int base = -1;
			for (int r = 0; r < runs.size(); r++) {
				if (!taken[r] && runs.get(r)[1] == idx.parts.length) {
					taken[r] = true;
					base = runs.get(r)[0];
					break;
				}
			}
			sets.add(new Set(containers.get(c)[0], idx, base,
					base < 0 ? -1 : base + idx.parts.length));
		}
	}

	private static byte[] entry(GARC g, int i) {
		try {
			if (g.getEntryStoredLength(i) <= 0) {
				return null;
			}
			return g.getDecompressedEntry(i);
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Number of models a BCH declares, read from its content header. A header
	 * peek, not a full parse: this runs over every subfile of a 6 MB archive,
	 * and a texture-only BCH answers 0 without being decoded at all.
	 */
	private static int modelCount(byte[] d) {
		if (d.length < 16 || d[0] != 'B' || d[1] != 'C' || d[2] != 'H' || d[3] != 0) {
			return 0;
		}
		int contentHeader = u32(d, 8);
		if (contentHeader < 0 || contentHeader + 8 > d.length) {
			return 0;
		}
		int count = u32(d, contentHeader + 4);
		return (count < 0 || count > 0xFFFF) ? 0 : count;
	}

	private static int u32(byte[] b, int p) {
		return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8)
				| ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
	}
}
