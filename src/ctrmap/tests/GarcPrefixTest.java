package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.garc.LZ11;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Opening an archive reads each entry's first bytes, not the entry, and decides exactly what the
 * whole read decided (F055).
 *
 * <p>WHY THIS SUITE EXISTS. The parse read every entry whole to look at 64 of its bytes, so
 * constructing an archive read all of it - FieldData's 860 regions to open it once - and a pack
 * opened the archive again for every entry it copied unchanged. The sniff it feeds decides what
 * an entry IS, compressed or raw, and an entry the file cannot hold is a cut; both answers must
 * survive reading less.
 *
 * <p>ORDER: needs no dump. The bytes-read half asks the operating system, through
 * /proc/self/io; where there is none it says UNKNOWN rather than passing.
 *
 * Usage: java ctrmap.tests.GarcPrefixTest
 */
public class GarcPrefixTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		theSniffDecidesAsTheWholeReadDid();
		theParseFindsWhatItFoundBefore();
		openingAnArchiveReadsItsPrefixes();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** Bytes that compress: a phrase repeated with a counter in it. */
	static byte[] text(int n, int seed) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; sb.length() < n; i++) {
			sb.append("region ").append(seed).append(" row ").append(i % 37).append(';');
		}
		return sb.substring(0, n).getBytes(StandardCharsets.US_ASCII);
	}

	static void theSniffDecidesAsTheWholeReadDid() {
		System.out.println("--- the strict prefix decode answers from 64 bytes and the stored length as it did from the whole entry");
		Random r = new Random(55);
		int streams = 0;
		int raws = 0;
		int acceptedLong = 0;
		int refusedRaw = 0;
		List<String> differ = new ArrayList<>();
		for (int k = 0; k < 400; k++) {
			byte[] stored;
			if (k % 2 == 0) {
				//three kinds of stream, because each reaches the bound a different way: text so
				//repetitive it can be decoded whole before byte 64, bytes that do not compress -
				//literals all the way to the bound, landing on it between tokens - and the two mixed
				byte[] plain;
				if (k % 6 == 0) {
					plain = text(1 + r.nextInt(5000), k);
				} else if (k % 6 == 2) {
					plain = new byte[65 + r.nextInt(2000)];
					r.nextBytes(plain);
				} else {
					plain = text(200 + r.nextInt(3000), k);
					for (int j = 0; j < plain.length; j += 1 + r.nextInt(9)) {
						plain[j] = (byte) r.nextInt(256);
					}
				}
				stored = LZ11.compress(plain);
				streams++;
			} else {
				stored = new byte[4 + r.nextInt(300)];
				r.nextBytes(stored);
				stored[0] = 0x11;
				raws++;
			}
			boolean whole = LZ11.prefixDecodes(stored, 64);
			boolean prefix = LZ11.prefixDecodes(Arrays.copyOf(stored, Math.min(64, stored.length)), 64, stored.length);
			if (whole != prefix) {
				differ.add(k + " (" + stored.length + " bytes)");
			}
			if (k % 2 == 0 && whole && stored.length > 64) {
				acceptedLong++;
			}
			if (k % 2 == 1 && !whole) {
				refusedRaw++;
			}
		}
		check(acceptedLong > 50 && refusedRaw > 50, "control: of " + streams + " streams and " + raws + " raw records, "
				+ acceptedLong + " streams longer than the bound decode and " + refusedRaw + " raw records fault");
		check(differ.isEmpty(), "and the two answers agree on all 400: " + differ);
	}

	static void theParseFindsWhatItFoundBefore() throws Exception {
		System.out.println("--- an archive's parse finds the same entries, flags and cut");
		byte[] longPlain = text(3000, 1);
		byte[] longStream = LZ11.compress(longPlain);
		byte[] raw11 = new byte[90];
		new Random(7).nextBytes(raw11);
		raw11[0] = 0x11;
		raw11[1] = (byte) 0xFF;
		raw11[2] = (byte) 0xFF;
		raw11[3] = (byte) 0x7F;
		byte[] plain = "plain bytes".getBytes(StandardCharsets.US_ASCII);
		byte[] shortPlain = text(40, 2);
		byte[] shortStream = LZ11.compress(shortPlain);
		File f = new File(Scratch.dir("ctrmap_garcprefix"), "a.garc");
		HandedGameTest.writeGarc(f, longStream, raw11, plain, shortStream);
		GARC g = new GARC(f);
		check(longStream.length > 64 && shortStream.length < 64, "control: one stream longer than the bound and one shorter ("
				+ longStream.length + ", " + shortStream.length + " bytes)");
		check(g.length == 4 && g.isEntryCompressed(0) && !g.isEntryCompressed(1) && !g.isEntryCompressed(2) && g.isEntryCompressed(3),
				"each entry is what it is: stream, raw record that starts 0x11, plain, short stream");
		check(Arrays.equals(g.getDecompressedEntry(0), longPlain) && Arrays.equals(g.getDecompressedEntry(1), raw11)
				&& Arrays.equals(g.getDecompressedEntry(3), shortPlain), "and each reads back whole");
		check(g.readableEntries == -1, "a whole archive is not a cut one");

		byte[] all = Files.readAllBytes(f.toPath());
		int cutAt = all.length - 8;   //inside the last entry
		File cut = new File(f.getParentFile(), "cut.garc");
		Files.write(cut.toPath(), Arrays.copyOf(all, cutAt));
		GARC c = new GARC(cut);
		check(c.readableEntries == 3 && c.length == 4, "an archive cut inside its last entry reads the three before the cut and"
				+ " records the cut, as the whole read found it: " + c.readableEntries + " of " + c.length);

		//PAST THE FIRST BYTES: the cut is where only the whole read used to reach
		File deep = new File(f.getParentFile(), "deep.garc");
		HandedGameTest.writeGarc(deep, plain, longStream);
		byte[] d = Files.readAllBytes(deep.toPath());
		Files.write(deep.toPath(), Arrays.copyOf(d, d.length - 8));
		GARC dc = new GARC(deep);
		check(dc.readableEntries == 1 && dc.length == 2, "and one cut beyond the first 64 bytes of a long entry is a cut too: "
				+ dc.readableEntries + " of " + dc.length);
	}

	/** This process's bytes read so far, or -1 when the system will not say. */
	static long bytesRead() {
		try {
			for (String line : Files.readAllLines(new File("/proc/self/io").toPath())) {
				if (line.startsWith("rchar:")) {
					return Long.parseLong(line.substring(6).trim());
				}
			}
		} catch (Exception notThere) {
			//not Linux
		}
		return -1;
	}

	static void openingAnArchiveReadsItsPrefixes() throws Exception {
		System.out.println("--- opening an archive reads its entries' first bytes, not the entries (F055)");
		if (bytesRead() < 0) {
			Skip.section("this system does not count a process's reads (/proc/self/io) - UNKNOWN, not passed");
			return;
		}
		File dir = Scratch.dir("ctrmap_garcprefix_big");
		byte[][] entries = new byte[8][];
		Random r = new Random(9);
		for (int i = 0; i < entries.length; i++) {
			entries[i] = new byte[2 << 20];
			r.nextBytes(entries[i]);
		}
		File big = new File(dir, "big.garc");
		HandedGameTest.writeGarc(big, entries);
		File probe = new File(dir, "probe.bin");
		Files.write(probe.toPath(), entries[0]);
		long a = bytesRead();
		Files.readAllBytes(probe.toPath());
		long readWhole = bytesRead() - a;
		check(readWhole >= (2 << 20), "control: reading a 2 MB file whole counts " + readWhole + " bytes");
		a = bytesRead();
		GARC g = new GARC(big);
		long parse = bytesRead() - a;
		check(g.length == 8 && parse < (256 << 10), "and opening a " + (big.length() >> 20) + " MB archive of 8 entries reads "
				+ parse + " bytes, not the entries");
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
