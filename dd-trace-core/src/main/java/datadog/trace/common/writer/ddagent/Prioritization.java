package datadog.trace.common.writer.ddagent;

import static datadog.trace.common.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.common.sampling.PrioritySampling.USER_DROP;

import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public enum Prioritization {
  ENSURE_TRACE {
    @Override
    public PrioritizationStrategy create(
        final Queue<Object> primary, final Queue<Object> secondary) {
      return new EnsureTraceStrategy(primary, secondary);
    }
  },
  FAST_LANE {
    @Override
    public PrioritizationStrategy create(
        final Queue<Object> primary, final Queue<Object> secondary) {
      return new FastLaneStrategy(primary, secondary);
    }
  },
  DEAD_LETTERS {
    @Override
    public PrioritizationStrategy create(
        final Queue<Object> primary, final Queue<Object> secondary) {
      return new DeadLettersStrategy(primary, secondary);
    }
  },
  DROP {
    @Override
    public PrioritizationStrategy create(Queue<Object> primary, Queue<Object> secondary) {
      return new DropStrategy(primary);
    }
  };

  public abstract PrioritizationStrategy create(Queue<Object> primary, Queue<Object> secondary);

  private abstract static class PrioritizationStrategyWithFlush implements PrioritizationStrategy {

    protected final Queue<Object> primary;

    protected PrioritizationStrategyWithFlush(Queue<Object> primary) {
      this.primary = primary;
    }

    @Override
    public boolean flush(final long timeout, final TimeUnit timeUnit) {
      // ok not to flush the secondary
      final CountDownLatch latch = new CountDownLatch(1);
      final FlushEvent event = new FlushEvent(latch);
      blockingOffer(primary, event);
      try {
        return latch.await(timeout, timeUnit);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    protected void blockingOffer(final Queue<Object> queue, final Object event) {
      boolean offered;
      do {
        offered = queue.offer(event);
      } while (!offered);
    }
  }

  private static final class EnsureTraceStrategy extends PrioritizationStrategyWithFlush {

    private final Queue<Object> secondary;

    private EnsureTraceStrategy(final Queue<Object> primary, final Queue<Object> secondary) {
      super(primary);
      this.secondary = secondary;
    }

    @Override
    public boolean publish(final int priority, final List<DDSpan> trace) {
      switch (priority) {
        case SAMPLER_DROP:
        case USER_DROP:
          return secondary.offer(trace);
        default:
          blockingOffer(primary, trace);
          return true;
      }
    }
  }

  private static final class FastLaneStrategy extends PrioritizationStrategyWithFlush {

    private final Queue<Object> secondary;

    private FastLaneStrategy(final Queue<Object> primary, final Queue<Object> secondary) {
      super(primary);
      this.secondary = secondary;
    }

    @Override
    public boolean publish(final int priority, final List<DDSpan> trace) {
      switch (priority) {
        case SAMPLER_DROP:
        case USER_DROP:
          return secondary.offer(trace);
        default:
          return primary.offer(trace);
      }
    }
  }

  private static final class DeadLettersStrategy implements PrioritizationStrategy {

    private final Queue<Object> primary;
    private final Queue<Object> secondary;

    private DeadLettersStrategy(final Queue<Object> primary, final Queue<Object> secondary) {
      this.primary = primary;
      this.secondary = secondary;
    }

    @Override
    public boolean publish(final int priority, final List<DDSpan> trace) {
      if (!primary.offer(trace)) {
        switch (priority) {
          case SAMPLER_DROP:
          case USER_DROP:
            return false;
          default:
            return secondary.offer(trace);
        }
      }
      return true;
    }

    @Override
    public boolean flush(final long timeout, final TimeUnit timeUnit) {
      // both queues need to be flushed
      final CountDownLatch latch = new CountDownLatch(2);
      final FlushEvent event = new FlushEvent(latch);
      offer(primary, event);
      offer(secondary, event);
      try {
        return latch.await(timeout, timeUnit);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    private void offer(final Queue<Object> queue, final FlushEvent event) {
      boolean offered;
      do {
        offered = queue.offer(event);
      } while (!offered);
    }
  }

  private static final class DropStrategy extends PrioritizationStrategyWithFlush {

    private DropStrategy(final Queue<Object> primary) {
      super(primary);
    }

    @Override
    public boolean publish(final int priority, final List<DDSpan> trace) {
      switch (priority) {
        case SAMPLER_DROP:
        case USER_DROP:
          return false;
        default:
          return primary.offer(trace);
      }
    }
  }
}
