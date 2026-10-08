package ctrmap.tests;

import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BchSections;
import ctrmap.formats.h3d.BchTexturePack;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every BCH reader reads the header and the relocation table through one reader, and still makes
 * of a file what it made of it before (F066).
 *
 * <p>WHY THIS SUITE EXISTS. Five classes read the BCH header themselves and four decoded the
 * relocation table themselves; the area animation placed every relocation as if it came from the
 * contents section, so a command-section entry was recorded at a contents address - on the texture
 * pack below, on top of a real contents entry, overwriting its flag. Before the readers were merged,
 * what each made of two dump-free files was recorded (PINS); every reader but the area animation
 * must still make exactly that of them, and the area animation must now record exactly the
 * contents relocations and the command relocations, each where it is.
 *
 * <p>ORDER: needs no dump. The subjects are the bundled DummyBCH3DModel.bch and a two-texture pack
 * BchTexturePack writes; their digests are recorded with the pins, so a changed writer reads as a
 * changed subject, not as a broken reader.
 *
 * Usage: java ctrmap.tests.BchSectionsTest
 */
public class BchSectionsTest {

	static int fails = 0;

	/** SHA-256 of the two subjects the pins were taken against, measured 2026-10-07. */
	static final String DUMMY_SHA = "84ae2a8c4d2752d70fcc85f7fdbce236db86755028d8ac40df91a4bc05d89821";
	static final String PACK_SHA = "c0e924d2244fe1d03da02339df981b9312c7245ee6e018afa54261b9798f5f6c";

	/**
	 * Measured 2026-10-07 by a scratch probe over the readers as they stood before F066 (the working
	 * tree over a93ef21): the first 16 hex digits of the SHA-256 of each reader's line, built exactly
	 * as {@link #describe} builds it. WorldAnim's two are the corrected decode's, taken after the
	 * merge, when the probe showed every other line unchanged.
	 */
	static final String[][] PINS = {
		{"dummy", "BchMapModel header", "9818d7d5db7f358a"},
		{"dummy", "BchMapModel ptrLocToTargetBase", "4cc9452441f3f952"},
		{"dummy", "BchMapModel cmdRelocs", "d0feb3f97b151fe8"},
		{"dummy", "BchMapModel vtxBuffers", "384e054c9c75705f"},
		{"dummy", "BchMapModel idxBuffers", "873b04acd3770903"},
		{"dummy", "BchModelAppender.decode", "50aebf4d45bd7277"},
		{"dummy", "WorldAnim relocMap", "5bbe1462b2b638a6"},
		{"dummy", "BCHFile header", "c4e058d8f9be5463"},
		{"dummy", "BCHFile buf hash", "3f63ca06d9087284"},
		{"dummy", "BchTexturePack.isTexturePack", "2692a0af9f0693e2"},
		{"dummy", "BchTexturePack.parse", "99d19c69b02e9a3e"},
		{"pack", "BchMapModel header", "0f31a1a826c63e16"},
		{"pack", "BchMapModel ptrLocToTargetBase", "9b63805e6aeb1576"},
		{"pack", "BchMapModel cmdRelocs", "8108c637f88b03dc"},
		{"pack", "BchMapModel vtxBuffers", "8f2ac0fc5b636a78"},
		{"pack", "BchMapModel idxBuffers", "b14633bf9a73d787"},
		{"pack", "BchModelAppender.decode", "46f9789913a88a59"},
		{"pack", "WorldAnim relocMap", "0496f869d436c21c"},
		{"pack", "BCHFile header", "8e2f900b177d61c7"},
		{"pack", "BCHFile buf hash", "996de340845a0418"},
		{"pack", "BchTexturePack.isTexturePack", "cb3e12ac91218e98"},
		{"pack", "BchTexturePack.parse", "5e23a7d9eff36236"},
	};

	/** One section of the suite. */
	interface Section {

		void run() throws Exception;
	}

	/**
	 * Runs a section so that one that throws is a failure said in words and the rest still run: a
	 * suite that dies at its first exception reports nothing about the checks after it.
	 */
	static void section(String name, Section s) {
		try {
			s.run();
		} catch (Throwable t) {
			check(false, name + " threw instead of answering: " + t);
		}
	}

	public static void main(String[] args) throws Exception {
		byte[] dummy = resource("ctrmap/resources/DummyBCH3DModel.bch");
		byte[] pack;
		String packSaid;
		try {
			pack = texturePack();
			packSaid = sha(pack).substring(0, 16);
		} catch (RuntimeException writerThrew) {
			pack = null;
			packSaid = "the writer threw: " + writerThrew;
		}
		boolean subjects = pack != null && sha(dummy).equals(DUMMY_SHA) && sha(pack).equals(PACK_SHA);
		check(subjects, "the subjects are the ones the pins were taken against (dummy " + sha(dummy).substring(0, 16)
				+ ", pack " + packSaid + ")" + (subjects ? "" : " - a changed subject is"
				+ " UNKNOWN to the pins, not a broken reader: re-take them with the probe the record names"));
		if (subjects) {
			final byte[] d = dummy, p = pack;
			section("the pinned readers", () -> everyReaderMakesOfThemWhatItDid(d, p));
			section("the area animation's relocations", () -> theAreaAnimationRecordsEachRelocationWhereItIs(d, p));
		}
		section("the header layouts", BchSectionsTest::theHeaderIsReadForEveryLayout);
		section("the refusals", BchSectionsTest::whatIsNotABchIsRefused);
		section("the string readers", BchSectionsTest::aStringNothingEndsIsRefused);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static void everyReaderMakesOfThemWhatItDid(byte[] dummy, byte[] pack) throws Exception {
		System.out.println("--- every reader makes of the two files what it made of them before (F066)");
		Map<String, Map<String, String>> lines = new LinkedHashMap<>();
		lines.put("dummy", describe(dummy));
		lines.put("pack", describe(pack));
		for (String[] pin : PINS) {
			String line = lines.get(pin[0]).get(pin[1]);
			String got = line == null ? "(no such line)" : sha(line.getBytes("UTF-8")).substring(0, 16);
			check(got.equals(pin[2]), pin[0] + " / " + pin[1] + (got.equals(pin[2]) ? "" : ": " + line));
		}
	}

	static void theAreaAnimationRecordsEachRelocationWhereItIs(byte[] dummy, byte[] pack) throws Exception {
		System.out.println("--- the area animation records each relocation in its own section (F066)");
		for (byte[] b : new byte[][]{dummy, pack}) {
			BchMapModel m = new BchMapModel(b);
			java.util.Set<Integer> expected = new java.util.TreeSet<>(((Map<Integer, Integer>) get(m, "ptrLocToTargetBase")).keySet());
			for (Object cr : (List<?>) get(m, "cmdRelocs")) {
				expected.add(((int[]) cr)[1]);
			}
			Object wa = Class.forName("ctrmap.formats.area.WorldAnim").getConstructor(byte[].class).newInstance((Object) b);
			java.util.Set<Integer> got = new java.util.TreeSet<>(((Map<Integer, Integer>) get(wa, "relocMap")).keySet());
			check(got.equals(expected), b.length + "-byte file: its locations are the contents relocations and the"
					+ " command relocations, " + expected.size() + " of them" + (got.equals(expected) ? "" : " - got " + got));
		}
	}

	/** A hand-made header for {@code bc}: every offset and length a distinct number. */
	static byte[] header(int bc) {
		byte[] b = new byte[0x60];
		b[0] = 'B';
		b[1] = 'C';
		b[2] = 'H';
		b[4] = (byte) bc;
		b[5] = (byte) bc;
		for (int o = 8, v = 0x10; o + 4 <= 0x48; o += 4, v += 4) {
			MapResizeTest.putI32(b, o, v);
		}
		return b;
	}

	static void theHeaderIsReadForEveryLayout() {
		System.out.println("--- the header is read for the layout its compatibility byte declares");
		//0x21: contents 8, strings 12, commands 16, raw 20, rawExt 24, reloc 28 | lengths 32..52
		BchSections s = BchSections.read(withReloc(header(0x21), 28, 52));
		check(s.contentsAddr == 0x10 && s.rawExtAddr == 0x20 && s.contentsLen == 0x28 && s.rawExtLen == 0x38,
				"0x21: the raw-extension section is read (rawExt at " + s.rawExtAddr + ", length " + s.rawExtLen + ")");
		//0x20: no raw extension - reloc at 24, lengths start at 28
		BchSections o = BchSections.read(withReloc(header(0x20), 24, 44));
		check(o.rawExtAddr == 0 && o.rawExtLen == 0 && o.contentsLen == 0x24,
				"0x20: no raw-extension section, and every offset after it moves up (contents length "
				+ Integer.toHexString(o.contentsLen) + ")");
		BchSections old = BchSections.read(withReloc(header(7), 24, 44));
		check(old.flags == 0 && old.addressCount == 0, "a layout of 7 or older has no flags or address count");
	}

	/** The header with its relocation table pointed inside the file: empty, at the end. */
	static byte[] withReloc(byte[] b, int addrAt, int lenAt) {
		MapResizeTest.putI32(b, addrAt, b.length);
		MapResizeTest.putI32(b, lenAt, 0);
		return b;
	}

	static void whatIsNotABchIsRefused() {
		System.out.println("--- what is not a whole BCH is refused in words");
		check(BchSections.hasMagic(new byte[]{'B', 'C', 'H', 0}) && !BchSections.hasMagic(new byte[]{'B', 'C', 'H', 1})
				&& !BchSections.isBch(new byte[]{'B', 'C', 'H', 0, 0x21}), "four magic bytes are a sniff, not a header");
		byte[] past = header(0x21);
		MapResizeTest.putI32(past, 28, 0x50);
		MapResizeTest.putI32(past, 52, 0x40);
		String said = refusal(past);
		check(said.contains("relocation table") && said.contains("past the end"), "a relocation table past the end: " + said);
		check(refusal(new byte[]{'X', 'Y', 'Z', 0, 0x21, 0, 0, 0}).equals("not a BCH"), "and what is not a BCH says so");
	}

	static void aStringNothingEndsIsRefused() throws Exception {
		System.out.println("--- a string nothing ends is refused, by the stream reader too (F066)");
		//the stream answers -1 at its end, which is not 0: the old loop appended it forever. This
		//stream gives up after 10,000 reads past its end, so a loop that never stops fails here
		//instead of hanging the suite
		java.io.InputStream bounded = new java.io.InputStream() {
			final byte[] b = {'s', 'e', 'a'};
			int at = 0;

			@Override
			public int read() {
				if (at < b.length) {
					return b[at++];
				}
				if (++at > b.length + 10000) {
					throw new IllegalStateException("read past the end 10,000 times");
				}
				return -1;
			}
		};
		String said;
		try {
			said = "read \"" + ctrmap.formats.h3d.StringUtils.readString(new ctrmap.LittleEndianDataInputStream(bounded)) + "\"";
		} catch (java.io.IOException | RuntimeException ex) {
			said = ex.getMessage();
		}
		check(said.contains("runs off the end") && said.contains("sea"), "the stream reader says where it ran out: " + said);
		String strict;
		try {
			strict = ctrmap.formats.h3d.StringUtils.cString(new byte[]{'s', 'e', 'a'}, 0);
		} catch (IllegalArgumentException ex) {
			strict = ex.getMessage();
		}
		check(strict.contains("runs off the end"), "and so does the array reader: " + strict);
		check(ctrmap.formats.h3d.StringUtils.cString(new byte[]{'s', 'e', 'a', 0, 'x'}, 0).equals("sea"),
				"control: a string its NUL ends is read to the NUL");
	}

	static String refusal(byte[] b) {
		try {
			BchSections.read(b);
			return "read";
		} catch (IllegalArgumentException ex) {
			return ex.getMessage();
		}
	}

	// ---- the probe the pins were taken with, kept here so they mean the same thing

	static Map<String, String> describe(byte[] b) throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		try {
			BchMapModel m = new BchMapModel(b);
			StringBuilder sb = new StringBuilder("BchMapModel header:");
			for (String f : new String[]{"bc", "fc", "converterVersion", "contentsAddr", "stringsAddr", "commandsAddr",
				"rawDataAddr", "rawExtAddr", "relocAddr", "contentsLen", "stringsLen", "commandsLen", "rawDataLen",
				"rawExtLen", "relocLen", "uninitData", "uninitCmd", "flagsByte", "addressCount", "baseAddrCount"}) {
				sb.append(' ').append(f).append('=').append(get(m, f));
			}
			put(out, sb.toString());
			put(out, "BchMapModel ptrLocToTargetBase: " + new TreeMap<>((Map<?, ?>) get(m, "ptrLocToTargetBase")));
			put(out, "BchMapModel cmdRelocs: " + ints((List<?>) get(m, "cmdRelocs")));
			put(out, "BchMapModel vtxBuffers: " + ints((List<?>) get(m, "vtxBuffers")));
			put(out, "BchMapModel idxBuffers: " + ints((List<?>) get(m, "idxBuffers")));
			Method decode = Class.forName("ctrmap.formats.h3d.BchModelAppender").getDeclaredMethod("decode", BchMapModel.class, byte[].class);
			decode.setAccessible(true);
			StringBuilder re = new StringBuilder("BchModelAppender.decode:");
			for (Object e : (List<?>) decode.invoke(null, m, b)) {
				re.append(" [").append(get(e, "flag")).append(',').append(get(e, "tgt")).append(',').append(get(e, "src"))
						.append(',').append(get(e, "ptrLoc")).append(',').append(get(e, "word")).append(']');
			}
			put(out, re.toString());
		} catch (RuntimeException | java.lang.reflect.InvocationTargetException ex) {
			put(out, "BchMapModel/decode: refused - " + ex);
		}
		try {
			Object wa = Class.forName("ctrmap.formats.area.WorldAnim").getConstructor(byte[].class).newInstance((Object) b);
			put(out, "WorldAnim relocMap: " + new TreeMap<>((Map<?, ?>) get(wa, "relocMap")));
		} catch (java.lang.reflect.InvocationTargetException ex) {
			put(out, "WorldAnim: refused - " + ex.getCause());
		}
		Object bf = Class.forName("ctrmap.formats.h3d.BCHFile").getConstructor(byte[].class).newInstance((Object) b);
		Object h = get(bf, "header");
		StringBuilder hs = new StringBuilder("BCHFile header:");
		for (Field f : h.getClass().getDeclaredFields()) {
			f.setAccessible(true);
			hs.append(' ').append(f.getName()).append('=').append(f.get(h));
		}
		put(out, hs.toString());
		put(out, "BCHFile buf hash: " + java.util.Arrays.hashCode((byte[]) get(bf, "buf")) + " models "
				+ ((List<?>) get(bf, "models")).size() + " textures " + ((List<?>) get(bf, "textures")).size()
				+ " errorlevel " + get(bf, "errorlevel"));
		put(out, "BchTexturePack.isTexturePack: " + BchTexturePack.isTexturePack(b));
		try {
			StringBuilder t = new StringBuilder("BchTexturePack.parse:");
			for (BchTexturePack.Texture x : BchTexturePack.parse(b)) {
				t.append(" [").append(x.name).append(',').append(x.format).append(',').append(x.dimParam).append(',')
						.append(java.util.Arrays.hashCode(x.data)).append(']');
			}
			put(out, t.toString());
		} catch (RuntimeException ex) {
			put(out, "BchTexturePack.parse: refused - " + ex);
		}
		return out;
	}

	static void put(Map<String, String> out, String line) {
		out.put(line.substring(0, line.indexOf(':')), line);
	}

	static String ints(List<?> l) {
		StringBuilder sb = new StringBuilder();
		for (Object o : l) {
			sb.append(java.util.Arrays.toString((int[]) o));
		}
		return sb.toString();
	}

	static Object get(Object o, String f) throws Exception {
		for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field x = c.getDeclaredField(f);
				x.setAccessible(true);
				return x.get(o);
			} catch (NoSuchFieldException next) {
				//try the superclass
			}
		}
		throw new NoSuchFieldException(f);
	}

	static byte[] resource(String path) throws Exception {
		try (java.io.InputStream in = BchSectionsTest.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("the bundled " + path + " is not on the classpath");
			}
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			for (int n; (n = in.read(buf)) > 0;) {
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	/** Two 8x8 RGBA8 textures through the pack writer - the probe's second subject. */
	static byte[] texturePack() {
		List<BchTexturePack.Texture> texes = new ArrayList<>();
		String[] names = {"chip_grass_a", "chip_sea_b"};
		for (int i = 0; i < names.length; i++) {
			BchTexturePack.Texture t = new BchTexturePack.Texture();
			t.name = names[i];
			t.format = 0;
			t.dimParam = 8 | (8 << 16);
			t.data = new byte[8 * 8 * 4];
			for (int b = 0; b < t.data.length; b++) {
				t.data[b] = (byte) (b * (i + 3));
			}
			texes.add(t);
		}
		return BchTexturePack.emit(texes);
	}

	static String sha(byte[] b) throws Exception {
		StringBuilder sb = new StringBuilder();
		for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) {
			sb.append(String.format("%02x", x));
		}
		return sb.toString();
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
