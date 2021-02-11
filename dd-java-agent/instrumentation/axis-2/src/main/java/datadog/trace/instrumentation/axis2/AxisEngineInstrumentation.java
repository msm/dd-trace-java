package datadog.trace.instrumentation.axis2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.axis2.AxisMessageDecorator.AXIS2_MESSAGE;
import static datadog.trace.instrumentation.axis2.AxisMessageDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Tracer;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.engine.Handler.InvocationResponse;

@AutoService(Instrumenter.class)
public final class AxisEngineInstrumentation extends Instrumenter.Tracing {

  public AxisEngineInstrumentation() {
    super("axis2");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.apache.axis2.engine.AxisEngine");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AxisMessageDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod()
            .and(namedOneOf("receive", "send", "sendFault"))
            .and(takesArgument(0, named("org.apache.axis2.context.MessageContext"))),
        getClass().getName() + "$HandleMessageAdvice");
    transformers.put(
        isMethod()
            .and(namedOneOf("resumeReceive", "resumeSend", "resumeSendFault"))
            .and(takesArgument(0, named("org.apache.axis2.context.MessageContext"))),
        getClass().getName() + "$ResumeMessageAdvice");
    transformers.put(
        isMethod()
            .and(named("invoke"))
            .and(takesArgument(0, named("org.apache.axis2.context.MessageContext"))),
        getClass().getName() + "$InvokeMessageAdvice");
    return transformers;
  }

  public static final class HandleMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginHandleMessage(@Advice.Argument(0) final MessageContext message) {
      if (DECORATE.shouldTrace(message)) {
        AgentSpan span = startSpan(AXIS2_MESSAGE);
        DECORATE.afterStart(span);
        DECORATE.onMessage(span, message);
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endHandleMessage(
        @Advice.Enter final AgentScope scope,
        @Advice.Argument(0) final MessageContext message,
        @Advice.Thrown final Throwable error) {
      if (null != scope && activeScope() == scope) {
        AgentSpan span = scope.span();
        if (null != error) {
          DECORATE.onError(span, error);
        }
        DECORATE.beforeFinish(span, message);
        scope.close();
        span.finish();
      }
    }
  }

  public static final class ResumeMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginResumeMessage(@Advice.Argument(0) final MessageContext message) {
      Object spanData = message.getSelfManagedData(Tracer.class, "span");
      if (spanData instanceof AgentSpan) {
        message.removeSelfManagedData(Tracer.class, "span");
        return activateSpan((AgentSpan) spanData);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endResumeMessage(
        @Advice.Enter final AgentScope scope,
        @Advice.Argument(0) final MessageContext message,
        @Advice.Thrown final Throwable error) {
      if (null != scope && activeScope() == scope) {
        AgentSpan span = scope.span();
        if (null != error) {
          DECORATE.onError(span, error);
        }
        DECORATE.beforeFinish(span, message);
        scope.close();
        span.finish();
      }
    }
  }

  public static final class InvokeMessageAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void recordSuspendedSpan(
        @Advice.Argument(0) final MessageContext message,
        @Advice.Return final InvocationResponse response) {
      if (InvocationResponse.SUSPEND.equals((Object) response)) {
        TraceScope scope = activeScope();
        if (scope instanceof AgentScope) {
          AgentSpan span = ((AgentScope) scope).span();
          if (DECORATE.sameTrace(span, message)) {
            message.setSelfManagedData(Tracer.class, "span", span);
            scope.close();
          }
        }
      }
    }
  }
}
