package pl.regavio.stockahead.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientAddressTests {

	@Test
	void ipv4AddressIsItsOwnKey() {
		assertThat(ClientAddress.rateLimitKey("203.0.113.7")).isEqualTo("203.0.113.7");
	}

	@Test
	void ipv6AddressesInOneSlash64ShareAKey() {
		assertThat(ClientAddress.rateLimitKey("2001:db8:0:1::1"))
			.isEqualTo(ClientAddress.rateLimitKey("2001:db8:0:1:ffff:abcd:1234:5678"))
			.isEqualTo("2001:db8:0:1:0:0:0:0/64");
	}

	@Test
	void ipv6AddressesInDifferentSlash64sHaveDifferentKeys() {
		assertThat(ClientAddress.rateLimitKey("2001:db8:0:1::1"))
			.isNotEqualTo(ClientAddress.rateLimitKey("2001:db8:0:2::1"));
	}

	@Test
	void ipv4MappedIpv6AddressCountsAsIpv4() {
		assertThat(ClientAddress.rateLimitKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
	}

	@Test
	void valueThatIsNotAnIpLiteralIsReturnedUnchanged() {
		assertThat(ClientAddress.rateLimitKey("not:an:address")).isEqualTo("not:an:address");
		assertThat(ClientAddress.rateLimitKey("example.com")).isEqualTo("example.com");
	}

}
