
package ctrmap.resources;

import java.io.IOException;
import java.io.InputStream;

/**
 * The resources the build ships - tables, icons, meshes, template files - opened one way (F130).
 * One that is missing is a broken build and is refused here, by name: getResourceAsStream answers
 * null, and five loaders took that for an empty table - a native-name table that named nothing, a
 * build that did not know its version, a move gizmo with no arrows - each indistinguishable from
 * "nothing matched". Depends on nothing above it, so the format layer may open resources here.
 */
public class ResourceAccess {
	/**
	 * A bundled resource's bytes, read to the end. IT READ ONE available()-SIZED BUFFER (F166, F131):
	 * from a directory that is the whole file, and from the shipped jar - where available() is an
	 * estimate - the right length with zeros at the end, which the battery, reading from a
	 * directory, could never see. A resource that is not there is said by name, not a null.
	 */
	public static byte[] getByteArray(String name){
		try (InputStream in = getStream(name)) {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		} catch (IOException ex) {
			throw new IllegalStateException("the program's resource " + name + " could not be read: " + ex, ex);
		}
	}

	/**
	 * A bundled resource, opened; the caller closes it.
	 *
	 * @throws IllegalStateException naming the resource, when this build does not have it
	 */
	public static InputStream getStream(String name) {
		InputStream in = ResourceAccess.class.getClassLoader().getResourceAsStream("ctrmap/resources/" + name);
		if (in == null) {
			throw new IllegalStateException("ctrmap/resources/" + name + " is missing from this build");
		}
		return in;
	}
}
