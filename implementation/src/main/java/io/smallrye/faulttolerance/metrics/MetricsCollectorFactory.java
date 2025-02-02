package io.smallrye.faulttolerance.metrics;

import java.util.function.Supplier;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricType;
import org.jboss.logging.Logger;

import com.netflix.hystrix.HystrixCircuitBreaker;
import com.netflix.hystrix.HystrixCommandMetrics;
import com.netflix.hystrix.HystrixThreadPoolKey;
import com.netflix.hystrix.HystrixThreadPoolMetrics;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.hystrix.exception.HystrixRuntimeException.FailureType;

import io.smallrye.faulttolerance.DefaultHystrixConcurrencyStrategy;
import io.smallrye.faulttolerance.HystrixCommandInterceptor;
import io.smallrye.faulttolerance.RetryContext;
import io.smallrye.faulttolerance.SimpleCommand;
import io.smallrye.faulttolerance.SynchronousCircuitBreaker;
import io.smallrye.faulttolerance.config.FaultToleranceOperation;

@ApplicationScoped
public class MetricsCollectorFactory {

    private static final Logger LOGGER = Logger.getLogger(DefaultHystrixConcurrencyStrategy.class);

    @Inject
    MetricRegistry registry;

    @Inject
    @ConfigProperty(name = "MP_Fault_Tolerance_Metrics_Enabled", defaultValue = "true")
    Boolean metricsEnabled;

    public MetricsCollector createCollector(FaultToleranceOperation operation, RetryContext retryContext,
            HystrixThreadPoolKey threadPoolKey) {
        if (metricsEnabled) {
            return new MetricsCollectorImpl(operation, retryContext, threadPoolKey);
        } else {
            return MetricsCollector.NOOP;
        }
    }

    public MetricRegistry getRegistry() {
        return registry;
    }

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    /**
     * TODO: SmallRye MetricRegistry is not thread-safe and so we synchronize on FaultToleranceOperation
     */
    class MetricsCollectorImpl implements MetricsCollector {

        private final FaultToleranceOperation operation;

        private final String metricsPrefix;

        private final RetryContext retryContext;

        private final HystrixThreadPoolKey threadPoolKey;

        private boolean isCircuitBreakerOpenBeforeExceptionProcessing;

        private long start;

        MetricsCollectorImpl(FaultToleranceOperation operation, RetryContext retryContext, HystrixThreadPoolKey threadPoolKey) {
            this.operation = operation;
            this.retryContext = retryContext;
            this.threadPoolKey = threadPoolKey;
            this.metricsPrefix = MetricNames.metricsPrefix(operation.getMethod());
        }

        @Override
        public void init(SynchronousCircuitBreaker circuitBreaker) {
            runSafely(() -> {
                counterInc(metricsPrefix + MetricNames.INVOCATIONS_TOTAL);
                start = 0;
                if (circuitBreaker != null) {
                    gaugeRegister(metricsPrefix + MetricNames.CB_OPEN_TOTAL, circuitBreaker::getOpenTotal);
                    gaugeRegister(metricsPrefix + MetricNames.CB_CLOSED_TOTAL, circuitBreaker::getClosedTotal);
                    gaugeRegister(metricsPrefix + MetricNames.CB_HALF_OPEN_TOTAL, circuitBreaker::getHalfOpenTotal);
                }
            });
        }

        @Override
        public void beforeExecute(SimpleCommand command) {
            runSafely(() -> {
                start = System.nanoTime();
                if (retryContext != null && retryContext.hasBeenRetried()) {
                    counterInc(metricsPrefix + MetricNames.RETRY_RETRIES_TOTAL);
                }
                if (operation.hasBulkhead()) {
                    if (operation.isAsync()) {
                        HystrixThreadPoolMetrics threadPoolMetrics = HystrixThreadPoolMetrics.getInstance(threadPoolKey);
                        gaugeRegister(metricsPrefix + MetricNames.BULKHEAD_WAITING_QUEUE_POPULATION,
                                () -> threadPoolMetrics.getCurrentQueueSize().longValue());
                    }
                    HystrixCommandMetrics hcm = command.getMetrics();
                    gaugeRegister(metricsPrefix + MetricNames.BULKHEAD_CONCURRENT_EXECUTIONS,
                            () -> (long) hcm.getCurrentConcurrentExecutionCount());
                }
            });
        }

        @Override
        public void afterSuccess(SimpleCommand command) {
            runSafely(() -> {
                if (retryContext != null) {
                    if (retryContext.hasBeenRetried()) {
                        counterInc(metricsPrefix + MetricNames.RETRY_CALLS_SUCCEEDED_RETRIED_TOTAL);
                    } else {
                        counterInc(metricsPrefix + MetricNames.RETRY_CALLS_SUCCEEDED_NOT_RETRIED_TOTAL);
                    }
                }
                if (operation.hasTimeout()) {
                    counterInc(metricsPrefix + MetricNames.TIMEOUT_CALLS_NOT_TIMED_OUT_TOTAL);
                }
                if (operation.hasCircuitBreaker()) {
                    counterInc(metricsPrefix + MetricNames.CB_CALLS_SUCCEEDED_TOTAL);
                }
                if (operation.hasBulkhead()) {
                    counterInc(metricsPrefix + MetricNames.BULKHEAD_CALLS_ACCEPTED_TOTAL);
                    if (start != 0) {
                        histogramUpdate(metricsPrefix + MetricNames.BULKHEAD_EXECUTION_DURATION, System.nanoTime() - start);
                    }
                    // TODO: I have no idea where to take this value
                    // HystrixCommandMetrics hcm = command.getMetrics();
                    // long execution = hcm.getExecutionTimePercentile(50) * 1000000;
                    // histogramUpdate(metricsPrefix + MetricNames.BULKHEAD_EXECUTION_DURATION, execution);
                    // if (operation.isAsync()) {
                    // histogramUpdate(metricsPrefix + MetricNames.BULKHEAD_WAITING_DURATION, hcm.getTotalTimePercentile(50) * 1000000 - execution);
                    // }
                }
            });
        }

        @Override
        public void onError(SimpleCommand command, HystrixRuntimeException e) {
            runSafely(() -> {
                if (operation.hasBulkhead()
                        && (FailureType.REJECTED_THREAD_EXECUTION == e.getFailureType()
                                || FailureType.REJECTED_SEMAPHORE_EXECUTION == e.getFailureType())) {
                    counterInc(metricsPrefix + MetricNames.BULKHEAD_CALLS_REJECTED_TOTAL);
                }

                if (operation.hasCircuitBreaker()) {
                    if (e.getFailureType() == FailureType.SHORTCIRCUIT) {
                        counterInc(metricsPrefix + MetricNames.CB_CALLS_PREVENTED_TOTAL);
                    } else {
                        if (circuitBreakerFailsOn(e, command)) {
                            counterInc(metricsPrefix + MetricNames.CB_CALLS_FAILED_TOTAL);
                        } else {
                            counterInc(metricsPrefix + MetricNames.CB_CALLS_SUCCEEDED_TOTAL);
                        }
                    }
                    isCircuitBreakerOpenBeforeExceptionProcessing = command.getCircuitBreaker().isOpen();
                }

                if (e.getFallbackException() != null && (retryContext == null || retryContext.isLastAttempt())) {
                    counterInc(metricsPrefix + MetricNames.FALLBACK_CALLS_TOTAL);
                }

                if (retryContext != null) {
                    if (retryContext.isLastAttempt()) {
                        counterInc(metricsPrefix + MetricNames.RETRY_CALLS_FAILED_TOTAL);
                        counterInc(metricsPrefix + MetricNames.INVOCATIONS_FAILED_TOTAL);
                    }
                } else {
                    counterInc(metricsPrefix + MetricNames.INVOCATIONS_FAILED_TOTAL);
                }
            });
        }

        private boolean circuitBreakerFailsOn(HystrixRuntimeException e, SimpleCommand command) {
            HystrixCircuitBreaker circuitBreaker = command.getCircuitBreaker();
            if (circuitBreaker instanceof SynchronousCircuitBreaker) {
                Exception cause = HystrixCommandInterceptor.getCause(e);
                SynchronousCircuitBreaker cb = (SynchronousCircuitBreaker) circuitBreaker;
                return cb.failsOn(cause);
            }

            // Hystrix's default circuit breaker fails on all exceptions
            return true;
        }

        @Override
        public void onProcessedError(SimpleCommand command, Exception exception) {
            runSafely(() -> {
                HystrixCircuitBreaker cb = command.getCircuitBreaker();
                if (cb != null && cb.isOpen() && !isCircuitBreakerOpenBeforeExceptionProcessing) {
                    counterInc(metricsPrefix + MetricNames.CB_OPENED_TOTAL);
                }
                if (exception != null && TimeoutException.class.equals(exception.getClass())) {
                    counterInc(metricsPrefix + MetricNames.TIMEOUT_CALLS_TIMED_OUT_TOTAL);
                }
            });
        }

        @Override
        public void afterExecute(SimpleCommand command) {
            runSafely(() -> {
                if (start != 0 && operation.hasTimeout()) {
                    histogramUpdate(metricsPrefix + MetricNames.TIMEOUT_EXECUTION_DURATION, System.nanoTime() - start);
                }
                if (command.isResponseFromFallback()) {
                    counterInc(metricsPrefix + MetricNames.FALLBACK_CALLS_TOTAL);
                }
            });
        }

        private void counterInc(String name) {
            counterOf(name).inc();
        }

        private void gaugeRegister(String name, Supplier<Long> supplier) {
            MetricID metricID = new MetricID(name);
            Gauge<?> gauge = registry.getGauges().get(metricID);
            if (gauge == null) {
                synchronized (operation) {
                    gauge = registry.getGauges().get(metricID);
                    if (gauge == null) {
                        registry.register(name, (Gauge<Long>) supplier::get);
                    }
                }
            }
        }

        private void histogramUpdate(String name, long value) {
            histogramOf(name).update(value);
        }

        private Counter counterOf(String name) {
            MetricID metricID = new MetricID(name);
            Counter counter = registry.getCounters().get(metricID);
            if (counter == null) {
                synchronized (operation) {
                    counter = registry.getCounters().get(metricID);
                    if (counter == null) {
                        counter = registry.counter(metadataOf(name, MetricType.COUNTER));
                    }
                }
            }
            return counter;
        }

        private Histogram histogramOf(String name) {
            MetricID metricID = new MetricID(name);
            Histogram histogram = registry.getHistograms().get(metricID);
            if (histogram == null) {
                synchronized (operation) {
                    histogram = registry.getHistograms().get(metricID);
                    if (histogram == null) {
                        histogram = registry.histogram(metadataOf(name, MetricType.HISTOGRAM));
                    }
                }
            }
            return histogram;
        }

    }

    private void runSafely(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException any) {
            LOGGER.warn("Collecting metrics failed", any);
        }
    }

    public static Metadata metadataOf(String name, MetricType metricType) {
        return Metadata.builder()
                .withName(name)
                .withType(metricType)
                .reusable()
                .build();
    }

}
