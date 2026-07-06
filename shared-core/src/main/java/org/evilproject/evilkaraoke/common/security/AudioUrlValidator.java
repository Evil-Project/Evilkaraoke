package org.evilproject.evilkaraoke.common.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

public final class AudioUrlValidator {
    public static final int MAX_URL_LENGTH = 2_048;

    private AudioUrlValidator() {
    }

    public static boolean hasHttpScheme(String value) {
        if (value == null) {
            return false;
        }
        int separator = value.indexOf(':');
        if (separator <= 0) {
            return false;
        }
        String scheme = value.substring(0, separator).toLowerCase(Locale.ROOT);
        return "http".equals(scheme) || "https".equals(scheme);
    }

    public static URI validatePublicHttpUrl(String rawUrl) {
        return validatePublicHttpUrl(rawUrl, InetAddress::getAllByName);
    }

    public static URI validatePublicHttpUrl(String rawUrl, AddressResolver resolver) {
        if (rawUrl == null) {
            throw new IllegalArgumentException("Audio URL is required");
        }
        if (!rawUrl.equals(rawUrl.strip())) {
            throw new IllegalArgumentException("Audio URL must not include leading or trailing whitespace");
        }
        if (rawUrl.isBlank()) {
            throw new IllegalArgumentException("Audio URL is required");
        }
        if (rawUrl.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("Audio URL is too long");
        }
        if (containsUnsafeCharacter(rawUrl)) {
            throw new IllegalArgumentException("Audio URL must not include whitespace or control characters");
        }
        try {
            return validatePublicHttpUrl(URI.create(rawUrl), resolver);
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("Audio URL")) {
                throw ex;
            }
            throw new IllegalArgumentException("Audio URL is not valid");
        }
    }

    public static URI validatePublicHttpUrl(URI uri) {
        return validatePublicHttpUrl(uri, InetAddress::getAllByName);
    }

    public static URI validatePublicHttpUrl(URI uri, AddressResolver resolver) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(resolver, "resolver");

        String ascii = uri.toASCIIString();
        if (ascii.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("Audio URL is too long");
        }
        if (containsUnsafeCharacter(ascii)) {
            throw new IllegalArgumentException("Audio URL must not include whitespace or control characters");
        }
        try {
            uri = uri.parseServerAuthority();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Audio URL authority is not valid");
        }

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Audio URL must use http or https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Audio URL must not include user info");
        }
        if (uri.getPort() > 65_535) {
            throw new IllegalArgumentException("Audio URL authority is not valid");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Audio URL must not include a fragment");
        }

        String host = normalizedHost(uri);
        if (host.isBlank()) {
            throw new IllegalArgumentException("Audio URL must include a host");
        }
        if (host.indexOf('%') >= 0) {
            throw new IllegalArgumentException("Audio URL host must not include an IPv6 zone id");
        }
        if (isLocalHostname(host)) {
            throw new IllegalArgumentException("Audio URL host is not public");
        }

        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException | SecurityException ex) {
            throw new IllegalArgumentException("Audio URL host could not be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("Audio URL host could not be resolved");
        }
        boolean hostIsIpLiteral = isIpLiteralHost(host);
        for (InetAddress address : addresses) {
            if (address == null) {
                throw new IllegalArgumentException("Audio URL host is not public");
            }
            if (isProxyFakeAddress(address) && !hostIsIpLiteral) {
                continue;
            }
            if (isUnsafeAddress(address)) {
                throw new IllegalArgumentException("Audio URL host is not public");
            }
        }

        return uri.normalize();
    }

    private static String normalizedHost(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return "";
        }
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    private static boolean isLocalHostname(String host) {
        return "localhost".equals(host)
                || "localhost.localdomain".equals(host)
                || "ip6-localhost".equals(host)
                || "ip6-loopback".equals(host)
                || host.endsWith(".localhost")
                || host.endsWith(".local");
    }

    private static boolean isIpLiteralHost(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        boolean hasDot = false;
        for (int i = 0; i < host.length(); i++) {
            char ch = host.charAt(i);
            if (ch == '.') {
                hasDot = true;
                continue;
            }
            if (!Character.isDigit(ch)) {
                return false;
            }
        }
        return hasDot;
    }

    private static boolean containsUnsafeCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isUnsafeIpv4(bytes);
        }
        if (bytes.length == 16) {
            return isUnsafeIpv6(bytes);
        }
        return true;
    }

    private static boolean isProxyFakeAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isProxyFakeIpv4(bytes);
        }
        byte[] embeddedIpv4 = embeddedIpv4(bytes);
        return embeddedIpv4 != null && isProxyFakeIpv4(embeddedIpv4);
    }

    private static boolean isProxyFakeIpv4(byte[] bytes) {
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 198 && (second == 18 || second == 19);
    }

    private static boolean isUnsafeIpv4(byte[] bytes) {
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        int third = bytes[2] & 0xFF;

        return first == 0
                || first == 10
                || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 192 && second == 0 && third == 0)
                || (first == 192 && second == 0 && third == 2)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || first >= 224;
    }

    private static boolean isUnsafeIpv6(byte[] bytes) {
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;

        if ((first & 0xFE) == 0xFC) {
            return true;
        }
        if (first == 0x20 && second == 0x01 && (bytes[2] & 0xFF) == 0x0D && (bytes[3] & 0xFF) == 0xB8) {
            return true;
        }
        byte[] embeddedIpv4 = embeddedIpv4(bytes);
        if (embeddedIpv4 != null) {
            return isUnsafeIpv4(embeddedIpv4);
        }
        return false;
    }

    private static byte[] embeddedIpv4(byte[] bytes) {
        if (bytes.length != 16) {
            return null;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                break;
            }
            if (i == 9 && bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF) {
                return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
            }
        }
        for (int i = 0; i < 8; i++) {
            if (bytes[i] != 0) {
                return null;
            }
        }
        if (bytes[8] == (byte) 0xFF && bytes[9] == (byte) 0xFF && bytes[10] == 0 && bytes[11] == 0) {
            return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        return null;
    }

    @FunctionalInterface
    public interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
