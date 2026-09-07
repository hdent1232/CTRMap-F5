package ctrmap.formats.area;

import static ctrmap.formats.LittleEndian.f32;
import static ctrmap.formats.LittleEndian.putF32;

/**
 * The per-area fog + ambient/light environment, stored in AreaData subfile 4
 * (a 2944-byte block of little-endian float32, one per area). Reverse-engineered
 * and byte-verified against the retail dump: the fog/sky color and fog near/far
 * are the high-confidence, editable fields (routes are blue with a long draw
 * distance, interiors white with a short one); an ambient/light color quad sits
 * beside them. Near/far are stored as 12 identical replicated copies (the engine
 * light-set slots) - writes update all 12.
 *
 * <p>This is PER-AREA: editing it changes every zone that shares the area's id.
 */
public class AreaEnv {

	// byte offsets within subfile 4 (float index = offset/4), measured on the dump
	public static final int OFF_FOG_COLOR = 0x000; // f[0..3] r,g,b,intensity
	public static final int OFF_AMBIENT = 0x010;   // f[4..7] light/ambient color quad
	public static final int OFF_FOG_NEAR = 0xAE0;  // f[696..707] (12 copies)
	public static final int OFF_FOG_FAR = 0xB10;   // f[708..719] (12 copies)
	public static final int SUB4_LEN = 2944;
	private static final int REPLICAS = 12;

	public final float[] fogColor = new float[4]; // r,g,b,intensity(blend)
	public final float[] ambient = new float[4];  // r,g,b,scalar
	public float fogNear;
	public float fogFar;

	/** Parses the fields from an AreaData subfile-4 block (2944 bytes). */
	public static AreaEnv read(byte[] sub4) {
		if (sub4 == null || sub4.length < SUB4_LEN) {
			throw new IllegalArgumentException("area env block must be " + SUB4_LEN + " bytes");
		}
		AreaEnv e = new AreaEnv();
		for (int i = 0; i < 4; i++) {
			e.fogColor[i] = f32(sub4, OFF_FOG_COLOR + i * 4);
			e.ambient[i] = f32(sub4, OFF_AMBIENT + i * 4);
		}
		e.fogNear = f32(sub4, OFF_FOG_NEAR);
		e.fogFar = f32(sub4, OFF_FOG_FAR);
		return e;
	}

	/** Writes the fields back into the subfile-4 block in place (length preserved). */
	public void writeInto(byte[] sub4) {
		for (int i = 0; i < 4; i++) {
			putF32(sub4, OFF_FOG_COLOR + i * 4, fogColor[i]);
			putF32(sub4, OFF_AMBIENT + i * 4, ambient[i]);
		}
		for (int r = 0; r < REPLICAS; r++) {
			putF32(sub4, OFF_FOG_NEAR + r * 4, fogNear);
			putF32(sub4, OFF_FOG_FAR + r * 4, fogFar);
		}
	}
}
