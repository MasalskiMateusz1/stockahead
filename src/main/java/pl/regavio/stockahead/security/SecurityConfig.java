package pl.regavio.stockahead.security;

import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import jakarta.servlet.DispatcherType;
import pl.regavio.stockahead.account.AccountRepository;

/**
 * Denies by default: outside the public pages ({@code /login},
 * {@code /setup/**}, health, static resources), {@code /} and the error page
 * (including the error dispatch) need any authenticated account,
 * {@code /operator/**} needs the operator, and every other route needs a
 * manager or technician, so a warehouse route that forgets its
 * {@code @PreAuthorize} still refuses the operator.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, AccountRepository accountRepository) throws Exception {
		http.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/login", "/setup/**", "/actuator/health/**").permitAll()
				.requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
				.dispatcherTypeMatchers(DispatcherType.ERROR).authenticated()
				.requestMatchers("/", "/error").authenticated()
				.requestMatchers("/operator/**").hasRole("OPERATOR")
				.anyRequest().hasAnyRole("MANAGER", "TECHNICIAN"))
			.formLogin(formLogin -> formLogin
				.loginPage("/login")
				.defaultSuccessUrl("/", true)
				.permitAll())
			.logout(logout -> logout
				.logoutSuccessUrl("/login?logout"));
		http.addFilterBefore(new AccountActivityFilter(accountRepository), AuthorizationFilter.class);

		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthenticationProvider authenticationProvider(AccountUserDetailsService accountUserDetailsService,
			PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider(accountUserDetailsService);
		authenticationProvider.setPasswordEncoder(passwordEncoder);
		return authenticationProvider;
	}

}
