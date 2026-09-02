package ctrmap.tests;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Scratch space for a test run: a directory or file under the JVM's temp
 * folder with a name nothing else can be using, removed when the JVM exits -
 * including the System.exit(1) a failing suite ends with.
 *
 * <p>Fixed names were the alternative, and BuildingCatalogTest had one: two
 * batteries running at once rewrote each other's region file between write
 * and read, and one of them reported 112 failures in a catalog that had not
 * changed. A guard nobody can trust is no guard at all.
 */
final class Scratch {

	private Scratch() {
	}

	/** A fresh, empty directory; deleted with everything in it at exit. */
	static File dir(String prefix) throws IOException {
		final File d = Files.createTempDirectory(prefix).toFile();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(d)));
		return d;
	}

	/** A fresh, empty file; deleted at exit. */
	static File file(String prefix) throws IOException {
		File f = File.createTempFile(prefix, null);
		f.deleteOnExit();
		return f;
	}

	static void deleteTree(File f) {
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				deleteTree(k);
			}
		}
		f.delete();
	}
}
