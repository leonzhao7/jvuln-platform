package com.jvuln.tracer;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;

/**
 * ByteBuddy-based javaagent entry point.
 * <p>
 * Usage: {@code -javaagent:jvuln-tracer.jar=includes=com.example;com.other,out=/tmp/trace.jsonl}
 * <p>
 * The agent installs ByteBuddy advice on every method of classes whose
 * fully-qualified name starts with one of the configured package prefixes.
 * Each method entry/exit is recorded via {@link TracerInterceptor} into a
 * JSONL file through {@link TracerEventWriter}.
 * <p>
 * Constraints:
 * <ul>
 *   <li>Java 8 compatible (no Java 9+ APIs)</li>
 *   <li>Never alters the demo application's control flow</li>
 *   <li>Exceptions are always rethrown unchanged by ByteBuddy</li>
 * </ul>
 */
public final class TracerAgent {

    private static final long DEFAULT_FILE_CAP = 5 * 1024 * 1024L;

    /**
     * Standard javaagent entry point invoked by the JVM before {@code main}.
     *
     * @param agentArgs comma-separated config: {@code includes=pkg1;pkg2,out=/path/to/trace.jsonl}
     * @param inst      JVM instrumentation handle
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            TracerConfig cfg = TracerConfig.parse(agentArgs);
            if (cfg.outputPath == null || cfg.includes.isEmpty()) {
                return;
            }

            TracerEventWriter writer = new TracerEventWriter(cfg.outputPath, DEFAULT_FILE_CAP);
            TracerInterceptor.setWriter(writer);

            AgentBuilder.Transformer transformer = new AgentBuilder.Transformer() {
                @Override
                public DynamicType.Builder<?> transform(
                        DynamicType.Builder<?> builder,
                        TypeDescription typeDescription,
                        ClassLoader classLoader,
                        JavaModule module,
                        ProtectionDomain protectionDomain) {
                    return builder
                            .visit(Advice.to(MethodEntryAdvice.class).on(ElementMatchers.isMethod()))
                            .visit(Advice.to(MethodExitAdvice.class).on(ElementMatchers.isMethod()));
                }
            };

            AgentBuilder agentBuilder = new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.DISABLED)
                    .ignore(ElementMatchers.none())
                    .type(buildPackageMatcher(cfg.includes))
                    .transform(transformer);

            agentBuilder.installOn(inst);
        } catch (Exception e) {
            System.err.println("TracerAgent failed to initialize: " + e.getMessage());
        }
    }

    /**
     * Builds a type matcher that matches any class whose name starts with
     * one of the given package prefixes (OR-combined).
     */
    private static ElementMatcher.Junction<TypeDescription> buildPackageMatcher(Set<String> packages) {
        ElementMatcher.Junction<TypeDescription> matcher = null;
        for (String pkg : packages) {
            ElementMatcher.Junction<TypeDescription> pkgMatcher =
                    ElementMatchers.nameStartsWith(pkg);
            matcher = (matcher == null) ? pkgMatcher : matcher.or(pkgMatcher);
        }
        return matcher == null ? ElementMatchers.<TypeDescription>none() : matcher;
    }

    /**
     * ByteBuddy advice class for method entry.
     * Inlined into the target method -- must only call static methods.
     */
    public static class MethodEntryAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin("#t") String className,
                                 @Advice.Origin("#m") String methodName,
                                 @Advice.AllArguments Object[] args) {
            TracerInterceptor.onEnter(className, methodName, args);
        }
    }

    /**
     * ByteBuddy advice class for method exit (normal return or exception).
     * Inlined into the target method -- must only call static methods.
     * {@code onThrowable = Throwable.class} ensures the advice runs even
     * when the method throws; ByteBuddy re-throws the exception automatically.
     */
    public static class MethodExitAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Origin("#t") String className,
                                @Advice.Origin("#m") String methodName,
                                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object ret,
                                @Advice.Thrown Throwable t) {
            if (t != null) {
                TracerInterceptor.onThrow(className, methodName, t);
            } else {
                TracerInterceptor.onExit(className, methodName, ret);
            }
        }
    }
}
