package dev.moneta;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MonetaMod implements ModInitializer {

	public static final Logger LOG = LoggerFactory.getLogger("moneta");

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern(
		"yyyy-MM-dd HH:mm:ss"
	).withZone(ZoneId.systemDefault());
	private static final int TRANSACTIONS_DEFAULT_LIMIT = 10;

	private MinecraftServer server;
	private MonetaConfig config;
	private Path configPath;
	private EconomyStore store;
	private long lastAutosave;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::stop);
		ServerTickEvents.END_SERVER_TICK.register(this::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, joinedServer) -> {
			if (store == null) return;
			store.ensureAccount(
				handler.player.getUUID(),
				handler.player.getGameProfile().name()
			);
			persistMaybe();
		});

		CommandRegistrationCallback.EVENT.register(
			(dispatcher, registries, environment) -> registerCommands(dispatcher)
		);
	}

	private void start(MinecraftServer startedServer) {
		server = startedServer;
		configPath = startedServer.getFile("config").resolve("moneta.json");
		config = MonetaConfig.load(configPath, LOG);

		Path dataDirectory = startedServer
			.getWorldPath(LevelResource.ROOT)
			.resolve("moneta");
		try {
			Files.createDirectories(dataDirectory);
		} catch (Exception e) {
			LOG.error("Moneta: impossibile creare la cartella dati: {}", e.toString());
		}

		store = new EconomyStore(
			dataDirectory.resolve("balances.json"),
			dataDirectory.resolve("transactions.json"),
			config,
			LOG
		);
		lastAutosave = System.currentTimeMillis();
		LOG.info(
			"Moneta pronto. {} conti, totale in circolazione: {}",
			store.accountCount(),
			config.money(store.total())
		);
	}

	private void stop(MinecraftServer stoppingServer) {
		if (store != null) {
			store.shutdown();
			store = null;
		}
		server = null;
	}

	private void tick(MinecraftServer tickingServer) {
		if (store == null || config == null) return;
		long now = System.currentTimeMillis();

		if (config.autosaveSeconds > 0 && store.isDirty()) {
			if (now - lastAutosave >= config.autosaveSeconds * 1000L) {
				store.saveAsync();
				lastAutosave = now;
			}
		}

		if (config.regulationEnabled && store.isRegulationDue(config, now)) {
			String summary = store.regulate(server, config);
			if (summary != null) {
				LOG.info("Moneta: {}", summary);
				persistMaybe();
			}
		}
	}

	private void persistMaybe() {
		if (store != null && config != null && config.autosaveSeconds == 0) {
			store.saveAsync();
		}
	}

	/* ---------- comandi ---------- */

	private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
			Commands.literal("balance")
				.executes(context -> showBalance(context.getSource(), null))
				.then(
					playerArgument("player").executes(context ->
						showBalance(
							context.getSource(),
							StringArgumentType.getString(context, "player")
						)
					)
				)
		);

		dispatcher.register(
			Commands.literal("send").then(
				playerArgument("player").then(
					Commands.argument("amount", LongArgumentType.longArg(1L)).executes(
						context ->
							sendMoney(
								context.getSource(),
								StringArgumentType.getString(context, "player"),
								LongArgumentType.getLong(context, "amount")
							)
					)
				)
			)
		);

		dispatcher.register(
			Commands.literal("mint").then(
				Commands.argument("amount", LongArgumentType.longArg(1L)).executes(
					context ->
						mint(
							context.getSource(),
							LongArgumentType.getLong(context, "amount")
						)
				)
			)
		);

		dispatcher.register(
			Commands.literal("economy")
				.executes(context -> economy(context.getSource(), -1))
				.then(
					Commands.argument("limit", IntegerArgumentType.integer(1)).executes(
						context ->
							economy(
								context.getSource(),
								IntegerArgumentType.getInteger(context, "limit")
							)
					)
				)
		);

		dispatcher.register(
			Commands.literal("treasury").executes(context ->
				showTreasury(context.getSource())
			)
		);

		dispatcher.register(
			Commands.literal("settax")
				.requires(this::isAdmin)
				.then(
					Commands.argument("percent", IntegerArgumentType.integer(0, 100)).executes(
						context ->
							setTax(
								context.getSource(),
								IntegerArgumentType.getInteger(context, "percent")
							)
					)
				)
		);

		dispatcher.register(
			Commands.literal("treasurypay")
				.requires(this::isAdmin)
				.then(
					playerArgument("player").then(
						Commands.argument("amount", LongArgumentType.longArg(1L)).executes(
							context ->
								payFromTreasury(
									context.getSource(),
									StringArgumentType.getString(context, "player"),
									LongArgumentType.getLong(context, "amount")
								)
						)
					)
				)
		);

		dispatcher.register(
			Commands.literal("setbalance")
				.requires(this::isAdmin)
				.then(
					playerArgument("player").then(
						Commands.argument("amount", LongArgumentType.longArg(0L)).executes(
							context ->
								setBalance(
									context.getSource(),
									StringArgumentType.getString(context, "player"),
									LongArgumentType.getLong(context, "amount")
								)
						)
					)
				)
		);

		dispatcher.register(
			Commands.literal("takebalance")
				.requires(this::isAdmin)
				.then(
					playerArgument("player").then(
						Commands.argument("amount", LongArgumentType.longArg(1L)).executes(
							context ->
								takeBalance(
									context.getSource(),
									StringArgumentType.getString(context, "player"),
									LongArgumentType.getLong(context, "amount")
								)
						)
					)
				)
		);

		dispatcher.register(
			Commands.literal("transactions")
				.requires(this::isAdmin)
				.executes(context -> showTransactions(context.getSource(), null, -1))
				.then(
					playerArgument("player")
						.executes(context ->
							showTransactions(
								context.getSource(),
								StringArgumentType.getString(context, "player"),
								-1
							)
						)
						.then(
							Commands.argument(
								"limit",
								IntegerArgumentType.integer(1)
							).executes(context ->
								showTransactions(
									context.getSource(),
									StringArgumentType.getString(context, "player"),
									IntegerArgumentType.getInteger(context, "limit")
								)
							)
						)
				)
		);
	}

	private RequiredArgumentBuilder<CommandSourceStack, String> playerArgument(
		String name
	) {
		return Commands.argument(name, StringArgumentType.word()).suggests(
			(context, builder) ->
				SharedSuggestionProvider.suggest(knownNames(), builder)
		);
	}

	private List<String> knownNames() {
		Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		if (store != null) names.addAll(store.knownNames());
		if (server != null) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				names.add(player.getGameProfile().name());
			}
		}
		return new ArrayList<>(names);
	}

	/* ---------- implementazione comandi ---------- */

	private int showBalance(CommandSourceStack source, String playerName) {
		if (store == null) return 0;

		if (playerName == null) {
			ServerPlayer player = source.getPlayer();
			if (player == null) {
				source.sendFailure(
					Component.literal("Specifica un giocatore: /balance <player>.")
				);
				return 0;
			}
			store.ensureAccount(player.getUUID(), player.getGameProfile().name());
			persistMaybe();
			source.sendSuccess(
				() ->
					Component.literal(
						"Il tuo saldo: " + config.money(store.balance(player.getUUID()))
					),
				false
			);
			return 1;
		}

		Optional<NameAndId> resolved = resolve(playerName);
		if (resolved.isEmpty()) {
			source.sendFailure(
				Component.literal("Giocatore non trovato: " + playerName + ".")
			);
			return 0;
		}
		NameAndId target = resolved.get();
		String targetName = nameOf(target);
		source.sendSuccess(
			() ->
				Component.literal(
					"Saldo di " +
						targetName +
						": " +
						config.money(store.balance(target.id()))
				),
			false
		);
		return 1;
	}

	private int sendMoney(
		CommandSourceStack source,
		String targetName,
		long amount
	) {
		if (store == null) return 0;
		ServerPlayer sender = source.getPlayer();
		if (sender == null) {
			source.sendFailure(
				Component.literal("Solo i giocatori possono inviare denaro.")
			);
			return 0;
		}
		if (amount <= 0L) {
			source.sendFailure(
				Component.literal("L'importo deve essere maggiore di zero.")
			);
			return 0;
		}

		Optional<NameAndId> resolved = resolve(targetName);
		if (resolved.isEmpty()) {
			source.sendFailure(
				Component.literal("Giocatore non trovato: " + targetName + ".")
			);
			return 0;
		}
		NameAndId target = resolved.get();
		if (sender.getUUID().equals(target.id())) {
			source.sendFailure(
				Component.literal("Non puoi inviare denaro a te stesso.")
			);
			return 0;
		}

		String senderName = sender.getGameProfile().name();
		String resolvedTargetName = nameOf(target);
		store.setName(sender.getUUID(), senderName);
		store.ensureAccount(target.id(), resolvedTargetName);

		long fee = config.transactionTaxPercent > 0
			? amount * config.transactionTaxPercent / 100L
			: 0L;
		if (fee <= 0L && config.transactionTaxPercent > 0) fee = 0L;
		long totalCost = amount + fee;

		long senderBalance = store.balance(sender.getUUID());
		if (senderBalance < totalCost) {
			source.sendFailure(
				Component.literal(
					"Fondi insufficienti. Saldo: " + config.money(senderBalance)
				)
			);
			return 0;
		}
		if (!store.canCredit(target.id(), amount)) {
			source.sendFailure(
				Component.literal("Il destinatario ha raggiunto il saldo massimo.")
			);
			return 0;
		}

		long newSenderBalance = store.debit(sender.getUUID(), totalCost);
		long newTargetBalance = store.credit(target.id(), amount);
		if (fee > 0L) store.creditTreasury(fee);
		store.log(
			"SEND",
			sender.getUUID(),
			senderName,
			target.id(),
			resolvedTargetName,
			amount,
			newTargetBalance,
			fee
		);
		persistMaybe();

		ServerPlayer onlineTarget = server.getPlayerList().getPlayer(target.id());
		String offlineSuffix = onlineTarget == null ? " (offline)" : "";
		source.sendSuccess(
			() ->
				Component.literal(
					"Inviati " +
						config.money(amount) +
						" a " +
						resolvedTargetName +
						offlineSuffix +
						". Nuovo saldo: " +
						config.money(newSenderBalance)
				),
			false
		);
		if (onlineTarget != null) {
			onlineTarget.sendSystemMessage(
				Component.literal(
					senderName +
						" ti ha inviato " +
						config.money(amount) +
						". Nuovo saldo: " +
						config.money(newTargetBalance)
				)
			);
		}
		return 1;
	}

	private int mint(CommandSourceStack source, long amount) {
		if (store == null || server == null) return 0;
		List<ServerPlayer> players = new ArrayList<>(
			server.getPlayerList().getPlayers()
		);
		if (players.isEmpty()) {
			source.sendFailure(
				Component.literal("Nessun giocatore online per distribuire il denaro.")
			);
			return 0;
		}
		if (amount <= 0L) {
			source.sendFailure(
				Component.literal("L'importo deve essere maggiore di zero.")
			);
			return 0;
		}
		long now = System.currentTimeMillis();
		if (!store.canMint(amount, now)) {
			source.sendFailure(
				Component.literal(
					"Zecca in raffreddamento o limite giornaliero raggiunto."
				)
			);
			return 0;
		}

		long share = amount / players.size();
		long remainder = amount % players.size();
		for (ServerPlayer player : players) {
			store.ensureAccount(player.getUUID(), player.getGameProfile().name());
		}
		for (int i = 0; i < players.size(); i++) {
			ServerPlayer player = players.get(i);
			long add = share + (i < remainder ? 1L : 0L);
			if (!store.canCredit(player.getUUID(), add)) {
				source.sendFailure(
					Component.literal(
						"Il saldo di " +
							player.getGameProfile().name() +
							" ha raggiunto il massimo."
					)
				);
				return 0;
			}
		}

		long distributed = 0L;
		for (int i = 0; i < players.size(); i++) {
			ServerPlayer player = players.get(i);
			long add = share + (i < remainder ? 1L : 0L);
			if (add <= 0L) continue;
			long newBalance = store.credit(player.getUUID(), add);
			distributed += add;
			store.log(
				"MINT",
				null,
				"Zecca",
				player.getUUID(),
				player.getGameProfile().name(),
				add,
				newBalance,
				0L
			);
		}
		store.recordMint(amount, now);
		persistMaybe();

		long totalDistributed = distributed;
		source.sendSuccess(
			() ->
				Component.literal(
					"Zecca: messi in circolazione " +
						config.money(totalDistributed) +
						" (totale ora: " +
						config.money(store.total()) +
						"), distribuiti a " +
						players.size() +
						" giocatori."
				),
			true
		);
		for (int i = 0; i < players.size(); i++) {
			long add = share + (i < remainder ? 1L : 0L);
			if (add <= 0L) continue;
			ServerPlayer player = players.get(i);
			long newBalance = store.balance(player.getUUID());
			player.sendSystemMessage(
				Component.literal(
					"La zecca ti ha accreditato " +
						config.money(add) +
						". Nuovo saldo: " +
						config.money(newBalance)
				)
			);
		}
		return 1;
	}

	private int economy(CommandSourceStack source, int requestedLimit) {
		if (store == null) return 0;
		int limit = requestedLimit > 0
			? Math.min(requestedLimit, config.economyMaxLimit)
			: config.economyTopLimit;
		long total = store.total();
		int accounts = store.accountCount();

		source.sendSuccess(
			() ->
				Component.literal(
					"Totale in circolazione: " +
						config.money(total) +
						" (" +
						accounts +
						" conti)"
				),
			false
		);
		if (total <= 0L) {
			source.sendSuccess(
				() -> Component.literal("Nessun denaro in circolazione."),
				false
			);
			return 1;
		}

		List<java.util.Map.Entry<UUID, Long>> top = store.top(limit);
		int rank = 1;
		for (java.util.Map.Entry<UUID, Long> entry : top) {
			String name = store.name(entry.getKey());
			if (name == null) name = entry.getKey().toString();
			double percentage = (entry.getValue() * 100.0) / total;
			String displayedName = name;
			String line =
				rank +
				". " +
				displayedName +
				": " +
				config.money(entry.getValue()) +
				" (" +
				String.format(Locale.ROOT, "%.2f", percentage) +
				"%)";
			source.sendSuccess(() -> Component.literal(line), false);
			rank++;
		}
		return 1;
	}

	private int showTreasury(CommandSourceStack source) {
		if (store == null || config == null) return 0;
		source.sendSuccess(
			() ->
				Component.literal(
					"Saldo della zecca: " +
						config.money(store.treasuryBalance()) +
						" (tassa: " +
						config.transactionTaxPercent +
						"%)"
				),
			false
		);
		return 1;
	}

	private int setTax(CommandSourceStack source, int percent) {
		if (config == null || configPath == null) return 0;
		config.transactionTaxPercent = percent;
		MonetaConfig.save(config, configPath, LOG);
		source.sendSuccess(
			() ->
				Component.literal(
					"Tassa sulle transazioni impostata a " + percent + "%."
				),
			true
		);
		return 1;
	}

	private int payFromTreasury(
		CommandSourceStack source,
		String targetName,
		long amount
	) {
		if (store == null || server == null) return 0;
		if (amount <= 0L) {
			source.sendFailure(
				Component.literal("L'importo deve essere maggiore di zero.")
			);
			return 0;
		}
		Optional<NameAndId> resolved = resolve(targetName);
		if (resolved.isEmpty()) {
			source.sendFailure(
				Component.literal("Giocatore non trovato: " + targetName + ".")
			);
			return 0;
		}
		NameAndId target = resolved.get();
		String resolvedTargetName = nameOf(target);
		store.ensureAccount(target.id(), resolvedTargetName);

		if (store.treasuryBalance() < amount) {
			source.sendFailure(
				Component.literal(
					"Saldo della zecca insufficiente: " +
						config.money(store.treasuryBalance())
				)
			);
			return 0;
		}
		if (!store.canCredit(target.id(), amount)) {
			source.sendFailure(
				Component.literal("Il destinatario ha raggiunto il saldo massimo.")
			);
			return 0;
		}

		store.debitTreasury(amount);
		long newBalance = store.credit(target.id(), amount);
		store.log(
			"TREASURY",
			source.getPlayer() == null ? null : source.getPlayer().getUUID(),
			source.getTextName(),
			target.id(),
			resolvedTargetName,
			amount,
			newBalance,
			0L
		);
		persistMaybe();

		source.sendSuccess(
			() ->
				Component.literal(
					"Pagati " +
						config.money(amount) +
						" dal tesoro a " +
						resolvedTargetName +
						". Nuovo saldo: " +
						config.money(newBalance) +
						"."
				),
			true
		);
		ServerPlayer onlineTarget = server.getPlayerList().getPlayer(target.id());
		if (onlineTarget != null) {
			onlineTarget.sendSystemMessage(
				Component.literal(
					"Hai ricevuto " +
						config.money(amount) +
						" dal tesoro. Nuovo saldo: " +
						config.money(newBalance)
				)
			);
		}
		return 1;
	}

	private int setBalance(
		CommandSourceStack source,
		String targetName,
		long amount
	) {
		if (store == null) return 0;
		Optional<NameAndId> resolved = resolve(targetName);
		if (resolved.isEmpty()) {
			source.sendFailure(
				Component.literal("Giocatore non trovato: " + targetName + ".")
			);
			return 0;
		}
		NameAndId target = resolved.get();
		String resolvedTargetName = nameOf(target);
		store.ensureAccount(target.id(), resolvedTargetName);
		long newBalance = store.setBalance(target.id(), amount);
		store.log(
			"SET",
			source.getPlayer() == null ? null : source.getPlayer().getUUID(),
			source.getTextName(),
			target.id(),
			resolvedTargetName,
			amount,
			newBalance,
			0L
		);
		persistMaybe();

		source.sendSuccess(
			() ->
				Component.literal(
					"Saldo di " +
						resolvedTargetName +
						" impostato a " +
						config.money(newBalance) +
						"."
				),
			true
		);
		ServerPlayer onlineTarget = server.getPlayerList().getPlayer(target.id());
		if (onlineTarget != null) {
			onlineTarget.sendSystemMessage(
				Component.literal(
					"Il tuo saldo è stato impostato a " +
						config.money(newBalance) +
						"."
				)
			);
		}
		return 1;
	}

	private int takeBalance(
		CommandSourceStack source,
		String targetName,
		long amount
	) {
		if (store == null) return 0;
		Optional<NameAndId> resolved = resolve(targetName);
		if (resolved.isEmpty()) {
			source.sendFailure(
				Component.literal("Giocatore non trovato: " + targetName + ".")
			);
			return 0;
		}
		NameAndId target = resolved.get();
		String resolvedTargetName = nameOf(target);
		store.ensureAccount(target.id(), resolvedTargetName);
		long currentBalance = store.balance(target.id());
		if (currentBalance < amount) {
			source.sendFailure(
				Component.literal(
					"Fondi insufficienti. Saldo di " +
						resolvedTargetName +
						": " +
						config.money(currentBalance)
				)
			);
			return 0;
		}

		long newBalance = store.debit(target.id(), amount);
		store.log(
			"TAKE",
			source.getPlayer() == null ? null : source.getPlayer().getUUID(),
			source.getTextName(),
			target.id(),
			resolvedTargetName,
			amount,
			newBalance,
			0L
		);
		persistMaybe();

		source.sendSuccess(
			() ->
				Component.literal(
					"Rimossi " +
						config.money(amount) +
						" a " +
						resolvedTargetName +
						". Nuovo saldo: " +
						config.money(newBalance)
				),
			true
		);
		ServerPlayer onlineTarget = server.getPlayerList().getPlayer(target.id());
		if (onlineTarget != null) {
			onlineTarget.sendSystemMessage(
				Component.literal(
					"Sono stati rimossi " +
						config.money(amount) +
						" dal tuo saldo. Nuovo saldo: " +
						config.money(newBalance)
				)
			);
		}
		return 1;
	}

	private int showTransactions(
		CommandSourceStack source,
		String playerName,
		int requestedLimit
	) {
		if (store == null) return 0;
		int limit = requestedLimit > 0
			? Math.min(requestedLimit, config.transactionLogLimit)
			: Math.min(TRANSACTIONS_DEFAULT_LIMIT, config.transactionLogLimit);

		UUID filterId = null;
		String filterName = null;
		if (playerName != null) {
			Optional<NameAndId> resolved = resolve(playerName);
			if (resolved.isPresent()) {
				filterId = resolved.get().id();
				filterName = nameOf(resolved.get());
			} else {
				filterName = playerName;
			}
		}

		List<EconomyStore.Transaction> selected = new ArrayList<>();
		for (EconomyStore.Transaction transaction : store.transactions()) {
			if (
				playerName != null && !transaction.involves(filterId, filterName)
			) {
				continue;
			}
			selected.add(transaction);
			if (selected.size() >= limit) break;
		}

		String scope = playerName == null ? "" : " di " + playerName;
		source.sendSuccess(
			() ->
				Component.literal(
					"Ultime " + selected.size() + " transazioni" + scope + ":"
				),
			false
		);
		if (selected.isEmpty()) {
			source.sendSuccess(
				() -> Component.literal("Nessuna transazione trovata."),
				false
			);
			return 1;
		}
		for (EconomyStore.Transaction transaction : selected) {
			String line = describe(transaction);
			source.sendSuccess(() -> Component.literal(line), false);
		}
		return 1;
	}

	private String describe(EconomyStore.Transaction transaction) {
		String when = TIME_FORMAT.format(Instant.ofEpochMilli(transaction.time()));
		String actor = transaction.actorName() != null
			? transaction.actorName()
			: transaction.actor() != null ? transaction.actor() : "server";
		String target = transaction.targetName() != null
			? transaction.targetName()
			: transaction.target() != null ? transaction.target() : "?";
		return switch (transaction.type()) {
			case "SEND" -> when + " SEND " + actor + " -> " + target + ": " +
				config.money(transaction.amount()) +
				(transaction.fee() > 0L ? " (tassa: " + config.money(transaction.fee()) + ")" : "") +
				" (saldo: " + config.money(transaction.balanceAfter()) + ")";
			case "MINT" -> when + " MINT -> " + target + ": " +
				config.money(transaction.amount()) + " (saldo: " +
				config.money(transaction.balanceAfter()) + ")";
			case "SET" -> when + " SET " + actor + " -> " + target + ": " +
				config.money(transaction.amount()) + " (saldo: " +
				config.money(transaction.balanceAfter()) + ")";
			case "TAKE" -> when + " TAKE " + actor + " -> " + target + ": " +
				config.money(transaction.amount()) + " (saldo: " +
				config.money(transaction.balanceAfter()) + ")";
			case "REGULATE" -> when + " REGULATE -> Economia: " +
				config.money(transaction.amount()) + " (totale: " +
				config.money(transaction.balanceAfter()) + ")";
			case "TREASURY" -> when + " TREASURY -> " + target + ": " +
				config.money(transaction.amount()) + " (saldo: " +
				config.money(transaction.balanceAfter()) + ")";
			default -> when + " " + transaction.type() + " " + actor + " -> " +
				target + ": " + config.money(transaction.amount());
		};
	}

	/* ---------- utilità ---------- */

	private boolean isAdmin(CommandSourceStack source) {
		return config != null && config.isAdmin(source);
	}

	private Optional<NameAndId> resolve(String name) {
		if (server == null || name == null || name.isBlank()) {
			return Optional.empty();
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.getGameProfile().name().equalsIgnoreCase(name)) {
				return Optional.of(new NameAndId(player.getGameProfile()));
			}
		}
		return server.services().nameToIdCache().get(name);
	}

	private static String nameOf(NameAndId player) {
		return player.name() == null ? player.id().toString() : player.name();
	}
}
