package ctrmap.formats.h3d.model;

import ctrmap.formats.vectors.Vec3f;

public class OhanaMeshUtils {

	public static void calculateBounds(H3DModel mdl, H3DVertex vertex) {
		if (vertex.position.x < mdl.minVector.x) {
			mdl.minVector.x = vertex.position.x;
		}
		if (vertex.position.x > mdl.maxVector.x) {
			mdl.maxVector.x = vertex.position.x;
		}
		if (vertex.position.y < mdl.minVector.y) {
			mdl.minVector.y = vertex.position.y;
		}
		if (vertex.position.y > mdl.maxVector.y) {
			mdl.maxVector.y = vertex.position.y;
		}
		if (vertex.position.z < mdl.minVector.z) {
			mdl.minVector.z = vertex.position.z;
		}
		if (vertex.position.z > mdl.maxVector.z) {
			mdl.maxVector.z = vertex.position.z;
		}
	}
	
	public static void makeBox(float[][] boxVectors, Vec3f minVector, Vec3f maxVector){
		boxVectors[0] = new float[]{minVector.x, minVector.y, minVector.z};
		boxVectors[1] = new float[]{minVector.x, maxVector.y, minVector.z};
		boxVectors[2] = new float[]{minVector.x, maxVector.y, minVector.z};
		boxVectors[3] = new float[]{maxVector.x, maxVector.y, minVector.z};
		boxVectors[4] = new float[]{maxVector.x, maxVector.y, minVector.z};
		boxVectors[5] = new float[]{maxVector.x, minVector.y, minVector.z};
		boxVectors[6] = new float[]{maxVector.x, minVector.y, minVector.z};
		boxVectors[7] = new float[]{minVector.x, minVector.y, minVector.z};
		
		boxVectors[8] = new float[]{minVector.x, minVector.y, maxVector.z};
		boxVectors[9] = new float[]{minVector.x, maxVector.y, maxVector.z};
		boxVectors[10] = new float[]{minVector.x, maxVector.y, maxVector.z};
		boxVectors[11] = new float[]{maxVector.x, maxVector.y, maxVector.z};
		boxVectors[12] = new float[]{maxVector.x, maxVector.y, maxVector.z};
		boxVectors[13] = new float[]{maxVector.x, minVector.y, maxVector.z};
		boxVectors[14] = new float[]{maxVector.x, minVector.y, maxVector.z};
		boxVectors[15] = new float[]{minVector.x, minVector.y, maxVector.z};
		
		boxVectors[16] = new float[]{minVector.x, minVector.y, minVector.z};
		boxVectors[17] = new float[]{minVector.x, minVector.y, maxVector.z};
		boxVectors[18] = new float[]{minVector.x, maxVector.y, minVector.z};
		boxVectors[19] = new float[]{minVector.x, maxVector.y, maxVector.z};
		boxVectors[20] = new float[]{maxVector.x, maxVector.y, minVector.z};
		boxVectors[21] = new float[]{maxVector.x, maxVector.y, maxVector.z};
		boxVectors[23] = new float[]{maxVector.x, minVector.y, maxVector.z};
		boxVectors[22] = new float[]{maxVector.x, minVector.y, minVector.z};
		
		//we make extra unnecessary vectors for better programmatic checking for boxes when picking objects
		
		boxVectors[24] = new float[]{minVector.x, minVector.y, minVector.z};
		boxVectors[25] = new float[]{maxVector.x, minVector.y, minVector.z};
		boxVectors[26] = new float[]{maxVector.x, minVector.y, maxVector.z};
		boxVectors[27] = new float[]{minVector.x, minVector.y, maxVector.z};
		boxVectors[28] = new float[]{minVector.x, maxVector.y, minVector.z};
		boxVectors[29] = new float[]{maxVector.x, maxVector.y, minVector.z};
		boxVectors[30] = new float[]{maxVector.x, maxVector.y, maxVector.z};
		boxVectors[31] = new float[]{minVector.x, maxVector.y, maxVector.z};
	}

	/** {@code value} clamped to 0-255, as an int. */
	public static int saturate(float value) {
		if (value > 255f) {
			return 0xff;
		}
		if (value < 0f) {
			return 0;
		}
		return (int)value;
	}

}
