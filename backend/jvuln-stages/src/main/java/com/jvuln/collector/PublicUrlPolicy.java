package com.jvuln.collector;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

public class PublicUrlPolicy {

    interface HostResolver {
        InetAddress[] resolve(String host) throws Exception;
    }

    private final HostResolver resolver;

    public PublicUrlPolicy() {
        this(InetAddress::getAllByName);
    }

    PublicUrlPolicy(HostResolver resolver) {
        this.resolver = resolver;
    }

    public URI requirePublic(String value) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Evidence URL is invalid", e);
        }
        String scheme = uri.getScheme() == null
                ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw new SecurityException("Evidence URL must use HTTP or HTTPS");
        }
        if (uri.getUserInfo() != null) {
            throw new SecurityException("Evidence URL must not contain credentials");
        }
        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new SecurityException("Evidence URL host is required");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        validateAddresses(host);
        return uri;
    }

    private void validateAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (Exception e) {
            throw new SecurityException("Evidence host could not be resolved", e);
        }
        if (addresses == null || addresses.length == 0) {
            throw new SecurityException("Evidence host resolved to no addresses");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new SecurityException("Evidence host resolved to a non-public address");
            }
        }
    }

    private boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        return (bytes[0] & 0xfe) != 0xfc && !isDocumentationIpv6(bytes);
    }

    private boolean isPublicIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
        if (first == 100 && second >= 64 && second <= 127) return false;
        if (first == 169 && second == 254) return false;
        if (first == 172 && second >= 16 && second <= 31) return false;
        if (first == 192 && (second == 0 || second == 168)) return false;
        if (first == 198 && (second == 18 || second == 19)) return false;
        if (first == 192 && second == 0 && (bytes[2] & 0xff) == 2) return false;
        if (first == 198 && second == 51 && (bytes[2] & 0xff) == 100) return false;
        return !(first == 203 && second == 0 && (bytes[2] & 0xff) == 113);
    }

    private boolean isDocumentationIpv6(byte[] bytes) {
        return (bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01
                && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8;
    }
}
