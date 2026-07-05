package com.qacopilot.embedding;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Renders {@link MissingApiKeyException} as a clean "APPLICATION FAILED TO START" block with a
 * concrete, provider-appropriate action, instead of a raw bean-instantiation stack trace.
 * Registered via {@code META-INF/spring.factories}. Spring Boot walks the cause chain, so this
 * fires even though the exception originates inside a bean constructor.
 */
public class MissingApiKeyFailureAnalyzer extends AbstractFailureAnalyzer<MissingApiKeyException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingApiKeyException cause) {
        String env = cause.getEnvVarName();
        String description = cause.getMessage()
                + " The app answers only via a real provider, so it will not start without a key.";
        String action = ("""
                Provide the key as an EXPORTED environment variable so the forked JVM inherits it \
                (a shell variable that only `echo` can see is NOT enough — confirm with \
                `python3 -c 'import os;print(os.environ.get("%s"))'`):

                  export %s=your-real-key
                  ./mvnw spring-boot:run

                or pass it inline for a single run:

                  %s=your-real-key ./mvnw spring-boot:run

                Spring Boot does not read .env files.""")
                .formatted(env, env, env);
        return new FailureAnalysis(description, action, cause);
    }
}
