package com.jvuln.collector;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicUrlPolicyTest {

    private final PublicUrlPolicy policy = new PublicUrlPolicy();

    @Test
    void acceptsOnlyPublicHttpAndHttpsTargets() {
        assertEquals(URI.create("https://93.184.216.34/report"),
                policy.requirePublic("https://93.184.216.34/report"));
        assertThrows(SecurityException.class,
                () -> policy.requirePublic("file:///etc/passwd"));
        assertThrows(SecurityException.class,
                () -> policy.requirePublic("http://user:password@93.184.216.34/report"));
    }

    @Test
    void rejectsLoopbackPrivateLinkLocalAndIpv6UniqueLocalAddresses() {
        assertThrows(SecurityException.class,
                () -> policy.requirePublic("http://127.0.0.1/private"));
        assertThrows(SecurityException.class,
                () -> policy.requirePublic("http://10.0.0.1/private"));
        assertThrows(SecurityException.class,
                () -> policy.requirePublic("http://169.254.169.254/metadata"));
        assertThrows(SecurityException.class,
                () -> policy.requirePublic("http://100.64.0.1/private"));
        assertThrows(SecurityException.class,
                () -> policy.requirePublic("http://192.0.2.1/private"));
        assertThrows(SecurityException.class,
                () -> policy.requirePublic("http://[::1]/private"));
        assertThrows(SecurityException.class,
                () -> policy.requirePublic("http://[fd00::1]/private"));
    }
}
