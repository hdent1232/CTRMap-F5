package ctrmap.tests;

import ctrmap.formats.encounters.EncounterTable;
import ctrmap.formats.encounters.EnPack;
import java.util.Arrays;

/**
 * The wild-encounter pack is read strictly and written by one class (F053), on packs built here -
 * no dump. EncounterTableTest checks the same over the game's own pack where there is one.
 *
 * <p>What it pins: a pack reads back byte-identical; a zone's blob is replaced with every other
 * carried verbatim; a pack grows by empty zones and is cut back to its first ones; and a pack that
 * is not one is refused in words - the encounter editor used to take any bytes for a pack, and a
 * pair of offsets that fell was a NegativeArraySizeException in the middle of an edit.
 *
 * Usage: java ctrmap.tests.EnPackTest
 */
public class EnPackTest {

	static int fails = 0;

	public static void main(String[] args) {
		System.out.println("--- an EN pack reads and writes through one class (F053)");
		byte[] a = {1, 2, 3}, c = {7, 8, 9, 10};
		byte[] pack = EnPack.parse(handMade(a, new byte[0], c)).bytes();
		check(Arrays.equals(pack, handMade(a, new byte[0], c)), "a pack reads back byte-identical");
		EnPack p = EnPack.parse(pack, 3);
		check(p.count() == 3 && Arrays.equals(p.blob(0), a) && p.blob(1).length == 0 && Arrays.equals(p.blob(2), c),
				"each zone's blob is its own, an empty one for a zone with no wild data");
		byte[] b = {4, 5};
		check(Arrays.equals(p.withBlob(1, b).bytes(), handMade(a, b, c)),
				"a zone's blob replaced, every other carried verbatim");
		check(Arrays.equals(p.grownBy(2).bytes(), handMade(a, new byte[0], c, new byte[0], new byte[0])),
				"grown by two empty zones");
		check(Arrays.equals(p.truncatedTo(2).bytes(), handMade(a, new byte[0])), "cut back to its first two");
		check(EncounterTable.read(pack, 1) == null, "a zone with no wild data reads as no table");

		System.out.println("--- a pack that is not one is refused in words, never read as one (F053)");
		refused("the wrong count", () -> EnPack.parse(pack, 4), "EN pack count 3 != zone count 4");
		byte[] magic = pack.clone();
		magic[1] = 'X';
		refused("the wrong magic", () -> EnPack.parse(magic), "wrong magic");
		byte[] falls = pack.clone();
		putI32(falls, 4 + 2 * 4, i32(falls, 4) - 1);   //zone 2 starts before zone 0 does
		refused("offsets that fall", () -> EnPack.parse(falls), "not monotonic");
		refused("the editor reading a zone from offsets that fall", () -> EncounterTable.read(falls, 1), "not monotonic");
		byte[] tail = Arrays.copyOf(pack, pack.length + 1);
		refused("a byte past the end sentinel", () -> EnPack.parse(tail), "end sentinel");
		refused("a table longer than the file", () -> EnPack.parse(new byte[]{'E', 'N', 9, 0, 0, 0, 0, 0}),
				"too short for its offset table (8 < 44)");
		//MEASURED by the 2026-10-14 shared sweep (EnPack 41, dropped, SURVIVED): no file shorter than a header, and
		//no file at all, had ever been handed in - dropped, nothing was a NullPointerException and 4 bytes were
		//refused for a table they never claimed
		refused("no file at all", () -> EnPack.parse(null), "EN pack too short (0 bytes).");
		refused("a file of 4 bytes, shorter than any header", () -> EnPack.parse(new byte[]{'E', 'N', 0, 0}),
				"EN pack too short (4 bytes).");
		//MEASURED by the 2026-10-14 shared sweep (EnPack 58, dropped, SURVIVED): no pack whose first zone starts past
		//the end of its table had been read - dropped, zone 0 of 3 bytes was read from 1 byte in, its first byte lost
		byte[] gap = pack.clone();
		putI32(gap, 4, i32(gap, 4) + 1);
		refused("a first zone starting 1 byte past the table's end", () -> EnPack.parse(gap),
				"EN pack first offset 0x15 != table end 0x14.");

		System.out.println("--- a count out of range is refused in the pack's words (2026-10-14)");
		//MEASURED by the 2026-10-14 shared sweep (EnPack 110, dropped, SURVIVED): no count out of range had been handed
		//to the pack's own methods - with a refusal dropped, Java's collections refused in their words instead, and
		//bytes() past 65535 zones would have written the count's low 16 bits
		refused("a pack grown by -1 zones", () -> p.grownBy(-1), "a pack is grown by a count of zones, not by -1");
		refused("a pack of 3 zones cut to 4", () -> p.truncatedTo(4), "keepCount 4 out of range (0..3)");
		refused("a pack cut to -1 zones", () -> p.truncatedTo(-1), "keepCount -1 out of range (0..3)");
		refused("zone 3 of a pack of 3 read", () -> p.blob(3), "zone 3 out of range (EN count 3)");
		refused("zone -1 replaced", () -> p.withBlob(-1, new byte[0]), "zone -1 out of range (EN count 3)");
		refused("a pack of 65536 zones written", () -> p.grownBy(65536 - 3).bytes(),
				"an EN pack holds at most 65535 zones, not 65536");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A pack laid out here by hand, independently of EnPack: magic, count, offsets, blobs. */
	static byte[] handMade(byte[]... blobs) {
		int table = 4 + (blobs.length + 1) * 4;
		int total = table;
		for (byte[] x : blobs) {
			total += x.length;
		}
		byte[] out = new byte[total];
		out[0] = 'E';
		out[1] = 'N';
		out[2] = (byte) blobs.length;
		int at = table;
		for (int i = 0; i < blobs.length; i++) {
			putI32(out, 4 + i * 4, at);
			System.arraycopy(blobs[i], 0, out, at, blobs[i].length);
			at += blobs[i].length;
		}
		putI32(out, 4 + blobs.length * 4, at);
		return out;
	}

	static void refused(String what, Runnable r, String says) {
		String said;
		try {
			r.run();
			said = "accepted";
		} catch (IllegalArgumentException ex) {
			said = ex.getMessage();
		} catch (RuntimeException other) {
			said = other.getClass().getSimpleName() + ": " + other.getMessage();
		}
		check(said != null && said.contains(says), what + " is refused in words: " + said);
	}

	static int i32(byte[] b, int at) {
		return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
	}

	static void putI32(byte[] b, int at, int v) {
		for (int k = 0; k < 4; k++) {
			b[at + k] = (byte) (v >> (8 * k));
		}
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		fails += ok ? 0 : 1;
	}
}
