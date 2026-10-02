package com.example.shortener.links;

import com.example.shortener.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DestinationValidatorTest {
    final DestinationValidator validator = new DestinationValidator("http://localhost:8080");

    @ParameterizedTest
    @ValueSource(strings = {
            "https://xn--bcher-kva.example/a", "http://printer:8081/a", "https://service.private-suffix/a",
            "https://example.org./a//b?q=2&q=1#part", "http://[::1]:8081/a%2Fb?q=%23#frag",
            "http://127.0.0.1:9090/?a=2&a=1", "https://example.org:65535/a",
            "HTTP://EXAMPLE.ORG/a", "http://localhost:8081/r/test", "http://localhost:8080/docs"
    })
    void acceptedLocalPrivateAndEncodedUrls(String value) {
        assertDoesNotThrow(() -> validator.validate(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.org:0", "https://example.org:", "https://[::1]:65536",
            "https://u:p@example.org/a", "http://123", "http://999.1.1.1", "http://1.2.3",
            "https://-bad.example/a", "https://bad_.example/a", "https://example.org/a%xy",
            "https://example.org/a\n", "https://éxample.org/a", "ftp://example.org/a"
    })
    void malformedUrlsAndApplicationPolicyReturnExistingCode(String value) {
        assertEquals("INVALID_DESTINATION", assertThrows(ApiException.class, () -> validator.validate(value)).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080/r/test", "http://LOCALHOST:8080/%72/test"})
    void decodedSelfRedirectsAreRejected(String value) {
        assertEquals("SELF_REDIRECT", assertThrows(ApiException.class, () -> validator.validate(value)).code());
    }

    @Test
    void implicitAndExplicitDefaultPortsShareTheSameOrigin() {
        var https = new DestinationValidator("https://example.org");
        assertEquals("SELF_REDIRECT", assertThrows(ApiException.class,
                () -> https.validate("https://example.org:443/r/test")).code());
        assertDoesNotThrow(() -> https.validate("https://example.org:444/r/test"));
    }

    @Test
    void configuredBaseMustBeAnOrigin() {
        for (String base : new String[]{"https://example.org/app", "https://example.org?x=1",
                "https://example.org#part", "https://u:p@example.org", "ftp://example.org", "https://example.org:0"}) {
            assertThrows(IllegalArgumentException.class, () -> new DestinationValidator(base));
        }
    }
}
