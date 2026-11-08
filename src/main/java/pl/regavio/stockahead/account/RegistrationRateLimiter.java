package pl.regavio.stockahead.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding-window cap on successful registrations per client IP.
 * A caller reserves a slot with {@link #tryReserve(String)} before it creates
 * anything and {@link #release(Reservation) releases} it when the
 * registration does not commit; a slot that is never released counts as a
 * successful registration until it ages out of the window. At most
 * {@code app.registration.max-per-ip} (default 5) reservations per IP may
 * exist within {@code app.registration.rate-window} (default 1 h). Every call
 * prunes reservations older than the window and drops IPs left with none, so
 * memory stays bounded by the IPs active within the window. All state is lost
 * on restart. All methods synchronize on this instance, so concurrent requests
 * from one IP can never exceed the cap.
 */
@Component
public class RegistrationRateLimiter {

	/** Opaque handle of one reserved slot; compared by identity. */
	public static final class Reservation {

		private final String ip;

		private final Instant reservedAt;

		private Reservation(String ip, Instant reservedAt) {
			this.ip = ip;
			this.reservedAt = reservedAt;
		}

	}

	private final int maxPerIp;

	private final Duration window;

	private final Clock clock;

	private final Map<String, List<Reservation>> reservationsByIp = new HashMap<>();

	@Autowired
	public RegistrationRateLimiter(@Value("${app.registration.max-per-ip:5}") int maxPerIp,
			@Value("${app.registration.rate-window:1h}") Duration window) {
		this(maxPerIp, window, Clock.systemUTC());
	}

	RegistrationRateLimiter(int maxPerIp, Duration window, Clock clock) {
		this.maxPerIp = maxPerIp;
		this.window = window;
		this.clock = clock;
	}

	/**
	 * Reserves a slot for {@code ip}, or returns empty when the IP already holds
	 * the maximum number of reservations within the window.
	 */
	public synchronized Optional<Reservation> tryReserve(String ip) {
		Instant now = clock.instant();
		prune(now);
		List<Reservation> reservations = reservationsByIp.get(ip);
		if (reservations != null && reservations.size() >= maxPerIp) {
			return Optional.empty();
		}
		Reservation reservation = new Reservation(ip, now);
		reservationsByIp.computeIfAbsent(ip, key -> new ArrayList<>()).add(reservation);
		return Optional.of(reservation);
	}

	/** Frees the slot held by {@code reservation}; a no-op if it already aged out. */
	public synchronized void release(Reservation reservation) {
		prune(clock.instant());
		List<Reservation> reservations = reservationsByIp.get(reservation.ip);
		if (reservations == null) {
			return;
		}
		reservations.removeIf(candidate -> candidate == reservation);
		if (reservations.isEmpty()) {
			reservationsByIp.remove(reservation.ip);
		}
	}

	/** Number of IPs currently tracked; for tests. */
	synchronized int trackedIpCount() {
		return reservationsByIp.size();
	}

	private void prune(Instant now) {
		Instant cutoff = now.minus(window);
		Iterator<List<Reservation>> lists = reservationsByIp.values().iterator();
		while (lists.hasNext()) {
			List<Reservation> reservations = lists.next();
			reservations.removeIf(reservation -> !reservation.reservedAt.isAfter(cutoff));
			if (reservations.isEmpty()) {
				lists.remove();
			}
		}
	}

}
