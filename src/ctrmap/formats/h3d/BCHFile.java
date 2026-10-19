package ctrmap.formats.h3d;

import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.texturing.H3DMaterial;
import ctrmap.formats.h3d.texturing.H3DTexture;
import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import ctrmap.util.LittleEndian;

/**
 * Based on gdkchan's Ohana3DS and Rebirth, mostly a copy-paste
 */
public class BCHFile {

	private byte[] buf;

	public BCHHeader header;
	public BCHContentHeader contentHeader;
	public ArrayList<H3DModel> models = new ArrayList<>();
	public ArrayList<H3DTexture> textures = new ArrayList<>();

	public int errorlevel = 0;

	public BCHFile(byte[] src) {
		try {
			buf = src.clone();
			RandomAccessBAIS in = new RandomAccessBAIS(buf);
			header = new BCHHeader();
			if (!BchSections.isBch(buf)) {
				//NOT A BCH IS AN ERROR, NOT AN EMPTY ONE. This returned with errorlevel 0, so every
				//caller that asks errorlevel read bytes that are not a BCH as one holding no models
				//and no textures - a merged prop pack that would not read came back "holds 0 of 1"
				//instead of refused, once the check here grew from the magic to the whole header (F066)
				errorlevel++;
				return;
			}
			//the header and the relocation words, read by the one reader of both (F066); this
			//loader keeps its own version-by-version meaning of the words below
			BchSections s;
			try {
				s = BchSections.read(buf);
			} catch (IllegalArgumentException notWhole) {
				throw new IOException(ctrmap.util.Bytes.reason(notWhole), notWhole);
			}
			header.magic = "BCH";
			header.backwardCompatibility = (byte) s.bc;
			header.forwardCompatibility = (byte) s.fc;
			header.version = (short) s.version;

			header.mainHeaderOffset = s.contentsAddr;
			header.stringTableOffset = s.stringsAddr;
			header.gpuCommandsOffset = s.commandsAddr;
			header.dataOffset = s.rawDataAddr;
			header.dataExtendedOffset = s.rawExtAddr;
			header.relocationTableOffset = s.relocAddr;

			header.mainHeaderLength = s.contentsLen;
			header.stringTableLength = s.stringsLen;
			header.gpuCommandsLength = s.commandsLen;
			header.dataLength = s.rawDataLen;
			header.dataExtendedLength = s.rawExtLen;
			header.relocationTableLength = s.relocLen;

			header.uninitializedDataSectionLength = s.uninitData;
			header.uninitializedDescriptionSectionLength = s.uninitCmd;

			header.flags = (short) s.flags;
			header.addressCount = (short) s.addressCount;

			//Normally we would parse the content header now. But first, we need to absolutize the offsets for it not to be a living hell.
			for (int value : s.relocWords) {
				int offset = value & 0x1ffffff;
				byte flags = (byte) (value >> 25);
				int seek;

				switch (flags) {
					case 0:
						seek = (offset * 4) + header.mainHeaderOffset;
						relocateOffset(seek, header.mainHeaderOffset, buf);
						break;

					case 1:
						seek = offset + header.mainHeaderOffset;
						relocateOffset(seek, header.stringTableOffset, buf);
						break;

					case 2:
						seek = (offset * 4) + header.mainHeaderOffset;
						relocateOffset(seek, header.gpuCommandsOffset, buf);
						break;

					case 7:
					case 0xc:
						seek = (offset * 4) + header.mainHeaderOffset;
						relocateOffset(seek, header.dataOffset, buf);
						break;
				}
				seek = (offset * 4) + header.gpuCommandsOffset;
				if (header.backwardCompatibility < 6) {
					switch (flags) {
						case 0x23:
							relocateOffset(seek, header.dataOffset, buf);
							break; //Texture
						case 0x25:
							relocateOffset(seek, header.dataOffset, buf);
							break; //Vertex
						case 0x26:
							changeValueTo(seek, ((peek(seek, buf) + header.dataOffset) & 0x7fffffff) | 0x80000000, buf);
							break; //Index 16 bits mode
						case 0x27:
							changeValueTo(seek, (peek(seek, buf) + header.dataOffset) & 0x7fffffff, buf);
							break; //Index 8 bits mode
					}
				} else if (header.backwardCompatibility < 8) {
					switch (flags) {
						case 0x24:
							relocateOffset(seek, header.dataOffset, buf);
							break; //Texture
						case 0x26:
							relocateOffset(seek, header.dataOffset, buf);
							break; //Vertex
						case 0x27:
							changeValueTo(seek, ((peek(seek, buf) + header.dataOffset) & 0x7fffffff) | 0x80000000, buf);
							break; //Index 16 bits mode
						case 0x28:
							changeValueTo(seek, (peek(seek, buf) + header.dataOffset) & 0x7fffffff, buf);
							break; //Index 8 bits mode
					}
				} else if (header.backwardCompatibility < 0x21) {
					switch (flags) {
						case 0x25:
							relocateOffset(seek, header.dataOffset, buf);
							break; //Texture
						case 0x27:
							relocateOffset(seek, header.dataOffset, buf);
							break; //Vertex
						case 0x28:
							changeValueTo(seek, ((peek(seek, buf) + header.dataOffset) & 0x7fffffff) | 0x80000000, buf);
							break; //Index 16 bits mode
						case 0x29:
							changeValueTo(seek, (peek(seek, buf) + header.dataOffset) & 0x7fffffff, buf);
							break; //Index 8 bits mode
					}
				} else {
					switch (flags) {
						case 0x25:
							relocateOffset(seek, header.dataOffset, buf);
							break; //Texture
						case 0x26:
							relocateOffset(seek, header.dataOffset, buf);
							break; //Vertex relative to Data Offset
						case 0x27:
							changeValueTo(seek, ((peek(seek, buf) + header.dataOffset) & 0x7fffffff) | 0x80000000, buf);
							break; //Index 16 bits mode relative to Data Offset
						case 0x28:
							changeValueTo(seek, (peek(seek, buf) + header.dataOffset) & 0x7fffffff, buf);
							break; //Index 8 bits mode relative to Data Offset
						case 0x2b:
							relocateOffset(seek, header.dataExtendedOffset, buf);
							break; //Vertex relative to Data Extended Offset
						case 0x2c:
							changeValueTo(seek, ((peek(seek, buf) + header.dataExtendedOffset) & 0x7fffffff) | 0x80000000, buf);
							break; //Index 16 bits mode relative to Data Extended Offset
						case 0x2d:
							changeValueTo(seek, (peek(seek, buf) + header.dataExtendedOffset) & 0x7fffffff, buf);
							break; //Index 8 bits mode relative to Data Extended Offset
					}
				}
			}

			if (errorlevel > 0) {
				return;
			}

			in.seek(header.mainHeaderOffset);
			contentHeader = new BCHContentHeader();
			contentHeader.modelsPointerTableOffset = in.readInt();
			contentHeader.modelsPointerTableEntries = in.readInt();
			contentHeader.modelsNameOffset = in.readInt();
			contentHeader.materialsPointerTableOffset = in.readInt();
			contentHeader.materialsPointerTableEntries = in.readInt();
			contentHeader.materialsNameOffset = in.readInt();
			contentHeader.shadersPointerTableOffset = in.readInt();
			contentHeader.shadersPointerTableEntries = in.readInt();
			contentHeader.shadersNameOffset = in.readInt();
			contentHeader.texturesPointerTableOffset = in.readInt();
			contentHeader.texturesPointerTableEntries = in.readInt();
			contentHeader.texturesNameOffset = in.readInt();
			contentHeader.materialsLUTPointerTableOffset = in.readInt();
			contentHeader.materialsLUTPointerTableEntries = in.readInt();
			contentHeader.materialsLUTNameOffset = in.readInt();
			contentHeader.lightsPointerTableOffset = in.readInt();
			contentHeader.lightsPointerTableEntries = in.readInt();
			contentHeader.lightsNameOffset = in.readInt();
			contentHeader.camerasPointerTableOffset = in.readInt();
			contentHeader.camerasPointerTableEntries = in.readInt();
			contentHeader.camerasNameOffset = in.readInt();
			contentHeader.fogsPointerTableOffset = in.readInt();
			contentHeader.fogsPointerTableEntries = in.readInt();
			contentHeader.fogsNameOffset = in.readInt();
			contentHeader.skeletalAnimationsPointerTableOffset = in.readInt();
			contentHeader.skeletalAnimationsPointerTableEntries = in.readInt();
			contentHeader.skeletalAnimationsNameOffset = in.readInt();
			contentHeader.materialAnimationsPointerTableOffset = in.readInt();
			contentHeader.materialAnimationsPointerTableEntries = in.readInt();
			contentHeader.materialAnimationsNameOffset = in.readInt();
			contentHeader.visibilityAnimationsPointerTableOffset = in.readInt();
			contentHeader.visibilityAnimationsPointerTableEntries = in.readInt();
			contentHeader.visibilityAnimationsNameOffset = in.readInt();
			contentHeader.lightAnimationsPointerTableOffset = in.readInt();
			contentHeader.lightAnimationsPointerTableEntries = in.readInt();
			contentHeader.lightAnimationsNameOffset = in.readInt();
			contentHeader.cameraAnimationsPointerTableOffset = in.readInt();
			contentHeader.cameraAnimationsPointerTableEntries = in.readInt();
			contentHeader.cameraAnimationsNameOffset = in.readInt();
			contentHeader.fogAnimationsPointerTableOffset = in.readInt();
			contentHeader.fogAnimationsPointerTableEntries = in.readInt();
			contentHeader.fogAnimationsNameOffset = in.readInt();
			contentHeader.scenePointerTableOffset = in.readInt();
			contentHeader.scenePointerTableEntries = in.readInt();
			contentHeader.sceneNameOffset = in.readInt();

			//Textures
			for (int textureIndex = 0; textureIndex < contentHeader.texturesPointerTableEntries; textureIndex++) {
				in.seek(contentHeader.texturesPointerTableOffset + (textureIndex * 4));
				textures.add(new H3DTexture(in, buf));
			}

			//Models
			for (int modelIndex = 0; modelIndex < contentHeader.modelsPointerTableEntries; modelIndex++) {
				in.seek(contentHeader.modelsPointerTableOffset + (modelIndex * 4));
				H3DModel model = new H3DModel(in, buf, header);
				//Materials
				for (int index = 0; index < model.modelHeader.materialsTableEntries; index++) {
					if (header.backwardCompatibility < 0x21) {
						in.seek(model.modelHeader.materialsTableOffset + (index * 0x58));
					} else {
						in.seek(model.modelHeader.materialsTableOffset + (index * 0x2c));
					}
					model.materials.add(new H3DMaterial(in, buf, header));
				}
				models.add(model);
			}
		} catch (IOException ex) {
			errorlevel++;
			//NOTHING, NEVER PART: what was read before the failure was kept, so a reader that does not ask
			//errorlevel - 11 of 19 - took half a pack as the whole one. BchSectionsTest classifies every reader
			models.clear();
			textures.clear();
			Logger.getLogger(BCHFile.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	public static int peek(int offset, byte[] b) {
		return LittleEndian.i32(b, offset);
	}

	public void relocateOffset(int offsetOfTheOffset, int relocationMod, byte[] b) {
		try {
			//NOT a wrapped buffer's putInt(0, ...) (F228): that index is absolute, so the first four
			//bytes of the whole array - the magic - were overwritten by every relocation
			LittleEndian.putI32(b, offsetOfTheOffset, LittleEndian.i32(b, offsetOfTheOffset) + relocationMod);
		} catch (Exception e) {
			errorlevel++;
			Logger.getLogger(BCHFile.class.getName()).log(Level.SEVERE, null, e);
		}
	}

	public static void changeValueTo(int offset, int newValue, byte[] b) {
		LittleEndian.putI32(b, offset, newValue);
	}
}
