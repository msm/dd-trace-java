package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.AggregateMetric.ERROR_TAG;
import static datadog.trace.common.metrics.Batch.REPORT;
import static datadog.trace.util.AgentThreadFactory.AgentThread.METRICS_AGGREGATOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreSpan;
import datadog.trace.util.AgentTaskScheduler;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jctools.queues.MpmcArrayQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

@Slf4j
public final class ConflatingMetricsAggregator implements MetricsAggregator, EventListener {

  private static final Integer ZERO = 0;

  static final Batch POISON_PILL = Batch.NULL;

  private final Queue<Batch> batchPool;
  private final ConcurrentHashMap<MetricKey, Batch> pending;
  private final Set<MetricKey> newKeysInInterval;
  private final Thread thread;
  private final BlockingQueue<Batch> inbox;
  private final Sink sink;
  private final Aggregator aggregator;
  private final long reportingInterval;
  private final TimeUnit reportingIntervalTimeUnit;

  private volatile boolean enabled = true;
  private volatile AgentTaskScheduler.Scheduled<?> cancellation;

  public ConflatingMetricsAggregator(Config config) {
    this(
        config.getWellKnownTags(),
        new OkHttpSink(
            config.getAgentUrl(),
            config.getAgentTimeout(),
            config.isTracerMetricsBufferingEnabled()),
        config.getTracerMetricsMaxAggregates(),
        config.getTracerMetricsMaxPending());
  }

  ConflatingMetricsAggregator(
      WellKnownTags wellKnownTags, Sink sink, int maxAggregates, int queueSize) {
    this(wellKnownTags, sink, maxAggregates, queueSize, 10, SECONDS);
  }

  ConflatingMetricsAggregator(
      WellKnownTags wellKnownTags,
      Sink sink,
      int maxAggregates,
      int queueSize,
      long reportingInterval,
      TimeUnit timeUnit) {
    this(
        sink,
        new SerializingMetricWriter(wellKnownTags, sink),
        maxAggregates,
        queueSize,
        reportingInterval,
        timeUnit);
  }

  ConflatingMetricsAggregator(
      Sink sink,
      MetricWriter metricWriter,
      int maxAggregates,
      int queueSize,
      long reportingInterval,
      TimeUnit timeUnit) {
    this.inbox = new MpscBlockingConsumerArrayQueue<>(queueSize);
    this.batchPool = new MpmcArrayQueue<>(maxAggregates);
    this.pending = new ConcurrentHashMap<>(maxAggregates * 4 / 3, 0.75f);
    this.newKeysInInterval = Collections.newSetFromMap(new ConcurrentHashMap<MetricKey, Boolean>());
    this.sink = sink;
    this.aggregator =
        new Aggregator(
            metricWriter,
            batchPool,
            inbox,
            pending,
            newKeysInInterval,
            maxAggregates,
            reportingInterval,
            timeUnit);
    this.thread = newAgentThread(METRICS_AGGREGATOR, aggregator);
    this.reportingInterval = reportingInterval;
    this.reportingIntervalTimeUnit = timeUnit;
  }

  @Override
  public void start() {
    sink.register(this);
    thread.start();
    cancellation =
        AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
            new ReportTask(),
            this,
            reportingInterval,
            reportingInterval,
            reportingIntervalTimeUnit);
  }

  @Override
  public void report() {
    boolean published;
    do {
      published = inbox.offer(REPORT);
    } while (!published);
  }

  @Override
  public boolean publish(List<? extends CoreSpan<?>> trace) {
    boolean forceKeep = false;
    if (enabled) {
      for (CoreSpan<?> span : trace) {
        if (span.isTopLevel() || span.isMeasured()) {
          forceKeep |= publish(span);
        }
      }
    }
    return forceKeep;
  }

  private boolean publish(CoreSpan<?> span) {
    MetricKey key =
        new MetricKey(
            span.getResourceName(),
            span.getServiceName(),
            span.getOperationName(),
            span.getType(),
            span.getTag(Tags.HTTP_STATUS, ZERO));
    long tag = span.getError() > 0 ? ERROR_TAG : 0L;
    long durationNanos = span.getDurationNano();
    Batch batch = pending.get(key);
    if (null != batch) {
      // there is a pending batch, try to win the race to add to it
      // returning false means that either the batch can't take any
      // more data, or it has already been consumed
      if (batch.add(tag, durationNanos)) {
        // added to a pending batch prior to consumption
        // so skip publishing to the queue (we also know
        // the key isn't rare enough to override the sampler)
        return false;
      }
      // recycle the older key
      key = batch.getKey();
    }
    batch = newBatch(key);
    batch.add(tag, durationNanos);
    // overwrite the last one if present, it was already full
    // or had been consumed by the time we tried to add to it
    pending.put(key, batch);
    // must offer to the queue after adding to pending
    inbox.offer(batch);
    // enforce a soft bound on the size of the collection,
    // which may lead to some false positive sampler overrides
    // when metric cardinality is high
    if (newKeysInInterval.size() > 10_000) {
      newKeysInInterval.remove(newKeysInInterval.iterator().next());
    }
    // force keep keys we haven't seen before or errors
    return newKeysInInterval.add(key) || span.getError() > 0;
  }

  private Batch newBatch(MetricKey key) {
    Batch batch = batchPool.poll();
    if (null == batch) {
      return new Batch(key);
    }
    return batch.reset(key);
  }

  public void stop() {
    if (null != cancellation) {
      cancellation.cancel();
    }
    inbox.offer(POISON_PILL);
  }

  @Override
  public void close() {
    stop();
    try {
      thread.join(THREAD_JOIN_TIMOUT_MS);
    } catch (InterruptedException ignored) {
    }
  }

  @Override
  public void onEvent(EventType eventType, String message) {
    switch (eventType) {
      case DOWNGRADED:
        log.debug("Disabling metric reporting because an agent downgrade was detected");
        disable();
        break;
      case BAD_PAYLOAD:
        log.debug("bad metrics payload sent to trace agent: {}", message);
        break;
      case ERROR:
        log.debug("trace agent errored receiving metrics payload: {}", message);
        break;
      default:
    }
  }

  private void disable() {
    this.enabled = false;
    this.thread.interrupt();
    this.pending.clear();
    this.batchPool.clear();
    this.inbox.clear();
    this.aggregator.clearAggregates();
  }

  private static final class ReportTask
      implements AgentTaskScheduler.Task<ConflatingMetricsAggregator> {

    @Override
    public void run(ConflatingMetricsAggregator target) {
      target.report();
    }
  }
}
