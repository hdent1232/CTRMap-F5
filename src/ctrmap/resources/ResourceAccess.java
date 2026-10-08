
package ctrmap.resources;

import ctrmap.CtrmapMainframe;
import ctrmap.Workspace;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ResourceAccess {
	/**
	 * A bundled resource's bytes, read to the end. IT READ ONE available()-SIZED BUFFER (F166, F131):
	 * from a directory that is the whole file, and from the shipped jar - where available() is an
	 * estimate - the right length with zeros at the end, which the battery, reading from a
	 * directory, could never see. A resource that is not there is said by name, not a null.
	 */
	public static byte[] getByteArray(String name){
		try (InputStream in = getStream(name)) {
			if (in == null) {
				throw new IllegalStateException("the program's resource " + name + " is missing");
			}
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
	public static InputStream getStream(String name){
		return ResourceAccess.class.getClassLoader().getResourceAsStream("ctrmap/resources/" + name);
	}
	/** A bundled resource copied to the workspace's temp folder, or null when it cannot be written there. */
	public static File copyToTemp(String name){
		byte[] b = getByteArray(name);
		File out = new File(Workspace.temp() + "/" + name);
		try {
			java.nio.file.Files.write(out.toPath(), b);
			return out;
		} catch (IOException ex) {
			return null;
		}
	}
}
