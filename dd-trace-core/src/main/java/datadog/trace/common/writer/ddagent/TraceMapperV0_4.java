package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.http.OkHttpUtils.msgpackRequestBodyOf;
import static datadog.trace.core.serialization.Util.integerToStringBuffer;
import static datadog.trace.core.serialization.Util.writeLongAsString;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import datadog.trace.core.serialization.Writable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import okhttp3.RequestBody;

public final class TraceMapperV0_4 implements TraceMapper {

  public static final byte[] SERVICE = "service".getBytes(ISO_8859_1);
  public static final byte[] NAME = "name".getBytes(ISO_8859_1);
  public static final byte[] RESOURCE = "resource".getBytes(ISO_8859_1);
  public static final byte[] TRACE_ID = "trace_id".getBytes(ISO_8859_1);
  public static final byte[] SPAN_ID = "span_id".getBytes(ISO_8859_1);
  public static final byte[] PARENT_ID = "parent_id".getBytes(ISO_8859_1);
  public static final byte[] START = "start".getBytes(ISO_8859_1);
  public static final byte[] DURATION = "duration".getBytes(ISO_8859_1);
  public static final byte[] TYPE = "type".getBytes(ISO_8859_1);
  public static final byte[] ERROR = "error".getBytes(ISO_8859_1);
  public static final byte[] METRICS = "metrics".getBytes(ISO_8859_1);
  public static final byte[] META = "meta".getBytes(ISO_8859_1);

  static final byte[] EMPTY = ByteBuffer.allocate(1).put((byte) 0x90).array();
  private final int size;

  public TraceMapperV0_4(int size) {
    this.size = size;
  }

  public TraceMapperV0_4() {
    this(5 << 20);
  }

  private static final class MetaWriter extends MetadataConsumer {

    private final byte[] numberByteArray = integerToStringBuffer();
    private Writable writable;

    MetaWriter withWritable(Writable writable) {
      this.writable = writable;
      return this;
    }

    @Override
    public void accept(Metadata metadata) {
      // since tags can "override" baggage, we need to count the non overlapping ones
      int size = metadata.getTags().size() + 2;
      // assume we can't have more than 64 baggage items,
      // and that iteration order is stable to avoid looking
      // up in the tags more than necessary
      long overlaps = 0L;
      if (!metadata.getBaggage().isEmpty()) {
        int i = 0;
        for (Map.Entry<String, String> key : metadata.getBaggage().entrySet()) {
          if (!metadata.getTags().containsKey(key.getKey())) {
            size++;
          } else {
            overlaps |= (1L << i);
          }
          ++i;
        }
      }
      writable.startMap(size);
      int i = 0;
      for (Map.Entry<String, String> entry : metadata.getBaggage().entrySet()) {
        // tags and baggage may intersect, but tags take priority
        if ((overlaps & (1L << i)) == 0) {
          writable.writeString(entry.getKey(), null);
          writable.writeString(entry.getValue(), null);
        }
        ++i;
      }
      writable.writeUTF8(THREAD_NAME);
      writable.writeUTF8(metadata.getThreadName());
      writable.writeUTF8(THREAD_ID);
      writeLongAsString(metadata.getThreadId(), writable, numberByteArray);
      for (Map.Entry<String, Object> entry : metadata.getTags().entrySet()) {
        writable.writeString(entry.getKey(), null);
        if (entry.getValue() instanceof Long || entry.getValue() instanceof Integer) {
          // TODO it would be nice not to need to do this, either because
          //  the agent would accept variably typed tag values, or numeric
          //  tags get moved to the metrics
          writeLongAsString(((Number) entry.getValue()).longValue(), writable, numberByteArray);
        } else if (entry.getValue() instanceof UTF8BytesString) {
          writable.writeUTF8((UTF8BytesString) entry.getValue());
        } else {
          writable.writeString(String.valueOf(entry.getValue()), null);
        }
      }
    }
  }

  private final MetaWriter metaWriter = new MetaWriter();

  @Override
  public void map(List<? extends CoreSpan<?>> trace, final Writable writable) {
    writable.startArray(trace.size());
    for (CoreSpan<?> span : trace) {
      writable.startMap(12);
      /* 1  */
      writable.writeUTF8(SERVICE);
      writable.writeString(span.getServiceName(), null);
      /* 2  */
      writable.writeUTF8(NAME);
      writable.writeObject(span.getOperationName(), null);
      /* 3  */
      writable.writeUTF8(RESOURCE);
      writable.writeObject(span.getResourceName(), null);
      /* 4  */
      writable.writeUTF8(TRACE_ID);
      writable.writeLong(span.getTraceId().toLong());
      /* 5  */
      writable.writeUTF8(SPAN_ID);
      writable.writeLong(span.getSpanId().toLong());
      /* 6  */
      writable.writeUTF8(PARENT_ID);
      writable.writeLong(span.getParentId().toLong());
      /* 7  */
      writable.writeUTF8(START);
      writable.writeLong(span.getStartTime());
      /* 8  */
      writable.writeUTF8(DURATION);
      writable.writeLong(span.getDurationNano());
      /* 9  */
      writable.writeUTF8(TYPE);
      writable.writeString(span.getType(), null);
      /* 10 */
      writable.writeUTF8(ERROR);
      writable.writeInt(span.getError());
      /* 11 */
      writeMetrics(span, writable);
      /* 12 */
      writable.writeUTF8(META);
      span.processTagsAndBaggage(metaWriter.withWritable(writable));
    }
  }

  private static void writeMetrics(CoreSpan<?> span, Writable writable) {
    writable.writeUTF8(METRICS);
    Map<CharSequence, Number> metrics = span.getUnsafeMetrics();
    int elementCount = metrics.size();
    elementCount += (span.hasSamplingPriority() ? 1 : 0);
    elementCount += (span.isMeasured() ? 1 : 0);
    elementCount += (span.isTopLevel() ? 1 : 0);
    writable.startMap(elementCount);
    if (span.hasSamplingPriority()) {
      writable.writeUTF8(SAMPLING_PRIORITY_KEY);
      writable.writeInt(span.samplingPriority());
    }
    if (span.isMeasured()) {
      writable.writeUTF8(InstrumentationTags.DD_MEASURED);
      writable.writeInt(1);
    }
    if (span.isTopLevel()) {
      writable.writeUTF8(InstrumentationTags.DD_TOP_LEVEL);
      writable.writeInt(1);
    }
    for (Map.Entry<CharSequence, Number> metric : metrics.entrySet()) {
      writable.writeString(metric.getKey(), null);
      writable.writeObject(metric.getValue(), null);
    }
  }

  @Override
  public Payload newPayload() {
    return new PayloadV0_4();
  }

  @Override
  public int messageBufferSize() {
    return size; // 5MB
  }

  @Override
  public void reset() {}

  @Override
  public String endpoint() {
    return "v0.4";
  }

  private static class PayloadV0_4 extends Payload {

    @Override
    int sizeInBytes() {
      return msgpackArrayHeaderSize(traceCount()) + body.remaining();
    }

    @Override
    void writeTo(WritableByteChannel channel) throws IOException {
      ByteBuffer header = msgpackArrayHeader(traceCount());
      while (header.hasRemaining()) {
        channel.write(header);
      }
      while (body.hasRemaining()) {
        channel.write(body);
      }
    }

    @Override
    RequestBody toRequest() {
      return msgpackRequestBodyOf(Arrays.asList(msgpackArrayHeader(traceCount()), body));
    }
  }
}
