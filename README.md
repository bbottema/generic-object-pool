[![APACHE v2 License](https://img.shields.io/badge/license-apachev2-blue.svg?style=flat)](LICENSE-2.0.txt) 
[![Latest Release](https://img.shields.io/maven-central/v/com.github.bbottema/generic-object-pool.svg?style=flat)](http://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22com.github.bbottema%22%20AND%20a%3A%22generic-object-pool%22)
[![Javadocs](https://img.shields.io/badge/javadoc-2.0.0-brightgreen.svg?color=brightgreen)](https://www.javadoc.io/doc/com.github.bbottema/generic-object-pool) 
[![Codacy](https://img.shields.io/codacy/grade/b1183f7def224cd7b505b42a9a1e2b65.svg?style=flat)](https://www.codacy.com/app/b-bottema/generic-object-pool)

# generic-object-pool

generic-object-pool is a lightweight generic object pool, providing object lifecycle management, 
metrics, claim / release mechanism and object invalidation, as well as auto initialize a core pool and 
auto expiry policies.

## Origins

The work that became generic-object-pool began in June 2019 with a fork of
[KBOP](https://github.com/gondor/kbop), created by Jeremy Unruh. It was subsequently modernized and
extensively redesigned for Simple Java Mail's SMTP connection pooling, and has continued as an
independent library. KBOP's original work is used under the MIT License; its complete copyright and
license notice is included in [NOTICE.txt](NOTICE.txt).

## Setup

Maven Dependency Setup

```xml
<dependency>
	<groupId>com.github.bbottema</groupId>
	<artifactId>generic-object-pool</artifactId>
	<version>2.5.0</version>
</dependency>
```

For JPMS applications, the published JAR declares the stable automatic module name
`org.bbottema.genericobjectpool`.

## Release Notes

2.5.0 (8 September 2026)

- [#22](https://github.com/bbottema/generic-object-pool/issues/22): Optionally cancel pending claims and give acquisition one total time budget with `ClaimOptions` and `ClaimControl`.
- Allocators can cooperate through `AllocationContext`; slow preparation no longer holds the pool's bookkeeping lock. Allocation and reuse callbacks remain serialized.
- `PoolableObject.getDisposalCompletion()` acknowledges actual cleanup, separately from scheduling invalidation.
- Existing claim methods and allocator subclasses remain supported. Java 8 and the JPMS module name are unchanged.

## Usage

#### Creating pools

```java
// basic pool with no eager loading and no expiry policy
PoolConfig<Foo> poolConfig = PoolConfig.<Foo>builder()
   .maxPoolsize(10)
   .build();

GenericObjectPool<Foo> pool = new GenericObjectPool<>(poolConfig, new MyFooAllocator());
```

```java
// more advanced pool with eager loading and auto expiry
PoolConfig<Foo> poolConfig = PoolConfig.<AtomicReference<Integer>>builder()
   .corePoolsize(20) // keeps 20 objects eagerly allocated at all times
   .maxPoolsize(20)
   // deallocate after 30 seconds, but every time an object is claimed the expiry timeout is reset
   .expirationPolicy(new TimeoutSinceLastAllocationExpirationPolicy<Foo>(30, TimeUnit.SECONDS))
   .build();

GenericObjectPool<Foo> pool = new GenericObjectPool<>(poolConfig, new MyFooAllocator());
````

#### Claim / release API

Claiming objects from the pool (blocking):
```java
// borrow an object and block until available
PoolableObject<Foo> obj = pool.claim();
````

Claiming objects from the pool (blocking until timeout):
```java
PoolableObject<Foo> obj = pool.claim(1, TimeUnit.SECONDS); // null if timed out
````

Claiming an already available object matching a predicate:
```java
PoolableObject<Foo> obj = pool.claimMatching(
	poolable -> poolable.idleAgeMs() >= TimeUnit.MINUTES.toMillis(5),
	1,
	TimeUnit.SECONDS);

if (obj != null) {
	try {
		obj.getAllocatedObject().ping();
		obj.release();
	} catch (IOException e) {
		obj.invalidate();
	}
}
````

The predicate is evaluated while the pool claim lock is held, so keep it fast and side-effect free. Run slow work such as ping/keep-alive checks after the object has been claimed.

Releasing Objects back to the Pool:
```java
PoolableObject<Foo> obj = pool.claim();
obj.release(); // make available for reuse
// or
obj.invalidate(); // remove from pool, deallocating
````

Invalidation wakes ordinary callers that are already waiting for capacity. With a core size of zero, a waiting
caller can create a replacement; a configured core pool also replenishes itself. Final cleanup remains asynchronous
and need not finish before a replacement can be used. Matching claims still only take available objects: they do not
allocate replacements themselves.

#### Optional cancellation and a total acquisition budget

For example, a cancelled export job should stop waiting for another database connection. Create the control before
starting its worker, then retain it in the job's stop handler:

```java
ClaimControl claimControl = new ClaimControl();
ClaimOptions options = ClaimOptions.withTimeout(30, TimeUnit.SECONDS)
    .withClaimControl(claimControl);

// On the job's worker; still an ordinary blocking call.
PoolableObject<Foo> resource = pool.claim(options);
// null: budget expired. CancellationException: stop requested. InterruptedException: worker interrupted.
if (resource != null) {
    try {
        resource.getAllocatedObject().doWork();
    } finally {
        resource.release();
    }
}

// In the stop handler, potentially on another thread:
claimControl.requestCancellation();
```

Creating a control does not cancel anything. A control is thread-safe and one-shot; options are immutable and reusable.
Each call starts a new monotonic budget covering selection by an outer pool, lock waiting, availability and preparation.
`claimMatching(predicate, options)` has the same contract but never allocates resources. Zero budget starts no preparation.
The legacy timeout methods retain their existing wait semantics; they do not gain a total deadline implicitly.

Cancellation ends at handoff: requesting it after `claim` returns does not revoke the borrowed resource. It does not
interrupt an executor thread. Allocator callbacks run on the claiming thread (core replenishment remains pool-owned),
and allocation/reuse/release preparation remains serialized per pool. Final deallocation runs on the cleanup worker.

Override `allocate(AllocationContext)` or `allocateForReuse(resource, AllocationContext)` to cooperate: check
`context.throwIfCancellationRequested()`, use `context.getRemainingTime(unit)` for your own I/O timeout, and optionally
register a quick, non-blocking abort action with `context.onCancellation(...)`. An earlier request invokes that handler
immediately. Handler runtime exceptions are logged and ignored. Close registrations when their resource is no longer
owned; the pool also detaches them before handoff. An allocator must clean up anything it creates but never returns.

An old allocator that blocks in `allocate()` remains supported, but cannot be forcibly stopped: cancellation is recorded
promptly, then the claim waits for allocation to exit and disposes any late resource instead of handing it off. Required
cleanup can also outlast the acquisition budget. New context-aware claims settle only after that cleanup finishes;
cleanup failures are reported (or suppressed on the original failure), not treated as successful disposal. A preparation
exception after cancellation was requested is retained as the cause of `CancellationException`.

`invalidate()` still only schedules cleanup. If physical disposal matters, use
`resource.getDisposalCompletion().toCompletableFuture().get()`. A healthy `release()` does not complete this stage;
the resource is still reusable. Each returned stage is detached from the pool's internal signal. Keep non-async stage
callbacks short because they can run on the cleanup worker.

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

For idle maintenance, `PoolableObject#idleAgeMs()` reports how long an object has been available for claiming. It returns 0 while the object is claimed.

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
