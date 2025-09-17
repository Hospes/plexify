package io.github.hospes.plexify.web.api

interface MediaServiceApi {
    /**
     * Checks if an API key or other required authentication token is available for the service.
     *
     * @return true if the API key or token is non-blank, indicating availability; false otherwise.
     */
    fun isApiKeyAvailable(): Boolean
}