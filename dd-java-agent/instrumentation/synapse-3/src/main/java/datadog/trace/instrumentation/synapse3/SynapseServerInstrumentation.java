package datadog.trace.instrumentation.synapse3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.synapse3.SynapseServerDecorator.DECORATE;
import static datadog.trace.instrumentation.synapse3.SynapseServerDecorator.SYNAPSE_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.HttpResponse;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.synapse.transport.passthru.SourceRequest;

@AutoService(Instrumenter.class)
public final class SynapseServerInstrumentation extends Instrumenter.Tracing {

  public SynapseServerInstrumentation() {
    super("synapse3");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return namedOneOf(
        "org.apache.synapse.transport.passthru.ServerWorker",
        "org.apache.synapse.transport.passthru.SourceResponse");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SynapseServerDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod().and(named("run")).and(takesArguments(0)),
        getClass().getName() + "$HandleRequestAdvice");
    transformers.put(
        isMethod()
            .and(named("start"))
            .and(takesArgument(0, named("org.apache.http.nio.NHttpServerConnection"))),
        getClass().getName() + "$CommitResponseAdvice");
    return transformers;
  }

  public static final class HandleRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginRequest(
        @Advice.FieldValue("request") final SourceRequest request) {
      AgentSpan span = startSpan(SYNAPSE_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, request.getConnection());
      DECORATE.onRequest(span, request);
      request.getConnection().getContext().setAttribute("span", span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endRequest(
        @Advice.Enter final AgentScope scope,
        @Advice.FieldValue("request") final SourceRequest request,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }
      if (null != error) {
        AgentSpan span = scope.span();
        DECORATE.onError(span, error);
        DECORATE.beforeFinish(span);
        scope.close();
        span.finish();
        request.getConnection().getContext().removeAttribute("span");
      } else {
        scope.close();
      }
    }
  }

  public static final class CommitResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginResponse(
        @Advice.Argument(0) final NHttpServerConnection connection) {
      Object spanData = connection.getContext().getAttribute("span");
      if (spanData instanceof AgentSpan) {
        connection.getContext().removeAttribute("span");
        return activateSpan((AgentSpan) spanData);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endResponse(
        @Advice.Enter final AgentScope scope,
        @Advice.FieldValue("response") final HttpResponse response,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }
      AgentSpan span = scope.span();
      DECORATE.onResponse(span, response);
      if (null != error) {
        DECORATE.onError(span, error);
      }
      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
    }
  }
}
