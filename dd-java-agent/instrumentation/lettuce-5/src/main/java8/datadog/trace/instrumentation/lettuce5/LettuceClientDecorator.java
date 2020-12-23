package datadog.trace.instrumentation.lettuce5;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import io.lettuce.core.RedisURI;
import io.lettuce.core.protocol.RedisCommand;

public class LettuceClientDecorator extends DBTypeProcessingDatabaseClientDecorator<RedisURI> {
  public static final CharSequence REDIS_CLIENT = UTF8BytesString.createConstant("redis-client");
  public static final CharSequence REDIS_QUERY = UTF8BytesString.createConstant("redis.query");
  public static final LettuceClientDecorator DECORATE = new LettuceClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"lettuce"};
  }

  @Override
  protected String service() {
    return "redis";
  }

  @Override
  protected CharSequence component() {
    return REDIS_CLIENT;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.REDIS;
  }

  @Override
  protected String dbType() {
    return "redis";
  }

  @Override
  protected String dbUser(final RedisURI connection) {
    return null;
  }

  @Override
  protected String dbInstance(final RedisURI connection) {
    return null;
  }

  @Override
  protected String dbHostname(RedisURI redisURI) {
    return redisURI.getHost();
  }

  @Override
  public AgentSpan onConnection(final AgentSpan span, final RedisURI connection) {
    if (connection != null) {
      span.setTag(Tags.PEER_HOSTNAME, connection.getHost());
      setPeerPort(span, connection.getPort());

      span.setTag("db.redis.dbIndex", connection.getDatabase());
      span.setResourceName(
          "CONNECT:"
              + connection.getHost()
              + ":"
              + connection.getPort()
              + "/"
              + connection.getDatabase());
    }
    return super.onConnection(span, connection);
  }

  public AgentSpan onCommand(final AgentSpan span, final RedisCommand command) {
    final String commandName = LettuceInstrumentationUtil.getCommandName(command);
    span.setResourceName(LettuceInstrumentationUtil.getCommandResourceName(commandName));
    return span;
  }
}
