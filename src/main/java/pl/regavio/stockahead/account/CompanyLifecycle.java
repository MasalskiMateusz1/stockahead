package pl.regavio.stockahead.account;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The single place that changes a company's status: approve and reject a
 * PENDING company, block an ACTIVE one and unblock a BLOCKED one. Every
 * transition locks the company row
 * ({@link CompanyRepository#findByIdForUpdate(Long)}) before re-reading the
 * status and acts only if the company is in the state the transition starts
 * from, so two concurrent operator clicks serialise on the lock and the loser
 * becomes a no-op ({@link Outcome#STATUS_CHANGED}). Only reject touches the
 * company's accounts; block and unblock change the status alone.
 *
 * <p>This is an operator-only, intentionally cross-company write: callers
 * must be operator routes ({@link OperatorCompanyController}), never a
 * company-scoped route.
 */
@Service
public class CompanyLifecycle {

	/** What a transition did. */
	public enum Outcome {
		/** The company was in the transition's starting state and the transition was applied. */
		DONE,
		/** The company was no longer in the transition's starting state; nothing changed. */
		STATUS_CHANGED
	}

	/**
	 * What a transition did, with the company's name read under the row lock
	 * (for the operator's feedback message).
	 */
	public record Result(Outcome outcome, String companyName) {
	}

	/** No company has the requested id; answered with 404. */
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public static class CompanyNotFoundException extends RuntimeException {

		public CompanyNotFoundException(Long companyId) {
			super("No company with id " + companyId);
		}

	}

	private final CompanyRepository companyRepository;

	private final AccountRepository accountRepository;

	public CompanyLifecycle(CompanyRepository companyRepository, AccountRepository accountRepository) {
		this.companyRepository = companyRepository;
		this.accountRepository = accountRepository;
	}

	/**
	 * PENDING → ACTIVE. The company's existing sessions reach the warehouse on
	 * their next request ({@code AccountActivityFilter} re-reads the status).
	 *
	 * @throws CompanyNotFoundException if no company has {@code companyId}
	 */
	@Transactional
	public Result approve(Long companyId) {
		Company company = lockCompany(companyId);
		if (company.getStatus() != CompanyStatus.PENDING) {
			return new Result(Outcome.STATUS_CHANGED, company.getName());
		}
		company.setStatus(CompanyStatus.ACTIVE);
		return new Result(Outcome.DONE, company.getName());
	}

	/**
	 * PENDING → REJECTED, and in the same transaction every account of the
	 * company is deactivated and its email released, so its users fail login
	 * generically and their emails can register again.
	 *
	 * @throws CompanyNotFoundException if no company has {@code companyId}
	 */
	@Transactional
	public Result reject(Long companyId) {
		Company company = lockCompany(companyId);
		if (company.getStatus() != CompanyStatus.PENDING) {
			return new Result(Outcome.STATUS_CHANGED, company.getName());
		}
		company.setStatus(CompanyStatus.REJECTED);
		accountRepository.releaseAllByCompanyId(companyId, Instant.now());
		return new Result(Outcome.DONE, company.getName());
	}

	/**
	 * ACTIVE → BLOCKED. Accounts are untouched: the company's users are refused
	 * at login, and its open sessions are gated to {@code /awaiting-approval}
	 * on their next request ({@code AccountActivityFilter} re-reads the
	 * status). Data is kept.
	 *
	 * @throws CompanyNotFoundException if no company has {@code companyId}
	 */
	@Transactional
	public Result block(Long companyId) {
		Company company = lockCompany(companyId);
		if (company.getStatus() != CompanyStatus.ACTIVE) {
			return new Result(Outcome.STATUS_CHANGED, company.getName());
		}
		company.setStatus(CompanyStatus.BLOCKED);
		return new Result(Outcome.DONE, company.getName());
	}

	/**
	 * BLOCKED → ACTIVE. Accounts are untouched, so every account that was
	 * active before the block logs in again, open sessions reach the warehouse
	 * on their next request, and deactivated accounts stay deactivated.
	 *
	 * @throws CompanyNotFoundException if no company has {@code companyId}
	 */
	@Transactional
	public Result unblock(Long companyId) {
		Company company = lockCompany(companyId);
		if (company.getStatus() != CompanyStatus.BLOCKED) {
			return new Result(Outcome.STATUS_CHANGED, company.getName());
		}
		company.setStatus(CompanyStatus.ACTIVE);
		return new Result(Outcome.DONE, company.getName());
	}

	/**
	 * Locks company {@code companyId} and returns it, so the caller re-reads
	 * its status under the lock. Must run inside a transaction.
	 */
	private Company lockCompany(Long companyId) {
		return companyRepository.findByIdForUpdate(companyId)
			.orElseThrow(() -> new CompanyNotFoundException(companyId));
	}

}
