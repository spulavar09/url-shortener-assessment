package com.example.shortener.links;

import com.example.shortener.common.ApiException;
import com.example.shortener.common.ErrorCodes;
import org.apache.commons.validator.routines.DomainValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.apache.commons.validator.routines.UrlValidator;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

final class DestinationValidator {
    private final URI publicBase;

    DestinationValidator(String publicBaseUrl) {
        try {
            publicBase = validatedUri(publicBaseUrl);
            if (publicBase.getRawQuery() != null || publicBase.getRawFragment() != null
                    || !(publicBase.getRawPath().isEmpty() || publicBase.getRawPath().equals("/"))) {
                throw new IllegalArgumentException("app.links.public-base-url must be an HTTP(S) origin");
            }
        } catch (ApiException failure) {
            throw new IllegalArgumentException("app.links.public-base-url must be an HTTP(S) origin", failure);
        }
    }

    void validate(String value) {
        URI uri = validatedUri(value);
        if (uri.getScheme().equalsIgnoreCase(publicBase.getScheme())
                && uri.getHost().equalsIgnoreCase(publicBase.getHost())
                && effectivePort(uri) == effectivePort(publicBase)
                && uri.getPath().startsWith("/r/")) {
            throw ApiException.badRequest(ErrorCodes.SELF_REDIRECT,
                    "Destination cannot point to this service's redirect route");
        }
    }

    private static URI validatedUri(String value) {
        if (value == null || value.length() > 4096 || !value.matches("[!-~]+")) throw invalid();
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (host == null) throw invalid();
            if (!urlValidatorFor(host).isValid(value)) throw invalid();
            // Application policy is stricter than the library on credentials and empty/zero ports.
            if (uri.getRawUserInfo() != null || uri.getPort() == 0 || uri.getRawAuthority().endsWith(":")) throw invalid();
            if (host.matches("[0-9.]+") && !InetAddressValidator.getInstance().isValidInet4Address(host)) throw invalid();
            return uri;
        } catch (URISyntaxException failure) {
            throw invalid();
        }
    }

    private static UrlValidator urlValidatorFor(String host) {
        // Validate hostname syntax without requiring a registered public suffix (private/test hosts are allowed).
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String suffix = name.substring(name.lastIndexOf('.') + 1);
        var domains = DomainValidator.getInstance(true, List.of(
                new DomainValidator.Item(DomainValidator.ArrayType.GENERIC_PLUS, suffix)));
        return new UrlValidator(new String[]{"http", "https"}, null,
                UrlValidator.ALLOW_LOCAL_URLS | UrlValidator.ALLOW_2_SLASHES, domains);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

    private static ApiException invalid() {
        return ApiException.badRequest(ErrorCodes.INVALID_DESTINATION,
                "destinationUrl must be an absolute ASCII HTTP(S) URL without credentials, whitespace, or an invalid host/port (maximum 4096 characters)");
    }
}
