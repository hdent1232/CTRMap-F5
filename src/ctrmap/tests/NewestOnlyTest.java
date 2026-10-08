package ctrmap.tests;

import ctrmap.humaninterface.NewestOnly;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/**
 * A preview's worker runs one request at a time, never runs one superseded while it waited, and
 * shows only the newest (F206).
 *
 * <p>WHY THIS SUITE EXISTS. Both previews started a thread per list selection: the building
 * palette cut a prefab and built a region for every row arrowed past, each to the end before it
 * asked whether anyone still wanted it, and the zone preview checked a plain int every worker
 * read without a lock. Neither could be asked anything by a suite, because the threading lived
 * inline in the listener. Now both hand their work to {@link NewestOnly}, and this asks it.
 *
 * <p>DETERMINISTIC: every check waits on the answer of the request it is about - a latch counted
 * down by its show or its discard - never on a request of its own, which would supersede the
 * one being watched.
 *
 * <p>ORDER: needs no game and no display; answers arrive through the event queue, which exists
 * headless.
 *
 * Usage: java ctrmap.tests.NewestOnlyTest
 */
public class NewestOnlyTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		aBurstRunsTheOneGoingAndTheNewestOnly();
		theWorkerIsOneDaemon();
		aSupersededAnswerIsHandedBackNotShown();
		aClosedWorkerRunsNothing();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** Forty-nine requests behind one still running: the running one and the last are all that run. */
	static void aBurstRunsTheOneGoingAndTheNewestOnly() throws Exception {
		System.out.println("--- a burst of requests runs the one already going and the newest, nothing between");
		NewestOnly w = new NewestOnly("test-preview");
		final CountDownLatch going = new CountDownLatch(1), release = new CountDownLatch(1), last = new CountDownLatch(1);
		final List<Integer> ran = Collections.synchronizedList(new ArrayList<Integer>());
		final List<Integer> shown = Collections.synchronizedList(new ArrayList<Integer>());
		final List<Integer> discarded = Collections.synchronizedList(new ArrayList<Integer>());
		for (int i = 1; i <= 50; i++) {
			final int n = i;
			w.ask(t -> {
				ran.add(n);
				if (n == 1) {
					going.countDown();
					await(release);
				}
				return n;
			}, x -> {
				shown.add(x);
				if (x == 50) {
					last.countDown();
				}
			}, x -> {
				discarded.add(x);
				if (x == 50) {
					last.countDown();
				}
			});
			if (i == 1) {
				check(going.await(5, TimeUnit.SECONDS), "control: the first request is running before the rest are asked");
			}
		}
		release.countDown();
		check(last.await(10, TimeUnit.SECONDS), "control: the newest request was answered");
		flushEdt();
		check(ran.equals(java.util.Arrays.asList(1, 50)), "of 50 requests only the one already running and the newest ran: " + ran);
		check(shown.equals(Collections.singletonList(50)), "and only the newest was shown: " + shown);
		check(discarded.equals(Collections.singletonList(1)), "the one superseded while it ran was handed back to be"
				+ " freed: " + discarded);
		w.close();
	}

	static void theWorkerIsOneDaemon() throws Exception {
		System.out.println("--- the worker is one named daemon thread");
		NewestOnly w = new NewestOnly("test-daemon");
		final List<Thread> threads = Collections.synchronizedList(new ArrayList<Thread>());
		for (int i = 0; i < 5; i++) {
			final CountDownLatch answered = new CountDownLatch(1);
			w.ask(t -> {
				threads.add(Thread.currentThread());
				return 0;
			}, x -> answered.countDown(), x -> answered.countDown());
			check(answered.await(10, TimeUnit.SECONDS), "control: request " + (i + 1) + " was answered");
		}
		java.util.Set<Thread> distinct = new java.util.HashSet<>(threads);
		check(threads.size() == 5 && distinct.size() == 1, "five requests asked one after another ran on "
				+ distinct.size() + " thread(s)");
		Thread t = threads.isEmpty() ? null : threads.get(0);
		check(t != null && t.isDaemon() && t.getName().equals("test-daemon"), "a daemon, named as asked: "
				+ (t == null ? "none" : t.getName() + (t.isDaemon() ? " (daemon)" : " (NOT a daemon)")));
		w.close();
	}

	/** Superseded after its work began, an answer goes to discard, so what it holds can be freed. */
	static void aSupersededAnswerIsHandedBackNotShown() throws Exception {
		System.out.println("--- an answer superseded while it was being made is handed back, not shown");
		NewestOnly w = new NewestOnly("test-supersede");
		final CountDownLatch going = new CountDownLatch(1), release = new CountDownLatch(1), answered = new CountDownLatch(1);
		final List<String> shown = Collections.synchronizedList(new ArrayList<String>());
		final List<String> freed = Collections.synchronizedList(new ArrayList<String>());
		final boolean[] stillWanted = {true};
		w.ask(t -> {
			going.countDown();
			await(release);
			stillWanted[0] = t.current();
			return "zone 7";
		}, x -> {
			shown.add(x);
			answered.countDown();
		}, x -> {
			freed.add(x);
			answered.countDown();
		});
		check(going.await(5, TimeUnit.SECONDS), "control: the request is running");
		w.supersede();
		release.countDown();
		check(answered.await(10, TimeUnit.SECONDS), "control: its answer came back");
		check(shown.isEmpty() && freed.equals(Collections.singletonList("zone 7")),
				"shown " + shown + ", handed back to be freed " + freed);
		check(!stillWanted[0], "and its ticket told the work it was no longer wanted, so long work can stop early");
		w.close();
	}

	static void aClosedWorkerRunsNothing() throws Exception {
		System.out.println("--- a closed worker runs nothing and throws nothing at its caller");
		NewestOnly w = new NewestOnly("test-closed");
		w.close();
		final CountDownLatch ran = new CountDownLatch(1);
		String threw = null;
		try {
			w.ask(t -> {
				ran.countDown();
				return 0;
			}, x -> {
			}, null);
		} catch (RuntimeException ex) {
			threw = String.valueOf(ex);
		}
		boolean didRun = ran.await(500, TimeUnit.MILLISECONDS);
		check(!didRun && threw == null, "asked after close, nothing ran and nothing was thrown"
				+ (threw == null ? "" : ": " + threw));
	}

	static void flushEdt() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
		});
	}

	static void await(CountDownLatch l) {
		try {
			l.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
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
