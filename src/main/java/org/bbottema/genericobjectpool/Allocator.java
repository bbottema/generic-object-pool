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
	 * Prepares a new resource for an opt-in claim. Override to observe cancellation and apply the remaining budget
	 * to blocking work. This default preserves existing allocator implementations by delegating to {@link #allocate()}.
	 * The allocator owns partially created resources if it throws before returning one to the pool.
	 *
	 * @since 2.5.0
	 */
	@NotNull
	public T allocate(@NotNull final AllocationContext context) {
		return allocate();
	}
	
	/**
	 * Uninitialize an instance which has been released back to the pool, until it is claimed again.
	 */
	public void deallocateForReuse(T object) {
		// overridable hook
	}
	
	/**
	 * Reinitialize an object so it is ready to be claimed.
	 */
	public void allocateForReuse(T object) {
		// overridable hook
	}

	/**
	 * Prepares an existing resource for an opt-in claim, with cooperative cancellation and a remaining budget.
	 * Delegates to {@link #allocateForReuse(Object)} for existing allocators. The pool disposes the resource if
	 * preparation fails or the claim is cancelled before handoff.
	 *
	 * @since 2.5.0
	 */
	public void allocateForReuse(final T object, @NotNull final AllocationContext context) {
		allocateForReuse(object);
	}
	
	/**
	 * Clean up an object no longer needed by the pool.
	 */
	public void deallocate(T object) {
		// overridable hook
	}
}
