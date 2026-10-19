package ctrmap.formats.h3d.model;

import ctrmap.formats.h3d.BCHHeader;
import ctrmap.formats.h3d.PICACommand;
import ctrmap.formats.h3d.PICACommandReader;
import ctrmap.formats.h3d.RandomAccessBAIS;
import ctrmap.formats.h3d.StringUtils;
import ctrmap.formats.h3d.texturing.H3DMaterial;
import ctrmap.formats.h3d.texturing.H3DTexture;
import ctrmap.formats.vectors.Vec2d;
import ctrmap.formats.vectors.Vec3f;
import ctrmap.formats.vectors.Vec4f;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

public class H3DModel {
	
	public float worldLocX = 0f;
	public float worldLocY = 0f;
	public float worldLocZ = 0f;
	
	public float scaleX = 1f;
	public float scaleY = 1f;
	public float scaleZ = 1f;
	
	public float rotationX = 0f;
	public float rotationY = 0f;
	public float rotationZ = 0f;
	
	public BCHModelHeader modelHeader;
	
	public String name;
	public int layerId;
	public List<H3DMesh> meshes = new ArrayList<>();
	public Transform4 transform;
	
	public Vec3f minVector = new Vec3f();
	public Vec3f maxVector = new Vec3f();
	public float[][] boxVectors = new float[32][];
	
	public List<H3DMaterial> materials = new ArrayList<>();
	
	public H3DSkeleton skeleton;
	
	
	/**
	 * Reads one model out of a BCH: the model header, the object-name
	 * dictionary, the skeleton, the mesh table, then for every mesh its
	 * vertex-shader uniforms (the eight scales and the position offset), its
	 * faces, and every vertex of every face decoded attribute by attribute.
	 * A port of Ohana's reader, section comments mark the phases.
	 *
	 * <p>LEFT AS ONE METHOD, DELIBERATELY. The candidate seam is the
	 * per-vertex decode (the inner {@code switch (att)}), but it reads
	 * fourteen values the outer loops set up - the attribute permutation and
	 * format tables, the eight scales, the offset, the node list, the
	 * skinning mode and the skeleton - and writes the mesh's flags, the
	 * vertex and the model's bounds. Handing all of that to a
	 * {@code readVertex(...)} would be a fourteen-parameter call, or those
	 * fourteen hoisted into fields: the same tangle, spread over two places.
	 * The header read (the first forty lines) is straight-line and reads as
	 * itself. What would make this decomposable is a VertexLayout value
	 * (permutation, formats, scales) that the whole mesh shares, and that
	 * is a design change for when this reader is next touched for a reason
	 * of its own, not a split for the sake of a shorter method.
	 */
	public H3DModel(RandomAccessBAIS in, byte[] buf, BCHHeader properties) throws IOException {
		int objectsHeaderOffset = in.readInt();

		//Objects header
		in.seek(objectsHeaderOffset);
		modelHeader = new BCHModelHeader();
		modelHeader.flags = in.readByte();
		modelHeader.skeletonScalingType = in.readByte();
		modelHeader.silhouetteMaterialEntries = in.readShort();
		
		modelHeader.worldTransform = new Transform4();
		modelHeader.worldTransform.cells()[0] = in.readFloat();
		modelHeader.worldTransform.cells()[1] = in.readFloat();
		modelHeader.worldTransform.cells()[2] = in.readFloat();
		modelHeader.worldTransform.cells()[3] = in.readFloat();
		
		modelHeader.worldTransform.cells()[4] = in.readFloat();
		modelHeader.worldTransform.cells()[5] = in.readFloat();
		modelHeader.worldTransform.cells()[6] = in.readFloat();
		modelHeader.worldTransform.cells()[7] = in.readFloat();
		
		modelHeader.worldTransform.cells()[8] = in.readFloat();
		modelHeader.worldTransform.cells()[9] = in.readFloat();
		modelHeader.worldTransform.cells()[10] = in.readFloat();
		modelHeader.worldTransform.cells()[11] = in.readFloat();
		
		modelHeader.materialsTableOffset = in.readInt();
		modelHeader.materialsTableEntries = in.readInt();
		modelHeader.materialsNameOffset = in.readInt();
		modelHeader.verticesTableOffset = in.readInt();
		modelHeader.verticesTableEntries = in.readInt();
		in.skip(properties.backwardCompatibility > 6 ? 0x28 : 0x20);
		modelHeader.skeletonOffset = in.readInt();
		modelHeader.skeletonEntries = in.readInt();
		modelHeader.skeletonNameOffset = in.readInt();
		modelHeader.objectsNodeVisibilityOffset = in.readInt();
		modelHeader.objectsNodeCount = in.readInt();
		int modelNameOffset = in.readInt();
		modelHeader.modelName = StringUtils.readString(modelNameOffset, buf);
		modelHeader.objectsNodeNameEntries = in.readInt();
		modelHeader.objectsNodeNameOffset = in.readInt();
		in.skip(4);
		modelHeader.metaDataPointerOffset = in.readInt();
		
		transform = modelHeader.worldTransform;
		name = modelHeader.modelName;
		
		modelHeader.objectNames = new String[modelHeader.objectsNodeNameEntries];
		in.seek(modelHeader.objectsNodeNameOffset);
		//the root node - reference bit, left and right node, name offset - is stepped over
		in.readInt();
		in.readShort();
		in.readShort();
		in.readInt();
		for (int i = 0; i < modelHeader.objectsNodeNameEntries; i++) {
			in.readInt();   //reference bit
			in.readUnsignedShort();   //left node
			in.readUnsignedShort();   //right node
			modelHeader.objectNames[i] = StringUtils.readString(in.readInt(), buf);
		}

		//Skeleton
		in.seek(modelHeader.skeletonOffset);
		skeleton = new H3DSkeleton(in, buf, modelHeader.skeletonEntries, name);
		
		in.seek(modelHeader.objectsNodeVisibilityOffset);
		int nodeVisibility = in.readInt();

		//Vertices header
		in.seek(modelHeader.verticesTableOffset);
		for (int index = 0; index < modelHeader.verticesTableEntries; index++) {
			H3DMesh objectEntry = new H3DMesh();
			objectEntry.materialId = in.readUnsignedShort();
			short flags = in.readShort();
			
			if (properties.backwardCompatibility != 8) {
				objectEntry.isSilhouette = (flags & 1) > 0;
			}
			objectEntry.nodeId = in.readUnsignedShort();
			objectEntry.renderPriority = in.readUnsignedShort();
			objectEntry.vshAttributesBufferCommandsOffset = in.readInt(); //Buffer 0
			objectEntry.vshAttributesBufferCommandsWordCount = in.readInt();
			objectEntry.facesHeaderOffset = in.readInt();
			objectEntry.facesHeaderEntries = in.readInt();
			objectEntry.vshExtraAttributesBufferCommandsOffset = in.readInt(); //Buffers 1-11
			objectEntry.vshExtraAttributesBufferCommandsWordCount = in.readInt();
			objectEntry.centerVector = new Vec3f(in.readFloat(), in.readFloat(), in.readFloat());
			objectEntry.flagsOffset = in.readInt();
			in.skip(4);
			objectEntry.boundingBoxOffset = in.readInt();
			
			meshes.add(objectEntry);
		}
		for (int objIndex = 0; objIndex < meshes.size(); objIndex++) {
			H3DMesh obj = meshes.get(objIndex);
			if (obj.nodeId < modelHeader.objectNames.length) {
				obj.name = modelHeader.objectNames[obj.nodeId];
			} else {
				obj.name = "mesh" + objIndex;
			}
			obj.isVisible = (nodeVisibility & (1 << obj.nodeId)) > 0;
			if (obj.isSilhouette) {
				continue;
			}
			//Vertices
			in.seek(obj.vshAttributesBufferCommandsOffset);
			PICACommandReader vshCommands = new PICACommandReader(in, obj.vshAttributesBufferCommandsWordCount, false);
			
			Stack<Float> vshAttributesUniformReg6 = vshCommands.getVSHFloatUniformData(6);
			Stack<Float> vshAttributesUniformReg7 = vshCommands.getVSHFloatUniformData(7);
			Vec4f positionOffset = new Vec4f(
					vshAttributesUniformReg6.pop(),
					vshAttributesUniformReg6.pop(),
					vshAttributesUniformReg6.pop(),
					vshAttributesUniformReg6.pop()
			);
			float texture0Scale = vshAttributesUniformReg7.pop();
			float texture1Scale = vshAttributesUniformReg7.pop();
			float texture2Scale = vshAttributesUniformReg7.pop();
			float boneWeightScale = vshAttributesUniformReg7.pop();
			float positionScale = vshAttributesUniformReg7.pop();
			float normalScale = vshAttributesUniformReg7.pop();
			float tangentScale = vshAttributesUniformReg7.pop();
			float colorScale = vshAttributesUniformReg7.pop();

			//Faces
			int facesCount = obj.facesHeaderEntries;
			boolean hasFaces = facesCount > 0;
			int facesTableOffset = 0;
			if (!hasFaces) {
				in.seek(modelHeader.verticesTableOffset + modelHeader.verticesTableEntries * 0x38);
				in.seek(objIndex * 0x1c + 0x10 + in.position());
				
				facesTableOffset = in.readInt();
				facesCount = in.readInt();
			}
			try {
				
				for (int f = 0; f < facesCount; f++) {
					ArrayList<Integer> nodeList = new ArrayList<>();
					int idxBufferOffset;
					PICACommand.indexBufferFormat idxBufferFormat;
					int idxBufferTotalVertices;
					SkinningMode skinningMode = SkinningMode.none;
					
					if (hasFaces) {
						int baseOffset = obj.facesHeaderOffset + f * 0x34;
						in.seek(baseOffset);
						//a mode the port does not list is drawn as stored, unposed (F079): it threw, and the
						//whole model failed to open
						skinningMode = ctrmap.util.Bytes.enumOf(SkinningMode.values(), in.readUnsignedShort(), SkinningMode.none);
						int nodeIdEntries = in.readUnsignedShort();
						for (int n = 0; n < nodeIdEntries; n++) {
							nodeList.add(in.readUnsignedShort());
						}
						
						in.seek(baseOffset + 0x2c);
						int faceHeaderOffset = in.readInt();
						int faceHeaderWordCount = in.readInt();
						in.seek(faceHeaderOffset);
						PICACommandReader idxCommands = new PICACommandReader(in, faceHeaderWordCount, false);
						idxBufferOffset = (int) idxCommands.getIndexBufferAddress();
						idxBufferFormat = idxCommands.getIndexBufferFormat();
						idxBufferTotalVertices = (int) idxCommands.getIndexBufferTotalVertices();
					} else {
						in.seek(facesTableOffset + f * 8);
						
						idxBufferOffset = in.readInt();
						idxBufferFormat = PICACommand.indexBufferFormat.unsignedShort;
						idxBufferTotalVertices = in.readInt();
					}
					
					int vshAttributesBufferOffset = (int) vshCommands.getVSHAttributesBufferAddress(0);
					int vshAttributesBufferStride = vshCommands.getVSHAttributesBufferStride(0);
					int vshTotalAttributes = (int) vshCommands.getVSHTotalAttributes(0);
					PICACommand.vshAttribute[] vshMainAttributesBufferPermutation = vshCommands.getVSHAttributesBufferPermutation();
					int[] vshAttributesBufferPermutation = vshCommands.getVSHAttributesBufferPermutation(0);
					PICACommand.attributeFormat[] vshAttributesBufferFormat = vshCommands.getVSHAttributesBufferFormat();
					for (int attribute = 0; attribute < vshTotalAttributes; attribute++) {
						switch (vshMainAttributesBufferPermutation[vshAttributesBufferPermutation[attribute]]) {
							case normal:
								obj.hasNormal = true;
								break;
							case tangent:
								obj.hasTangent = true;
								break;
							case color:
								obj.hasColor = true;
								break;
							case textureCoordinate0:
								obj.texUVCount = Math.max(obj.texUVCount, 1);
								break;
							case textureCoordinate1:
								obj.texUVCount = Math.max(obj.texUVCount, 2);
								break;
							case textureCoordinate2:
								obj.texUVCount = Math.max(obj.texUVCount, 3);
								break;
						}
					}
					
					if (nodeList.size() > 0) {
						obj.hasNode = true;
						obj.hasWeight = true;
					}
					
					in.seek(idxBufferOffset);
					for (int faceIndex = 0; faceIndex < idxBufferTotalVertices; faceIndex++) {
						int index = 0;
						switch (idxBufferFormat) {
							case unsignedShort:
								index = in.readUnsignedShort();
								break;
							case unsignedByte:
								index = in.read();
								break;
						}
						
						int dataPosition = in.position();
						int vertexOffset = vshAttributesBufferOffset + (index * vshAttributesBufferStride);
						in.seek(vertexOffset);
						
						H3DVertex vertex = new H3DVertex();
						vertex.diffuseColor = 0xffffffff;
						for (int attribute = 0; attribute < vshTotalAttributes; attribute++) {
							PICACommand.vshAttribute att = vshMainAttributesBufferPermutation[vshAttributesBufferPermutation[attribute]];
							PICACommand.attributeFormat format = vshAttributesBufferFormat[vshAttributesBufferPermutation[attribute]];
							if (att == PICACommand.vshAttribute.boneWeight) {
								format.type = PICACommand.attributeFormatType.unsignedByte;
							}
							Vec4f vector = getVector(in, format);
							
							switch (att) {
								case position:
									float x = (vector.x * positionScale) + positionOffset.x;
									float y = (vector.y * positionScale) + positionOffset.y;
									float z = (vector.z * positionScale) + positionOffset.z;
									vertex.position = new Vec3f(x, y, z);
									break;
								case normal:
									vertex.normal = new Vec3f(vector.x * normalScale, vector.y * normalScale, vector.z * normalScale);
									break;
								case tangent:
									vertex.tangent = new Vec3f(vector.x * tangentScale, vector.y * tangentScale, vector.z * tangentScale);
									break;
								case color:
									int r = OhanaMeshUtils.saturate((vector.x * colorScale) * 0xff);
									int g = OhanaMeshUtils.saturate((vector.y * colorScale) * 0xff);
									int b = OhanaMeshUtils.saturate((vector.z * colorScale) * 0xff);
									int a = OhanaMeshUtils.saturate((vector.w * colorScale) * 0xff);
									//vertex.diffuseColor = new Color(r, g, b, a).getRGB();
									vertex.diffuseColor = b | (g << 8) | (r << 16) | (a << 24);
									break;
								case textureCoordinate0:
									vertex.texture0 = new Vec2d(vector.x * texture0Scale, vector.y * texture0Scale);
									break;
								case textureCoordinate1:
									vertex.texture1 = new Vec2d(vector.x * texture1Scale, vector.y * texture1Scale);
									break;
								case textureCoordinate2:
									vertex.texture2 = new Vec2d(vector.x * texture2Scale, vector.y * texture2Scale);
									break;
								case boneIndex:
									vertex.node.add(nodeList.get((int) vector.x));
									if (skinningMode == SkinningMode.smoothSkinning) {
										if (format.attributeLength > 0) {
											vertex.node.add(nodeList.get((int) vector.y));
										}
										if (format.attributeLength > 1) {
											vertex.node.add(nodeList.get((int) vector.z));
										}
										if (format.attributeLength > 2) {
											vertex.node.add(nodeList.get((int) vector.w));
										}
									}
									break;
								case boneWeight:
									vertex.weight.add(vector.x * boneWeightScale);
									if (skinningMode == SkinningMode.smoothSkinning) {
										if (format.attributeLength > 0) {
											vertex.weight.add(vector.y * boneWeightScale);
										}
										if (format.attributeLength > 1) {
											vertex.weight.add(vector.z * boneWeightScale);
										}
										if (format.attributeLength > 2) {
											vertex.weight.add(vector.w * boneWeightScale);
										}
									}
									break;
							}
						}

						//If the node list have 4 or less bones, then there is no need to store the indices per vertex
						//Instead, the entire list is used, since it supports up to 4 bones.
						if (vertex.node.isEmpty() && nodeList.size() <= 4) {
							for (int n = 0; n < nodeList.size(); n++) {
								vertex.node.add(nodeList.get(n));
							}
							if (vertex.weight.isEmpty()) {
								vertex.weight.add(1f);
							}
						}
						
						if (skinningMode != SkinningMode.smoothSkinning && vertex.node.size() > 0) {
							//Note: Rigid skinning can have only one bone per vertex
							//Note2: Vertex with Rigid skinning seems to be always have meshes centered, so is necessary to make them follow the skeleton
							if (vertex.weight.isEmpty()) {
								vertex.weight.add(1f);
							}
							vertex.position.x += skeleton.transform.get(vertex.node.get(0)).cells()[12] * vertex.weight.get(0);
							vertex.position.y += skeleton.transform.get(vertex.node.get(0)).cells()[13] * vertex.weight.get(0);
							vertex.position.z += skeleton.transform.get(vertex.node.get(0)).cells()[14] * vertex.weight.get(0);
						}
						
						OhanaMeshUtils.calculateBounds(this, vertex);
						obj.vertices.add(vertex);
						
						in.seek(dataPosition);
					}
				}
			} catch (EOFException e) {
				e.printStackTrace();
			}
		}
		makeBox();
	}
	
	public void makeBox() {
		OhanaMeshUtils.makeBox(boxVectors, minVector, maxVector);
	}

	
	public void makeAllBOs() {
		for (int i = 0; i < meshes.size(); i++) {
			if (materials.size() > meshes.get(i).materialId) {
				meshes.get(i).makeBOs(materials.get(meshes.get(i).materialId));
			} else {
				meshes.get(i).makeBOs(null);
			}
			meshes.get(i).doVBOUpdate = true;
			meshes.get(i).useVBO = true;
		}
	}
	
	public void setMaterialTextures(List<H3DTexture> textures) {
		for (int i = 0; i < materials.size(); i++) {
			H3DMaterial mat = materials.get(i);
			boolean[] complete = new boolean[3];
			for (int j = 0; j < textures.size(); j++) {
				if (textures.get(j).textureName.equals(mat.name0) && !"projection_dummy".equals(mat.name0)) {
					mat.texture0 = textures.get(j);
					complete[0] = true;
				}
				if (textures.get(j).textureName.equals(mat.name1) && !"projection_dummy".equals(mat.name1)) {
					mat.texture1 = textures.get(j);
					complete[1] = true;
				}
				if (textures.get(j).textureName.equals(mat.name2)) {
					mat.texture2 = textures.get(j);
					complete[2] = true;
				}
				if (complete[0] && complete[1] && complete[2]) {
					break;
				}
			}
		}
	}
	
	
	public enum SkinningMode {
		none,
		smoothSkinning,
		rigidSkinning
	}
	
	private static Vec4f getVector(RandomAccessBAIS input, PICACommand.attributeFormat format) throws IOException {
		Vec4f output = new Vec4f();
		
		switch (format.type) {
			case signedByte:
				output.x = input.readByte();
				if (format.attributeLength > 0) {
					output.y = input.readByte();
				}
				if (format.attributeLength > 1) {
					output.z = input.readByte();
				}
				if (format.attributeLength > 2) {
					output.w = input.readByte();
				}
				break;
			case unsignedByte:
				output.x = input.read();
				if (format.attributeLength > 0) {
					output.y = input.read();
				}
				if (format.attributeLength > 1) {
					output.z = input.read();
				}
				if (format.attributeLength > 2) {
					output.w = input.read();
				}
				break;
			case signedShort:
				output.x = input.readShort();
				if (format.attributeLength > 0) {
					output.y = input.readShort();
				}
				if (format.attributeLength > 1) {
					output.z = input.readShort();
				}
				if (format.attributeLength > 2) {
					output.w = input.readShort();
				}
				break;
			case single:
				output.x = input.readFloat();
				if (format.attributeLength > 0) {
					output.y = input.readFloat();
				}
				if (format.attributeLength > 1) {
					output.z = input.readFloat();
				}
				if (format.attributeLength > 2) {
					output.w = input.readFloat();
				}
				break;
		}
		
		return output;
	}
	
	public static class H3DMesh {
		
		public String name;
		
		public int materialId;
		public boolean isSilhouette;
		public int nodeId;
		public int renderPriority;
		public int vshAttributesBufferCommandsOffset;
		public int vshAttributesBufferCommandsWordCount;
		public int facesHeaderOffset;
		public int facesHeaderEntries;
		public int vshExtraAttributesBufferCommandsOffset;
		public int vshExtraAttributesBufferCommandsWordCount;
		public int flagsOffset;
		public int boundingBoxOffset;
		public boolean hasNormal;
		public boolean hasTangent;
		public boolean hasColor;
		public boolean hasNode;
		public boolean hasWeight;
		public boolean isVisible;
		public int texUVCount;
		
		public ArrayList<H3DVertex> vertices = new ArrayList<>();
		public Vec3f centerVector;
		
		public FloatBuffer vbo;
		public ByteBuffer vcbo;
		public DoubleBuffer[] tcbo;
		
		public int texindex;
		public int textureWidth;
		public int textureHeight;
		public byte[] textureData;
		
		public boolean doVBOUpdate = true;
		public boolean useVBO = false;
		
		/**
		 * The buffer and texture names ctrmap.render.ModelRenderer was given for this mesh, per GL
		 * context it was drawn in - kept here, as plain ints, so the renderer keeps no state (F214).
		 */
		public Map<Object, int[]> glBufPtrs = new HashMap<>();
		public Map<Object, int[]> glTexturePtrs = new HashMap<>();
		
		public void makeBOs(H3DMaterial mat) { //maybe someday?
			vbo = FloatBuffer.allocate(vertices.size() * 3);
			vcbo = ByteBuffer.allocateDirect(vertices.size() * 4);
			tcbo = new DoubleBuffer[3];
			boolean mirrorx0 = false;
			boolean mirrorx1 = false;
			boolean mirrorx2 = false;
			
			if (mat != null) {
				tcbo[0] = DoubleBuffer.allocate(vertices.size() * 2);
				tcbo[1] = DoubleBuffer.allocate(vertices.size() * 2);
				tcbo[2] = DoubleBuffer.allocate(vertices.size() * 2);
				
				if (mat.mappers[0].wrapU == H3DMaterial.TextureMapper.TextureWrap.mirroredRepeat) {
					mirrorx0 = true;
				}
				if (mat.mappers[1].wrapU == H3DMaterial.TextureMapper.TextureWrap.mirroredRepeat) {
					mirrorx1 = true;
				}
				if (mat.mappers[2].wrapU == H3DMaterial.TextureMapper.TextureWrap.mirroredRepeat) {
					mirrorx2 = true;
				}
				if (!(mat.texture0 == null && mat.texture1 == null && mat.texture2 == null)) {
					if (mat.texture0 != null) {
						texindex = 0;
						textureWidth = mat.texture0.textureSize.width;
						textureHeight = mat.texture0.textureSize.height;
						textureData = mat.texture0.textureData;
					} else if (mat.texture1 != null) {
						texindex = 1;
						textureWidth = mat.texture1.textureSize.width;
						textureHeight = mat.texture1.textureSize.height;
						textureData = mat.texture1.textureData;
					} else {
						texindex = 2;
						textureWidth = mat.texture2.textureSize.width;
						textureHeight = mat.texture2.textureSize.height;
						textureData = mat.texture2.textureData;
					}
				}
			}
			for (int i = 0; i < vertices.size(); i++) {
				H3DVertex v = vertices.get(i);
				vbo.put(v.position.x);
				vbo.put(v.position.y);
				vbo.put(v.position.z);
				
				if (mat == null || !(mat.name.contains("blc") || mat.name0.equals("enc_grass"))) {
					vcbo.put((byte) (v.diffuseColor >> 16 & 0xFF));
					vcbo.put((byte) (v.diffuseColor >> 8 & 0xFF));
					vcbo.put((byte) (v.diffuseColor & 0xFF));
					vcbo.put((byte) (v.diffuseColor >> 24 & 0xFF));
				} else {
					vcbo.putInt(0xffffffff);
				}
				if (mat != null) {
					tcbo[0].put(((mirrorx0) ? 1 : 0) - v.texture0.x);
					tcbo[0].put(v.texture0.y);
					tcbo[1].put(((mirrorx1) ? 1 : 0) - v.texture1.x);
					tcbo[1].put(v.texture1.y);
					tcbo[2].put(((mirrorx2) ? 1 : 0) - v.texture2.x);
					tcbo[2].put(v.texture2.y);
				}
			}
		}

		/**
		 * Every buffer this mesh uploads, rewound, and the bytes they take together. A mesh with
		 * no material has no texture coordinates (makeBOs leaves them null): the upload rewound them
		 * before asking, a NullPointerException on the render thread (F078). Apart from the upload
		 * (ctrmap.render.ModelRenderer) so that it can be asked without a GL context.
		 */
		public long rewound() {
			vbo.rewind();
			vcbo.rewind();
			for (DoubleBuffer t : tcbo) {
				if (t != null) {
					t.rewind();
				}
			}
			long cap = vbo.capacity() * Float.BYTES + vcbo.capacity();
			if (tcbo[0] != null) {
				cap += tcbo[0].capacity() * Double.BYTES;
			}
			return cap;
		}

		/**
		 * Whether the immediate-mode draw colours this mesh's vertices from their own colour - not
		 * for tall grass, which is drawn white - asked of a mesh that may have no material (F078: it
		 * read the null material's name).
		 */
		public boolean vertexColoured(H3DMaterial mat) {
			return hasColor && (mat == null || !(mat.name.contains("blc") || mat.name0.equals("enc_grass")));
		}
	}
}
