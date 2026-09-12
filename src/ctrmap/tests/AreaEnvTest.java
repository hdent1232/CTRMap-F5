package ctrmap.tests;

import ctrmap.formats.area.AreaEnv;
import ctrmap.formats.garc.GARC;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static ctrmap.formats.containers.ContainerBytes.subfile;

/**
 * The AreaData subfile-4 environment codec, against every area in the retail
 * dump.
 *
 * <p>RETARGETED, NOT RELAXED. Its two anchors used to read "route fog colour
 * blue, indoor fog colour white" out of offset 0x000 - and those two sentences
 * were both true of the bytes and both wrong about the game. 0x000 is one
 * channel's RED at four times of day, so "blue" was the third time of day of a
 * red and "white" was three floats that happen to be 1.0. Deleting the anchors
 * would have left the new offsets pinned by nothing; they are replaced by
 * assertions about the thing that actually justifies them - the block's
 * structure - measured over every area rather than two.
 *
 * <p>What is asserted here:
 *
 * <ul>
 * <li>every area's block is 2944 bytes and read -&gt; writeInto is byte-identical,
 *     which is what makes this safe to edit at all;</li>
 * <li>the fog channels are THREE IDENTICAL GROUPS OF FOUR - the claim the whole
 *     four-times-of-day editor rests on. If GameFreak had ever varied a group,
 *     writing all three would silently flatten it;</li>
 * <li>near and far are ONE value across all twelve lanes, so they are not a
 *     time-of-day ramp and the dialog is right to offer one spinner each;</li>
 * <li>channel 0's second group is 1.0 everywhere, which is why the "Ambient /
 *     light colour" control was removed rather than repointed: there was no
 *     colour there for a user to choose;</li>
 * <li>and the behavioural anchor that survived all of this - outdoor areas draw
 *     far, interiors draw near.</li>
 * </ul>
 *
 * Usage: java ctrmap.tests.AreaEnvTest &lt;path-to-a014-garc&gt;
 */
public class AreaEnvTest {

	/** The fog channels, and near/far which are not a ramp. */
	private static final int[] RAMPED = {AreaEnv.CH_FOG_STRENGTH, AreaEnv.CH_FOG_R,
		AreaEnv.CH_FOG_G, AreaEnv.CH_FOG_B};
	private static final int[] FLAT = {AreaEnv.CH_FOG_NEAR, AreaEnv.CH_FOG_FAR};

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File garc = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/4");
		GARC ad = new GARC(garc); // default: LZ11-decompress
		int checked = 0, roundTripFails = 0;
		List<String> groupsDiffer = new ArrayList<>();
		List<String> laneVaries = new ArrayList<>();
		List<String> ambientNotOne = new ArrayList<>();
		int longDraw = 0, shortDraw = 0;

		for (int area = 0; area < ad.length; area++) {
			byte[] entry = ad.getDecompressedEntry(area);
			if (entry == null || entry.length < 16 || entry[0] != 'A' || entry[1] != 'D') {
				continue;
			}
			byte[] s4 = subfile(entry, 4);
			if (s4 == null || s4.length != AreaEnv.SUB4_LEN) {
				continue;
			}
			checked++;
			AreaEnv e = AreaEnv.read(s4);
			byte[] copy = s4.clone();
			e.writeInto(copy);
			if (!Arrays.equals(copy, s4)) {
				roundTripFails++;
				if (roundTripFails <= 5) {
					System.out.println("  area " + area + ": no-op writeInto is not byte-identical");
				}
			}

			//THE STRUCTURE THE EDITOR RESTS ON: four times of day, three times over
			for (int ch : RAMPED) {
				for (int t = 0; t < AreaEnv.TIMES; t++) {
					float first = AreaEnv.channel(s4, ch, t);
					for (int g = 1; g < AreaEnv.GROUPS; g++) {
						if (AreaEnv.channel(s4, ch, g * AreaEnv.TIMES + t) != first
								&& groupsDiffer.size() < 6) {
							groupsDiffer.add("area " + area + " channel " + ch + " time " + t
									+ ": group 0 has " + first + ", group " + g + " has "
									+ AreaEnv.channel(s4, ch, g * AreaEnv.TIMES + t));
						}
					}
				}
			}
			//...and near/far, which are not a ramp at all
			for (int ch : FLAT) {
				float first = AreaEnv.channel(s4, ch, 0);
				for (int l = 1; l < AreaEnv.LANES; l++) {
					if (AreaEnv.channel(s4, ch, l) != first && laneVaries.size() < 6) {
						laneVaries.add("area " + area + " channel " + ch + " lane " + l
								+ ": " + AreaEnv.channel(s4, ch, l) + " against " + first);
					}
				}
			}
			//the four floats the dialog used to call "Ambient / light colour"
			for (int l = AreaEnv.TIMES; l < AreaEnv.TIMES * 2; l++) {
				if (AreaEnv.channel(s4, 0, l) != 1.0f && ambientNotOne.size() < 6) {
					ambientNotOne.add("area " + area + " lane " + l + " = " + AreaEnv.channel(s4, 0, l));
				}
			}
			if (e.fogFar >= 2000) {
				longDraw++;
			} else if (e.fogFar > 0 && e.fogFar < 800) {
				shortDraw++;
			}
		}

		check(checked > 200, checked + " retail areas read");
		check(roundTripFails == 0, "read then writeInto is byte-identical for every one of them"
				+ " - nothing is rewritten just by opening the dialog (" + roundTripFails + " differed)");
		check(groupsDiffer.isEmpty(), "every fog channel is three identical groups of four, so"
				+ " writing all three cannot flatten a ramp GameFreak varied " + groupsDiffer);
		check(laneVaries.isEmpty(), "fog near and far are one value across all twelve lanes, so they"
				+ " are not a time-of-day ramp " + laneVaries);
		check(ambientNotOne.isEmpty(), "channel 0's second group is 1.0 in every area - there was no"
				+ " \"ambient colour\" there for anyone to have chosen " + ambientNotOne);
		check(longDraw > 0 && shortDraw > 0, "and the anchor that survived the retarget: areas draw"
				+ " far and areas draw near (" + longDraw + " at 2000+, " + shortDraw + " under 800)");

		//AND THE ONE THING THAT MUST NEVER COME BACK: fog read out of channel 0.
		byte[] probe = new byte[AreaEnv.SUB4_LEN];
		for (int l = 0; l < AreaEnv.LANES; l++) {
			ctrmap.formats.LittleEndian.putF32(probe, AreaEnv.offsetOf(0, l), 0.5f);
		}
		AreaEnv fromProbe = AreaEnv.read(probe);
		boolean anyHalf = false;
		for (int t = 0; t < AreaEnv.TIMES; t++) {
			for (int c = 0; c < 3; c++) {
				anyHalf |= fromProbe.fogColor[t][c] == 0.5f;
			}
			anyHalf |= fromProbe.fogStrength[t] == 0.5f;
		}
		check(!anyHalf, "a block whose ONLY non-zero channel is channel 0 reads back as no fog at"
				+ " all - the fog fields do not come from there any more");

		System.out.println("AreaEnv: " + checked + " areas, " + AreaEnv.CHANNELS + "x" + AreaEnv.LANES
				+ " floats each, fog on channels " + AreaEnv.CH_FOG_STRENGTH + ".." + AreaEnv.CH_FOG_FAR
				+ ", failures=" + fails);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			fails++;
			System.out.println("  FAIL: " + what);
		}
	}
}
