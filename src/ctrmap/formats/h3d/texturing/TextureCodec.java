package ctrmap.formats.h3d.texturing;

import ctrmap.formats.h3d.PICACommandReader;
import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


/**
 * Full port of the Ohana class
 */
public class TextureCodec {

	private static final int[] tileOrder = {0, 1, 8, 9, 2, 3, 10, 11, 16, 17, 24, 25, 18, 19, 26, 27, 4, 5, 12, 13, 6, 7, 14, 15, 20, 21, 28, 29, 22, 23, 30, 31, 32, 33, 40, 41, 34, 35, 42, 43, 48, 49, 56, 57, 50, 51, 58, 59, 36, 37, 44, 45, 38, 39, 46, 47, 52, 53, 60, 61, 54, 55, 62, 63};
	private static final int[][] etc1LUT = {{2, 8, -2, -8}, {5, 17, -5, -17}, {9, 29, -9, -29}, {13, 42, -13, -42}, {18, 60, -18, -60}, {24, 80, -24, -80}, {33, 106, -33, -106}, {47, 183, -47, -183}};

	/**
	 * Decodes one PICA200 texture to 8-bit BGRA, 4 bytes per texel, rotated
	 * 180 degrees from storage order (bottom-up and right-to-left - the row
	 * order the renderer has always been handed; see {@link #flipToBgra}).
	 *
	 * <p>Three steps, and every format shares two of them. The uncompressed
	 * formats are stored as 8x8 tiles in Z-order ({@link #tileOrder});
	 * {@link #untile} walks that order once and hands each texel to the
	 * format's converter, which is the only thing that differs between, say,
	 * rgba8 and rgb565 - so the converters are the part worth reading, and
	 * each one is testable on a single texel. The two 4-bit formats keep their
	 * own walk because a texel is half a byte. ETC1 is a block codec with its
	 * own 4x4 scramble and decodes on its own path. Last, the top-down RGBA the
	 * converters write is turned into the BGRA the renderer reads.
	 */
	public static byte[] decode(byte[] data, int width, int height, PICACommandReader.TextureFormat format) {
		byte[] output = new byte[width * height * 4];
		switch (format) {
			case rgba8:
				untile(data, 4, width, height, output, TextureCodec::rgba8);
				break;
			case rgb8:
				untile(data, 3, width, height, output, TextureCodec::rgb8);
				break;
			case rgba5551:
				untile(data, 2, width, height, output, TextureCodec::rgba5551);
				break;
			case rgb565:
				untile(data, 2, width, height, output, TextureCodec::rgb565);
				break;
			case rgba4:
				untile(data, 2, width, height, output, TextureCodec::rgba4);
				break;
			case la8:
			case hilo8:
				untile(data, 2, width, height, output, TextureCodec::la8);
				break;
			case l8:
				untile(data, 1, width, height, output, TextureCodec::l8);
				break;
			case a8:
				untile(data, 1, width, height, output, TextureCodec::a8);
				break;
			case la4:
				untile(data, 1, width, height, output, TextureCodec::la4);
				break;
			case l4:
				untileNibbles(data, width, height, output, false);
				break;
			case a4:
				untileNibbles(data, width, height, output, true);
				break;
			case etc1:
			case etc1a4:
				untileEtc1(etc1Decode(data, width, height, format == PICACommandReader.TextureFormat.etc1a4),
						width, height, output);
				break;
			default:
				//dontCare: nothing is decoded, the texture stays transparent black
				break;
		}
		return flipToBgra(output, width, height);
	}

	/** Converts the stored texel at {@code data[d..]} to RGBA at {@code out[o..o+3]}. */
	private interface Texel {

		void read(byte[] data, int d, byte[] out, int o);
	}

	/**
	 * The walk every byte-granular tiled format shares: the stored texels are
	 * consecutive, {@code bytesPerTexel} each, and the p-th one lands at
	 * {@link #tiledOffset}.
	 */
	private static void untile(byte[] data, int bytesPerTexel, int width, int height, byte[] output, Texel texel) {
		int dataOffset = 0;
		for (int p = 0, n = tiledTexels(width, height); p < n; p++) {
			texel.read(data, dataOffset, output, tiledOffset(p, width));
			dataOffset += bytesPerTexel;
		}
	}

	/**
	 * The same walk for the two 4-bit formats, where a texel is half a byte:
	 * the low nibble first, then the high nibble of the same byte.
	 */
	private static void untileNibbles(byte[] data, int width, int height, byte[] output, boolean alphaOnly) {
		int dataOffset = 0;
		boolean toggle = false;
		for (int p = 0, n = tiledTexels(width, height); p < n; p++) {
			int o = tiledOffset(p, width);
			byte c = toggle ? (byte) ((data[dataOffset++] & 0xf0) >> 4) : (byte) (data[dataOffset] & 0xf);
			toggle = !toggle;
			c = (byte) ((c << 4) | c);
			if (alphaOnly) {
				output[o] = (byte) 0xff;
				output[o + 1] = (byte) 0xff;
				output[o + 2] = (byte) 0xff;
				output[o + 3] = c;
			} else {
				output[o] = c;
				output[o + 1] = c;
				output[o + 2] = c;
				output[o + 3] = (byte) 0xff;
			}
		}
	}

	/**
	 * How many texels the 8x8 tiling stores: whole tiles only. A width or
	 * height that is not a multiple of 8 has its remainder left undecoded,
	 * as it always was.
	 */
	static int tiledTexels(int width, int height) {
		return (width / 8) * (height / 8) * 64;
	}

	/**
	 * Byte offset, in a top-down RGBA image {@code width} texels wide, of the
	 * p-th texel in storage order: tiles run left to right then top to bottom,
	 * and within a tile the 64 texels follow {@link #tileOrder}.
	 */
	static int tiledOffset(int p, int width) {
		int tile = p >> 6, tilesPerRow = width / 8;
		int tX = tile % tilesPerRow, tY = tile / tilesPerRow;
		int x = tileOrder[p & 63] % 8;
		int y = (tileOrder[p & 63] - x) / 8;
		return ((tX * 8) + x + ((tY * 8 + y) * width)) * 4;
	}

	//---- the converters: one stored texel to one RGBA texel ----------------

	/** Stored as A,B,G,R. */
	private static void rgba8(byte[] data, int d, byte[] out, int o) {
		out[o] = data[d + 1];
		out[o + 1] = data[d + 2];
		out[o + 2] = data[d + 3];
		out[o + 3] = data[d];
	}

	private static void rgb8(byte[] data, int d, byte[] out, int o) {
		System.arraycopy(data, d, out, o, 3);
		out[o + 3] = (byte) 0xff;
	}

	private static int le16(byte[] data, int d) {
		return (data[d] & 0xFF) | ((data[d + 1] & 0xFF) << 8);
	}

	/** a in bit 0, then 5 bits each of r, g, b; the top 3 bits repeat into the low 3. */
	private static void rgba5551(byte[] data, int d, byte[] out, int o) {
		int pixelData = le16(data, d);
		int r = ((pixelData >> 1) & 0x1f) << 3;
		int g = ((pixelData >> 6) & 0x1f) << 3;
		int b = ((pixelData >> 11) & 0x1f) << 3;
		int a = (pixelData & 1) * 0xff;
		out[o] = (byte) (r | (r >> 5));
		out[o + 1] = (byte) (g | (g >> 5));
		out[o + 2] = (byte) (b | (b >> 5));
		out[o + 3] = (byte) a;
	}

	private static void rgb565(byte[] data, int d, byte[] out, int o) {
		int pixelData = le16(data, d);
		int r = (pixelData & 0x1f) << 3;
		int g = ((pixelData >> 5) & 0x3f) << 2;
		int b = ((pixelData >> 11) & 0x1f) << 3;
		out[o] = (byte) (r | (r >> 5));
		out[o + 1] = (byte) (g | (g >> 6));
		out[o + 2] = (byte) (b | (b >> 5));
		out[o + 3] = (byte) 0xff;
	}

	private static void rgba4(byte[] data, int d, byte[] out, int o) {
		int pixelData = le16(data, d);
		int r = (pixelData >> 4) & 0xf;
		int g = (pixelData >> 8) & 0xf;
		int b = (pixelData >> 12) & 0xf;
		int a = pixelData & 0xf;
		out[o] = (byte) (r | (r << 4));
		out[o + 1] = (byte) (g | (g << 4));
		out[o + 2] = (byte) (b | (b << 4));
		out[o + 3] = (byte) (a | (a << 4));
	}

	/** Luminance then alpha; hilo8 is read the same way. */
	private static void la8(byte[] data, int d, byte[] out, int o) {
		out[o] = data[d];
		out[o + 1] = data[d];
		out[o + 2] = data[d];
		out[o + 3] = data[d + 1];
	}

	private static void l8(byte[] data, int d, byte[] out, int o) {
		out[o] = data[d];
		out[o + 1] = data[d];
		out[o + 2] = data[d];
		out[o + 3] = (byte) 0xff;
	}

	private static void a8(byte[] data, int d, byte[] out, int o) {
		out[o] = (byte) 0xff;
		out[o + 1] = (byte) 0xff;
		out[o + 2] = (byte) 0xff;
		out[o + 3] = data[d];
	}

	/**
	 * High nibble luminance, low nibble alpha - and neither is replicated into
	 * 8 bits, and the luminance shift is arithmetic on a signed byte, so a
	 * high nibble of 8..F comes out 0xF8..0xFF rather than 0x88..0xFF. That
	 * is what the port has always done; it is recorded here, not endorsed,
	 * and no retail area texture uses the format.
	 */
	private static void la4(byte[] data, int d, byte[] out, int o) {
		out[o] = (byte) (data[d] >> 4);
		out[o + 1] = (byte) (data[d] >> 4);
		out[o + 2] = (byte) (data[d] >> 4);
		out[o + 3] = (byte) (data[d] & 0xf);
	}

	/** ETC1 decodes to 4x4 blocks in a scrambled order; put each block where it belongs. */
	private static void untileEtc1(byte[] decodedData, int width, int height, byte[] output) {
		int[] etc1Order = etc1Scramble(width, height);
		int i = 0;
		for (int tY = 0; tY < height / 4; tY++) {
			for (int tX = 0; tX < width / 4; tX++) {
				int TX = etc1Order[i] % (width / 4);
				int TY = (etc1Order[i] - TX) / (width / 4);
				for (int y = 0; y < 4; y++) {
					for (int x = 0; x < 4; x++) {
						int dataOffset = ((TX * 4) + x + (((TY * 4) + y) * width)) * 4;
						int outputOffset = ((tX * 4) + x + (((tY * 4 + y)) * width)) * 4;
						System.arraycopy(decodedData, dataOffset, output, outputOffset, 4);
					}
				}
				i += 1;
			}
		}
	}

	/**
	 * Top-down RGBA to what the renderer reads: the texel at (x, y) is taken
	 * from (width-1-x, height-1-y) with red and blue swapped. That is a 180
	 * degree turn, not the vertical flip the old comment called it - the
	 * renderer's UVs have been compensating since the port, so it stays.
	 */
	private static byte[] flipToBgra(byte[] output, int width, int height) {
		byte[] flip = new byte[width * height * 4];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int offset = y * width * 4 + x * 4;
				byte red = output[output.length - offset - 4];
				byte green = output[output.length - offset - 4 + 1];
				byte blue = output[output.length - offset - 4 + 2];
				byte alpha = output[output.length - offset - 4 + 3];
				flip[offset] = blue;
				flip[offset + 1] = green;
				flip[offset + 2] = red;
				flip[offset + 3] = alpha;
			}
		}
		return flip;
	}

	private static byte[] etc1Decode(byte[] input, int width, int height, boolean alpha) {
		byte[] output = new byte[(width * height * 4)];
		int offset = 0;

		for (int y = 0; y < height / 4; y++) {
			for (int x = 0; x < width / 4; x++) {
				byte[] colorBlock = new byte[8];
				byte[] alphaBlock = new byte[8];
				if (alpha) {
					for (int i = 0; i < 8; i++) {
						colorBlock[7 - i] = input[offset + 8 + i];
						alphaBlock[i] = input[offset + i];
					}
					offset += 16;
				} else {
					for (int i = 0; i < 8; i++) {
						colorBlock[7 - i] = input[offset + i];
						alphaBlock[i] = (byte) 0xff;
					}
					offset += 8;
				}
				/*System.out.print("[");
                     for (int h = 0; h < colorBlock.length; h++)
                    {
                        System.out.print((colorBlock[h] & 0xFF) + ", ");
                    }
                    System.out.print("]\n");*/
				colorBlock = etc1DecodeBlock(colorBlock);

				boolean toggle = false;
				int alphaOffset = 0;
				for (int tX = 0; tX < 4; tX++) {
					for (int tY = 0; tY < 4; tY++) {
						int outputOffset = (x * 4 + tX + ((y * 4 + tY) * width)) * 4;
						int blockOffset = (tX + (tY * 4)) * 4;
						System.arraycopy(colorBlock, blockOffset, output, outputOffset, 3);

						byte a = (byte) (toggle ? (alphaBlock[alphaOffset++] & 0xf0) >> 4 : alphaBlock[alphaOffset] & 0xf);
						output[outputOffset + 3] = (byte) ((a << 4) | a);
						toggle = !toggle;
					}
				}
			}
		}

		return output;
	}

	private static byte[] etc1DecodeBlock(byte[] data) {
		long blockTop = Integer.toUnsignedLong(ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
		long blockBottom = Integer.toUnsignedLong(ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
		
		
		boolean flip = (blockTop & 0x1000000) > 0;
		boolean difference = (blockTop & 0x2000000) > 0;

		long r1, g1, b1;
		long r2, g2, b2;

		if (difference) {
			r1 = blockTop & 0xf8;
			g1 = (blockTop & 0xf800) >> 8;
			b1 = (blockTop & 0xf80000) >> 16;

			r2 = (byte)(r1 >> 3) + ((byte)((blockTop & 7) << 5) >> 5);
			g2 = (byte)(g1 >> 3) + ((byte)((blockTop & 0x700) >> 3) >> 5);
			b2 = (byte)(b1 >> 3) + ((byte)((blockTop & 0x70000) >> 11) >> 5);

			r1 |= r1 >> 5;
			g1 |= g1 >> 5;
			b1 |= b1 >> 5;

			r2 = (r2 << 3) | (r2 >> 2);
			g2 = (g2 << 3) | (g2 >> 2);
			b2 = (b2 << 3) | (b2 >> 2);
		} else {
			r1 = blockTop & 0xf0;
			g1 = (blockTop & 0xf000) >> 8;
			b1 = (blockTop & 0xf00000) >> 16;

			r2 = (blockTop & 0xf) << 4;
			g2 = (blockTop & 0xf00) >> 4;
			b2 = (blockTop & 0xf0000) >> 12;

			r1 |= r1 >> 4;
			g1 |= g1 >> 4;
			b1 |= b1 >> 4;

			r2 |= r2 >> 4;
			g2 |= g2 >> 4;
			b2 |= b2 >> 4;
		}
		
		long table1 = (blockTop >> 29) & 7;
		long table2 = (blockTop >> 26) & 7;

		byte[] output = new byte[(4 * 4 * 4)];
		if (!flip) {
			for (int y = 0; y <= 3; y++) {
				for (int x = 0; x <= 1; x++) {
					Color color1 = etc1Pixel((int)r1, (int)g1, (int)b1, x, y, blockBottom, table1);
					Color color2 = etc1Pixel((int)r2, (int)g2, (int)b2, x + 2, y, blockBottom, table2);
					
					int offset1 = (y * 4 + x) * 4;
					output[offset1] = (byte) color1.getBlue();
					output[offset1 + 1] = (byte) color1.getGreen();
					output[offset1 + 2] = (byte) color1.getRed();

					int offset2 = (y * 4 + x + 2) * 4;
					output[offset2] = (byte) color2.getBlue();
					output[offset2 + 1] = (byte) color2.getGreen();
					output[offset2 + 2] = (byte) color2.getRed();
				}
			}
		} else {
			for (int y = 0; y <= 1; y++) {
				for (int x = 0; x <= 3; x++) {
					Color color1 = etc1Pixel((int)r1, (int)g1, (int)b1, x, y, blockBottom, table1);
					Color color2 = etc1Pixel((int)r2, (int)g2, (int)b2, x, y + 2, blockBottom, table2);

					int offset1 = (y * 4 + x) * 4;
					output[offset1] = (byte) color1.getBlue();
					output[offset1 + 1] = (byte) color1.getGreen();
					output[offset1 + 2] = (byte) color1.getRed();

					int offset2 = ((y + 2) * 4 + x) * 4;
					output[offset2] = (byte) color2.getBlue();
					output[offset2 + 1] = (byte) color2.getGreen();
					output[offset2 + 2] = (byte) color2.getRed();
				}
			}
		}
		return output;
	}

	private static Color etc1Pixel(int r, int g, int b, int x, int y, long block, long table) {
		int index = x * 4 + y;
		long MSB = block << 1;

		int pixel = (index < 8)
				? etc1LUT[(int)table][(int)(((block >> (index + 24)) & 1) + ((MSB >> (index + 8)) & 2))]
				: etc1LUT[(int)table][(int)(((block >> (index + 8)) & 1) + ((MSB >> (index - 8)) & 2))];
		
		r = saturate(r + pixel);
		g = saturate(g + pixel);
		b = saturate(b + pixel);

		Color ret = new Color(r, g, b);
		return ret;
	}

	private static int saturate(int value) {
		if (value > 0xff) {
			return 0xff;
		}
		if (value < 0) {
			return 0;
		}
		return value & 0xff;
	}

	private static int[] etc1Scramble(int width, int height) {
		int[] tileScramble = new int[((width / 4) * (height / 4))];
		int baseAccumulator = 0;
		int rowAccumulator = 0;
		int baseNumber = 0;
		int rowNumber = 0;

		for (int tile = 0; tile < tileScramble.length; tile++) {
			if ((tile % (width / 4) == 0) && tile > 0) {
				if (rowAccumulator < 1) {
					rowAccumulator += 1;
					rowNumber += 2;
					baseNumber = rowNumber;
				} else {
					rowAccumulator = 0;
					baseNumber -= 2;
					rowNumber = baseNumber;
				}
			}

			tileScramble[tile] = baseNumber;

			if (baseAccumulator < 1) {
				baseAccumulator++;
				baseNumber++;
			} else {
				baseAccumulator = 0;
				baseNumber += 3;
			}
		}

		return tileScramble;
	}
}
