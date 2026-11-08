package pl.regavio.stockahead.account;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Decides which client address a request comes from. The {@code CF-Connecting-IP}
 * header set by Cloudflare wins when present and non-blank; otherwise the
 * socket's {@link HttpServletRequest#getRemoteAddr() remote address} is used.
 * Trusting the header is safe only because production is reachable solely
 * through Cloudflare Tunnel, which overwrites it: if the app is ever exposed
 * on a route that bypasses Cloudflare, a client could spoof the header and
 * this must change.
 */
final class ClientAddress {

	static final String CF_CONNECTING_IP_HEADER = "CF-Connecting-IP";

	private static final int IPV6_PREFIX_BYTES = 8;

	private ClientAddress() {
	}

	static String of(HttpServletRequest request) {
		String header = request.getHeader(CF_CONNECTING_IP_HEADER);
		if (header != null && !header.isBlank()) {
			return header.strip();
		}
		return request.getRemoteAddr();
	}

	/**
	 * The key a per-client limit counts under. One subscriber usually holds a
	 * whole IPv6 /64, so an IPv6 address counts under its /64 prefix; an IPv4
	 * address (including an IPv4-mapped IPv6 one) counts as itself. A value that
	 * is not an IP literal is returned unchanged. Only values containing
	 * {@code ':'} are parsed, and {@link InetAddress#getByName} treats those as
	 * IPv6 literals, so this never performs a DNS lookup.
	 */
	static String rateLimitKey(String address) {
		if (address == null || address.indexOf(':') < 0) {
			return address;
		}
		InetAddress parsed;
		try {
			parsed = InetAddress.getByName(address);
		}
		catch (UnknownHostException ex) {
			return address;
		}
		if (!(parsed instanceof Inet6Address)) {
			return parsed.getHostAddress();
		}
		byte[] prefix = Arrays.copyOf(Arrays.copyOf(parsed.getAddress(), IPV6_PREFIX_BYTES), 16);
		try {
			return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
		}
		catch (UnknownHostException ex) {
			throw new IllegalStateException("16-byte address rejected", ex);
		}
	}

}
