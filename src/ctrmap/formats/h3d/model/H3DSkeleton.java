package ctrmap.formats.h3d.model;

import ctrmap.formats.h3d.RandomAccessBAIS;
import ctrmap.formats.h3d.StringUtils;
import ctrmap.formats.vectors.Vec3f;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class H3DSkeleton {

	public List<H3DBone> bones = new ArrayList<>();
	public List<Transform4> transform = new ArrayList<>();

	public H3DSkeleton(RandomAccessBAIS in, byte[] buf, int numBones, String name) throws IOException {
		for (int index = 0; index < numBones; index++) {
			H3DBone bone = new H3DBone();

			int boneFlags = in.readInt();
			bone.isSegmentScaleCompensate = (boneFlags & 0x00400000) > 0;
			bone.parentId = in.readShort();
			in.readUnsignedShort();   //spacer
			bone.scale = new Vec3f(in.readFloat(), in.readFloat(), in.readFloat());
			bone.rotation = new Vec3f(in.readFloat(), in.readFloat(), in.readFloat());
			bone.translation = new Vec3f(in.readFloat(), in.readFloat(), in.readFloat());
			bone.absoluteScale = new Vec3f(bone.scale);

			Transform4 boneMatrix = new Transform4();
			boneMatrix.cells()[0] = in.readFloat();
			boneMatrix.cells()[1] = in.readFloat();
			boneMatrix.cells()[2] = in.readFloat();
			boneMatrix.cells()[3] = in.readFloat();

			boneMatrix.cells()[4] = in.readFloat();
			boneMatrix.cells()[5] = in.readFloat();
			boneMatrix.cells()[6] = in.readFloat();
			boneMatrix.cells()[7] = in.readFloat();

			boneMatrix.cells()[8] = in.readFloat();
			boneMatrix.cells()[9] = in.readFloat();
			boneMatrix.cells()[10] = in.readFloat();
			boneMatrix.cells()[11] = in.readFloat();
			
			bone.invTransform = boneMatrix;

			bone.name = StringUtils.readString(in.readInt(), buf);
			in.readInt(); //the bone's user metadata, which nothing here reads (F079)

			bones.add(bone);
		}

		for (int index = 0; index < numBones; index++) {
			scaleSkeleton(bones, index, index);
		}

		for (int index = 0; index < numBones; index++) {
			Transform4 mtx = new Transform4();
			transformSkeleton(bones, index, mtx);
			transform.add(mtx);
		}
	}

	private static void scaleSkeleton(List<H3DSkeleton.H3DBone> skeleton, int index, int parentIndex) {
		if (index != parentIndex) {
			skeleton.get(parentIndex).absoluteScale.x *= skeleton.get(index).scale.x;
			skeleton.get(parentIndex).absoluteScale.y *= skeleton.get(index).scale.y;
			skeleton.get(parentIndex).absoluteScale.z *= skeleton.get(index).scale.z;

			skeleton.get(parentIndex).translation.x *= skeleton.get(index).scale.x;
			skeleton.get(parentIndex).translation.y *= skeleton.get(index).scale.y;
			skeleton.get(parentIndex).translation.z *= skeleton.get(index).scale.z;
		}

		if (skeleton.get(index).parentId > -1) {
			scaleSkeleton(skeleton, skeleton.get(index).parentId, parentIndex);
		}
	}

	public static void transformSkeleton(List<H3DBone> bones, int index, Transform4 target) {
		H3DBone bone = bones.get(index);
		target.scale(bone.absoluteScale.x, bone.absoluteScale.y, bone.absoluteScale.z);
		target.rotate((float) Math.toRadians(bone.rotation.x), 1f, 0f, 0f);
		target.rotate((float) Math.toRadians(bone.rotation.y), 0f, 1f, 0f);
		target.rotate((float) Math.toRadians(bone.rotation.z), 0f, 0f, 1f);
		target.translate(bone.translation.x, bone.translation.y, bone.translation.z);

		if (bone.parentId > -1) {
			transformSkeleton(bones, bone.parentId, target);
		}
	}

	public static class H3DBone {

		public Vec3f translation;
		public Vec3f rotation;
		public Vec3f scale;
		public Vec3f absoluteScale;
		public Transform4 invTransform;
		public short parentId;
		public String name = null;

		public boolean isSegmentScaleCompensate;
	}
}
