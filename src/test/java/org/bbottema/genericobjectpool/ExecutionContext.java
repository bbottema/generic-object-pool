package org.bbottema.genericobjectpool;

import lombok.Builder;
import lombok.Value;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Value
@Builder
class ExecutionContext {
	@Builder.Default private final AtomicReference<Exception> asyncException = new AtomicReference<>();
	@Builder.Default private final long maxWaitTime = 100;
	@Builder.Default private final long sleepTime = 600;
	@Builder.Default private final boolean failIfUnableToClaim = false;
	@Builder.Default private final boolean reusable = true;
	@Builder.Default private final AtomicInteger timeoutCount = new AtomicInteger();
}