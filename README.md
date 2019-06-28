# Generic Object Pool

GOP is a lightweight generic object pool, providing object lifecycle management, metrics, claim / release mechanism and object invalidation, as well as auto initialize a core pool and auto expiry 
policies.

### About
[API Documentation](https://www.javadoc.io/doc/com.github.bbottema/generic-object-pool/1.0.0)

### Setup

Maven Dependency Setup

```xml
<dependency>
	<groupId>com.github.bbottema</groupId>
	<artifactId>generic-object-pool</artifactId>
	<version>1.0.0</version>
</dependency>
```

## Usage

Creating pools:

```java
// basic pool with no eager loading and no expiry policy
PoolConfig<Foo> poolConfig = PoolConfig.<Foo>builder()
   .maxPoolsize(10)
   .build();

SimpleObjectPool<Foo> pool = new SimpleObjectPool<>(poolConfig, new MyFooAllocator());
```

```java
// more advanced pool with eager loading and auto expiry
PoolConfig<Foo> poolConfig = PoolConfig.<AtomicReference<Integer>>builder()
   .corePoolsize(20)
   .maxPoolsize(20)
   .expirationPolicy(new TimeoutSinceLastAllocationExpirationPolicy<Foo>(30, TimeUnit.SECONDS))
   .build();

SimpleObjectPool<Foo> pool = new SimpleObjectPool<>(poolConfig, new MyFooAllocator());
````
The above pool eagerly warms up 20 Foo instances and expires them 30 seconds after they have last been allocated.

Claiming objects from the pool (blocking):
```java
// borrow an object and block until available
PoolableObject<Foo> obj = pool.claim();
````

Claiming objects from the pool (blocking until timeout):
```java
PoolableObject<Foo> obj = pool.claim(key, 1, TimeUnit.SECONDS);
````

Releasing Objects back to the Pool:
```java
PoolableObject<Foo> obj = pool.claim();
obj.release(); // make available for reuse
// or
obj.invalidate(); // remove from pool, deallocating
````

Shutting down a pool:
```java
Future<?> shutdownSequence = pool.shutdown();

// wait for shutdown to complete
shutdownSequence.get();
// until timeout
shutdownSequence.get(10, TimeUnit.SECONDS);
````

Implementing a simple Allocator to create your objects when populating the pool either eagerly or lazily.
Every method except `allocate` is optional:
```java
static class FooAllocator extends Allocator<Foo> {
	/**
	 * Initial creation and initialization. Called when claim comes or when pool is eagerly loading for core size.
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
	 * Initial creation and initialization. Called when claim comes or when pool is eagerly loading for core size.
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