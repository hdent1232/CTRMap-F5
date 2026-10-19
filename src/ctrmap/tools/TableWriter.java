package ctrmap.tools;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;

/**
 * The writer a harvester writes a tracked table with: UTF-8, every line ended LF on every machine.
 *
 * <p>A PrintWriter ends a line with the platform's separator, so on Windows the three tools that
 * regenerate a table under {@code src/ctrmap/resources} wrote it CRLF while the index holds it LF -
 * and a suite that reads the table and compares it with LF text then failed for the endings alone
 * (measured 2026-10-09: six suites, on a checkout holding 622 such files). {@code .gitattributes}
 * gives every text file LF on checkout; this gives the same to the files these tools write.
 *
 * <p>Not: {@link ctrmap.util.Text} - that reads what a string says, and nothing in ctrmap.util writes a text
 * file; and a plain PrintWriter is the very thing that wrote CRLF.
 */
public final class TableWriter {

	private TableWriter() {
	}

	/** A PrintWriter over {@code path} whose {@code println} writes {@code '\n'}, whatever the platform. */
	public static PrintWriter open(String path) throws FileNotFoundException, UnsupportedEncodingException {
		return new PrintWriter(path, "UTF-8") {
			@Override
			public void println() {
				write('\n');
			}
		};
	}
}
