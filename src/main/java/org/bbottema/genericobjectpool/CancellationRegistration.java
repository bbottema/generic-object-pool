package org.bbottema.genericobjectpool;

/**
 * Lifetime of a cooperative allocator's cancellation handler. Close it before releasing its resource for reuse.
 * Closing is idempotent and waits for an already-running handler to exit; handlers must therefore be non-blocking.
 *
 * @since 2.5.0
 */
@FunctionalInterface
public interface CancellationRegistration extends AutoCloseable {
	/** Detaches the handler without requesting cancellation. */
	@Override
	void close();
}
