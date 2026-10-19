package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.garc.LZ11;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
		aPackFromAFolderThatIsNotThereIsRefused();
		anInPlacePackRefusesWhatItCannotWriteOver();
		anEntryThatWritesAnotherLengthIsRefused();
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
			boolean whole = LZ11.prefixDecodes(stored, 64, (stored).length);
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

	/**
	 * A pack whose staging folder is not there is refused, naming it - never written as the archive it already was.
	 *
	 * <p>Measured by the 2026-10-14 shared sweep (GARC 383, dropped, SURVIVED): no pack had ever been handed a folder
	 * that is not there. Dropped, the pack went on to read its staged files out of nothing.
	 */
	static void aPackFromAFolderThatIsNotThereIsRefused() throws Exception {
		System.out.println("--- a pack whose staging folder is not there is refused, naming it");
		File dir = Scratch.dir("ctrmap_garcpack_gone");
		File f = new File(dir, "a.garc");
		HandedGameTest.writeGarc(f, "one".getBytes(StandardCharsets.US_ASCII), "two".getBytes(StandardCharsets.US_ASCII));
		GARC g = new GARC(f);
		File staged = new File(dir, "staged");
		staged.mkdirs();
		File out = new File(dir, "a.garc_new");
		g.prepareRepack(staged, file -> true, null, out);
		check(out.isFile(), "control: a pack from a staging folder that is there writes its archive");
		out.delete();
		File gone = new File(dir, "gone");
		String said;
		try {
			g.prepareRepack(gone, file -> true, null, out);
			said = out.isFile() ? "it wrote " + out.length() + " bytes" : "it wrote nothing and said nothing";
		} catch (IOException refused) {
			said = refused.getMessage();
		} catch (RuntimeException other) {
			said = "it failed some other way: " + other;
		}
		check(said.equals(gone + " is not there, so nothing staged in it can be packed into a.garc.") && !out.exists(),
				"a pack from a staging folder that is not there is refused, naming it, and nothing is written: " + said);
	}

	/**
	 * An in-place pack refuses, in its own words, every staged file it cannot write over its own entry: one naming no
	 * entry, one past the archive's last, one over an entry stored compressed, and one of another length.
	 *
	 * <p>Measured by the 2026-10-14 shared sweep (GARC 422, dropped, SURVIVED): none of the in-place pack's refusals was
	 * asked by its words, so a refusal dropped was answered by the next one down, or by an index error.
	 */
	static void anInPlacePackRefusesWhatItCannotWriteOver() throws Exception {
		System.out.println("--- an in-place pack refuses, in its own words, what it cannot write over its own entry");
		File dir = Scratch.dir("ctrmap_garc_inplace");
		File f = new File(dir, "items.garc");
		HandedGameTest.writeGarc(f, "abcd".getBytes(StandardCharsets.US_ASCII), "efgh".getBytes(StandardCharsets.US_ASCII),
				LZ11.compress(text(3000, 3)));
		GARC g = new GARC(f);
		check(!g.isEntryCompressed(1) && g.isEntryCompressed(2), "control: entry 1 is stored plain and entry 2 compressed");
		String wrote = inPlace(g, dir, "ok", "1", "WXYZ".getBytes(StandardCharsets.US_ASCII));
		check(wrote.equals("it wrote [87, 88, 89, 90] over entry 1"),
				"control: a staged entry of its own length is written over its own bytes: " + wrote);
		String none = inPlace(g, dir, "none", "notes.txt", new byte[4]);
		check(none.endsWith(" but names no entry of it."), "a staged file naming no entry is refused: " + none);
		String past = inPlace(g, dir, "past", "3", new byte[4]);
		check(past.startsWith("entry 3 is staged for ") && past.endsWith(", which holds 3; an archive written in place does"
				+ " not grow."), "a staged entry past the archive's last is refused: " + past);
		String packed = inPlace(g, dir, "packed", "2", new byte[4]);
		check(packed.contains("is stored compressed, so writing its bytes in place would corrupt it"),
				"a staged entry over one stored compressed is refused: " + packed);
		String longer = inPlace(g, dir, "longer", "1", new byte[5]);
		check(longer.contains("is stored as 4 bytes and staged as 5"),
				"a staged entry of another length is refused: " + longer);
	}

	/** What an in-place pack of one staged file said: its refusal, or what it wrote over that entry. */
	static String inPlace(GARC g, File dir, String name, String file, byte[] bytes) throws Exception {
		File staged = new File(dir, "staged-" + name);
		staged.mkdirs();
		Files.write(new File(staged, file).toPath(), bytes);
		File out = new File(dir, "items-" + name + ".garc");
		try {
			g.prepareInPlace(staged, edit -> true, out);
		} catch (IOException refused) {
			return out.exists() ? "it refused AND wrote " + out.length() + " bytes: " + refused.getMessage()
					: refused.getMessage();
		} catch (RuntimeException other) {
			return "it failed some other way: " + other;
		}
		if (!out.isFile()) {
			return "it wrote nothing and said nothing";
		}
		try {
			return "it wrote " + Arrays.toString(new GARC(out).getDecompressedEntry(Integer.parseInt(file))) + " over entry "
					+ file;
		} catch (RuntimeException unreadable) {
			return "it wrote a file that does not read back: " + unreadable;
		}
	}

	/**
	 * The archive writer refuses an entry that writes another number of bytes than its FATB record declares, naming it.
	 *
	 * <p>Measured by the 2026-10-14 shared sweep (GarcWriter 148, dropped, SURVIVED): both of the writer's callers hand
	 * it entries that agree by construction, so nothing had ever asked. Dropped, an entry 1 byte short would leave
	 * every entry after it 1 byte from where its record says it starts. Asked through reflection, as the writer is the
	 * format package's own.
	 */
	static void anEntryThatWritesAnotherLengthIsRefused() throws Exception {
		System.out.println("--- the archive writer refuses an entry that writes another length than it declares");
		Class<?> writer = Class.forName("ctrmap.formats.garc.GarcWriter");
		Class<?> entries = Class.forName("ctrmap.formats.garc.GarcWriter$Entries");
		Class<?> template = Class.forName("ctrmap.formats.garc.GarcWriter$Template");
		Method read = template.getDeclaredMethod("read", RandomAccessFile.class);
		read.setAccessible(true);
		Method write = writer.getDeclaredMethod("write", RandomAccessFile.class, template, entries, int.class);
		write.setAccessible(true);
		File dir = Scratch.dir("ctrmap_garcwriter");
		File src = new File(dir, "a.garc");
		HandedGameTest.writeGarc(src, "abcd".getBytes(StandardCharsets.US_ASCII));
		Object t;
		try (RandomAccessFile in = new RandomAccessFile(src, "r")) {
			t = read.invoke(null, in);
		}
		String honest = writeOneEntry(write, t, entries, new File(dir, "honest.garc"), 4);
		check(honest.equals("it wrote the archive"), "control: an entry that writes the 4 bytes it declares is written: "
				+ honest);
		String shortOne = writeOneEntry(write, t, entries, new File(dir, "short.garc"), 3);
		check(shortOne.equals("entry 0 wrote 3 bytes, not the 4 its FATB record says"),
				"an entry that writes 3 of the 4 bytes it declares is refused, naming it: " + shortOne);
	}

	/** What the writer said of 1 entry that declares 4 bytes and writes {@code writes}: its refusal, or that it wrote. */
	static String writeOneEntry(Method write, Object template, Class<?> entries, File out, int writes) {
		Object e = Proxy.newProxyInstance(entries.getClassLoader(), new Class<?>[]{entries}, (proxy, m, a) -> {
			switch (m.getName()) {
				case "count":
				case "flags":
					return 1;
				case "length":
					return 4;
				case "fresh":
					return true;
				case "write":
					((RandomAccessFile) a[1]).write(new byte[writes]);
					return null;
				default:
					throw new UnsupportedOperationException(m.getName());
			}
		});
		try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
			write.invoke(null, raf, template, e, 4);
			return "it wrote the archive";
		} catch (InvocationTargetException thrown) {
			return String.valueOf(thrown.getCause().getMessage());
		} catch (Exception other) {
			return "it failed some other way: " + other;
		}
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
