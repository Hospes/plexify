package io.github.hospes.plexify.data

import io.ktor.client.engine.*
import io.ktor.client.engine.curl.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getenv

actual fun createHttpClientEngine(): HttpClientEngine = Curl.create {
    caInfo = systemCaBundle
}

/**
 * Ktor's libcurl is statically linked with OpenSSL and only knows Debian's CA bundle path, so on
 * Fedora, RHEL or openSUSE every HTTPS request would fail verification. Use the bundle this distro
 * actually has; `SSL_CERT_FILE` (or curl's `CURL_CA_BUNDLE`) overrides it. Null keeps curl's default.
 */
@OptIn(ExperimentalForeignApi::class)
private val systemCaBundle: String? by lazy {
    listOf("SSL_CERT_FILE", "CURL_CA_BUNDLE").firstNotNullOfOrNull { getenv(it)?.toKString()?.takeIf(String::isNotBlank) }
        ?: CA_BUNDLE_PATHS.firstOrNull { SystemFileSystem.exists(Path(it)) }
}

// The same list Go's crypto/x509 probes.
private val CA_BUNDLE_PATHS = listOf(
    "/etc/ssl/certs/ca-certificates.crt",                // Debian, Ubuntu, Arch, Gentoo
    "/etc/pki/tls/certs/ca-bundle.crt",                  // Fedora, RHEL 6
    "/etc/ssl/ca-bundle.pem",                            // openSUSE
    "/etc/pki/tls/cacert.pem",                           // OpenELEC
    "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem", // CentOS, RHEL 7
    "/etc/ssl/cert.pem",                                 // Alpine
)
