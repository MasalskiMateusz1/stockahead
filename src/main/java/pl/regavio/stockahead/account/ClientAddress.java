package pl.regavio.stockahead.account;

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

	private ClientAddress() {
	}

	static String of(HttpServletRequest request) {
		String header = request.getHeader(CF_CONNECTING_IP_HEADER);
		if (header != null && !header.isBlank()) {
			return header.strip();
		}
		return request.getRemoteAddr();
	}

}
