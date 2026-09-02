package com.nstut.economybounties.minecraft;

import com.google.gson.Gson;
import com.nstut.economy.api.EconomyApi;
import com.nstut.economy.api.EconomyId;
import com.nstut.economy.api.IAccountManager;
import com.nstut.economy.api.IBankAccount;
import com.nstut.economy.api.ITransactionContext;
import com.nstut.economy.api.ITransactionRecord;
import com.nstut.economybounties.api.EscrowProvider;
import com.nstut.economybounties.api.RewardProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Economy public-API-only money adapter with durable operation idempotency. */
public final class EconomyMoneyAdapter implements EscrowProvider, RewardProvider {
    private static final EconomyId FUND = EconomyId.of("economy_bounties", "bounty_fund");
    private static final EconomyId PAYOUT = EconomyId.of("economy_bounties", "bounty_reward");
    private static final EconomyId REFUND = EconomyId.of("economy_bounties", "bounty_refund");
    private static final int RECOVERY_HISTORY = 256;
    private static final System.Logger LOGGER = System.getLogger(EconomyMoneyAdapter.class.getName());
    private static final Gson GSON = new Gson();

    private final Path journalPath;
    private final Set<UUID> completedOperations = new LinkedHashSet<>();

    public EconomyMoneyAdapter() { this(null); }

    public EconomyMoneyAdapter(Path journalPath) {
        this.journalPath = journalPath;
        loadJournal();
    }

    @Override
    public EscrowProvider.Result fund(UUID bountyId, UUID creatorId, BigDecimal amount, Map<String, String> metadata) {
        if (!EconomyApi.isReady()) return EscrowProvider.Result.failure("Economy API is not ready");
        IAccountManager accounts = EconomyApi.accounts();
        IBankAccount creator = accounts.getOrCreatePlayerAccount(creatorId);
        IBankAccount escrow = accounts.getServerAccount();
        return transfer(accounts, creator, escrow, amount, operationId(bountyId, "fund"), FUND,
                "Fund player bounty", creatorId.toString(), metadata);
    }

    @Override
    public EscrowProvider.Result payout(UUID bountyId, UUID claimantId, BigDecimal amount, Map<String, String> metadata) {
        if (!EconomyApi.isReady()) return EscrowProvider.Result.failure("Economy API is not ready");
        IAccountManager accounts = EconomyApi.accounts();
        IBankAccount escrow = accounts.getServerAccount();
        IBankAccount claimant = accounts.getOrCreatePlayerAccount(claimantId);
        return transfer(accounts, escrow, claimant, amount, operationId(bountyId, "payout"), PAYOUT,
                "Pay player bounty", bountyId.toString(), metadata);
    }

    @Override
    public EscrowProvider.Result refund(UUID bountyId, UUID creatorId, BigDecimal amount, Map<String, String> metadata) {
        if (!EconomyApi.isReady()) return EscrowProvider.Result.failure("Economy API is not ready");
        IAccountManager accounts = EconomyApi.accounts();
        IBankAccount escrow = accounts.getServerAccount();
        IBankAccount creator = accounts.getOrCreatePlayerAccount(creatorId);
        return transfer(accounts, escrow, creator, amount, operationId(bountyId, "refund"), REFUND,
                "Refund player bounty", bountyId.toString(), metadata);
    }

    @Override
    public RewardProvider.PayoutResult payout(RewardProvider.RewardContext reward) {
        Objects.requireNonNull(reward, "reward");
        if (reward.currencyAmount().signum() <= 0) return RewardProvider.PayoutResult.success("none");
        if (!EconomyApi.isReady()) return RewardProvider.PayoutResult.failure("Economy API is not ready");
        IAccountManager accounts = EconomyApi.accounts();
        IBankAccount target = accounts.getOrCreatePlayerAccount(reward.playerId());
        UUID txId = reward.payoutKey();
        if (seen(target, txId)) return RewardProvider.PayoutResult.success(txId.toString());

        Map<String, String> metadata = new java.util.LinkedHashMap<>(reward.metadata());
        metadata.put("economy_bounties:payout_key", reward.payoutKey().toString());
        TxContext context = new TxContext(txId, Instant.now(), PAYOUT, "Generated bounty reward",
                reward.bountyId().toString(), Map.copyOf(metadata));
        String funding = reward.metadata().getOrDefault("funding", "mint");
        boolean success = "treasury".equalsIgnoreCase(funding)
                ? accounts.transfer(accounts.getServerAccount(), target, reward.currencyAmount(), context)
                : target.credit(reward.currencyAmount(), context);
        if (success) markCompleted(txId);
        return success ? RewardProvider.PayoutResult.success(txId.toString())
                : RewardProvider.PayoutResult.failure("Economy rejected the bounty payout");
    }

    private EscrowProvider.Result transfer(IAccountManager accounts, IBankAccount source, IBankAccount target,
                                           BigDecimal amount, UUID txId, EconomyId cause, String description,
                                           String sourceId, Map<String, String> metadata) {
        if (amount == null || amount.signum() <= 0) return EscrowProvider.Result.failure("Amount must be positive");
        if (seen(source, txId) || seen(target, txId)) {
            markCompleted(txId);
            return EscrowProvider.Result.success(txId.toString());
        }
        Map<String, String> values = new java.util.LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        values.put("economy_bounties:operation_id", txId.toString());
        TxContext context = new TxContext(txId, Instant.now(), cause, description, sourceId, Map.copyOf(values));
        boolean success = accounts.transfer(source, target, amount, context);
        if (success) markCompleted(txId);
        return success ? EscrowProvider.Result.success(txId.toString())
                : EscrowProvider.Result.failure("Insufficient funds or Economy rejected the transfer");
    }

    private synchronized boolean seen(IBankAccount account, UUID transactionId) {
        if (completedOperations.contains(transactionId)) return true;
        for (ITransactionRecord record : account.getRecentTransactions(RECOVERY_HISTORY)) {
            if (transactionId.equals(record.getTransactionId())) return true;
        }
        return false;
    }

    private synchronized void markCompleted(UUID transactionId) {
        if (!completedOperations.add(transactionId)) return;
        persistJournal();
    }

    private synchronized void loadJournal() {
        if (journalPath == null || !Files.isRegularFile(journalPath)) return;
        try {
            String[] values = GSON.fromJson(Files.readString(journalPath), String[].class);
            if (values == null) return;
            for (String value : values) {
                try { completedOperations.add(UUID.fromString(value)); }
                catch (IllegalArgumentException ignored) { LOGGER.log(System.Logger.Level.WARNING, "Ignoring malformed bounty operation id " + value); }
            }
        } catch (IOException | RuntimeException error) {
            LOGGER.log(System.Logger.Level.ERROR, "Failed to load bounty operation journal " + journalPath, error);
        }
    }

    private void persistJournal() {
        if (journalPath == null) return;
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = journalPath.resolveSibling(journalPath.getFileName() + ".tmp");
            String[] values = completedOperations.stream().map(UUID::toString).toArray(String[]::new);
            Files.writeString(temp, GSON.toJson(values));
            try {
                Files.move(temp, journalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, journalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            // The Economy transaction already committed. Recent history still protects the immediate crash window.
            LOGGER.log(System.Logger.Level.ERROR, "Failed to persist bounty operation journal " + journalPath, error);
        }
    }

    private static UUID operationId(UUID bountyId, String operation) {
        return UUID.nameUUIDFromBytes(("economy_bounties:" + bountyId + ':' + operation).getBytes(StandardCharsets.UTF_8));
    }

    private record TxContext(UUID transactionId, Instant timestamp, EconomyId causeId, String description,
                             String source, Map<String, String> metadata) implements ITransactionContext {
        @Override public UUID getTransactionId() { return transactionId; }
        @Override public Instant getTimestamp() { return timestamp; }
        @Override public TransactionType getType() { return TransactionType.CUSTOM; }
        @Override public EconomyId getCauseId() { return causeId; }
        @Override public String getDescription() { return description; }
        @Override public String getSource() { return source; }
        @Override public Map<String, String> getMetadata() { return metadata; }
    }
}
