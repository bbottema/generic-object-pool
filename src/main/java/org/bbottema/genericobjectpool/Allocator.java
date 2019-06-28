package org.bbottema.genericobjectpool;

import org.jetbrains.annotations.NotNull;

/**
 * A factory which is responsible for creating the Object V based on the Pool Key.  The returned Object will be wrapped in a {@link PoolableObject} and inserted into the Pool for access
 *
 * @param <T> the value type
 */
@SuppressWarnings("unused")
public abstract class Allocator<T> {
	
	/**
	 * @return A new Object to be inserted into the Pool.
	 */
	@NotNull
	protected abstract T allocate();
	
	/**
	 * Reinitialize an object to be returned as available again.
	 */
	protected void allocateForReuse(@NotNull T object) {
		// overridable hook
	}
	
	/**
	 * Uninitialize an instance which has been released back to the pool.
	 */
	protected void deallocateForReuse(@NotNull T object) {
		// overridable hook
	}
	
	/**
	 * Clean up an object no longer needed by the pool.
	 */
	protected void deallocate(@NotNull T object) {
		// overridable hook
	}
}