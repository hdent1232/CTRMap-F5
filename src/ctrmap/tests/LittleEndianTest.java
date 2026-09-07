package ctrmap.tests;

import ctrmap.formats.LittleEndian;
import java.util.Arrays;

/**
 * The one little-endian codec every byte[] reader now shares.
 *
 * <p>Sixty-two private copies of these functions were folded into
 * {@link LittleEndian}; the suites that exercised the copies still run, but
 * they exercised them through whatever format sat on top, and not one of them
 * would have named a sign-extension bug as such. This suite pins the codec
 * itself: width, signedness, byte order, and that a write touches exactly the
 * bytes it claims to.
 *
 * Usage: java ctrmap.tests.LittleEndianTest
 */
public class LittleEndianTest {

	static int fails = 0;

	public static void main(String[] args) {
		reads();
		signednessIsTheNameOnTheTin();
		writes();
		widthIsExact();
		pastTheEndThrows();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static void reads() {
		byte[] b = {(byte) 0x78, (byte) 0x56, (byte) 0x34, (byte) 0x12, (byte) 0x00, (byte) 0x00, (byte) 0x80, (byte) 0x3F};
		check(LittleEndian.u16(b, 0) == 0x5678, "u16 reads the low byte first: " + Integer.toHexString(LittleEndian.u16(b, 0)));
		check(LittleEndian.u16(b, 1) == 0x3456, "u16 honours the offset: " + Integer.toHexString(LittleEndian.u16(b, 1)));
		check(LittleEndian.i32(b, 0) == 0x12345678, "i32 reads four bytes low first: " + Integer.toHexString(LittleEndian.i32(b, 0)));
		check(LittleEndian.u32(b, 0) == 0x12345678L, "u32 agrees with i32 when the sign bit is clear");
		check(LittleEndian.f32(b, 4) == 1.0f, "f32 decodes 00 00 80 3F as 1.0: " + LittleEndian.f32(b, 4));
	}

	/** The bug every hand-rolled copy is one missing mask away from. */
	static void signednessIsTheNameOnTheTin() {
		byte[] ff = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
		check(LittleEndian.u16(ff, 0) == 65535, "u16 of FF FF is 65535, not -1: " + LittleEndian.u16(ff, 0));
		check(LittleEndian.i32(ff, 0) == -1, "i32 of FF FF FF FF is -1: " + LittleEndian.i32(ff, 0));
		check(LittleEndian.u32(ff, 0) == 4294967295L, "u32 of FF FF FF FF is 4294967295: " + LittleEndian.u32(ff, 0));
		byte[] top = {(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80};
		check(LittleEndian.i32(top, 0) == Integer.MIN_VALUE, "i32 with only the sign bit set is MIN_VALUE");
		check(LittleEndian.u32(top, 0) == 2147483648L, "u32 with only the sign bit set is 2^31");
		byte[] mid = {(byte) 0x00, (byte) 0x80};
		check(LittleEndian.u16(mid, 0) == 0x8000, "u16 with the top bit set is 32768, not negative: " + LittleEndian.u16(mid, 0));
	}

	static void writes() {
		byte[] b = new byte[8];
		LittleEndian.putU16(b, 0, 0xABCD);
		check(b[0] == (byte) 0xCD && b[1] == (byte) 0xAB, "putU16 stores the low byte first");
		check(LittleEndian.u16(b, 0) == 0xABCD, "putU16 then u16 round-trips");
		LittleEndian.putU16(b, 2, 0x1FFFF);
		check(LittleEndian.u16(b, 2) == 0xFFFF, "putU16 keeps the low 16 bits of an oversized value: " + Integer.toHexString(LittleEndian.u16(b, 2)));

		for (int v : new int[]{0, 1, -1, 0x12345678, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
			LittleEndian.putI32(b, 4, v);
			check(LittleEndian.i32(b, 4) == v, "putI32 then i32 round-trips " + v + ": " + LittleEndian.i32(b, 4));
		}
		LittleEndian.putI32(b, 4, 0x12345678);
		check(b[4] == 0x78 && b[5] == 0x56 && b[6] == 0x34 && b[7] == 0x12, "putI32 stores the low byte first");

		for (float f : new float[]{0f, 1f, -1f, 0.1f, 1e-30f, Float.MAX_VALUE, Float.NEGATIVE_INFINITY}) {
			LittleEndian.putF32(b, 0, f);
			check(LittleEndian.f32(b, 0) == f, "putF32 then f32 round-trips " + f);
			check(LittleEndian.i32(b, 0) == Float.floatToIntBits(f), "putF32 stores floatToIntBits, low byte first");
		}
	}

	/** A write of one width must not spill into its neighbours. */
	static void widthIsExact() {
		byte[] b = new byte[10];
		Arrays.fill(b, (byte) 0x5A);
		LittleEndian.putU16(b, 3, 0);
		check(b[2] == 0x5A && b[5] == 0x5A && b[3] == 0 && b[4] == 0, "putU16 touches exactly two bytes");
		Arrays.fill(b, (byte) 0x5A);
		LittleEndian.putI32(b, 3, 0);
		check(b[2] == 0x5A && b[7] == 0x5A && b[3] == 0 && b[6] == 0, "putI32 touches exactly four bytes");
		Arrays.fill(b, (byte) 0x5A);
		check(LittleEndian.u16(b, 8) == 0x5A5A, "u16 at the last two bytes reads only those two");
		check(LittleEndian.i32(b, 6) == 0x5A5A5A5A, "i32 at the last four bytes reads only those four");
	}

	/**
	 * A read past the end is an exception, not a zero: the copies all behaved
	 * this way and a format reader that wants leniency has to say so itself.
	 */
	static void pastTheEndThrows() {
		byte[] b = new byte[3];
		check(throwsOn(() -> LittleEndian.u16(b, 2)), "u16 one byte short throws");
		check(throwsOn(() -> LittleEndian.i32(b, 0)), "i32 one byte short throws");
		check(throwsOn(() -> LittleEndian.putU16(b, 2, 1)), "putU16 one byte short throws");
		check(throwsOn(() -> LittleEndian.putI32(b, 0, 1)), "putI32 one byte short throws");
		check(throwsOn(() -> LittleEndian.u16(b, -1)), "a negative offset throws");
	}

	static boolean throwsOn(Runnable r) {
		try {
			r.run();
			return false;
		} catch (ArrayIndexOutOfBoundsException ex) {
			return true;
		}
	}

	static void check(boolean ok, String what) {
		System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what);
		if (!ok) {
			fails++;
		}
	}
}
