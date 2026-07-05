package com.qacopilot.embedding;

/**
 * Thrown at startup when the active provider's API key is absent or unresolved.
 * Carries the environment variable name so {@link MissingApiKeyFailureAnalyzer} can render a
 * provider-appropriate, clean startup error.
 */
public class MissingApiKeyException extends RuntimeException {

    private final String envVarName;

    public MissingApiKeyException(String envVarName, String message) {
        super(message);
        this.envVarName = envVarName;
    }

    public String getEnvVarName() {
        return envVarName;
    }
}
