/*
 * Copyright (C) 2019 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
	 * Clean up an object no longer needed by the pool.
	 */
	public void deallocate(T object) {
		// overridable hook
	}
}