package ctrmap.formats.h3d.texturing;

import ctrmap.formats.h3d.BCHHeader;
import ctrmap.formats.h3d.PICACommandReader;
import ctrmap.formats.h3d.RandomAccessBAIS;
import ctrmap.formats.h3d.StringUtils;
import ctrmap.util.Bytes;
import java.io.IOException;

/**
 * One material of an H3D model, as the 3D view draws it: its names, its three textures' wrap
 * modes, its blend mode and its alpha test. Ported from Ohana3DS.
 *
 * <p>DECODES WHAT IS DRAWN (F079). The port decoded every field of the material - texture
 * coordinators, six TEV combiner stages, twelve colours, lighting and fog flags and indices,
 * filters, LOD, border colours, stencil, depth, culling, logic op and the user metadata - and
 * nothing read any of it. Each enum among them was decoded by indexing values() with raw bits, so
 * a value the port did not list (a magnification filter byte of 0x80 or more read through a
 * signed shift, for one) threw and the whole BCH failed to open over a field nothing drew. The
 * bytes are still stepped over exactly as they were; what is drawn is pinned by BchSectionsTest's
 * BCHFile materials line, measured on the old decoder.
 */
public class H3DMaterial {

	public String name;

	public String name0;
	public String name1;
	public String name2;

	public H3DTexture texture0;
	public H3DTexture texture1;
	public H3DTexture texture2;

	public Params params = new Params();

	public TextureMapper[] mappers = new TextureMapper[3];

	public H3DMaterial(RandomAccessBAIS in, byte[] data, BCHHeader properties) throws IOException {
		int materialParametersOffset = in.readInt();
		in.readInt(); //TODO
		in.readInt();
		in.readInt();
		in.readInt();   //texture commands offset
		in.readInt();   //texture commands word count

		int materialMapperOffset;
		if (properties.backwardCompatibility < 0x21) {
			materialMapperOffset = in.position();
			in.seek(0x30 + materialMapperOffset);
		} else {
			materialMapperOffset = in.readInt();
		}

		//A ZERO POINTER IS NO NAME (F228): offset 0 of a BCH is its header, never a string. An unused
		//texture slot read whatever sat there - the relocation's stray write until that was fixed,
		//and the magic "BCH" after - as the name of a texture to look for
		int p0 = in.readInt(), p1 = in.readInt(), p2 = in.readInt(), pn = in.readInt();
		name0 = p0 == 0 ? "" : StringUtils.readString(p0, data);
		name1 = p1 == 0 ? "" : StringUtils.readString(p1, data);
		name2 = p2 == 0 ? "" : StringUtils.readString(p2, data);
		name = pn == 0 ? "" : StringUtils.readString(pn, data);

		if (materialParametersOffset != 0) {
			//past the hash and the material flags (lighting, fog, polygon offset: nothing here draws them)
			in.seek(materialParametersOffset + 4 + 2);
			short fragmentFlags = in.readShort();
			params.blendop.mode = Bytes.enumOf(BlendOperation.BlendMode.values(), (fragmentFlags >> 10) & 3, null);

			//stepped over, as the port read them: an int; three texture coordinators (an int and five
			//floats each); the light set and fog indices (two shorts); twelve colours (four bytes
			//each); the colour scale; six ints, an int, 24 bytes and an int; the polygon offset unit
			in.seek(materialParametersOffset + 8 + 4 + 3 * 24 + 2 * 2 + 12 * 4 + 4 + 6 * 4 + 4 + 24 + 4 + 4);
			int fshCommandsOffset = in.readInt();
			int fshCommandsWordCount = in.readInt();

			//Mapper
			in.seek(materialMapperOffset);
			for (int i = 0; i < 3; i++) {
				TextureMapper mapper = new TextureMapper();
				in.seek(materialMapperOffset + i * 16); //each mapper: this word, LOD and min filter, LOD bias, border colour
				int wrapAndMagFilter = in.readInt();
				mapper.wrapU = TextureMapper.wrapOf((wrapAndMagFilter >> 8) & 0xff);
				mapper.wrapV = TextureMapper.wrapOf((wrapAndMagFilter >> 16) & 0xff);

				mappers[i] = mapper;
			}

			//Fragment Shader commands
			in.seek(fshCommandsOffset);
			params.alphaTest = new PICACommandReader(in, fshCommandsWordCount, false).getAlphaTest();
		}
	}

	public static class TextureMapper {

		public TextureWrap wrapU, wrapV;

		/**
		 * The wrap mode {@code raw} names, or repeat for one the port did not list (F079): repeat is
		 * what the renderer has always drawn for an unlisted mode, and a texture drawn repeating is a
		 * better answer than a model that does not open. Which GL wrap each draws as is the
		 * renderer's ({@code ctrmap.render.ModelRenderer.glWrap}, F214).
		 */
		static TextureWrap wrapOf(int raw) {
			return Bytes.enumOf(TextureWrap.values(), raw, TextureWrap.repeat);
		}

		public enum TextureWrap {
			clampToEdge,
			clampToBorder,
			repeat,
			mirroredRepeat
		}
	}

	public static class Params {

		public BlendOperation blendop = new BlendOperation();
		public AlphaTest alphaTest;
	}

	public static class AlphaTest {

		public boolean isTestEnabled;
		public TestFunction testFunction;
		public int testReference;
	}

	public enum TestFunction {
		never,
		always,
		equal,
		notEqual,
		less,
		lessOrEqual,
		greater,
		greaterOrEqual
	}

	public static class BlendOperation {

		public BlendMode mode;

		public enum BlendMode {
			logical,
			dummy,
			notUsed,
			blend
		}
	}
}
