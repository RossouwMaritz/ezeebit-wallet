package com.ezeebit.wallet.conversion;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.ezeebit.wallet.Currency;
import com.ezeebit.wallet.ErrorCode;
import com.ezeebit.wallet.WalletException;
import com.ezeebit.wallet.conversion.ExchangeRateClient.Rate;
import com.ezeebit.wallet.ledger.AccountType;
import com.ezeebit.wallet.ledger.InsufficientFundsException;
import com.ezeebit.wallet.ledger.LedgerService;
import com.ezeebit.wallet.ledger.Posting;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Converts between a merchant's currencies against the house FX stock, in two steps. A quote fixes the
 * price for 30 seconds; executing it books one entry that moves both legs. The feed is not trusted:
 * a stale, outlying or missing rate gives no quote rather than a wrong one.
 */
@Service
public class ConversionService {

	private static final Logger log = LoggerFactory.getLogger(ConversionService.class);

	static final Duration QUOTE_TTL = Duration.ofSeconds(30);

	static final Duration MAX_RATE_AGE = Duration.ofSeconds(10);

	static final BigDecimal MAX_RATE_JUMP = new BigDecimal("0.02");

	public record QuoteRequest(@NotNull Currency sellCurrency, @NotNull Currency buyCurrency,
			@NotNull @Positive BigDecimal sellAmount) {
	}

	public record Quote(String id, Currency sellCurrency, BigDecimal sellAmount, Currency buyCurrency,
			BigDecimal buyAmount, BigDecimal rate, Instant expiresAt) {
	}

	public record ConversionRequest(@NotBlank @Size(max = 36) String quoteId) {
	}

	public record Conversion(String quoteId, long entryId, Currency sellCurrency, BigDecimal sellAmount,
			Currency buyCurrency, BigDecimal buyAmount, BigDecimal rate) {
	}

	private record QuoteRow(Quote quote, Long entryId, boolean expired) {
	}

	private final LedgerService ledger;

	private final ExchangeRateClient feed;

	private final JdbcClient jdbc;

	private final TransactionTemplate tx;

	private final int spreadBps;

	// shortcut: per instance and lost on restart, so the first rate after a deploy is only checked for age;
	// move to a shared store when running more than one instance.
	private final Map<String, BigDecimal> lastGoodRate = new ConcurrentHashMap<>();

	ConversionService(LedgerService ledger, ExchangeRateClient feed, JdbcClient jdbc, TransactionTemplate tx,
			@Value("${wallet.fx.spread-bps}") int spreadBps) {
		this.ledger = ledger;
		this.feed = feed;
		this.jdbc = jdbc;
		this.tx = tx;
		this.spreadBps = spreadBps;
	}

	public Quote quote(String merchantId, QuoteRequest request) {
		Currency sell = request.sellCurrency();
		Currency buy = request.buyCurrency();
		if (sell == buy) {
			throw new WalletException(ErrorCode.CURRENCY_MISMATCH, "Cannot convert " + sell + " to itself");
		}
		LedgerService.requireValidAmount(sell, request.sellAmount());
		BigDecimal rate = checkedRate(sell, buy)
			.multiply(BigDecimal.valueOf(10_000 - spreadBps))
			.movePointLeft(4)
			.setScale(18, RoundingMode.DOWN);
		// Round in the house's favour, never up.
		BigDecimal buyAmount = request.sellAmount().multiply(rate).setScale(buy.scale, RoundingMode.DOWN);
		LedgerService.requireValidAmount(buy, buyAmount);

		String id = UUID.randomUUID().toString();
		jdbc.sql("""
				INSERT INTO quote (id, merchant_id, sell_currency, sell_amount, buy_currency, buy_amount, rate, expires_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6) + INTERVAL ? MICROSECOND)""")
			.params(id, merchantId, sell.name(), request.sellAmount(), buy.name(), buyAmount, rate,
					QUOTE_TTL.toNanos() / 1000)
			.update();
		return find(merchantId, id, false).quote();
	}

	/**
	 * Executes a quote once. Replaying it returns the same conversion, even after expiry. If the merchant
	 * or the house is short, nothing moves and the quote can still be used until it expires.
	 */
	public Conversion convert(String merchantId, ConversionRequest request) {
		return tx.execute(status -> {
			// Locking the quote serialises concurrent executions of it, and the locking read sees the latest entry_id.
			QuoteRow row = find(merchantId, request.quoteId(), true);
			Quote q = row.quote();
			if (row.entryId() != null) {
				return conversion(q, row.entryId());
			}
			if (row.expired()) {
				throw new WalletException(ErrorCode.QUOTE_EXPIRED, "Quote " + q.id() + " expired at " + q.expiresAt());
			}
			long sellFrom = ledger.account(merchantId, AccountType.AVAILABLE, q.sellCurrency());
			long sellTo = ledger.account(LedgerService.PLATFORM, AccountType.FX, q.sellCurrency());
			long buyFrom = ledger.account(LedgerService.PLATFORM, AccountType.FX, q.buyCurrency());
			long buyTo = ledger.account(merchantId, AccountType.AVAILABLE, q.buyCurrency());
			long entryId;
			try {
				entryId = ledger.post("CONVERSION", q.id(),
						List.of(new Posting(sellFrom, q.sellCurrency(), q.sellAmount().negate()),
								new Posting(sellTo, q.sellCurrency(), q.sellAmount()),
								new Posting(buyFrom, q.buyCurrency(), q.buyAmount().negate()),
								new Posting(buyTo, q.buyCurrency(), q.buyAmount())));
			}
			catch (InsufficientFundsException e) {
				if (e.accountId() != buyFrom) {
					throw e;
				}
				log.warn("House {} stock too low for quote {}", q.buyCurrency(), q.id());
				throw new WalletException(ErrorCode.INSUFFICIENT_LIQUIDITY,
						"Cannot fill " + q.buyAmount().toPlainString() + " " + q.buyCurrency() + " right now");
			}
			jdbc.sql("UPDATE quote SET entry_id = ? WHERE id = ?").params(entryId, q.id()).update();
			return conversion(q, entryId);
		});
	}

	/** The feed's rate, if it is fresh and close to the last one we trusted. Fails closed. */
	private BigDecimal checkedRate(Currency sell, Currency buy) {
		Rate rate;
		try {
			rate = feed.rate(sell, buy);
		}
		catch (RuntimeException e) {
			log.warn("Rate feed failed for {}/{}", sell, buy, e);
			throw unavailable(sell, buy);
		}
		if (rate.rate().signum() <= 0 || rate.asOf().isBefore(Instant.now().minus(MAX_RATE_AGE))) {
			log.warn("Rejected rate {} {}/{} as of {}", rate.rate(), sell, buy, rate.asOf());
			throw unavailable(sell, buy);
		}
		String pair = sell + "/" + buy;
		BigDecimal last = lastGoodRate.get(pair);
		if (last != null
				&& rate.rate().subtract(last).abs().divide(last, MathContext.DECIMAL64).compareTo(MAX_RATE_JUMP) > 0) {
			log.warn("Rejected rate {} {}: more than 2% from last good {}", rate.rate(), pair, last);
			throw unavailable(sell, buy);
		}
		lastGoodRate.put(pair, rate.rate());
		return rate.rate();
	}

	private static WalletException unavailable(Currency sell, Currency buy) {
		return new WalletException(ErrorCode.RATE_UNAVAILABLE, "No reliable " + sell + "/" + buy + " rate right now");
	}

	private QuoteRow find(String merchantId, String id, boolean forUpdate) {
		return jdbc.sql("""
				SELECT id, sell_currency, sell_amount, buy_currency, buy_amount, rate, expires_at, entry_id,
				       expires_at <= NOW(6) AS expired
				FROM quote WHERE id = ? AND merchant_id = ?""" + (forUpdate ? " FOR UPDATE" : ""))
			.params(id, merchantId)
			.query(ConversionService::map)
			.optional()
			.orElseThrow(() -> new WalletException(ErrorCode.QUOTE_NOT_FOUND, "No quote " + id));
	}

	private static QuoteRow map(ResultSet rs, int row) throws SQLException {
		Currency sell = Currency.valueOf(rs.getString("sell_currency"));
		Currency buy = Currency.valueOf(rs.getString("buy_currency"));
		Quote quote = new Quote(rs.getString("id"), sell, rs.getBigDecimal("sell_amount").setScale(sell.scale), buy,
				rs.getBigDecimal("buy_amount").setScale(buy.scale), rs.getBigDecimal("rate"),
				rs.getTimestamp("expires_at").toInstant());
		return new QuoteRow(quote, rs.getObject("entry_id", Long.class), rs.getBoolean("expired"));
	}

	private static Conversion conversion(Quote q, long entryId) {
		return new Conversion(q.id(), entryId, q.sellCurrency(), q.sellAmount(), q.buyCurrency(), q.buyAmount(),
				q.rate());
	}

}
