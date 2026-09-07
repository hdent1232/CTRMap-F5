package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchTexturePack;
import ctrmap.formats.h3d.PICACommandReader.TextureFormat;
import ctrmap.formats.h3d.texturing.TextureCodec;
import ctrmap.formats.propdata.PropDatabase;
import java.io.File;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The texture decoder, one texel at a time and then over the whole corpus.
 *
 * <p>WHY. {@code TextureCodec.decode} was one 290-line switch whose twelve
 * cases each carried a private copy of the same 8x8 Z-order tile walk, with
 * the four lines that actually differ between formats buried in the middle.
 * It is now one walk and twelve converters, and a converter is something a
 * suite can hand a single texel. Nothing exercised the decoder directly before
 * this - it was reached only through the 3D view and the terrain previews.
 *
 * <p>Three sections:
 * <ol>
 * <li><b>Every converter on one texel.</b> A solid 8x8 tile per format, with
 *     the expected BGRA worked out by hand from the format's bit layout. This
 *     is where a wrong shift or a dropped replicate shows up, by name.</li>
 * <li><b>The tile walk.</b> Texel p carries the value p; the decoder must put
 *     it where the PICA200's Z-order tiling says, and the output is turned 180
 *     degrees (it always was - the renderer expects it), so the checks read
 *     from the far corner.</li>
 * <li><b>The corpus decodes as it always has.</b> Every texture in every area
 *     texture pack, decoded and digested per format, against digests recorded
 *     from the decoder BEFORE it was restructured. This is the proof the split
 *     changed nothing; a deliberate change to a format's decoding will fail it
 *     and must re-record the digest, which is the point.</li>
 * </ol>
 *
 * <p>Six of the fourteen formats (rgba8, hilo8, a8, la4, l4, a4) appear in no
 * retail area pack, so for those the first section is the only guard.
 *
 * Usage: java ctrmap.tests.TextureCodecTest &lt;path-to-a014-garc&gt;
 */
public class TextureCodecTest {

	static int fails = 0;

	static void check(boolean cond, String msg) {
		if (cond) {
			System.out.println("  ok: " + msg);
		} else {
			System.out.println("  FAIL: " + msg);
			fails++;
		}
	}

	public static void main(String[] args) throws Exception {
		everyConverterOnOneTexel();
		theTileWalkPutsTexelsWhereTheHardwareDoes();
		aTextureNarrowerThanATileDecodesToNothing();
		theCorpusDecodesAsItAlwaysHas(args.length > 0 ? new File(args[0]) : null);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A solid 8x8 tile of one stored texel. */
	static byte[] solid(byte[] texel) {
		byte[] data = new byte[64 * texel.length];
		for (int i = 0; i < 64; i++) {
			System.arraycopy(texel, 0, data, i * texel.length, texel.length);
		}
		return data;
	}

	static byte[] bytes(int... v) {
		byte[] b = new byte[v.length];
		for (int i = 0; i < v.length; i++) {
			b[i] = (byte) v[i];
		}
		return b;
	}

	/** The decoded texel at (x, y) of an image {@code w} wide, as 4 bytes. */
	static byte[] texel(byte[] img, int w, int x, int y) {
		return Arrays.copyOfRange(img, (y * w + x) * 4, (y * w + x) * 4 + 4);
	}

	static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for (byte x : b) {
			sb.append(String.format("%02x", x));
		}
		return sb.toString();
	}

	/** Decodes a solid tile and demands every one of the 64 texels equals {@code expect}. */
	static void solidIs(TextureFormat f, byte[] storedTexel, byte[] expect, String why) {
		byte[] out = TextureCodec.decode(solid(storedTexel), 8, 8, f);
		boolean all = out.length == 256;
		String first = "";
		for (int i = 0; all && i < 64; i++) {
			byte[] t = texel(out, 8, i % 8, i / 8);
			if (!Arrays.equals(t, expect)) {
				all = false;
				first = " (texel " + i + " is " + hex(t) + ")";
			}
		}
		check(all, f + " " + hex(storedTexel) + " -> BGRA " + hex(expect) + ": " + why + first);
	}

	static void everyConverterOnOneTexel() {
		System.out.println("--- every converter, on one texel");
		solidIs(TextureFormat.rgba8, bytes(0x11, 0x22, 0x33, 0x44), bytes(0x44, 0x33, 0x22, 0x11),
				"stored A,B,G,R comes out B,G,R,A");
		solidIs(TextureFormat.rgb8, bytes(0x11, 0x22, 0x33), bytes(0x33, 0x22, 0x11, 0xff),
				"stored R,G,B, opaque");
		solidIs(TextureFormat.rgba5551, bytes(0xFF, 0xFF), bytes(0xff, 0xff, 0xff, 0xff), "all ones is white");
		solidIs(TextureFormat.rgba5551, bytes(0x3F, 0x00), bytes(0x00, 0x00, 0xff, 0xff),
				"bit 0 is alpha, bits 1-5 are red");
		solidIs(TextureFormat.rgba5551, bytes(0x21, 0x00), bytes(0x00, 0x00, 0x84, 0xff),
				"5-bit 0b10000 replicates its top bits into the low ones: 0x84, not 0x80");
		solidIs(TextureFormat.rgba5551, bytes(0x00, 0x00), bytes(0, 0, 0, 0), "zero is transparent black");
		solidIs(TextureFormat.rgb565, bytes(0xFF, 0xFF), bytes(0xff, 0xff, 0xff, 0xff), "all ones is white");
		solidIs(TextureFormat.rgb565, bytes(0x1F, 0x00), bytes(0x00, 0x00, 0xff, 0xff), "low 5 bits are red");
		solidIs(TextureFormat.rgb565, bytes(0xE0, 0x07), bytes(0x00, 0xff, 0x00, 0xff), "middle 6 bits are green");
		solidIs(TextureFormat.rgb565, bytes(0x00, 0xF8), bytes(0xff, 0x00, 0x00, 0xff), "top 5 bits are blue");
		solidIs(TextureFormat.rgb565, bytes(0x00, 0x04), bytes(0x00, 0x82, 0x00, 0xff),
				"6-bit 0b100000 replicates two bits: 0x82");
		solidIs(TextureFormat.rgb565, bytes(0x10, 0x00), bytes(0x00, 0x00, 0x84, 0xff),
				"5-bit 0b10000 replicates three bits: 0x84, not 0x88");
		solidIs(TextureFormat.rgba4, bytes(0x34, 0x12), bytes(0x11, 0x22, 0x33, 0x44),
				"nibbles a,r,g,b from the low end, each doubled to 8 bits");
		solidIs(TextureFormat.la8, bytes(0x5A, 0x80), bytes(0x5a, 0x5a, 0x5a, 0x80), "luminance then alpha");
		solidIs(TextureFormat.hilo8, bytes(0x5A, 0x80), bytes(0x5a, 0x5a, 0x5a, 0x80), "read exactly as la8");
		solidIs(TextureFormat.l8, bytes(0x5A), bytes(0x5a, 0x5a, 0x5a, 0xff), "luminance, opaque");
		solidIs(TextureFormat.a8, bytes(0x5A), bytes(0xff, 0xff, 0xff, 0x5a), "alpha over white");
		solidIs(TextureFormat.la4, bytes(0x7C), bytes(0x07, 0x07, 0x07, 0x0c),
				"high nibble luminance, low nibble alpha, neither widened to 8 bits");
		solidIs(TextureFormat.la4, bytes(0xF0), bytes(0xff, 0xff, 0xff, 0x00),
				"and a high nibble of 8..F sign-extends (recorded behaviour of the port, not endorsed)");
		solidIs(TextureFormat.l4, bytes(0x55, 0x55, 0x55, 0x55), bytes(0x55, 0x55, 0x55, 0xff),
				"a 4-bit luminance doubled to 8, opaque");
		solidIs(TextureFormat.a4, bytes(0x55, 0x55, 0x55, 0x55), bytes(0xff, 0xff, 0xff, 0x55),
				"a 4-bit alpha doubled to 8, over white");

		//the two nibble formats: low nibble is the first texel, high nibble the second
		byte[] nib = new byte[32];
		Arrays.fill(nib, (byte) 0xA5);
		byte[] out = TextureCodec.decode(nib, 8, 8, TextureFormat.l4);
		check(Arrays.equals(texel(out, 8, 7, 7), bytes(0x55, 0x55, 0x55, 0xff))
				&& Arrays.equals(texel(out, 8, 6, 7), bytes(0xaa, 0xaa, 0xaa, 0xff)),
				"l4 0xA5: texel 0 takes the LOW nibble (0x55), texel 1 the high (0xAA) - got "
				+ hex(texel(out, 8, 7, 7)) + " / " + hex(texel(out, 8, 6, 7)));
		out = TextureCodec.decode(nib, 8, 8, TextureFormat.a4);
		check(texel(out, 8, 7, 7)[3] == (byte) 0x55 && texel(out, 8, 6, 7)[3] == (byte) 0xaa,
				"a4 0xA5: same nibble order for alpha");
	}

	/**
	 * The PICA200 stores an 8x8 tile in Z-order (Morton order): texel 1 is
	 * (1,0), texel 2 is (0,1), texel 16 is (4,0), texel 32 is (0,4), texel 63
	 * is (7,7). The decoder then turns the image 180 degrees, so stored (x,y)
	 * is read back at (w-1-x, h-1-y).
	 */
	static void theTileWalkPutsTexelsWhereTheHardwareDoes() {
		System.out.println("--- the tile walk");
		byte[] data = new byte[64 * 4];
		for (int p = 0; p < 64; p++) {
			Arrays.fill(data, p * 4, p * 4 + 4, (byte) p);
		}
		byte[] out = TextureCodec.decode(data, 8, 8, TextureFormat.rgba8);
		check(texel(out, 8, 7, 7)[0] == 0, "texel 0 is (0,0) stored, read at (7,7)");
		check(texel(out, 8, 6, 7)[0] == 1, "texel 1 is (1,0), read at (6,7)");
		check(texel(out, 8, 7, 6)[0] == 2, "texel 2 is (0,1), read at (7,6)");
		check(texel(out, 8, 3, 7)[0] == 16, "texel 16 is (4,0), read at (3,7)");
		check(texel(out, 8, 7, 3)[0] == 32, "texel 32 is (0,4), read at (7,3)");
		check(texel(out, 8, 0, 0)[0] == 63, "texel 63 is (7,7), read at (0,0)");
		//every value appears exactly once: a walk that lands two texels on one
		//spot or skips one is a different kind of wrong from a wrong swizzle
		boolean[] seen = new boolean[64];
		boolean once = true;
		for (int i = 0; i < 64; i++) {
			int v = out[i * 4] & 0xFF;
			once &= v < 64 && !seen[v];
			seen[v] = true;
		}
		check(once, "all 64 texels land on 64 distinct spots");

		//a second tile: the walk goes left to right across tiles before it goes down
		byte[] wide = new byte[128 * 4];
		for (int p = 0; p < 128; p++) {
			Arrays.fill(wide, p * 4, p * 4 + 4, (byte) p);
		}
		out = TextureCodec.decode(wide, 16, 8, TextureFormat.rgba8);
		check(texel(out, 16, 7, 7)[0] == 64, "16x8: texel 64 starts the second tile at stored (8,0), read at (7,7)");
		check(texel(out, 16, 15, 7)[0] == 0, "and texel 0 is still stored (0,0), now read at (15,7)");
		byte[] tall = new byte[128 * 4];
		for (int p = 0; p < 128; p++) {
			Arrays.fill(tall, p * 4, p * 4 + 4, (byte) p);
		}
		out = TextureCodec.decode(tall, 8, 16, TextureFormat.rgba8);
		check(texel(out, 8, 7, 7)[0] == 64, "8x16: texel 64 starts the second tile at stored (0,8), read at (7,7)");
	}

	static void aTextureNarrowerThanATileDecodesToNothing() {
		System.out.println("--- a texture narrower than one tile");
		byte[] out = TextureCodec.decode(new byte[64], 4, 4, TextureFormat.rgba8);
		boolean zero = out.length == 64;
		for (byte b : out) {
			zero &= b == 0;
		}
		check(zero, "4x4 has no whole tile: 64 bytes of transparent black, no exception");
	}

	/**
	 * Recorded from the decoder as it stood before the restructure (commit
	 * 705ba17), over every texture of every area texture pack in a/0/1/4, in
	 * archive order, digested per format. Re-record ONLY for a deliberate
	 * change to how a format decodes, and say so in the commit.
	 */
	static final Map<String, String> RECORDED = new TreeMap<>();

	static {
		RECORDED.put("etc1", "3b4079f87f961ae8bd430be58f69f61c1d6f7e4e08bd76268d052664e310d059");
		RECORDED.put("etc1a4", "00756c42e8b32d5acbcdfb5638c4ff04c222274ae40a841583bbefb39a901c9c");
		RECORDED.put("l8", "fc62e1a053ba56b5ff6334e12b8162ada057b944929204cd3f0548816715c1ef");
		RECORDED.put("la8", "943eee51ba4fd6b3c35317215d5be2786fc4d07b73f0b408a656f9534c73c121");
		RECORDED.put("rgb565", "61c84050d75e8c762f6272350f742f8f6e908110da418a181ed3ffb9238a5690");
		RECORDED.put("rgb8", "7fbb6f4523bec74730983567cdc39bb049091f5ed628390b4e5a407920d66fcf");
		RECORDED.put("rgba4", "c05e3d49722f4451f93d7c8f9c3d9bcccbed236b14c3f5f1b613853c2d63f65b");
		RECORDED.put("rgba5551", "09728b2853eb2bb1943dc3ad7ba54ab06eeff1cd27e2836a4cf4870a8a2cd51a");
	}

	static void theCorpusDecodesAsItAlwaysHas(File a014) throws Exception {
		System.out.println("--- the corpus decodes as it always has");
		if (a014 == null || !a014.isFile()) {
			System.out.println("  skip: no a/0/1/4 GARC at " + a014);
			return;
		}
		GARC ad = new GARC(a014);
		Map<String, MessageDigest> md = new TreeMap<>();
		Map<String, Integer> count = new TreeMap<>();
		int packs = 0;
		for (int area = 0; area < ad.length; area++) {
			byte[] cont = ad.getDecompressedEntry(area);
			if (cont == null || cont.length < 8 || cont[0] != 'A' || cont[1] != 'D') {
				continue;
			}
			byte[] p = PropDatabase.getSubfile(cont, 1);
			if (!BchTexturePack.isTexturePack(p)) {
				continue;
			}
			packs++;
			List<BchTexturePack.Texture> texes = BchTexturePack.parse(p);
			for (BchTexturePack.Texture t : texes) {
				TextureFormat f = TextureFormat.values()[t.format];
				byte[] out = TextureCodec.decode(t.data, t.getWidth(), t.getHeight(), f);
				MessageDigest d = md.get(f.name());
				if (d == null) {
					d = MessageDigest.getInstance("SHA-256");
					md.put(f.name(), d);
				}
				d.update(out);
				count.merge(f.name(), 1, Integer::sum);
			}
		}
		check(packs > 200, packs + " area texture packs decoded");
		check(md.keySet().equals(RECORDED.keySet()), "the corpus still uses exactly the recorded formats "
				+ RECORDED.keySet() + " (found " + md.keySet() + ")");
		for (String f : RECORDED.keySet()) {
			MessageDigest d = md.get(f);
			String got = d == null ? "absent" : hex(d.digest());
			check(RECORDED.get(f).equals(got), f + ": " + count.getOrDefault(f, 0)
					+ " textures decode byte-for-byte as before (" + got.substring(0, 12) + "...)");
		}
	}
}
