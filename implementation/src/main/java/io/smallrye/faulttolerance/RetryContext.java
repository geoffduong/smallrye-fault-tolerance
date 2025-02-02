/*
 * Copyright 2017 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.smallrye.faulttolerance;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceException;

import io.smallrye.faulttolerance.config.RetryConfig;

public class RetryContext {

    private final RetryConfig config;

    private final AtomicInteger remainingAttempts;

    private final AtomicReference<Boolean> shouldRetry = new AtomicReference<>();

    private final long start;

    private final long maxDuration;

    private final long delay;

    RetryContext(RetryConfig config) {
        this.config = config;
        this.start = System.nanoTime();
        this.remainingAttempts = new AtomicInteger(config.<Integer> get(RetryConfig.MAX_RETRIES));
        this.maxDuration = Duration.of(config.get(RetryConfig.MAX_DURATION), config.get(RetryConfig.DURATION_UNIT)).toNanos();
        this.delay = Duration.of(config.get(RetryConfig.DELAY), config.get(RetryConfig.DELAY_UNIT)).toMillis();
    }

    RetryConfig getConfig() {
        return config;
    }

    /**
     *
     * @param throwable
     * @return an exception to rethrow or null if we should try again
     */
    Exception nextRetry(Throwable throwable) {
        // Decrement the retry count for this attempt
        // Check the exception type
        if (shouldRetryOn(throwable)) {
            shouldRetry.set(null);
            remainingAttempts.decrementAndGet();
            return delayIfNeeded();
        } else {
            if (throwable instanceof Error) {
                throw (Error) throwable;
            } else if (throwable instanceof Exception) {
                return (Exception) throwable;
            } else {
                // Business method interceptors may only throw exceptions
                return new FaultToleranceException(throwable);
            }
        }
    }

    boolean shouldRetry() {
        return remainingAttempts.get() > 0;
    }

    public boolean isLastAttempt() {
        return remainingAttempts.get() == 1;
    }

    boolean shouldRetryOn(Throwable exception) {
        Boolean retry = shouldRetry.get();
        if (retry == null) {
            retry = shouldRetry()
                    // The given exception should not abort execution
                    && (config.getAbortOn().length == 0
                            || Arrays.stream(config.getAbortOn()).noneMatch(ex -> ex.isAssignableFrom(exception.getClass())))
                    // We should retry on the given exception
                    && retryOn(exception)
                    // Once the duration is reached, no more retries should be performed
                    && (System.nanoTime() - start <= maxDuration);
            retry = shouldRetry.compareAndSet(null, retry) ? retry : shouldRetry.get();
        }
        return retry;
    }

    private boolean retryOn(Throwable throwable) {
        Class<?>[] retryOn = config.getRetryOn();
        if (retryOn.length == 0) {
            return false;
        }
        if (retryOn.length == 1) {
            Class<?> exceptionClass = retryOn[0];

            return throwable == null || exceptionClass.isAssignableFrom(throwable.getClass());
        }
        return Arrays.stream(retryOn).anyMatch(t -> t.isAssignableFrom(throwable.getClass()));
    }

    /**
     *
     * @return an exception to rethrow or null if we should try again
     */
    private Exception delayIfNeeded() {
        long jitterBase = config.getJitter();
        long jitter = (long) (Math.random() * ((jitterBase * 2) + 1)) - jitterBase; // random number between -jitter and +jitter
        try {
            TimeUnit.MILLISECONDS.sleep(delay + Duration.of(jitter, config.getJitterDelayUnit()).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        }
        return null;
    }

    @Override
    public String toString() {
        return "RetryContext [remainingAttempts=" + remainingAttempts + ", start=" + start + "]";
    }

    public boolean hasBeenRetried() {
        return remainingAttempts.get() < (config.<Integer> get(RetryConfig.MAX_RETRIES));
    }

    public void cancel() {
        remainingAttempts.set(0);
    }
}
