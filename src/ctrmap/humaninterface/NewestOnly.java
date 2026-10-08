package ctrmap.humaninterface;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.SwingUtilities;

/**
 * The background worker of a preview the user can re-ask faster than it answers (F206): requests
 * run ONE AT A TIME on a single daemon thread, a request superseded before its turn never runs,
 * and only the newest request's answer is shown, on the event thread.
 *
 * <p>WHY. Both previews started a {@code new Thread} per request. Arrowing through the
 * 3,583-entry building catalogue started a thread per row, every one ran its prefab cut and
 * region build to the end before checking whether anybody still wanted it, they queued on one
 * static lock, and they were not daemons. The zone preview did the same with a sequence number
 * held in a plain array that every worker read without a lock.
 *
 * <p>Not: SwingWorker - it is one worker per task, so "skip what is queued, show only the
 * newest" would be rebuilt around it at every site, which is the duplication this replaces.
 */
public final class NewestOnly {

	private final ExecutorService worker;
	private final AtomicInteger newest = new AtomicInteger();

	/** @param name the worker thread's name, as a thread dump shows it */
	public NewestOnly(final String name) {
		worker = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true); //a preview is never a reason to keep the program running
			return t;
		});
	}

	/** One request's place in line: still wanted while no newer request has been made. */
	public final class Ticket {

		private final int number;

		Ticket(int number) {
			this.number = number;
		}

		/** True while this is the newest request; long work asks it and stops early when not. */
		public boolean current() {
			return number == newest.get();
		}
	}

	/**
	 * Asks for {@code work} to run on the worker. If a newer request is made before it starts, it
	 * never runs; if one is made while it runs, its answer goes to {@code discard} instead of
	 * {@code show}. Both are called on the event thread.
	 *
	 * @param discard what to do with an answer nobody wants any more - free what it holds; may be
	 *                null when there is nothing to free
	 */
	public <T> Ticket ask(Function<Ticket, T> work, Consumer<T> show, Consumer<T> discard) {
		final Ticket ticket = new Ticket(newest.incrementAndGet());
		try {
			worker.execute(() -> {
				if (!ticket.current()) {
					return; //superseded while it waited: the work is never done
				}
				final T answer = work.apply(ticket);
				SwingUtilities.invokeLater(() -> {
					if (ticket.current()) {
						show.accept(answer);
					} else if (discard != null) {
						discard.accept(answer);
					}
				});
			});
		} catch (RejectedExecutionException closed) {
			//asked after close(): the preview is gone, and there is nothing to show it in
		}
		return ticket;
	}

	/** Makes every pending request stale, so none of them shows; the next ask runs normally. */
	public void supersede() {
		newest.incrementAndGet();
	}

	/** Supersedes everything and stops the worker; later asks do nothing. */
	public void close() {
		newest.incrementAndGet();
		worker.shutdownNow();
	}
}
