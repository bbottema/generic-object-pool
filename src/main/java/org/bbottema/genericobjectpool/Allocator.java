package org.bbottema.genericobjectpool;

import org.jetbrains.annotations.NotNull;

/**
 * Creates the resources your pool lends to callers and provides hooks for preparing, reusing and disposing them.
 * The pool wraps each returned resource in a {@link PoolableObject} to manage borrowing and release.
 *
 * @param <T> the value type
 */
@SuppressWarnings("unused")
public abstract class Allocator<T> {

	/**
	 * Lets you pause the pool's automatic top-up without stopping callers from borrowing resources.
	 * For example, an application can stop creating spare service connections while that service is unavailable,
	 * then allow them again when its own recovery logic decides to resume.
	 * <p>
	 * Returning {@code false} postpones creation of resources needed to maintain the configured core pool size.
	 * It does not report an allocation failure or keep capacity reserved. Existing resources remain usable;
	 * explicit claims can still create resources when needed. Expiration and disposal continue normally.
	 * <p>
	 * Keep this method fast and thread-safe. The maintenance worker polls it outside pool bookkeeping locks and
	 * checks again immediately before allocation. An allocation already in progress may still complete after
	 * permission changes. The pool does not schedule recovery attempts or decide when to resume; the default
	 * returns {@code true}, preserving automatic top-up for existing allocators.
	 *
	 * @return whether the maintenance worker may create another resource
	 * @since 2.6.0
	 */
	public boolean isBackgroundAllocationAllowed() {
		return true;
	}
	
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
