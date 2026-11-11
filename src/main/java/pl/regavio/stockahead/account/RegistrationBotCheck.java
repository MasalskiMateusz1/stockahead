package pl.regavio.stockahead.account;

import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Self-hosted human checks for the public registration form: a honeypot field
 * that humans never see and a minimum fill time measured from the moment the
 * form was last rendered into the session. A submission passes only when the
 * honeypot is empty, the form was stamped into the session, and at least
 * {@code app.registration.min-fill-time} (default 3 s) has passed since then.
 */
@Component
public class RegistrationBotCheck {

	/** Session attribute holding the {@link Instant} the form was last rendered. */
	public static final String FORM_ISSUED_AT_SESSION_ATTRIBUTE = RegistrationBotCheck.class.getName()
			+ ".FORM_ISSUED_AT";

	/** Request parameter of the off-screen honeypot input; must stay empty. */
	public static final String HONEYPOT_PARAMETER = "hp_7c1f";

	private final Duration minFillTime;

	public RegistrationBotCheck(@Value("${app.registration.min-fill-time:3s}") Duration minFillTime) {
		this.minFillTime = minFillTime;
	}

	public void stamp(HttpSession session) {
		session.setAttribute(FORM_ISSUED_AT_SESSION_ATTRIBUTE, Instant.now());
	}

	/** Removes the stamp, so the next submission needs a freshly rendered form. */
	public void clear(HttpSession session) {
		session.removeAttribute(FORM_ISSUED_AT_SESSION_ATTRIBUTE);
	}

	public boolean passes(HttpSession session, String honeypotValue) {
		if (honeypotValue != null && !honeypotValue.isEmpty()) {
			return false;
		}
		if (!(session.getAttribute(FORM_ISSUED_AT_SESSION_ATTRIBUTE) instanceof Instant issuedAt)) {
			return false;
		}
		return !Instant.now().isBefore(issuedAt.plus(minFillTime));
	}

}
