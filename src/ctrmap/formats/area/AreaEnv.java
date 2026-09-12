package ctrmap.formats.area;

import static ctrmap.formats.LittleEndian.f32;
import static ctrmap.formats.LittleEndian.putF32;

/**
 * The per-area environment GameFreak stores in AreaData subfile 4: 2944 bytes
 * of little-endian float32, one block per area.
 *
 * <p>THE BLOCK IS {@code float[61][12]}, not a struct of named fields. 736
 * floats, 61 channels of 12 lanes, {@value #CHANNEL_STRIDE} bytes apart. Within a
 * channel the four lanes of a group are TIMES OF DAY - night, dawn, day, dusk.
 * Measured on retail: area 24 (a route) carries fog colours pale-grey, blue and
 * orange across lanes 1-3 with lane 3 the sunset; area 48 (an interior) carries
 * the same value four times over, because a cave has no day cycle.
 *
 * <p>THE THREE GROUPS ARE IDENTICAL FOR THE FOG CHANNELS AND NOT FOR THE BLOCK.
 * That distinction is worth the sentence: an earlier draft of this class said
 * "the 12 lanes are three identical groups of four" flatly, which is true of
 * channels 54-60 in every retail area and false of 25 of the other 61 - channels
 * 46-52 differ between groups in ALL 228. A blind three-group write to one of
 * those would flatten something every area varies, so {@link #putTime} refuses
 * outside the channels where the claim was measured.
 *
 * <p>WHAT THIS CLASS USED TO SAY WAS WRONG, and it is worth writing down which
 * part. It declared {@code OFF_FOG_COLOR = 0x000} and read the four floats
 * there as red, green, blue and a strength. Those four floats are one channel's
 * four TIMES OF DAY - the red of some colour at night, dawn, day and dusk. So
 * choosing a blue fog wrote four unrelated reds across the day cycle and never
 * touched fog at all, and reading it back gave the editor's 3D view a "colour"
 * assembled from three different times of day. In ten of the fourteen areas
 * extracted in the owner's workspace those three floats are 1.0, 1.0, 1.0 -
 * which the view handed to {@code glClearColor} and {@code GL_FOG_COLOR}, and
 * that is why those maps opened as a blank white screen. {@code OFF_AMBIENT =
 * 0x010} was not a field either: it is lanes 4-7 of that same channel, the
 * second of the three groups, and it is 1.0 in every retail area.
 *
 * <p>THESE CHANNEL NUMBERS ARE PROVEN, not inferred. The chain: {@code
 * DllField.cro} 0xA03C8 fetches AreaData subfile 4 (the literal index 4, bounded
 * against the container's 12 subfiles) and 0x9BD4C hands all 2944 bytes to
 * {@code code.bin} 0x48E390, which stores the buffer at the receiving object's
 * +0x44. The CONSUMER is {@code code.bin} 0x12DDFC: it takes that same buffer
 * (0x12E6B4 {@code ldr r0,[r8,#0x44]}) and at 0x12E7B8-0x12EA10 reads six
 * constants - 0x288, 0x294, 0x2A0, 0x2AC, 0x2B8, 0x2C4 - which it uses as FLOAT
 * INDICES. Divided by the twelve floats in a channel those are channels 54, 55,
 * 56, 57, 58, 59, and they are handed to Fog::SetDensity (0x139078, clamped to
 * [0,1]), SetColor(r,g,b) (0x1390AC), SetNear (0x138FF8) and SetFar (0x138F78).
 * Independently, those engine fields were traced forward to GPUREG_FOG_COLOR
 * (0x00E1) and the 128-entry fog LUT, which is what the PICA200 actually draws
 * with.
 *
 * <p>WHY IT TOOK SO LONG TO FIND, recorded because the next person will search
 * the same way: the binary stores these as float indices, not byte offsets. Four
 * separate scans for an instruction forming {@code base + 0xA20} and friends all
 * came back empty and were read as "nothing reads the block through a constant
 * offset". Nothing reads it through a constant BYTE offset. 0x288 is 648 floats,
 * and 648 floats is 0xA20 bytes.
 *
 * <p>A WRITE IS ONLY MADE WHERE A VALUE CHANGED. Retail replicates the four
 * times across all three groups, and this writes all three, so an edit follows
 * the convention. But an area whose groups DIFFER would be rewritten into
 * agreement by a blind write - and the old dialog rewrote its fields on every
 * Save whether or not the user had touched them. Comparing against what was
 * read means an untouched field is not written at all, which is also what makes
 * {@code read} then {@code writeInto} byte-identical for every retail area
 * regardless of what the groups hold.
 *
 * <p>This is PER-AREA: editing it changes every zone that shares the area's id.
 */
public class AreaEnv {

	public static final int SUB4_LEN = 2944;
	/** Bytes between one channel and the next: 12 floats. */
	public static final int CHANNEL_STRIDE = 0x30;
	/** Floats in a channel. */
	public static final int LANES = 12;
	/** Times of day in a channel. */
	public static final int TIMES = 4;
	/** How many times the four times of day repeat across the 12 lanes, for the fog channels. */
	public static final int GROUPS = 3;
	/** Channels in the block; the last 4 floats are a tail, not a channel. */
	public static final int CHANNELS = 61;

	/** What each of the four lanes in a group is, in order. */
	public static final String[] TIME_NAMES = {"Night", "Dawn", "Day", "Dusk"};
	/** The time of day a static editor view should show. */
	public static final int TIME_DAY = 2;

	public static final int CH_FOG_STRENGTH = 54; // 0xA20
	public static final int CH_FOG_R = 55;        // 0xA50
	public static final int CH_FOG_G = 56;        // 0xA80
	public static final int CH_FOG_B = 57;        // 0xAB0
	public static final int CH_FOG_NEAR = 58;     // 0xAE0
	public static final int CH_FOG_FAR = 59;      // 0xB10
	/**
	 * The last channel whose three groups retail holds identical in every area.
	 * Measured, not assumed - see {@code AreaEnvTest}, which reads all 228 and
	 * fails if the range moves.
	 */
	public static final int CH_GROUP_IDENTICAL_LAST = 60;

	private static final int[] COLOR_CHANNELS = {CH_FOG_R, CH_FOG_G, CH_FOG_B};

	/** Fog colour per time of day: {@code [time][r,g,b]}. */
	public final float[][] fogColor = new float[TIMES][3];
	/** How much the fog colour takes over, per time of day. 0 = no fog at all. */
	public final float[] fogStrength = new float[TIMES];
	/** Where fog begins and where it hides everything. Retail holds one value for all 12 lanes. */
	public float fogNear;
	public float fogFar;

	/** What {@link #read} saw, so {@link #writeInto} can write only what moved. */
	private final float[][] readColor = new float[TIMES][3];
	private final float[] readStrength = new float[TIMES];
	private float readNear;
	private float readFar;

	/** Byte offset of one lane of one channel. */
	public static int offsetOf(int channel, int lane) {
		if (channel < 0 || channel >= CHANNELS || lane < 0 || lane >= LANES) {
			throw new IllegalArgumentException("channel " + channel + " lane " + lane
					+ " is outside the " + CHANNELS + "x" + LANES + " block");
		}
		return channel * CHANNEL_STRIDE + lane * 4;
	}

	/** One lane of one channel, straight out of the block. */
	public static float channel(byte[] sub4, int channel, int lane) {
		return f32(sub4, offsetOf(channel, lane));
	}

	/**
	 * Sets one time of day of one channel, in all {@value #GROUPS} groups - and
	 * REFUSES for a channel where writing all three is not known to be safe.
	 *
	 * <p>Retail holds the three groups identical for the fog channels, in every
	 * one of the 228 areas, which is what makes a three-group write the right way
	 * to edit them. It does NOT hold for the block at large: 25 of the 61 channels
	 * differ between groups somewhere, and channels 46-52 differ in every single
	 * area. Writing all three groups of one of those would flatten a distinction
	 * GameFreak makes everywhere, silently, in a file the user cannot inspect.
	 *
	 * <p>So the range is a refusal rather than a comment. The alternative - a note
	 * saying "only call this for fog" - is the shape of every defect this project
	 * has had to fix twice.
	 */
	public static void putTime(byte[] sub4, int channel, int time, float value) {
		if (time < 0 || time >= TIMES) {
			throw new IllegalArgumentException("time " + time + " is not one of the " + TIMES);
		}
		if (channel < CH_FOG_STRENGTH || channel > CH_GROUP_IDENTICAL_LAST) {
			throw new IllegalArgumentException("channel " + channel + " is outside the range whose"
				+ " three groups retail holds identical (" + CH_FOG_STRENGTH + ".."
				+ CH_GROUP_IDENTICAL_LAST + "), so writing all " + GROUPS + " of them would flatten"
				+ " a difference every area makes. Write the lane you mean instead.");
		}
		for (int g = 0; g < GROUPS; g++) {
			putF32(sub4, offsetOf(channel, g * TIMES + time), value);
		}
	}

	/** Parses the fog fields from an AreaData subfile-4 block. */
	public static AreaEnv read(byte[] sub4) {
		if (sub4 == null || sub4.length < SUB4_LEN) {
			throw new IllegalArgumentException("area env block must be " + SUB4_LEN + " bytes");
		}
		AreaEnv e = new AreaEnv();
		for (int t = 0; t < TIMES; t++) {
			for (int c = 0; c < COLOR_CHANNELS.length; c++) {
				e.fogColor[t][c] = channel(sub4, COLOR_CHANNELS[c], t);
				e.readColor[t][c] = e.fogColor[t][c];
			}
			e.fogStrength[t] = channel(sub4, CH_FOG_STRENGTH, t);
			e.readStrength[t] = e.fogStrength[t];
		}
		e.fogNear = channel(sub4, CH_FOG_NEAR, 0);
		e.fogFar = channel(sub4, CH_FOG_FAR, 0);
		e.readNear = e.fogNear;
		e.readFar = e.fogFar;
		return e;
	}

	/**
	 * Writes back the fields that CHANGED since {@link #read}, in place. A block
	 * nobody edited comes out byte-identical, whatever its lanes hold.
	 */
	public void writeInto(byte[] sub4) {
		for (int t = 0; t < TIMES; t++) {
			for (int c = 0; c < COLOR_CHANNELS.length; c++) {
				if (fogColor[t][c] != readColor[t][c]) {
					putTime(sub4, COLOR_CHANNELS[c], t, fogColor[t][c]);
				}
			}
			if (fogStrength[t] != readStrength[t]) {
				putTime(sub4, CH_FOG_STRENGTH, t, fogStrength[t]);
			}
		}
		//near and far are one value across all 12 lanes in every area measured,
		//so they are not a time-of-day ramp and are written as one value
		if (fogNear != readNear) {
			for (int l = 0; l < LANES; l++) {
				putF32(sub4, offsetOf(CH_FOG_NEAR, l), fogNear);
			}
		}
		if (fogFar != readFar) {
			for (int l = 0; l < LANES; l++) {
				putF32(sub4, offsetOf(CH_FOG_FAR, l), fogFar);
			}
		}
	}

	/**
	 * The colour a static view should paint, at the given time of day, already
	 * mixed by that time's strength: fog at strength 0 is no fog, and the old
	 * code painted the sky with a colour the area did not use at all.
	 *
	 * @return {@code {r, g, b}}, or null when this area draws no fog then
	 */
	public float[] viewFog(int time) {
		if (time < 0 || time >= TIMES || fogStrength[time] <= 0f) {
			return null;
		}
		return new float[]{fogColor[time][0], fogColor[time][1], fogColor[time][2]};
	}
}
