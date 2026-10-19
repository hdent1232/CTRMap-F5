package ctrmap.formats.h3d.model;


public class BCHModelHeader {

	public byte flags;
	public byte skeletonScalingType;
	public short silhouetteMaterialEntries;
	public Transform4 worldTransform;

	public int materialsTableOffset;
	public int materialsTableEntries;
	public int materialsNameOffset;
	public int verticesTableOffset;
	public int verticesTableEntries;
	public int skeletonOffset;
	public int skeletonEntries;
	public int skeletonNameOffset;
	public int objectsNodeVisibilityOffset;
	public int objectsNodeCount;
	public String modelName;
	public String[] objectNames;
	public int objectsNodeNameEntries;
	public int objectsNodeNameOffset;
	public int metaDataPointerOffset;
}
