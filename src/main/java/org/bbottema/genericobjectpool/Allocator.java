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
	public abstract T allocate();
	
	/**
	 * Uninitialize an instance which has been released back to the pool, until it is claimed again.
	 */
	public void deallocateForReuse(@NotNull T object) {
		// overridable hook
	}
	
	/**
	 * Reinitialize an object so it is ready to be claimed.
	 */
	public void allocateForReuse(@NotNull T object) {
		// overridable hook
	}
	
	/**
	 * Clean up an object no longer needed by the pool.
	 */
	public void deallocate(@NotNull T object) {
		// overridable hook
	}
}