[![APACHE v2 License](https://img.shields.io/badge/license-apachev2-blue.svg?style=flat)](LICENSE-2.0.txt) 
[![Latest Release](https://img.shields.io/maven-central/v/com.github.bbottema/generic-object-pool.svg?style=flat)](http://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22com.github.bbottema%22%20AND%20a%3A%22generic-object-pool%22)
[![Javadocs](https://img.shields.io/badge/javadoc-2.0.0-brightgreen.svg?color=brightgreen)](https://www.javadoc.io/doc/com.github.bbottema/generic-object-pool) 
[![Codacy](https://img.shields.io/codacy/grade/b1183f7def224cd7b505b42a9a1e2b65.svg?style=flat)](https://www.codacy.com/app/b-bottema/generic-object-pool)

# generic-object-pool

generic-object-pool is a lightweight generic object pool, providing object lifecycle management, 
metrics, claim / release mechanism and object invalidation, as well as auto initialize a core pool and 
auto expiry policies.

## Setup

Maven Dependency Setup

```xml
<dependency>
	<groupId>com.github.bbottema</groupId>
	<artifactId>generic-object-pool</artifactId>
	<version>2.2.0</version>
</dependency>
```

## Usage

#### Creating pools

```java
// basic pool with no eager loading and no expiry policy
PoolConfig<Foo> poolConfig = PoolConfig.<Foo>builder()
   .maxPoolsize(10)
   .build();

GenericObjectPool<Foo> pool = new SimpleObjectPool<>(poolConfig, new MyFooAllocator());
```

```java
// more advanced pool with eager loading and auto expiry
PoolConfig<Foo> poolConfig = PoolConfig.<AtomicReference<Integer>>builder()
   .corePoolsize(20) // keeps 20 objects eagerly allocated at all times
   .maxPoolsize(20)
   // deallocate after 30 seconds, but every time an object is claimed the expiry timeout is reset
   .expirationPolicy(new TimeoutSinceLastAllocationExpirationPolicy<Foo>(30, TimeUnit.SECONDS))
   .build();

GenericObjectPool<Foo> pool = new SimpleObjectPool<>(poolConfig, new MyFooAllocator());
````

#### Claim / release API

Claiming objects from the pool (blocking):
```java
// borrow an object and block until available
PoolableObject<Foo> obj = pool.claim();
````

Claiming objects from the pool (blocking until timeout):
```java
PoolableObject<Foo> obj = pool.claim(key, 1, TimeUnit.SECONDS); // null if timed out
````

Releasing Objects back to the Pool:
```java
PoolableObject<Foo> obj = pool.claim();
obj.release(); // make available for reuse
// or
obj.invalidate(); // remove from pool, deallocating
````

#### Shutting down a pool

```java
Future<?> shutdownSequence = pool.shutdown();

// wait for shutdown to complete
shutdownSequence.get();
// until timeout
shutdownSequence.get(10, TimeUnit.SECONDS);
````

#### Creating your objects

Implementing a simple Allocator to create your objects when populating the pool either eagerly or lazily.
Every method except `allocate` is optional:
```java
static class FooAllocator extends Allocator<Foo> {
	/**
	 * Initial creation and initialization.
	 * Called when claim comes or when pool is eagerly loading for core size.
	 */
	@Override
	public AtomicReference<Integer> allocate() {
		return new Foo();
	}
}
```

More comprehensive life cycle management:
```java
static class FooAllocator extends Allocator<Foo> {
	/**
	 * Initial creation and initialization.
	 * Called when claim comes or when pool is eagerly loading for core size.
	 */
	@Override
	public AtomicReference<Integer> allocate() {
		return new Foo();
	}
	
	/**
	 * Uninitialize an instance which has been released back to the pool, until it is claimed again.
	 */
	@Override
	protected void deallocateForReuse(Foo object) {
		object.putAtRest();
	}
	
	/**
	 * Reinitialize an object so it is ready to be claimed.
	 */
	@Override
	protected void allocateForReuse(Foo object) {
		object.reinitialize();
	}
	
	/**
	 * Clean up an object no longer needed by the pool.
	 */
	@Override
	protected void deallocate(Foo object) {
		object.clear();
	}
}
```

#### Metrics

```java
PoolMetrics metrics = pool.getPoolMetrics();
metrics.getCurrentlyClaimed(); // currently claimed by threads and not released yet
metrics.getCurrentlyWaitingCount(); // currently waiting threads that want to claim
metrics.getCorePoolsize(); // number of instances to auto allocated (eager loading)
metrics.getMaxPoolsize(); // max number of objects allowed at all times
metrics.getCurrentlyAllocated(); // available + claimed objects
metrics.getTotalAllocated(); // total number of allocations during pool's existence
metrics.getTotalClaimed(); // total number of claims during pool's existence
```

If for some reason you need to have more control over how threads are created, you can provide you own ThreadFactory:
```java
PoolConfig<Foo> poolConfig = PoolConfig.<AtomicReference<Integer>>builder()
   .threadFactory(new MyCustomThreadFactory())
   .build();
```

#### Other Expiry strategies

You can expire objects based on age since creation or age since last allocation. For these use:
* `TimeoutSinceCreationExpirationPolicy`
* `TimeoutSinceLastAllocationExpirationPolicy`

You can also spread the expiry around in a bandwidth to avoid having everything expire at the same time, hogging system resources. for these use:
* `SpreadedTimeoutSinceCreationExpirationPolicy`
* `SpreadedTimeoutSinceLastAllocationExpirationPolicy`

You can also combine multiple expirations, by passing instances of them as a set to:
* `CombinedExpirationPolicies`

Finally, you can extend any of these or create your own from scratch by implementing:
* `ExpirationPolicy`

To aid you in creating your own expiry policy, you can calculate and store an expiry age on the poolable object:
```java
poolableObject.getExpiries().put(this, calculatedAge);
Long previouslyCalculateAge = poolableObject.getExpiries().get(this);
```
You can always extend one the abstract classes `SpreadedTimeoutExpirationPolicy` and `TimeoutExpirationPolicy`, which do this for you.