package ctrmap.formats.h3d;

import ctrmap.formats.h3d.texturing.H3DMaterial;
import ctrmap.util.Bytes;
import java.awt.Dimension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * Reads a block of PICA200 GPU commands - the register writes a BCH stores for a mesh's vertex
 * layout, a texture unit or a material's fragment stage - and answers what the last write to each
 * register said. Ported from Ohana3DS.
 *
 * <p>Only registers something in this program asks about have a getter (F071, F079). The port
 * carried five more as commented-out C# (texture units 0-2's wrap and filter mappers, units 1-2's
 * formats) and the material decoder asked for TEV stages, the fragment buffer colour, blending
 * functions, logic op, stencil, depth and culling, which nothing drew - and each of those decoded
 * an enum by indexing values() with raw bits, so a value it did not know threw and the whole BCH
 * failed to open. Every enum read here goes through {@link Bytes#enumOf}.
 *
 * <p>The command table holds only the registers the block writes. It was a 64K-entry long[] - 512
 * KB per reader, and one is built per mesh, per face group, per material and per texture.
 */
public class PICACommandReader {

	ArrayList<ArrayList<Float>> floatUniform = new ArrayList<>(96);
	List<Float> uniform = new ArrayList<>();

	float[] lookUpTable = new float[256];
	int lutIndex;

	/** The last value written to each register the block wrote; a register it never wrote reads 0. */
	private final Map<Integer, Long> commands = new HashMap<>();
	long currentUniform;

	public PICACommandReader(RandomAccessBAIS input, int wordCount, boolean ignoreAlign) throws IOException {
		int readWords = 0;
		while (readWords < wordCount) {
			long parameter = Integer.toUnsignedLong(input.readInt());
			long header = Integer.toUnsignedLong(input.readInt());
			readWords += 2;

			int id = (int) (header & 0xffff);
			long mask = (header >> 16) & 0xf;
			long extraParameters = (header >> 20) & 0x7ff;
			boolean consecutiveWriting = (header & 0x80000000) > 0;

			commands.put(id, (getParameter(id) & (~mask & 0xf)) | (parameter & (0xfffffff0 | mask)));
			if (id == PICACommand.blockEnd) {
				break;
			} else if (id == PICACommand.vertexShaderFloatUniformConfig) {
				currentUniform = parameter & 0x7fffffff;
			} else if (id == PICACommand.vertexShaderFloatUniformData) {
				uniform.add(toFloat((int) getParameter(id)));
			} else if (id == PICACommand.fragmentShaderLookUpTableData) {
				lookUpTable[lutIndex++] = getParameter(id);
			}
			for (int i = 0; i < extraParameters; i++) {
				if (consecutiveWriting) {
					id++;
				}
				//A REGISTER IS 16 BITS: the 64K table threw ArrayIndexOutOfBounds past its end, and a
				//map would quietly hold whatever a damaged run counted up to
				if (id > 0xffff) {
					throw new IOException("A PICA command block writes past register 0xFFFF - it is damaged.");
				}
				commands.put(id, (getParameter(id) & (~mask & 0xf)) | (input.readInt() & (0xfffffff0 | mask)));
				readWords++;

				if (id > PICACommand.vertexShaderFloatUniformConfig && id < PICACommand.vertexShaderFloatUniformData + 8) {
					uniform.add(toFloat((int) getParameter(id)));
				} else if (id == PICACommand.fragmentShaderLookUpTableData) {
					lookUpTable[lutIndex++] = getParameter(id);
				}
			}

			if (uniform.size() > 0) {
				while (floatUniform.size() <= currentUniform) {
					floatUniform.add(new ArrayList<>());
				}
				floatUniform.get((int) currentUniform).addAll(uniform);
				uniform.clear();
			}
			lutIndex = 0;

			if (!ignoreAlign) {
				while ((input.position() & 7) != 0) {
					input.readInt(); //Ignore 0x0 padding Words
				}
			}
		}
	}

	/** The last parameter written to register {@code commandId}, or 0 if the block never wrote it. */
	public long getParameter(int commandId) {
		Long value = commands.get(commandId);
		return value == null ? 0 : value;
	}

	/** An IEEE 754 float stored in an int. */
	private float toFloat(int value) {
		return Float.intBitsToFloat(value);
	}

	/** The order of every vertex attribute in the main attributes buffer. */
	public PICACommand.vshAttribute[] getVSHAttributesBufferPermutation() {
		long permutation = getParameter(PICACommand.vertexShaderAttributesPermutationLow);
		permutation |= getParameter(PICACommand.vertexShaderAttributesPermutationHigh) << 32;

		PICACommand.vshAttribute[] attributes = new PICACommand.vshAttribute[23];
		for (int attribute = 0; attribute < attributes.length; attribute++) {
			attributes[attribute] = Bytes.enumOf(PICACommand.vshAttribute.values(), (permutation >> (attribute * 4)) & 0xf, null);
		}
		return attributes;
	}

	/** The format of each attribute of the main attributes buffer: its length and its type (byte, float, short...). */
	public PICACommand.attributeFormat[] getVSHAttributesBufferFormat() {
		long format = getParameter(PICACommand.vertexShaderAttributesBufferFormatLow);
		format |= getParameter(PICACommand.vertexShaderAttributesBufferFormatHigh) << 32;

		PICACommand.attributeFormat[] formats = new PICACommand.attributeFormat[23];
		for (int attribute = 0; attribute < formats.length; attribute++) {
			byte value = (byte) ((format >> (attribute * 4)) & 0xf);
			formats[attribute] = new PICACommand.attributeFormat();
			formats[attribute].type = Bytes.enumOf(PICACommand.attributeFormatType.values(), value & 3, null);
			formats[attribute].attributeLength = value >> 2;
		}
		return formats;
	}

	/** The address of attributes buffer {@code bufferIndex} (0-11), relative to the main buffer's. */
	public long getVSHAttributesBufferAddress(int bufferIndex) {
		return getParameter((short) (PICACommand.vertexShaderAttributesBuffer0Address + (bufferIndex * 3)));
	}

	/** How many attributes attributes buffer {@code bufferIndex} (0-11) holds. */
	public long getVSHTotalAttributes(int bufferIndex) {
		long value = getParameter((short) (PICACommand.vertexShaderAttributesBuffer0Stride + (bufferIndex * 3)));
		return value >> 28;
	}

	/**
	 * The permutation of attributes buffer {@code bufferIndex} (0-11): each value is a position in the
	 * main permutation.
	 */
	public int[] getVSHAttributesBufferPermutation(int bufferIndex) {
		long permutation = getParameter((short) (PICACommand.vertexShaderAttributesBuffer0Permutation + (bufferIndex * 3)));
		permutation |= (getParameter((short) (PICACommand.vertexShaderAttributesBuffer0Stride + (bufferIndex * 3))) & 0xffff) << 32;

		int[] attributes = new int[23];
		for (int attribute = 0; attribute < attributes.length; attribute++) {
			attributes[attribute] = (int) ((permutation >> (attribute * 4)) & 0xf);
		}
		return attributes;
	}

	/** The stride of attributes buffer {@code bufferIndex} (0-11). */
	public byte getVSHAttributesBufferStride(int bufferIndex) {
		long value = getParameter((short) (PICACommand.vertexShaderAttributesBuffer0Stride + (bufferIndex * 3)));
		return (byte) ((value >> 16) & 0xff);
	}

	/** The float uniform data written to {@code register} (observed: 6 and 7). */
	public Stack<Float> getVSHFloatUniformData(int register) {
		Stack<Float> data = new Stack<>();
		floatUniform.get(register).forEach((value) -> {
			data.push(value);
		});
		return data;
	}

	/** Where the index buffer is. */
	public long getIndexBufferAddress() {
		return getParameter(PICACommand.indexBufferConfig) & 0x7fffffff;
	}

	/** Whether the index buffer holds bytes or shorts. */
	public PICACommand.indexBufferFormat getIndexBufferFormat() {
		return Bytes.enumOf(PICACommand.indexBufferFormat.values(), getParameter(PICACommand.indexBufferConfig) >> 31, null);
	}

	/** How many vertices the index buffer indexes. */
	public long getIndexBufferTotalVertices() {
		return getParameter(PICACommand.indexBufferTotalVertices);
	}

	/**
	 * The alpha test: whether it is on, its function and its reference. The function is the
	 * register's three bits 4-6 (F079: read as four, so a set bit 7 indexed past the eight functions
	 * and threw).
	 */
	public H3DMaterial.AlphaTest getAlphaTest() {
		H3DMaterial.AlphaTest output = new H3DMaterial.AlphaTest();

		int value = (int) getParameter(PICACommand.alphaTestConfig);
		output.isTestEnabled = (value & 1) > 0;
		output.testFunction = Bytes.enumOf(H3DMaterial.TestFunction.values(), (value >> 4) & 7, null);
		output.testReference = ((value >> 8) & 0xff);

		return output;
	}

	/** Where texture unit 0's texture is. */
	public int getTexUnit0Address() {
		return (int) getParameter(PICACommand.texUnit0Address);
	}

	/** The resolution of texture unit 0's texture. */
	public Dimension getTexUnit0Size() {
		long value = getParameter(PICACommand.texUnit0Size);
		return new Dimension((int) (value >> 16), (int) (value & 0xffff));
	}

	/** How texture unit 0's texture is encoded; a format this reader does not know is refused in words. */
	public TextureFormat getTexUnit0Format() {
		return Bytes.enumOf(TextureFormat.values(), getParameter(PICACommand.texUnit0Type), null);
	}

	public enum TextureFormat {
		rgba8,
		rgb8,
		rgba5551,
		rgb565,
		rgba4,
		la8,
		hilo8,
		l8,
		a8,
		la4,
		l4,
		a4,
		etc1,
		etc1a4,
		dontCare
	}
}
