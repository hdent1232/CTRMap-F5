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

	private static final String DEFAULT_CODE = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\code.bin";
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

		// 2. patch values for N=1 (M=540): master idx 540, size 540*0x38=0x7620, EN 541, bound 540
		List<Patch> p = ZoneLimitPatch.patches(1);
		checkEq(5, p.size(), "5 patch sites");
		checkBytes(p.get(0), 0x012bc0, new byte[]{(byte) 0x87, 0x1F, (byte) 0xA0, (byte) 0xE3}, "master idx -> mov r1,#0x21C(540)");
		checkBytes(p.get(1), 0x2d9774, new byte[]{(byte) 0x87, 0x0F, 0x55, (byte) 0xE3}, "bound A -> cmp r5,#0x21C");
		checkBytes(p.get(2), 0x2d99f8, new byte[]{(byte) 0x87, 0x0F, 0x56, (byte) 0xE3}, "bound B -> cmp r6,#0x21C");
		checkBytes(p.get(3), 0x012c0c, new byte[]{0x20, 0x76, 0x00, 0x00}, "master size -> 0x7620 (540*0x38)");
		checkBytes(p.get(4), 0x00eae0, new byte[]{0x1D, 0x02, 0x00, 0x00}, "EN idx -> 541");

		// 3. IPS well-formed: 'PATCH' + 5*(3+2+4) + 'EOF'
		byte[] ips = ZoneLimitPatch.buildIPS(1);
		check("PATCH".equals(new String(ips, 0, 5, "ASCII")), "IPS starts with PATCH");
		check("EOF".equals(new String(ips, ips.length - 3, 3, "ASCII")), "IPS ends with EOF");
		checkEq(5 + 5 * 9 + 3, ips.length, "IPS length = 53");

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
			System.out.println("(code.bin not found - skipped real-binary verification)");
		}

		System.out.println();
		if (fails == 0) {
			System.out.println("PASS");
		} else {
			System.out.println("FAIL (" + fails + ")");
			System.exit(1);
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
