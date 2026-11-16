package dev.moneta;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

public final class EconomyStore {

	private final Logger log;
	private final Path balancesPath;
	private final Path transactionsPath;
	private final Path requestsPath;
	private final MonetaConfig config;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	private final Map<UUID, Long> balances = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final List<Transaction> transactions = new ArrayList<>();
	private final List<PaymentRequest> requests = new ArrayList<>();
	private long treasuryBalance;

	private long lastMintTime;
	private long mintedToday;
	private long mintDay;
	private long lastRegulationTime;

	private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "moneta-save");
		thread.setDaemon(true);
		return thread;
	});

	private final Object writeLock = new Object();
	private volatile boolean dirty;

	public EconomyStore(
		Path balancesPath,
		Path transactionsPath,
		Path requestsPath,
		MonetaConfig config,
		Logger log
	) {
		this.balancesPath = balancesPath;
		this.transactionsPath = transactionsPath;
		this.requestsPath = requestsPath;
		this.config = config;
		this.log = log;
		loadBalances();
		loadTransactions();
		loadRequests();
	}

	/* ---------- saldi ---------- */

	public boolean hasAccount(UUID id) {
		return balances.containsKey(id);
	}

	public long balance(UUID id) {
		return balances.getOrDefault(id, 0L);
	}

	public String name(UUID id) {
		return names.get(id);
	}

	public Collection<String> knownNames() {
		return names.values();
	}

	public int accountCount() {
		return balances.size();
	}

	public void ensureAccount(UUID id, String name) {
		boolean changed = false;
		if (!balances.containsKey(id)) {
			long startingBalance = config.startingBalance;
			if (config.maxBalance > 0L && startingBalance > config.maxBalance) {
				startingBalance = config.maxBalance;
			}
			balances.put(id, startingBalance);
			changed = true;
		}
		if (name != null && !name.equals(names.get(id))) {
			names.put(id, name);
			changed = true;
		}
		if (changed) markDirty();
	}

	public void setName(UUID id, String name) {
		if (name != null && !name.equals(names.get(id))) {
			names.put(id, name);
			markDirty();
		}
	}

	public boolean canCredit(UUID id, long amount) {
		if (amount < 0L) return false;
		long current = balance(id);
		if (Long.MAX_VALUE - current < amount) return false;
		if (config.maxBalance > 0L && current + amount > config.maxBalance) {
			return false;
		}
		return true;
	}

	public long credit(UUID id, long amount) {
		if (amount < 0L) throw new IllegalArgumentException("amount < 0");
		long current = balance(id);
		long value;
		try {
			value = Math.addExact(current, amount);
		} catch (ArithmeticException e) {
			value = Long.MAX_VALUE;
		}
		if (config.maxBalance > 0L && value > config.maxBalance) {
			value = config.maxBalance;
		}
		balances.put(id, value);
		markDirty();
		return value;
	}

	public long debit(UUID id, long amount) {
		if (amount < 0L) throw new IllegalArgumentException("amount < 0");
		long current = balance(id);
		if (amount > current) {
			throw new IllegalArgumentException("saldo insufficiente");
		}
		long value = current - amount;
		balances.put(id, value);
		markDirty();
		return value;
	}

	public long setBalance(UUID id, long value) {
		if (value < 0L) throw new IllegalArgumentException("value < 0");
		if (config.maxBalance > 0L && value > config.maxBalance) {
			value = config.maxBalance;
		}
		balances.put(id, value);
		markDirty();
		return value;
	}

	public long total() {
		long total = 0L;
		for (long value : balances.values()) {
			if (value <= 0L) continue;
			if (Long.MAX_VALUE - total < value) return Long.MAX_VALUE;
			total += value;
		}
		return total;
	}

	public List<Map.Entry<UUID, Long>> top(int limit) {
		List<Map.Entry<UUID, Long>> entries = new ArrayList<>();
		for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
			if (entry.getValue() > 0L) entries.add(entry);
		}
		entries.sort(
			Comparator.comparingLong((Map.Entry<UUID, Long> entry) ->
				entry.getValue()
			).reversed()
		);
		if (entries.size() > limit) return entries.subList(0, limit);
		return entries;
	}

	/* ---------- zecca e limiti ---------- */

	public boolean canMint(long amount, long now) {
		if (amount <= 0L) return false;
		if (config.mintCooldownSeconds > 0
			&& now - lastMintTime < config.mintCooldownSeconds * 1000L) {
			return false;
		}
		if (config.mintDailyLimit > 0L) {
			long day = now / 86_400_000L;
			if (day != mintDay) {
				mintDay = day;
				mintedToday = 0L;
			}
			if (mintedToday + amount > config.mintDailyLimit) return false;
		}
		return true;
	}

	public void recordMint(long amount, long now) {
		lastMintTime = now;
		if (config.mintDailyLimit > 0L) {
			long day = now / 86_400_000L;
			if (day != mintDay) {
				mintDay = day;
				mintedToday = 0L;
			}
			mintedToday += amount;
		}
	}

	/* ---------- politica monetaria ---------- */

	public boolean isRegulationDue(MonetaConfig config, long now) {
		if (!config.regulationEnabled) return false;
		if (lastRegulationTime == 0L) {
			lastRegulationTime = now;
			return false;
		}
		return now - lastRegulationTime >= config.regulationIntervalSeconds * 1000L;
	}

	public String regulate(MinecraftServer server, MonetaConfig config) {
		if (!config.regulationEnabled) return null;
		lastRegulationTime = System.currentTimeMillis();

		long supply = total();
		long accounts = Math.max(1L, accountCount());
		long target = config.targetBalancePerPlayer * accounts;
		long tolerance = target * config.regulationTolerancePercent / 100L;

		if (supply < target - tolerance) {
			long deficit = target - supply;
			List<ServerPlayer> players = new ArrayList<>(
				server.getPlayerList().getPlayers()
			);
			if (!players.isEmpty()) {
				long minted = distributeRegulation(players, deficit, config);
				if (minted > 0L) {
					log(
						"REGULATE",
						null,
						"Zecca",
						null,
						"Economia",
						minted,
						supply + minted,
						0L
					);
					return "Regolazione: creati " + config.money(minted) + " (deficit).";
				}
			}
		}

		if (supply > target + tolerance) {
			long burned = burnExcess(config, supply - target, config.regulationRatePercent);
			if (burned > 0L) {
				log(
					"REGULATE",
					null,
					"Zecca",
					null,
					"Economia",
					burned,
					supply - burned,
					0L
				);
				return "Regolazione: bruciati " + config.money(burned) + " (eccesso).";
			}
		}
		return null;
	}

	private long distributeRegulation(
		List<ServerPlayer> players,
		long amount,
		MonetaConfig config
	) {
		long share = amount / players.size();
		long remainder = amount % players.size();
		for (ServerPlayer player : players) {
			ensureAccount(player.getUUID(), player.getGameProfile().name());
		}
		long minted = 0L;
		for (int i = 0; i < players.size(); i++) {
			ServerPlayer player = players.get(i);
			long add = share + (i < remainder ? 1L : 0L);
			if (add <= 0L) continue;
			long current = balance(player.getUUID());
			long room = config.maxBalance > 0L
				? Math.max(0L, config.maxBalance - current)
				: add;
			long actual = Math.min(add, room);
			if (actual <= 0L) continue;
			credit(player.getUUID(), actual);
			minted += actual;
		}
		return minted;
	}

	private long burnExcess(
		MonetaConfig config,
		long excess,
		int ratePercent
	) {
		if (ratePercent <= 0) return 0L;
		long toBurn = excess * ratePercent / 100L;
		if (toBurn < 1L) toBurn = 1L;

		List<Map.Entry<UUID, Long>> entries = new ArrayList<>();
		for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
			if (entry.getValue() > 0L) entries.add(entry);
		}
		entries.sort(
			Comparator.comparingLong((Map.Entry<UUID, Long> entry) ->
				entry.getValue()
			).reversed()
		);

		long burned = 0L;
		for (Map.Entry<UUID, Long> entry : entries) {
			if (burned >= toBurn) break;
			UUID id = entry.getKey();
			long current = entry.getValue();
			long targetForPlayer = config.targetBalancePerPlayer;
			if (current <= targetForPlayer) continue;
			long available = current - targetForPlayer;
			long burn = available * ratePercent / 100L;
			if (burn < 1L) burn = 1L;
			if (burn > available) burn = available;
			if (burned + burn > toBurn) burn = toBurn - burned;
			if (burn <= 0L) continue;
			balances.put(id, current - burn);
			burned += burn;
		}
		if (burned > 0L) markDirty();
		return burned;
	}

	/* ---------- zecca / tesoro ---------- */

	public long treasuryBalance() {
		return treasuryBalance;
	}

	public long creditTreasury(long amount) {
		if (amount < 0L) throw new IllegalArgumentException("amount < 0");
		try {
			treasuryBalance = Math.addExact(treasuryBalance, amount);
		} catch (ArithmeticException e) {
			treasuryBalance = Long.MAX_VALUE;
		}
		markDirty();
		return treasuryBalance;
	}

	public long debitTreasury(long amount) {
		if (amount < 0L) throw new IllegalArgumentException("amount < 0");
		if (amount > treasuryBalance) {
			throw new IllegalArgumentException("saldo della zecca insufficiente");
		}
		treasuryBalance -= amount;
		markDirty();
		return treasuryBalance;
	}

	public void setTreasuryBalance(long value) {
		if (value < 0L) value = 0L;
		treasuryBalance = value;
		markDirty();
	}

	/* ---------- richieste di denaro ---------- */

	public List<PaymentRequest> requestsForPayer(UUID payer) {
		List<PaymentRequest> result = new ArrayList<>();
		for (PaymentRequest request : requests) {
			if (request.isPayer(payer)) result.add(request);
		}
		result.sort(
			Comparator.comparingLong(PaymentRequest::createdAt)
		);
		return result;
	}

	public PaymentRequest findRequest(UUID payer, UUID requester) {
		for (PaymentRequest request : requests) {
			if (request.isPayer(payer) && request.isRequester(requester)) {
				return request;
			}
		}
		return null;
	}

	public int pendingCount(UUID payer) {
		int count = 0;
		for (PaymentRequest request : requests) {
			if (request.isPayer(payer)) count++;
		}
		return count;
	}

	public long pendingTotal(UUID payer) {
		long total = 0L;
		for (PaymentRequest request : requests) {
			if (!request.isPayer(payer)) continue;
			if (Long.MAX_VALUE - total < request.amount()) return Long.MAX_VALUE;
			total += request.amount();
		}
		return total;
	}

	public void addRequest(
		UUID requesterId,
		String requesterName,
		UUID payerId,
		String payerName,
		long amount
	) {
		PaymentRequest request = new PaymentRequest();
		request.requester = requesterId.toString();
		request.requesterName = requesterName;
		request.payer = payerId.toString();
		request.payerName = payerName;
		request.amount = amount;
		request.createdAt = System.currentTimeMillis();
		requests.add(request);
		markDirty();
	}

	public boolean removeRequest(PaymentRequest request) {
		boolean removed = requests.remove(request);
		if (removed) markDirty();
		return removed;
	}

	public int removeAllForPayer(UUID payer) {
		List<PaymentRequest> toRemove = requestsForPayer(payer);
		requests.removeAll(toRemove);
		if (!toRemove.isEmpty()) markDirty();
		return toRemove.size();
	}

	public List<PaymentRequest> allRequests() {
		return Collections.unmodifiableList(requests);
	}

	/* ---------- registro transazioni ---------- */

	public void log(
		String type,
		UUID actorId,
		String actorName,
		UUID targetId,
		String targetName,
		long amount,
		long balanceAfter,
		long fee
	) {
		Transaction transaction = new Transaction();
		transaction.time = System.currentTimeMillis();
		transaction.type = type;
		transaction.actor = actorId == null ? null : actorId.toString();
		transaction.actorName = actorName;
		transaction.target = targetId == null ? null : targetId.toString();
		transaction.targetName = targetName;
		transaction.amount = amount;
		transaction.balanceAfter = balanceAfter;
		transaction.fee = fee;

		transactions.add(0, transaction);
		while (transactions.size() > config.transactionLogLimit) {
			transactions.remove(transactions.size() - 1);
		}
		markDirty();
	}

	public List<Transaction> transactions() {
		return Collections.unmodifiableList(transactions);
	}

	/* ---------- persistenza ---------- */

	public boolean isDirty() {
		return dirty;
	}

	public void markDirty() {
		dirty = true;
	}

	public void saveAsync() {
		if (!dirty) return;
		dirty = false;
		String balancesJson = balancesJson();
		String transactionsJson = transactionsJson();
		String requestsJson = requestsJson();
		writer.execute(() -> {
			try {
				writeAll(balancesJson, transactionsJson, requestsJson);
			} catch (Exception e) {
				dirty = true;
				log.error("Moneta: salvataggio fallito: {}", e.toString());
			}
		});
	}

	public void saveNow() {
		dirty = false;
		try {
			writeAll(balancesJson(), transactionsJson(), requestsJson());
		} catch (Exception e) {
			dirty = true;
			log.error("Moneta: salvataggio fallito: {}", e.toString());
		}
	}

	public void shutdown() {
		writer.shutdown();
		try {
			if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
				writer.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		saveNow();
	}

	private void writeAll(
		String balancesJson,
		String transactionsJson,
		String requestsJson
	) throws IOException {
		synchronized (writeLock) {
			writeAtomic(balancesPath, balancesJson);
			writeAtomic(transactionsPath, transactionsJson);
			writeAtomic(requestsPath, requestsJson);
		}
	}

	private static void writeAtomic(Path path, String json) throws IOException {
		Files.createDirectories(path.getParent());
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			writer.write(json);
		}
		try {
			Files.move(
				temporary,
				path,
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE
			);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private String balancesJson() {
		JsonObject root = new JsonObject();
		JsonObject players = new JsonObject();
		for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
			JsonObject player = new JsonObject();
			player.addProperty("balance", entry.getValue());
			String name = names.get(entry.getKey());
			if (name != null) player.addProperty("name", name);
			players.add(entry.getKey().toString(), player);
		}
		root.add("players", players);
		root.addProperty("treasury", treasuryBalance);
		return gson.toJson(root);
	}

	private String transactionsJson() {
		JsonObject root = new JsonObject();
		JsonArray array = new JsonArray();
		for (Transaction transaction : transactions) {
			array.add(gson.toJsonTree(transaction));
		}
		root.add("transactions", array);
		return gson.toJson(root);
	}

	private String requestsJson() {
		JsonObject root = new JsonObject();
		JsonArray array = new JsonArray();
		for (PaymentRequest request : requests) {
			array.add(gson.toJsonTree(request));
		}
		root.add("requests", array);
		return gson.toJson(root);
	}

	private void loadBalances() {
		if (!Files.exists(balancesPath)) return;
		try (Reader reader = Files.newBufferedReader(balancesPath, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			if (!root.has("players") || !root.get("players").isJsonObject()) return;
			JsonObject players = root.getAsJsonObject("players");
			for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
				UUID id;
				try {
					id = UUID.fromString(entry.getKey());
				} catch (IllegalArgumentException e) {
					continue;
				}
				if (!entry.getValue().isJsonObject()) continue;
				JsonObject player = entry.getValue().getAsJsonObject();
				long balance = 0L;
				if (player.has("balance") && player.get("balance").isJsonPrimitive()) {
					balance = player.get("balance").getAsLong();
				}
				if (balance < 0L) balance = 0L;
				balances.put(id, balance);
				if (player.has("name") && player.get("name").isJsonPrimitive()) {
					names.put(id, player.get("name").getAsString());
				}
			}
			if (root.has("treasury") && root.get("treasury").isJsonPrimitive()) {
				long treasury = root.get("treasury").getAsLong();
				if (treasury < 0L) treasury = 0L;
				treasuryBalance = treasury;
			}
		} catch (Exception e) {
			backupCorrupt(balancesPath);
			log.error("Moneta: saldi non leggibili: {}", e.toString());
		}
	}

	private void loadTransactions() {
		if (!Files.exists(transactionsPath)) return;
		try (Reader reader = Files.newBufferedReader(
			transactionsPath,
			StandardCharsets.UTF_8
		)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			if (!root.has("transactions") || !root.get("transactions").isJsonArray()) {
				return;
			}
			for (JsonElement element : root.getAsJsonArray("transactions")) {
				Transaction transaction = gson.fromJson(element, Transaction.class);
				if (transaction == null || transaction.type == null) continue;
				transactions.add(transaction);
			}
			while (transactions.size() > config.transactionLogLimit) {
				transactions.remove(transactions.size() - 1);
			}
			for (Transaction transaction : transactions) {
				rememberName(transaction.actor, transaction.actorName);
				rememberName(transaction.target, transaction.targetName);
			}
		} catch (Exception e) {
			backupCorrupt(transactionsPath);
			log.error("Moneta: transazioni non leggibili: {}", e.toString());
		}
	}

	private void loadRequests() {
		if (!Files.exists(requestsPath)) return;
		try (Reader reader = Files.newBufferedReader(
			requestsPath,
			StandardCharsets.UTF_8
		)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			if (!root.has("requests") || !root.get("requests").isJsonArray()) {
				return;
			}
			for (JsonElement element : root.getAsJsonArray("requests")) {
				PaymentRequest request = gson.fromJson(element, PaymentRequest.class);
				if (request == null || request.amount <= 0L) continue;
				requests.add(request);
				rememberName(request.requester, request.requesterName);
				rememberName(request.payer, request.payerName);
			}
		} catch (Exception e) {
			backupCorrupt(requestsPath);
			log.error("Moneta: richieste non leggibili: {}", e.toString());
		}
	}

	private void rememberName(String rawId, String name) {
		if (rawId == null || name == null) return;
		try {
			names.putIfAbsent(UUID.fromString(rawId), name);
		} catch (IllegalArgumentException e) {
			// ignora identificativi non validi nel registro
		}
	}

	private void backupCorrupt(Path path) {
		try {
			Path backup = path.resolveSibling(
				path.getFileName() + ".corrupt-" + System.currentTimeMillis()
			);
			Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
			log.warn("Moneta: copia di backup creata in {}", backup.getFileName());
		} catch (Exception e) {
			log.error("Moneta: impossibile creare il backup: {}", e.toString());
		}
	}

	public static final class PaymentRequest {

		String requester;
		String requesterName;
		String payer;
		String payerName;
		long amount;
		long createdAt;

		public UUID requesterId() {
			return uuid(requester);
		}

		public UUID payerId() {
			return uuid(payer);
		}

		public String requesterName() {
			return requesterName;
		}

		public String payerName() {
			return payerName;
		}

		public long amount() {
			return amount;
		}

		public long createdAt() {
			return createdAt;
		}

		public boolean isPayer(UUID id) {
			return id != null && id.toString().equals(payer);
		}

		public boolean isRequester(UUID id) {
			return id != null && id.toString().equals(requester);
		}

		private static UUID uuid(String raw) {
			if (raw == null) return null;
			try {
				return UUID.fromString(raw);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	public static final class Transaction {

		long time;
		String type;
		String actor;
		String actorName;
		String target;
		String targetName;
		long amount;
		long balanceAfter;
		long fee;

		public boolean involves(UUID id, String name) {
			boolean idMatch =
				id != null &&
				(id.toString().equals(actor) || id.toString().equals(target));
			boolean nameMatch =
				name != null &&
				((actorName != null && actorName.equalsIgnoreCase(name)) ||
					(targetName != null && targetName.equalsIgnoreCase(name)));
			return idMatch || nameMatch;
		}

		public long time() {
			return time;
		}

		public String type() {
			return type;
		}

		public String actorName() {
			return actorName;
		}

		public String actor() {
			return actor;
		}

		public String targetName() {
			return targetName;
		}

		public String target() {
			return target;
		}

		public long amount() {
			return amount;
		}

		public long balanceAfter() {
			return balanceAfter;
		}

		public long fee() {
			return fee;
		}
	}
}
