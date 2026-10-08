package ctrmap.tests;

import ctrmap.formats.codepatch.ZoneLimitPatch;
import ctrmap.formats.codepatch.ZoneLimitPatch.Patch;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Validates the auto-scaling zone-limit patch: the master-index math, the
 * generated patch values/IPS, and - if a decompressed ORAS code.bin is present -
 * that every reverse-engineered stock word matches the real executable and the
 * patched bytes are exactly what we expect.
 *
 * Run: java -cp "build/classes;..." ctrmap.tests.ZoneLimitPatchTest [code.bin]
 */
public class ZoneLimitPatchTest {

	private static final String DEFAULT_CODE = "../code.bin";
	private static int fails = 0;

	public static void main(String[] args) throws Exception {
		// 1. master-index rounding (next multiple of 4 >= 536+N)
		checkEq(540, ZoneLimitPatch.masterIndex(1), "N=1 -> 540");
		checkEq(540, ZoneLimitPatch.masterIndex(2), "N=2 -> 540");
		checkEq(540, ZoneLimitPatch.masterIndex(4), "N=4 -> 540");
		checkEq(544, ZoneLimitPatch.masterIndex(5), "N=5 -> 544");
		checkEq(544, ZoneLimitPatch.masterIndex(8), "N=8 -> 544");
		checkEq(548, ZoneLimitPatch.masterIndex(9), "N=9 -> 548");
		checkEq(3, ZoneLimitPatch.spareZones(1), "N=1 spares=3");
		checkEq(0, ZoneLimitPatch.spareZones(4), "N=4 spares=0");
		checkEq(0, ZoneLimitPatch.spareZones(8), "N=8 spares=0");

		// 1b. growing a game a previous append grew (F039): counted from the stock base, the
		// previous append's spares included, so a second append is the same patch for a larger N
		checkEq(540, ZoneLimitPatch.masterIndexFrom(536, 1), "from stock, +1 -> 540");
		checkEq(544, ZoneLimitPatch.masterIndexFrom(540, 1), "from 540 (an append before), +1 -> 544");
		checkEq(544, ZoneLimitPatch.masterIndexFrom(540, 4), "from 540, +4 -> 544");
		checkEq(552, ZoneLimitPatch.masterIndexFrom(544, 5), "from 544, +5 -> 552");
		int grown = ZoneLimitPatch.masterIndexFrom(540, 1);
		checkEq(grown, ZoneLimitPatch.masterIndex(grown - ZoneLimitPatch.BASE_ZONES),
				"the patch for N = M - 536 names that same master index");
		String fewer = null;
		try {
			ZoneLimitPatch.masterIndexFrom(535, 1);
		} catch (IllegalArgumentException refused) {
			fewer = refused.getMessage();
		}
		check(fewer != null && fewer.contains("fewer than the stock"),
				"a game with fewer than the stock zones is refused, in words: " + fewer);
		String past = null;
		try {
			ZoneLimitPatch.masterIndexFrom(ZoneLimitPatch.BASE_ZONES + ZoneLimitPatch.MAX_NEW_ZONES - 4, 5);
		} catch (IllegalArgumentException refused) {
			past = refused.getMessage();
		}
		check(past != null && past.contains("at most"), "and a total past the limit is refused: " + past);

		// 2. patch values for N=1 (M=540): master idx 540, size 540*0x38=0x7620, EN 541, bound 540
		List<Patch> p = ZoneLimitPatch.patches(1);
		checkEq(5, p.size(), "5 patch sites");
		checkBytes(p.get(0), 0x012bc0, new byte[]{(byte) 0x87, 0x1F, (byte) 0xA0, (byte) 0xE3}, "master idx -> mov r1,#0x21C(540)");
		checkBytes(p.get(1), 0x2d9774, new byte[]{(byte) 0x87, 0x0F, 0x55, (byte) 0xE3}, "bound A -> cmp r5,#0x21C");
		checkBytes(p.get(2), 0x2d99f8, new byte[]{(byte) 0x87, 0x0F, 0x56, (byte) 0xE3}, "bound B -> cmp r6,#0x21C");
		checkBytes(p.get(3), 0x012c0c, new byte[]{0x20, 0x76, 0x00, 0x00}, "master size -> 0x7620 (540*0x38)");
		checkBytes(p.get(4), 0x00eae0, new byte[]{0x1D, 0x02, 0x00, 0x00}, "EN idx -> 541");

		// 3. IPS well-formed: 'PATCH' + 5*(3+2+4) + 'EOF' - built against an executable holding
		// the stock words, which is the only kind it may be built against now (F046)
		byte[] stock = stockCode();
		byte[] ips = ZoneLimitPatch.buildIPS(1, stock);
		check("PATCH".equals(new String(ips, 0, 5, "ASCII")), "IPS starts with PATCH");
		check("EOF".equals(new String(ips, ips.length - 3, 3, "ASCII")), "IPS ends with EOF");
		checkEq(5 + 5 * 9 + 3, ips.length, "IPS length = 53");

		// 3b. any other build is refused, naming the site - never patched blind (F046). One site
		// differing is what another build's executable looks like from here.
		final byte[] other = stock.clone();
		other[0x2d9774] ^= 0x01;
		String refused = refusal(() -> ZoneLimitPatch.buildIPS(1, other));
		check(refused != null && refused.contains("0x2D9774") && refused.contains("Alpha Sapphire"),
				"a code.bin that differs at one site is refused, naming the site and the edition: " + refused);
		check(refusal(() -> ZoneLimitPatch.applyToCode(1, other)) != null, "and applyToCode refuses it too");
		String tooShort = refusal(() -> ZoneLimitPatch.buildIPS(1, new byte[0x1000]));
		check(tooShort != null && tooShort.contains("too short"),
				"a file too short to hold the sites is refused, not read past its end: " + tooShort);

		// 3c. the dialog that hands the patch out verifies the user's executable BEFORE it stages
		// a single zone, and builds the patch from that same file
		File panel = new File("src/ctrmap/humaninterface/ZoneLoadingPanel.java");
		if (!panel.isFile()) {
			fail("the zone dialog's source is there to read: " + panel.getAbsolutePath());
		} else {
			String body = SourceSeamTest.stripComments(new String(Files.readAllBytes(panel.toPath()),
					java.nio.charset.StandardCharsets.UTF_8));
			int verified = body.indexOf("ZoneLimitPatch.verify(code)");
			int appended = body.indexOf("ZoneAppender.appendZones(realZones");
			int built = body.indexOf("ZoneLimitPatch.buildIPS(res.patchZones, code)");
			check(verified >= 0 && appended > verified && built > appended,
					"the dialog verifies code.bin, then appends, then builds the patch from it ("
					+ verified + ", " + appended + ", " + built + ")");
		}

		// 4. against the real code.bin: stock words must all match (verifier throws otherwise)
		File codeFile = new File(args.length > 0 ? args[0] : DEFAULT_CODE);
		if (codeFile.exists()) {
			byte[] code = Files.readAllBytes(codeFile.toPath());
			try {
				byte[] patched = ZoneLimitPatch.applyToCode(1, code);
				check(true, "applyToCode(N=1) verified all 5 stock words against real code.bin");
				// spot-check the patched words landed correctly
				checkEq(0x87, patched[0x012bc0] & 0xFF, "patched master-idx low byte = 0x87");
				checkEq(0x76, patched[0x012c0d] & 0xFF, "patched master-size high byte = 0x76");
				checkEq(0x1D, patched[0x00eae0] & 0xFF, "patched EN-idx low byte = 0x1D");
				// surgical: every changed byte lies inside one of the five 4-byte windows
				int diff = 0;
				for (int i = 0; i < code.length; i++) {
					if (code[i] != patched[i]) {
						diff++;
						boolean inWindow = false;
						for (Patch pp : ZoneLimitPatch.patches(1)) {
							if (i >= pp.fileOffset && i < pp.fileOffset + 4) {
								inWindow = true;
								break;
							}
						}
						check(inWindow, "changed byte 0x" + Integer.toHexString(i) + " is inside a patch window");
					}
				}
				checkEq(6, diff, "N=1 changes exactly 6 bytes (the immediates/low bytes of the 5 sites)");
			} catch (IllegalStateException ex) {
				fails++;
				System.out.println("FAILURE: real code.bin stock-word mismatch: " + ex.getMessage());
			}
		} else {
			Skip.section("code.bin not found - the real-binary verification did not run");
		}

		System.out.println();
		if (fails == 0) {
			System.out.println("PASS");
		} else {
			System.out.println("FAIL (" + fails + ")");
			System.exit(1);
		}
	}

	/** An executable holding the stock word at every site and nothing anywhere else. */
	private static byte[] stockCode() {
		byte[] code = new byte[0x2E0000];
		for (Patch p : ZoneLimitPatch.patches(1)) {
			System.arraycopy(p.original, 0, code, p.fileOffset, 4);
		}
		return code;
	}

	private interface Act {
		void run();
	}

	/** The refusal an act threw, or null when it did not refuse; any other exception is named. */
	private static String refusal(Act act) {
		try {
			act.run();
			return null;
		} catch (IllegalStateException refused) {
			return refused.getMessage();
		} catch (RuntimeException threw) {
			return "threw instead of refusing: " + threw;
		}
	}

	private static void checkBytes(Patch p, int expOff, byte[] expBytes, String what) {
		if (p.fileOffset != expOff) {
			fail(what + " (offset 0x" + Integer.toHexString(p.fileOffset) + " != 0x" + Integer.toHexString(expOff) + ")");
			return;
		}
		for (int i = 0; i < 4; i++) {
			if (p.patched[i] != expBytes[i]) {
				fail(what + " (byte " + i + " = " + (p.patched[i] & 0xFF) + " != " + (expBytes[i] & 0xFF) + ")");
				return;
			}
		}
	}

	private static void checkEq(int exp, int got, String what) {
		if (exp != got) {
			fail(what + " (got " + got + ", expected " + exp + ")");
		}
	}

	private static void check(boolean cond, String what) {
		if (!cond) {
			fail(what);
		}
	}

	private static void fail(String what) {
		System.out.println("FAILURE: " + what);
		fails++;
	}
}
