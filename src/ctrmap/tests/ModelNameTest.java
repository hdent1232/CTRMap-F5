package ctrmap.tests;

import ctrmap.gamedef.ArchiveType;
import ctrmap.formats.h3d.H3DModelNameGet;
import ctrmap.formats.propdata.GRProp;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A model's name is read from inside its BCH or refused in words - never by a loop that runs off
 * the end (F069).
 *
 * <p>WHY THIS SUITE EXISTS. The name reader stopped only at a NUL, and at the end of the stream
 * read() answers -1, which is not 0: a name or a pointer running off the end appended U+FFFF
 * until the program ran out of memory - an Error the callers' catch blocks do not catch - and
 * none of the offsets it followed was checked. Building-model names, the prop database and the
 * move-model pool all read through it.
 *
 * <p>ORDER: needs no dump and no display; writes nothing. Each read runs under a time limit, so
 * the defect this guards against fails the suite rather than hanging it.
 *
 * Usage: java ctrmap.tests.ModelNameTest
 */
public class ModelNameTest {

	static int fails = 0;
	static final ExecutorService RUN = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "name-read");
		t.setDaemon(true);
		return t;
	});

	public static void main(String[] args) throws Exception {
		byte[] good = HandedGameTest.bch("tr0001_00", 1);
		check("tr0001_00".equals(read(good)), "control: a whole BCH names its model: " + read(good));

		byte[] noEnd = Arrays.copyOf(good, good.length - 1);
		refused("a name with no NUL before the end of the file", noEnd, "runs off the end");

		byte[] farName = good.clone();
		putI32(farName, 12, good.length + 100);
		refused("a string table past the end", farName, "runs off the end");

		byte[] farModel = good.clone();
		int content = i32(good, 8);
		int table = i32(good, content) + content;
		putI32(farModel, table, good.length * 2);
		refused("a model pointer past the end", farModel, "the first model's name field is at byte");

		byte[] farTable = good.clone();
		putI32(farTable, content, -content - 400);
		refused("a model pointer table before the start", farTable, "the model pointer table is at byte -");

		byte[] notBch = good.clone();
		notBch[0] = 'X';
		refused("a file that is not a BCH", notBch, "not a BCH");
		refused("an empty file", new byte[0], "not a BCH");

		System.out.println("--- a prop whose model's name cannot be read is still a prop");
		FakeGameFiles game = HandedGameTest.propGame(0, "pc01", "tree01");
		GRProp control = new GRProp();
		control.uid = 1;
		control.updateName(null, game);
		check("pc01".equals(control.name), "control: a prop over a whole model is named by it (" + control.name + ")");
		File model = game.staged(ArchiveType.BUILDING_MODELS, 2);
		Files.write(model.toPath(), HandedGameTest.container("BM", new byte[64]));
		GRProp prop = new GRProp();
		prop.uid = 2;
		String caption;
		try {
			prop.updateName(null, game);
			caption = prop.name;
		} catch (RuntimeException threw) {
			caption = "THREW " + threw;
		}
		check(caption != null && caption.contains("cannot be read") && caption.contains("2") && !caption.startsWith("THREW"),
				"one over a model whose name cannot be read is captioned so, and the zone's props still load (" + caption + ")");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** The name, read under a time limit; what was thrown, as text, otherwise. */
	static String read(byte[] bch) throws Exception {
		Future<String> name = RUN.submit(() -> H3DModelNameGet.H3DModelNameGet(bch));
		try {
			return name.get(10, TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException hung) {
			name.cancel(true);
			return "HUNG: the read did not end in 10 s";
		} catch (java.util.concurrent.ExecutionException threw) {
			Throwable why = threw.getCause();
			return (why instanceof IllegalStateException ? "REFUSED: " : "THREW: ") + why;
		}
	}

	static void refused(String what, byte[] bch, String why) throws Exception {
		String got = read(bch);
		check(got.startsWith("REFUSED: ") && got.contains(why), what + " is refused in words: " + got);
	}

	static int i32(byte[] b, int at) {
		return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
	}

	static void putI32(byte[] b, int at, int v) {
		b[at] = (byte) v;
		b[at + 1] = (byte) (v >> 8);
		b[at + 2] = (byte) (v >> 16);
		b[at + 3] = (byte) (v >> 24);
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
