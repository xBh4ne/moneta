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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

public final class EconomyStore {

	private final Logger log;
	private final Path balancesPath;
	private final Path transactionsPath;
	private final MonetaConfig config;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	private final Map<UUID, Long> balances = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final List<Transaction> transactions = new ArrayList<>();

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
		MonetaConfig config,
		Logger log
	) {
		this.balancesPath = balancesPath;
		this.transactionsPath = transactionsPath;
		this.config = config;
		this.log = log;
		loadBalances();
		loadTransactions();
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
			balances.put(id, config.startingBalance);
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
		return Long.MAX_VALUE - current >= amount;
	}

	public long credit(UUID id, long amount) {
		if (amount < 0L) throw new IllegalArgumentException("amount < 0");
		long value = Math.addExact(balance(id), amount);
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

	/* ---------- registro transazioni ---------- */

	public void log(
		String type,
		UUID actorId,
		String actorName,
		UUID targetId,
		String targetName,
		long amount,
		long balanceAfter
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
		writer.execute(() -> {
			try {
				writeBoth(balancesJson, transactionsJson);
			} catch (Exception e) {
				dirty = true;
				log.error("Moneta: salvataggio fallito: {}", e.toString());
			}
		});
	}

	public void saveNow() {
		dirty = false;
		try {
			writeBoth(balancesJson(), transactionsJson());
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

	private void writeBoth(String balancesJson, String transactionsJson)
		throws IOException {
		synchronized (writeLock) {
			writeAtomic(balancesPath, balancesJson);
			writeAtomic(transactionsPath, transactionsJson);
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

	public static final class Transaction {

		long time;
		String type;
		String actor;
		String actorName;
		String target;
		String targetName;
		long amount;
		long balanceAfter;

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
	}
}
