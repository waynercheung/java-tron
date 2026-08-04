package org.tron.core.services.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.jsonrpc4j.ErrorResolver;
import com.googlecode.jsonrpc4j.JsonRpcError;
import com.googlecode.jsonrpc4j.JsonRpcErrors;
import com.googlecode.jsonrpc4j.ProxyUtil;
import com.googlecode.jsonrpc4j.ReflectionUtil;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.tron.core.exception.TronError;
import org.tron.core.exception.jsonrpc.JsonRpcException;

/**
 * {@link ErrorResolver} that uses annotations.
 */
@Slf4j(topic = "API")
public enum JsonRpcErrorResolver implements ErrorResolver {
  INSTANCE;

  private static final Set<String> SEEN_FAILURES = ConcurrentHashMap.newKeySet();

  /**
   * {@inheritDoc}
   */
  @Override
  public JsonError resolveError(
      Throwable thrownException, Method method, List<JsonNode> arguments) {
    Error fatal = findFatal(thrownException);
    if (fatal != null) {
      throw fatal;
    }

    JsonRpcError resolver = method == null
        ? null : getResolverForException(thrownException, method);
    if (resolver == null) {
      logUnhandledException(method, thrownException);
      return new JsonError(JsonError.INTERNAL_ERROR.code, "Internal error", null);
    }

    String message = hasErrorMessage(resolver) ? resolver.message() : thrownException.getMessage();
    if (StringUtils.isBlank(message)) {
      message = defaultMessageFor(resolver.code());
    }

    // data priority: exception > annotation
    Object data = null;
    if (thrownException instanceof JsonRpcException) {
      JsonRpcException jsonRpcException = (JsonRpcException) thrownException;
      data = jsonRpcException.getData();
    }

    if (data == null && hasErrorData(resolver)) {
      data = resolver.data();
    }

    return new JsonError(resolver.code(), message, data);
  }

  private static Error findFatal(Throwable throwable) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = throwable;
        cause != null && seen.add(cause); cause = cause.getCause()) {
      if (cause instanceof VirtualMachineError
          || cause instanceof ThreadDeath
          || cause instanceof LinkageError
          || cause instanceof TronError) {
        return (Error) cause;
      }
    }
    return null;
  }

  private static void logUnhandledException(Method method, Throwable thrownException) {
    String methodName = rpcMethodName(method);
    String exceptionName = thrownException.getClass().getName();
    String key = methodName + '\0' + exceptionName;
    if (SEEN_FAILURES.add(key)) {
      // The first occurrence retains the Throwable for diagnosis. Repeated failures omit both
      // the stack and exception message so a request loop cannot flood the WARN log.
      logger.warn("Unhandled exception in JSON-RPC method {}", methodName, thrownException);
    } else {
      logger.debug("Repeated unhandled exception in JSON-RPC method {} ({})",
          methodName, exceptionName);
    }
  }

  static void clearSeenFailuresForTest() {
    SEEN_FAILURES.clear();
  }

  private static String rpcMethodName(Method method) {
    if (method == null) {
      return "unknown";
    }
    try {
      return ProxyUtil.getMethodName(method);
    } catch (RuntimeException e) {
      return method.getName();
    }
  }

  private static String defaultMessageFor(int code) {
    switch (code) {
      case -32600:
        return "Invalid Request";
      case -32601:
        return "Method not found";
      case -32602:
        return "Invalid params";
      default:
        return "Internal error";
    }
  }

  private JsonRpcError getResolverForException(Throwable thrownException, Method method) {
    JsonRpcErrors errors = ReflectionUtil.getAnnotation(method, JsonRpcErrors.class);
    if (hasAnnotations(errors)) {
      for (JsonRpcError errorDefined : errors.value()) {
        if (isExceptionInstanceOfError(thrownException, errorDefined)) {
          return errorDefined;
        }
      }
    }
    return null;
  }

  private boolean hasErrorMessage(JsonRpcError em) {
    // noinspection ConstantConditions
    return em.message() != null && !em.message().trim().isEmpty();
  }

  private boolean hasErrorData(JsonRpcError em) {
    // noinspection ConstantConditions
    return em.data() != null && !em.data().trim().isEmpty();
  }

  private boolean hasAnnotations(JsonRpcErrors errors) {
    return errors != null;
  }

  private boolean isExceptionInstanceOfError(Throwable target, JsonRpcError em) {
    return em.exception().isInstance(target);
  }
}
