package ctrmap.tests;

import ctrmap.formats.area.WorldAnim;
import ctrmap.formats.garc.GARC;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates the world-animation codec + water-scroll splicer against the whole
 * pristine AreaData corpus:
 * <ol>
 * <li>every retail subfile-2 BCH passes the strict structural validator
 *     (independent re-verification of the measured format spec);</li>
 * <li>the patricia-tree builder reproduces every retail dict-9 tree
 *     byte-identically from its name list (the gold test for insertion);</li>
 * <li>v1 splice (append the sea pair into an EXISTING bound animation) is run
 *     against every retail _chikei_ animation: result revalidates, gains the
 *     scroll, leaves every other animation logically untouched, idempotent;</li>
 * <li>v2 splice (whole new animation incl. values-array grow + tree rebuild +
 *     empty-dict bootstrap) is run against every area with a synthetic model
 *     name: same gates + patricia lookup resolves the new name;</li>
 * <li>a chained splice (v2 then v1 on the same file) revalidates.</li>
 * </ol>
 * Usage: java ctrmap.tests.AnimSpliceTest &lt;path-to-a014-garc&gt;
 */
public class AnimSpliceTest {

	public static void main(String[] args) throws Exception {
		File garc = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/4");
		GARC ad = new GARC(garc);

		int files = 0, valErrs = 0, treeOk = 0, treeBad = 0;
		int v1ok = 0, v1fail = 0, v2ok = 0, v2fail = 0, already = 0;
		List<byte[]> subs = new ArrayList<>();
		List<Integer> ids = new ArrayList<>();
		for (int id = 0; id < 229; id++) {
			byte[] c = ad.getDecompressedEntry(id);
			if (c == null || c.length < 4 || c[0] != 'A' || c[1] != 'D') {
				continue; // entry 228 is not an AD container
			}
			byte[] sub2 = sub(c, 2);
			if (sub2 == null) {
				continue;
			}
			files++;
			subs.add(sub2);
			ids.add(id);

			// (1) strict validation of the pristine file
			WorldAnim wa = new WorldAnim(sub2);
			List<String> errs = wa.validate();
			if (!errs.isEmpty()) {
				if (valErrs == 0) {
					System.out.println("FAIL validate area " + id + ": " + errs.get(0));
				}
				valErrs++;
				continue;
			}

			// (2) patricia gold: rebuild the dict-9 tree byte-identically
			if (wa.animCount() > 0) {
				if (java.util.Arrays.equals(wa.retailTreeBytes(), wa.rebuiltTreeBytes())) {
					treeOk++;
				} else {
					if (treeBad == 0) {
						System.out.println("TREE MISMATCH area " + id + " (" + wa.animCount() + " anims)");
						dumpTree("retail ", wa.retailTreeBytes());
						dumpTree("rebuilt", wa.rebuiltTreeBytes());
						for (String n : wa.animNames()) {
							System.out.println("    name: " + n);
						}
					}
					treeBad++;
				}
			}

			// (3) v1: splice into every existing _chikei_ animation
			for (int i = 0; i < wa.animCount(); i++) {
				String name = wa.animName(i);
				if (!name.endsWith(WorldAnim.ANIM_SUFFIX)) {
					continue;
				}
				String model = name.substring(0, name.length() - WorldAnim.ANIM_SUFFIX.length());
				if (wa.animHasSeaScroll(i)) {
					byte[] out = WorldAnim.spliceSeaScroll(sub2, model);
					if (out != sub2) {
						System.out.println("FAIL v1 area " + id + " '" + name + "': not identity on existing scroll");
						v1fail++;
					} else {
						already++;
					}
					continue;
				}
				String why = trySplice(sub2, model, wa);
				if (why == null) {
					v1ok++;
				} else {
					if (v1fail == 0) {
						System.out.println("FAIL v1 area " + id + " '" + name + "': " + why);
					}
					v1fail++;
				}
			}

			// (4) v2: whole-new-animation path on every area
			String why = trySplice(sub2, "zztest_99_99", wa);
			if (why == null) {
				v2ok++;
			} else {
				if (v2fail == 0) {
					System.out.println("FAIL v2 area " + id + ": " + why);
				}
				v2fail++;
			}
		}

		// (5) chained: v2 then v1 on one real water area (10) + one empty-dict area
		int chained = 0;
		for (byte[] s : new byte[][]{subs.get(10), firstEmptyDict(subs)}) {
			byte[] step1 = WorldAnim.spliceSeaScroll(s, "zztest_99_99");
			WorldAnim w1 = new WorldAnim(step1);
			String model2 = null;
			for (String n : w1.animNames()) {
				if (n.endsWith(WorldAnim.ANIM_SUFFIX) && !w1.animHasSeaScroll(w1.findAnim(n))) {
					model2 = n.substring(0, n.length() - WorldAnim.ANIM_SUFFIX.length());
					break;
				}
			}
			byte[] step2 = model2 != null ? WorldAnim.spliceSeaScroll(step1, model2) : step1;
			WorldAnim w2 = new WorldAnim(step2);
			if (w2.validate().isEmpty() && w2.hasSeaScroll("zztest_99_99")
					&& (model2 == null || w2.hasSeaScroll(model2))) {
				chained++;
			} else {
				System.out.println("FAIL chained splice: " + w2.validate());
			}
		}

		// (6) AD container store/read round-trip of a spliced subfile
		boolean adOk = false;
		try {
			File tmp = File.createTempFile("animsplice_ad", null);
			tmp.deleteOnExit();
			byte[] entry = ad.getDecompressedEntry(ids.get(10));
			java.nio.file.Files.write(tmp.toPath(), entry);
			ctrmap.formats.containers.AD cont = new ctrmap.formats.containers.AD(tmp);
			byte[] spliced = WorldAnim.spliceSeaScroll(cont.getFile(2), "zztest_99_99");
			cont.storeFile(2, spliced);
			byte[] back = new ctrmap.formats.containers.AD(tmp).getFile(2);
			WorldAnim wb = new WorldAnim(back);
			adOk = wb.validate().isEmpty() && wb.hasSeaScroll("zztest_99_99");
			if (!adOk) {
				System.out.println("FAIL AD round-trip: " + wb.validate());
			}
		} catch (Exception ex) {
			System.out.println("FAIL AD round-trip: " + ex);
		}

		System.out.println("corpus: " + files + " files, validator errors " + valErrs
				+ "; patricia gold " + treeOk + "/" + (treeOk + treeBad));
		System.out.println("v1 splice: " + v1ok + " ok, " + v1fail + " fail, " + already + " already-scrolling (identity)");
		System.out.println("v2 splice: " + v2ok + " ok, " + v2fail + " fail; chained " + chained + "/2; AD round-trip " + (adOk ? "ok" : "FAIL"));
		boolean pass = valErrs == 0 && treeBad == 0 && v1fail == 0 && v2fail == 0 && chained == 2 && files == 228 && adOk;
		System.out.println(pass ? "ALL PASS" : "FAILURES PRESENT");
		if (!pass) {
			System.exit(1);
		}
	}

	/** Splices, revalidates, checks the scroll landed and nothing else changed. */
	static String trySplice(byte[] sub2, String model, WorldAnim before) {
		try {
			// canonical dumps of every pre-existing animation EXCEPT the target
			String targetName = model + WorldAnim.ANIM_SUFFIX;
			List<String> pre = new ArrayList<>();
			for (int i = 0; i < before.animCount(); i++) {
				if (!before.animName(i).equals(targetName)) {
					pre.add(before.describeAnim(i));
				}
			}
			byte[] out = WorldAnim.spliceSeaScroll(sub2, model);
			WorldAnim wa = new WorldAnim(out);
			List<String> errs = wa.validate();
			if (!errs.isEmpty()) {
				return "revalidate: " + errs.get(0);
			}
			if (!wa.hasSeaScroll(model)) {
				return "scroll not present after splice";
			}
			// every other animation logically identical
			List<String> post = new ArrayList<>();
			for (int i = 0; i < wa.animCount(); i++) {
				if (!wa.animName(i).equals(targetName)) {
					post.add(wa.describeAnim(i));
				}
			}
			if (!pre.equals(post)) {
				return "other animations changed";
			}
			// the target anim gained the donor pair, its curves scaled to the
			// HOST anim's loop length (donor 239/240 when the host frame count
			// is unusable) so the scroll wraps seamlessly
			String d = wa.describeAnim(wa.findAnim(targetName));
			String line1 = d.substring(0, d.indexOf('\n'));
			int framesBits = (int) Long.parseLong(line1.substring(line1.lastIndexOf('|') + 1), 16);
			float f = Float.intBitsToFloat(framesBits);
			boolean usable = f >= 1f && f <= 4093f && f == Math.floor(f);
			int endBits = usable ? framesBits : 0x436F0000;
			int loop = usable ? (int) f + 1 : 240;
			String end = Integer.toHexString(endBits);
			// keyframe words, little-endian in the dump's byte-hex
			String dataA = leHex(0x00000000) + leHex((0xFFFFF << 12) | loop);
			String dataB = leHex(0xFFFFF000) + leHex(loop);
			if (!d.contains("chip_sea_b|20015|c02 G{bf800000," + end + ",")
					|| !d.contains("chip_sea_b|20018|c02 G{bf800000," + end + ",")
					|| !d.contains(dataA + "}") || !d.contains(dataB + "}")) {
				return "donor pair content mismatch (want end " + end + " loop " + loop + "):\n" + d;
			}
			// idempotence
			if (WorldAnim.spliceSeaScroll(out, model) != out) {
				return "not idempotent";
			}
			return null;
		} catch (RuntimeException ex) {
			return "exception " + ex;
		}
	}

	static byte[] firstEmptyDict(List<byte[]> subs) {
		for (byte[] s : subs) {
			if (new WorldAnim(s).animCount() == 0) {
				return s;
			}
		}
		throw new IllegalStateException("no empty-dict area");
	}

	static void dumpTree(String tag, byte[] t) {
		StringBuilder sb = new StringBuilder("    " + tag + ":");
		for (int i = 0; i < t.length / 12; i++) {
			sb.append(String.format(" [%d]{%08x,L%d,R%d,n%04x}", i, le32(t, i * 12),
					(t[i * 12 + 4] & 0xFF) | ((t[i * 12 + 5] & 0xFF) << 8),
					(t[i * 12 + 6] & 0xFF) | ((t[i * 12 + 7] & 0xFF) << 8), le32(t, i * 12 + 8)));
		}
		System.out.println(sb);
	}

	static byte[] sub(byte[] c, int i) {
		if (c == null || c.length < 8) {
			return null;
		}
		int count = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= count) {
			return null;
		}
		int o0 = le32(c, 4 + i * 4), o1 = le32(c, 4 + (i + 1) * 4);
		if (o0 < 0 || o1 > c.length || o1 < o0) {
			return null;
		}
		return java.util.Arrays.copyOfRange(c, o0, o1);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	/** A word as the byte-hex its little-endian encoding produces in dumps. */
	static String leHex(int v) {
		return String.format("%02x%02x%02x%02x", v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >>> 24) & 0xFF);
	}
}
