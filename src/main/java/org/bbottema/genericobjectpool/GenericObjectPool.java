package org.bbottema.genericobjectpool;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bbottema.genericobjectpool.util.SleepUtil;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.bbottema.genericobjectpool.util.ForeverTimeout.WAIT_FOREVER;

@Slf4j
public class GenericObjectPool<T> {

	private static final int DEALLOCATION_WAIT_MS = 100;

	@NotNull private final Lock claimLock = new ReentrantLock();
	// Keep existing allocators serialized, without holding up cancellation, metrics or pool bookkeeping.
	@NotNull private final Lock allocatorLock = new ReentrantLock();
	@NotNull private final Lock deallocateLock = new ReentrantLock();
	@NotNull private final LinkedList<PoolableObject<T>> available = new LinkedList<>();
	@NotNull private final LinkedList<PoolableObject<T>> waitingForDeallocation = new LinkedList<>();
	@NotNull private final LinkedList<ClaimAttempt> waitingClaims = new LinkedList<>();
	@NotNull private final Condition objectWaitingForDeallocation = deallocateLock.newCondition();

	@NotNull @Getter private final PoolConfig<T> poolConfig;
	@NotNull @Getter private final Allocator<T> allocator;
	@Nullable private volatile Future<Void> shutdownSequence;

	@NotNull private final AtomicInteger currentlyDeallocating = new AtomicInteger();
	@NotNull private final AtomicLong totalAllocated = new AtomicLong();
	@NotNull private final AtomicLong totalClaimed = new AtomicLong();
	// All four counts below are protected by claimLock. Reservations consume capacity before allocation starts.
	private int currentlyAllocated;
	private int currentlyClaimed;
	private int pendingAllocations;
	private int pendingClaims;

	public GenericObjectPool(final PoolConfig<T> poolConfig, @NotNull final Allocator<T> allocator) {
		this.poolConfig = requireNonNull(poolConfig, "poolConfig");
		this.allocator = requireNonNull(allocator, "allocator");
		poolConfig.getThreadFactory().newThread(new AutoAllocator()).start();
		poolConfig.getThreadFactory().newThread(new AutoDeallocator()).start();
	}

	/** Delegates to {@link #claim(Timeout)} with unlimited timeout. */
	@NotNull
	@SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "Unlimited legacy claims do not time out")
	public PoolableObject<T> claim() throws InterruptedException {
		return requireNonNull(claim(WAIT_FOREVER));
	}

	/** Delegates to {@link #claim(Timeout)}. */
	@Nullable
	public PoolableObject<T> claim(final long timeout, final TimeUnit timeUnit) throws InterruptedException {
		return claim(new Timeout(timeout, timeUnit));
	}

	/**
	 * Claims an available object, creates one if there is room, or waits for either to become possible.
	 * The legacy timeout limits each availability wait, not lock acquisition or allocator callbacks.
	 *
	 * @throws IllegalStateException if a new claim is made after shutdown starts
	 * @throws InterruptedException if a waiting claim is interrupted or the pool shuts down while it waits
	 */
	@Nullable
	public PoolableObject<T> claim(final Timeout timeout) throws InterruptedException {
		return claimResource(new ClaimAttempt(requireNonNull(timeout, "timeout"), null, false), null);
	}

	/**
	 * Claims using one total acquisition budget and optional cancellation control. Preparation stays on the caller's
	 * thread. Returns null on timeout and throws {@link java.util.concurrent.CancellationException} on cancellation.
	 * A non-cooperative allocator or required disposal may delay settlement, but cannot cause a cancelled resource
	 * to be handed off. After successful return, acquisition cancellation no longer affects the borrowed object.
	 *
	 * @since 2.5.0
	 */
	@Nullable
	public PoolableObject<T> claim(final ClaimOptions options) throws InterruptedException {
		return claimWithContext(requireNonNull(options, "options").start());
	}

	/**
	 * Integration entry point for a budget already started by an outer pool. Do not restart the context between layers
	 * or reuse it for independent claims; ordinary callers should use {@link #claim(ClaimOptions)}.
	 *
	 * @since 2.5.0
	 */
	@Nullable
	public PoolableObject<T> claimWithContext(final AllocationContext context) throws InterruptedException {
		return claimResource(new ClaimAttempt(null, requireNonNull(context, "context"), false), null);
	}

	/** Delegates to {@link #claimMatching(Predicate, Timeout)}. */
	@Nullable
	public PoolableObject<T> claimMatching(@NotNull final Predicate<PoolableObject<T>> predicate,
			final long timeout, final TimeUnit timeUnit) throws InterruptedException {
		return claimMatching(predicate, new Timeout(timeout, timeUnit));
	}

	/**
	 * Claims an already available matching object, without allocating a new one. The predicate is rechecked as time
	 * passes; keep it fast and side-effect free because it runs under the bookkeeping lock.
	 */
	@Nullable
	public PoolableObject<T> claimMatching(@NotNull final Predicate<PoolableObject<T>> predicate, final Timeout timeout)
			throws InterruptedException {
		requireNonNull(predicate, "predicate");
		return claimResource(new ClaimAttempt(requireNonNull(timeout, "timeout"), null, true), predicate);
	}

	/**
	 * Matching-only counterpart of {@link #claim(ClaimOptions)}, with the same cancellation and cleanup contract.
	 * Never creates a new resource.
	 *
	 * @since 2.5.0
	 */
	@Nullable
	public PoolableObject<T> claimMatching(@NotNull final Predicate<PoolableObject<T>> predicate, final ClaimOptions options)
			throws InterruptedException {
		return claimMatchingWithContext(predicate, requireNonNull(options, "options").start());
	}

	/** Integration counterpart of {@link #claimMatching(Predicate, ClaimOptions)} that preserves an outer budget. */
	@Nullable
	public PoolableObject<T> claimMatchingWithContext(@NotNull final Predicate<PoolableObject<T>> predicate,
			final AllocationContext context) throws InterruptedException {
		requireNonNull(predicate, "predicate");
		return claimResource(new ClaimAttempt(null, requireNonNull(context, "context"), true), predicate);
	}

	@Nullable
	private PoolableObject<T> claimResource(final ClaimAttempt attempt, final Predicate<PoolableObject<T>> predicate)
			throws InterruptedException {
		try (ClaimAttempt ignored = attempt) {
			while (attempt.acquire(claimLock)) {
				final Reservation reservation;
				try {
					ensureOpenFor(attempt);
					reservation = reserveResource(predicate);
					if (reservation == null) {
						attempt.prepareToWait();
						waitingClaims.add(attempt);
					}
				} finally {
					claimLock.unlock();
				}
				if (reservation != null) {
					return prepareAndHandOff(reservation, attempt);
				}
				if (!waitForAvailability(attempt)) {
					return null;
				}
			}
			return null;
		}
	}

	private void ensureOpenFor(final ClaimAttempt attempt) throws InterruptedException {
		if (isShuttingDown()) {
			if (attempt.hasWaited()) {
				throw new InterruptedException("Pool is shutting down");
			}
			throw new IllegalStateException("Pool has been shutdown");
		}
	}

	private Reservation reserveResource(final Predicate<PoolableObject<T>> predicate) {
		for (Iterator<PoolableObject<T>> iterator = available.iterator(); iterator.hasNext(); ) {
			final PoolableObject<T> entry = iterator.next();
			if (predicate == null || predicate.test(entry)) {
				iterator.remove();
				entry.setCurrentPoolStatus(PoolableObject.PoolStatus.PREPARING);
				pendingClaims++;
				return new Reservation(entry);
			}
		}
		return predicate == null && currentlyAllocated + pendingAllocations < poolConfig.getMaxPoolsize()
				? reserveAllocation() : null;
	}

	private Reservation reserveAllocation() {
		pendingAllocations++;
		pendingClaims++;
		return new Reservation(null);
	}

	private boolean waitForAvailability(final ClaimAttempt attempt) throws InterruptedException {
		try {
			final boolean signalled = attempt.awaitAvailability();
			ensureOpenFor(attempt);
			return signalled;
		} finally {
			claimLock.lock();
			try {
				waitingClaims.remove(attempt);
			} finally {
				claimLock.unlock();
			}
		}
	}

	private PoolableObject<T> prepareAndHandOff(final Reservation reservation, final ClaimAttempt attempt)
			throws InterruptedException {
		PoolableObject<T> prepared = reservation.existing;
		Throwable failure = null;
		try {
			prepared = prepareResource(reservation, attempt);
			// Detach outside bookkeeping: a cooperative handler might still be finishing its short abort action.
			attempt.close();
			if (prepared != null && handOff(reservation, prepared, attempt)) {
				return prepared;
			}
			attempt.canContinue();
		} catch (RuntimeException | Error | InterruptedException preparationFailure) {
			failure = attempt.preparationFailure(preparationFailure);
		} finally {
			attempt.close();
		}
		failure = disposeFailedReservation(reservation, prepared, attempt, failure);
		rethrowFailure(failure);
		attempt.canContinue();
		return null;
	}

	private PoolableObject<T> prepareResource(final Reservation reservation, final ClaimAttempt attempt) throws InterruptedException {
		if (!attempt.acquire(allocatorLock)) {
			return reservation.existing;
		}
		try {
			if (!attempt.canContinue()) {
				return reservation.existing;
			}
			if (reservation.existing == null) {
				final T resource = attempt.isControlled() ? allocator.allocate(attempt.getContext()) : allocator.allocate();
				return new PoolableObject<>(this, requireNonNull(resource, "Allocated resource"));
			}
			if (attempt.isControlled()) {
				allocator.allocateForReuse(reservation.existing.getAllocatedObject(), attempt.getContext());
			} else {
				allocator.allocateForReuse(reservation.existing.getAllocatedObject());
			}
			return reservation.existing;
		} finally {
			allocatorLock.unlock();
		}
	}

	private boolean handOff(final Reservation reservation, final PoolableObject<T> prepared, final ClaimAttempt attempt)
			throws InterruptedException {
		claimLock.lock();
		try {
			ensureOpenFor(attempt);
			if (prepared.isInvalidationRequested()) {
				throw new IllegalStateException("Resource invalidated during preparation");
			}
			return attempt.handOff(() -> {
				finishReservation(reservation, prepared);
				prepared.resetAllocationTimestamp();
				prepared.setCurrentPoolStatus(PoolableObject.PoolStatus.CLAIMED);
				currentlyClaimed++;
				totalClaimed.incrementAndGet();
			});
		} finally {
			claimLock.unlock();
		}
	}

	private Throwable disposeFailedReservation(final Reservation reservation, final PoolableObject<T> prepared,
			final ClaimAttempt attempt, final Throwable failure) {
		claimLock.lock();
		try {
			finishReservation(reservation, prepared);
			if (prepared != null) {
				queueInvalidated(prepared);
			}
			signalAllWaitingClaimers();
		} finally {
			claimLock.unlock();
		}
		if (prepared != null && attempt.isControlled()) {
			try {
				// Cleanup must settle even when interrupted. join preserves the interrupt flag.
				prepared.getDisposalCompletion().toCompletableFuture().join();
			} catch (CompletionException cleanupFailure) {
				if (failure == null) {
					return cleanupFailure.getCause();
				}
				if (failure != cleanupFailure.getCause()) {
					failure.addSuppressed(cleanupFailure.getCause());
				}
			}
		}
		return failure;
	}

	private void finishReservation(final Reservation reservation, final PoolableObject<T> prepared) {
		pendingClaims--;
		if (reservation.existing == null) {
			pendingAllocations--;
			if (prepared != null) {
				currentlyAllocated++;
				totalAllocated.incrementAndGet();
			}
		}
	}

	private static void rethrowFailure(final Throwable failure) throws InterruptedException {
		if (failure instanceof InterruptedException) {
			throw (InterruptedException) failure;
		}
		if (failure instanceof RuntimeException) {
			throw (RuntimeException) failure;
		}
		if (failure instanceof Error) {
			throw (Error) failure;
		}
	}

	void releasePoolableObject(final PoolableObject<T> claimedObject) {
		if (!beginRelease(claimedObject)) {
			return;
		}
		Throwable failure = null;
		allocatorLock.lock();
		try {
			allocator.deallocateForReuse(claimedObject.getAllocatedObject());
		} catch (RuntimeException | Error releaseFailure) {
			failure = releaseFailure;
		} finally {
			allocatorLock.unlock();
			finishRelease(claimedObject, failure);
		}
		if (failure instanceof RuntimeException) {
			throw (RuntimeException) failure;
		}
		if (failure instanceof Error) {
			throw (Error) failure;
		}
	}

	private boolean beginRelease(final PoolableObject<T> entry) {
		claimLock.lock();
		try {
			if (entry.getCurrentPoolStatus() != PoolableObject.PoolStatus.CLAIMED) {
				return false;
			}
			currentlyClaimed--;
			if (isShuttingDown()) {
				queueInvalidated(entry);
				return false;
			}
			entry.setCurrentPoolStatus(PoolableObject.PoolStatus.RELEASING);
			return true;
		} finally {
			claimLock.unlock();
		}
	}

	private void finishRelease(final PoolableObject<T> entry, final Throwable failure) {
		claimLock.lock();
		try {
			if (failure != null || isShuttingDown() || entry.isInvalidationRequested()) {
				queueInvalidated(entry);
			} else {
				entry.resetAvailableTimestamp();
				entry.setCurrentPoolStatus(PoolableObject.PoolStatus.AVAILABLE);
				available.addLast(entry);
			}
			signalAllWaitingClaimers();
		} finally {
			claimLock.unlock();
		}
	}

	void invalidatePoolableObject(final PoolableObject<T> entry) {
		claimLock.lock();
		try {
			switch (entry.getCurrentPoolStatus()) {
				case PREPARING:
				case RELEASING:
					entry.setInvalidationRequested(true);
					return;
				case CLAIMED:
					currentlyClaimed--;
					break;
				case AVAILABLE:
					available.remove(entry);
					break;
				default:
					return;
			}
			queueInvalidated(entry);
		} finally {
			claimLock.unlock();
		}
	}

	/** Caller holds claimLock; publishing capacity and the disposal queue is one atomic transition. */
	private void queueInvalidated(final PoolableObject<T> entry) {
		deallocateLock.lock();
		try {
			currentlyAllocated--;
			entry.setCurrentPoolStatus(PoolableObject.PoolStatus.WAITING_FOR_DEALLOCATION);
			waitingForDeallocation.addLast(entry);
			objectWaitingForDeallocation.signal();
		} finally {
			deallocateLock.unlock();
		}
		signalAllWaitingClaimers();
	}

	/**
	 * Stops new allocations and disposes available objects. Completes after borrowers return their objects,
	 * outstanding preparation exits and final cleanup finishes.
	 */
	public synchronized Future<Void> shutdown() {
		if (!isShuttingDown()) {
			final FutureTask<Void> sequence = new FutureTask<>(new ShutdownSequence(), null);
			shutdownSequence = sequence;
			final ExecutorService executor = newSingleThreadExecutor(poolConfig.getThreadFactory());
			executor.execute(sequence);
			executor.shutdown();
		}
		return shutdownSequence;
	}

	private boolean isShuttingDown() {
		return shutdownSequence != null;
	}

	/** Gets the live allocation size, including preparation but excluding reservations and queued disposal. */
	public int getCurrentlyAllocated() {
		claimLock.lock();
		try {
			return currentlyAllocated;
		} finally {
			claimLock.unlock();
		}
	}

	/** @see PoolMetrics */
	@NotNull
	public PoolMetrics getPoolMetrics() {
		claimLock.lock();
		try {
			return new PoolMetrics(currentlyClaimed, waitingClaims.size(), currentlyAllocated, poolConfig.getCorePoolsize(),
					poolConfig.getMaxPoolsize(), totalAllocated.get(), totalClaimed.get());
		} finally {
			claimLock.unlock();
		}
	}

	private PoolableObject<T> takeForDisposal(final boolean wait) {
		deallocateLock.lock();
		try {
			if (wait && waitingForDeallocation.isEmpty() && !isShuttingDown()) {
				final boolean signalled = objectWaitingForDeallocation.await(DEALLOCATION_WAIT_MS, TimeUnit.MILLISECONDS);
				if (!signalled && waitingForDeallocation.isEmpty()) {
					return null;
				}
			}
			if (waitingForDeallocation.isEmpty()) {
				return null;
			}
			currentlyDeallocating.incrementAndGet();
			return waitingForDeallocation.removeFirst();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return null;
		} finally {
			deallocateLock.unlock();
		}
	}

	private void disposeResource(final PoolableObject<T> entry) {
		Throwable failure = null;
		try {
			allocator.deallocate(entry.getAllocatedObject());
		} catch (RuntimeException | Error cleanupFailure) {
			failure = cleanupFailure;
			log.error("Error deallocating an object already removed from the pool", cleanupFailure);
		} finally {
			entry.dereferenceObject();
			entry.setCurrentPoolStatus(PoolableObject.PoolStatus.DEALLOCATED);
			entry.completeDisposal(failure);
			currentlyDeallocating.decrementAndGet();
		}
	}

	private void scheduleDeallocations() {
		claimLock.lock();
		try {
			for (Iterator<PoolableObject<T>> iterator = available.iterator(); iterator.hasNext(); ) {
				final PoolableObject<T> entry = iterator.next();
				if (poolConfig.getExpirationPolicy().hasExpired(entry)) {
					iterator.remove();
					queueInvalidated(entry);
				}
			}
		} finally {
			claimLock.unlock();
		}
	}

	private Reservation reserveCoreAllocation() {
		claimLock.lock();
		try {
			return !isShuttingDown() && currentlyAllocated + pendingAllocations < poolConfig.getCorePoolsize()
					? reserveAllocation() : null;
		} finally {
			claimLock.unlock();
		}
	}

	private void replenishCore(final Reservation reservation) {
		PoolableObject<T> entry = null;
		allocatorLock.lock();
		try {
			// Core replenishment belongs to the pool, not to whichever caller happened to arrive first.
			if (!isShuttingDown()) {
				entry = new PoolableObject<>(this, requireNonNull(allocator.allocate(), "Allocated resource"));
			}
		} catch (RuntimeException failure) {
			log.error("Unable to replenish the core pool; will retry", failure);
		} finally {
			allocatorLock.unlock();
			publishCoreAllocation(reservation, entry);
		}
	}

	private void publishCoreAllocation(final Reservation reservation, final PoolableObject<T> entry) {
		claimLock.lock();
		try {
			finishReservation(reservation, entry);
			if (entry != null) {
				if (isShuttingDown()) {
					queueInvalidated(entry);
				} else {
					available.addLast(entry);
				}
			}
			signalAllWaitingClaimers();
		} finally {
			claimLock.unlock();
		}
	}

	private boolean maintenanceNeeded() {
		final Future<Void> sequence = shutdownSequence;
		return sequence == null || !sequence.isDone();
	}

	private void signalAllWaitingClaimers() {
		for (ClaimAttempt attempt : waitingClaims) {
			attempt.signal();
		}
	}

	private final class Reservation {
		private final PoolableObject<T> existing;

		private Reservation(final PoolableObject<T> existing) {
			this.existing = existing;
		}
	}

	/** Existing maintenance worker: expiration and final disposal, independent of allocator preparation. */
	private final class AutoDeallocator implements Runnable {
		@Override
		public void run() {
			while (maintenanceNeeded()) {
				final boolean expires = poolConfig.getExpirationPolicy() != ExpirationPolicy.NeverExpirePolicy.getInstance();
				final PoolableObject<T> entry = takeForDisposal(!expires);
				if (entry != null) {
					disposeResource(entry);
				} else if (expires) {
					scheduleDeallocations();
				}
				SleepUtil.sleep(isShuttingDown() ? 0 : entry != null ? 50 : expires ? 10 : 0);
			}
		}
	}

	/** Existing maintenance worker: reserves capacity before running pool-owned core allocation outside bookkeeping. */
	private final class AutoAllocator implements Runnable {
		@Override
		public void run() {
			while (maintenanceNeeded()) {
				final Reservation reservation = reserveCoreAllocation();
				if (reservation != null) {
					replenishCore(reservation);
				}
				SleepUtil.sleep(5);
			}
		}
	}

	private final class ShutdownSequence implements Runnable {
		@Override
		public void run() {
			claimLock.lock();
			try {
				while (!available.isEmpty()) {
					queueInvalidated(available.removeFirst());
				}
				signalAllWaitingClaimers();
			} finally {
				claimLock.unlock();
			}
			waitUntilShutDown();
			log.info("Generic Object Pool shutdown complete");
		}

		private void waitUntilShutDown() {
			while (hasOutstandingWork()) {
				SleepUtil.sleep(10);
			}
		}

		private boolean hasOutstandingWork() {
			claimLock.lock();
			try {
				deallocateLock.lock();
				try {
					return currentlyAllocated > 0 || pendingClaims > 0 || currentlyDeallocating.get() > 0
							|| !waitingClaims.isEmpty() || !waitingForDeallocation.isEmpty();
				} finally {
					deallocateLock.unlock();
				}
			} finally {
				claimLock.unlock();
			}
		}
	}
}
