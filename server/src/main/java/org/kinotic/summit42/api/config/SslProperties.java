package org.kinotic.summit42.api.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * TLS for Summit 42's HTTP port from PEM files, bound under {@code summit42.ssl.*}.
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
public class SslProperties {

    /**
     * Serve the HTTP port over TLS with the certificate and key below.
     */
    private boolean enabled = false;

    /**
     * Path to the PEM-encoded certificate chain.
     */
    private String certPath;

    /**
     * Path to the PEM-encoded private key.
     */
    private String keyPath;
}
