package datadog.trace.instrumentation.springscheduling;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springscheduling.SpringSchedulingDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInvocation;

public class SpannedMethodInvocation implements MethodInvocation {

  private final TraceScope.Continuation continuation;
  private final MethodInvocation delegate;

  public SpannedMethodInvocation(TraceScope.Continuation continuation, MethodInvocation delegate) {
    this.continuation = continuation;
    this.delegate = delegate;
  }

  @Override
  public Method getMethod() {
    return delegate.getMethod();
  }

  @Override
  public Object[] getArguments() {
    return delegate.getArguments();
  }

  @Override
  public Object proceed() throws Throwable {
    CharSequence spanName = DECORATE.spanNameForMethod(delegate.getMethod());
    return null == continuation ? invokeWithSpan(spanName) : invokeWithContinuation(spanName);
  }

  private Object invokeWithContinuation(CharSequence spanName) throws Throwable {
    try (TraceScope scope = continuation.activate()) {
      scope.setAsyncPropagation(true);
      return invokeWithSpan(spanName);
    }
  }

  private Object invokeWithSpan(CharSequence spanName) throws Throwable {
    AgentSpan span = startSpan(spanName);
    try (AgentScope scope = activateSpan(span)) {
      scope.setAsyncPropagation(true);
      return delegate.proceed();
    } finally {
      span.finish();
    }
  }

  @Override
  public Object getThis() {
    return delegate.getThis();
  }

  @Override
  public AccessibleObject getStaticPart() {
    return delegate.getStaticPart();
  }
}
