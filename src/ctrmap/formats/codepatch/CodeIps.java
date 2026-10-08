package ctrmap.formats.codepatch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The one code.ips: every executable patch this program makes goes into the same file, which the
 * game loads whole (F152).
 *
 * <p>WHY THIS EXISTS. The shop editor, the item-icon editor and the zone-limit writer each saved it
 * their own way: the shop editor read a closed merge question as "overwrite", the zone writer never
 * asked and always overwrote, and only the icon editor refused a closed dialog. So closing a dialog,
 * or adding zones after editing shops, deleted the other patches the game needs to boot. A save
 * through here never asks: it keeps every patch already in the file and replaces only the ranges
 * the saving patch owns - so a value put back to the game's own is put back in the file too, which
 * a plain merge of the new patch over the old would not do.
 *
 * <p>Not: {@link Ips#merge} - the byte machinery a merge is made of, with no idea which
 * bytes a save owns.
 */
public final class CodeIps {

	private CodeIps() {
	}

	/**
	 * The file a save leaves: {@code existing} without the bytes inside {@code owned}, then
	 * {@code ours} over it.
	 *
	 * @param existing the code.ips there now, or null when there is none
	 * @param owned the [start, end) code.bin ranges the saving patch writes
	 * @throws IllegalStateException when {@code existing} is not an IPS patch: it is neither merged
	 * into nor replaced, since it may be something the game needs that this program did not write
	 */
	public static byte[] merged(byte[] existing, byte[] ours, int[][] owned) {
		if (existing == null) {
			return ours;
		}
		if (!Ips.isIps(existing)) {
			throw new IllegalStateException("the code.ips there is not an IPS patch (it does not start with PATCH and end"
					+ " with EOF), so it was neither merged into nor replaced");
		}
		List<int[]> records;
		try {
			records = Ips.parse(existing);
		} catch (RuntimeException unreadable) {   //a record running past the end, or one this reader does not take
			throw new IllegalStateException("the code.ips there could not be read record by record ("
					+ ctrmap.util.Bytes.reason(unreadable) + "), so it was neither merged into nor replaced", unreadable);
		}
		List<int[]> kept = new ArrayList<>();
		for (int[] rec : records) {
			int start = -1;
			for (int k = 0; k <= rec[1]; k++) {
				boolean keep = k < rec[1] && !inside(rec[0] + k, owned);
				if (keep && start < 0) {
					start = k;
				} else if (!keep && start >= 0) {
					int[] part = new int[2 + k - start];
					part[0] = rec[0] + start;
					part[1] = k - start;
					System.arraycopy(rec, 2 + start, part, 2, k - start);
					kept.add(part);
					start = -1;
				}
			}
		}
		return Ips.merge(Ips.write(kept), ours);
	}

	/**
	 * Saves {@code ours} into the code.ips at {@code out}, keeping the patches already there.
	 *
	 * @return a note for the user: what happened to the patches already there
	 */
	public static String save(File out, byte[] ours, int[][] owned) throws IOException {
		byte[] existing = out.isFile() ? Files.readAllBytes(out.toPath()) : null;
		ctrmap.util.Bytes.replaceFile(out, merged(existing, ours, owned));
		return existing == null ? "" : " (the patches already in it were kept)";
	}

	/** A patch that patches nothing - written by the one writer, not spelled here (F047). */
	static final byte[] EMPTY = Ips.write(new ArrayList<>());

	/**
	 * Takes the patch that owns {@code owned} out of the code.ips at {@code file} and keeps every
	 * other one - what removing the added zones needs, where deleting the file, as the user used to
	 * be told to, took the shop and icon patches with it.
	 *
	 * @return how many patched bytes are left in it
	 */
	public static int strip(File file, int[][] owned) throws IOException {
		byte[] left = merged(Files.readAllBytes(file.toPath()), EMPTY, owned);
		ctrmap.util.Bytes.replaceFile(file, left);
		int n = 0;
		for (int[] rec : Ips.parse(left)) {
			n += rec[1];
		}
		return n;
	}

	private static boolean inside(int offset, int[][] owned) {
		for (int[] r : owned) {
			if (offset >= r[0] && offset < r[1]) {
				return true;
			}
		}
		return false;
	}

}
