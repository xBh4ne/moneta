package dev.moneta;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MonetaMod implements ModInitializer {

	public static final Logger LOG = LoggerFactory.getLogger("moneta");
	public static final String OBJECTIVE = "saldo";

	private final Map<UUID, Long> balances = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private Path dataPath;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::stop);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			UUID id = handler.player.getUUID();
			names.put(id, handler.player.getGameProfile().name());
			LOG.info(
				"Moneta: {} joined, updating display",
				handler.player.getGameProfile().name()
			);
			// Delay: send after the client has finished its login sequence
			server.execute(() -> showMoney(handler.player));
		});

		CommandRegistrationCallback.EVENT.register(
			(dispatcher, registries, environment) -> {
				dispatcher.register(
					Commands.literal("balance").executes(ctx ->
						showBalance(ctx.getSource())
					)
				);
				dispatcher.register(
					Commands.literal("bal").executes(ctx ->
						showBalance(ctx.getSource())
					)
				);
				dispatcher.register(
					Commands.literal("money").executes(ctx ->
						showBalance(ctx.getSource())
					)
				);

				dispatcher.register(
					Commands.literal("send").then(
						Commands.argument(
							"amount",
							IntegerArgumentType.integer(1)
						).then(
							Commands.argument(
								"player",
								EntityArgument.player()
							).executes(ctx ->
								sendMoney(
									ctx.getSource(),
									IntegerArgumentType.getInteger(
										ctx,
										"amount"
									),
									EntityArgument.getPlayer(ctx, "player")
								)
							)
						)
					)
				);

				dispatcher.register(
					Commands.literal("mint")
						.requires(src ->
							src
								.permissions()
								.hasPermission(Permissions.COMMANDS_GAMEMASTER)
						)
						.then(
							Commands.argument(
								"amount",
								IntegerArgumentType.integer(1)
							).executes(ctx ->
								mint(
									ctx.getSource(),
									IntegerArgumentType.getInteger(
										ctx,
										"amount"
									)
								)
							)
						)
				);

				dispatcher.register(
					Commands.literal("economy").executes(ctx ->
						economy(ctx.getSource())
					)
				);
				dispatcher.register(
					Commands.literal("moneyinfo").executes(ctx ->
						economy(ctx.getSource())
					)
				);
				dispatcher.register(
					Commands.literal("balancetop").executes(ctx ->
						economy(ctx.getSource())
					)
				);
			}
		);
	}

	private void start(MinecraftServer server) {
		dataPath = server
			.getWorldPath(LevelResource.ROOT)
			.resolve("moneta")
			.resolve("balances.json");
		try {
			Files.createDirectories(dataPath.getParent());
		} catch (Exception e) {
			LOG.error("Moneta: could not create data dir: {}", e.toString());
		}
		load();
		LOG.info("Moneta ready. Total in circulation: {}", totalMoney());
	}

	private void stop(MinecraftServer server) {
		save();
	}

	/* ---------- data ---------- */

	private long getBalance(UUID id) {
		return balances.getOrDefault(id, 0L);
	}

	private void setBalance(UUID id, long value, String name) {
		balances.put(id, Math.max(0L, value));
		if (name != null) names.put(id, name);
	}

	private void setBalance(UUID id, long value) {
		setBalance(id, value, null);
	}

	private long totalMoney() {
		long t = 0;
		for (long v : balances.values()) t += v;
		return t;
	}

	private void load() {
		if (dataPath == null || !Files.exists(dataPath)) return;
		try (Reader r = Files.newBufferedReader(dataPath)) {
			JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
			if (!root.has("players")) return;
			JsonObject players = root.getAsJsonObject("players");
			for (String key : players.keySet()) {
				UUID id;
				try {
					id = UUID.fromString(key);
				} catch (IllegalArgumentException e) {
					continue;
				}
				JsonObject po = players.getAsJsonObject(key);
				long bal = po.has("balance")
					? po.get("balance").getAsLong()
					: 0L;
				balances.put(id, bal);
				if (po.has("name")) names.put(id, po.get("name").getAsString());
			}
		} catch (Exception e) {
			LOG.error("Moneta: failed to load balances: {}", e.toString());
		}
	}

	private void save() {
		if (dataPath == null) return;
		try {
			Files.createDirectories(dataPath.getParent());
			JsonObject root = new JsonObject();
			JsonObject players = new JsonObject();
			for (UUID id : balances.keySet()) {
				JsonObject po = new JsonObject();
				po.addProperty("balance", balances.get(id));
				String n = names.get(id);
				if (n != null) po.addProperty("name", n);
				players.add(id.toString(), po);
			}
			root.add("players", players);
			try (Writer w = Files.newBufferedWriter(dataPath)) {
				gson.toJson(root, w);
			}
		} catch (Exception e) {
			LOG.error("Moneta: failed to save balances: {}", e.toString());
		}
	}

	/* ---------- scoreboard display (top-right) ---------- */

	private void showMoney(ServerPlayer p) {
		LOG.info("showMoney called for {}", p.getGameProfile().name());
		String objName = OBJECTIVE;
		net.minecraft.world.scores.Scoreboard dummy =
			new net.minecraft.world.scores.Scoreboard();
		net.minecraft.world.scores.Objective obj =
			new net.minecraft.world.scores.Objective(
				dummy,
				objName,
				net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY,
				Component.literal(" "),
				net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER,
				false,
				null
			);
		// Remove any stale objective first, then re-add
		p.connection.send(
			new net.minecraft.network.protocol.game.ClientboundSetObjectivePacket(
				obj,
				net.minecraft.network.protocol.game.ClientboundSetObjectivePacket.METHOD_REMOVE
			)
		);
		p.connection.send(
			new net.minecraft.network.protocol.game.ClientboundSetObjectivePacket(
				obj,
				net.minecraft.network.protocol.game.ClientboundSetObjectivePacket.METHOD_ADD
			)
		);
		p.connection.send(
			new net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket(
				net.minecraft.world.scores.DisplaySlot.SIDEBAR, obj
			)
		);
		long bal = getBalance(p.getUUID());
		int score = (int) Math.min(bal, Integer.MAX_VALUE);
		p.connection.send(
			new net.minecraft.network.protocol.game.ClientboundSetScorePacket(
				" ",
				objName,
				score,
				java.util.Optional.empty(),
				java.util.Optional.of(
					new net.minecraft.network.chat.numbers.FixedFormat(
						net.minecraft.network.chat.Component.literal("$" + bal)
							.withStyle(net.minecraft.ChatFormatting.GREEN)
					)
				)
			)
		);
		names.put(p.getUUID(), p.getGameProfile().name());
	}

	/* ---------- commands ---------- */

	private int showBalance(CommandSourceStack src) {
		ServerPlayer p = src.getPlayer();
		if (p == null) {
			src.sendFailure(
				Component.literal(
					"Solo i giocatori possono usare questo comando."
				)
			);
			return 0;
		}
		names.put(p.getUUID(), p.getGameProfile().name());
		long bal = getBalance(p.getUUID());
		src.sendSuccess(() -> Component.literal("Il tuo saldo: " + bal), false);
		return 1;
	}

	private int sendMoney(
		CommandSourceStack src,
		int amount,
		ServerPlayer target
	) {
		ServerPlayer sender = src.getPlayer();
		if (sender == null) {
			src.sendFailure(
				Component.literal("Solo i giocatori possono inviare denaro.")
			);
			return 0;
		}
		if (amount <= 0) {
			src.sendFailure(
				Component.literal("L'importo deve essere maggiore di zero.")
			);
			return 0;
		}
		if (target == null || !target.isAlive()) {
			src.sendFailure(Component.literal("Giocatore non trovato."));
			return 0;
		}
		if (sender.getUUID().equals(target.getUUID())) {
			src.sendFailure(
				Component.literal("Non puoi inviare denaro a te stesso.")
			);
			return 0;
		}
		long senderBal = getBalance(sender.getUUID());
		if (senderBal < amount) {
			src.sendFailure(
				Component.literal("Fondi insufficienti. Saldo: " + senderBal)
			);
			return 0;
		}
		names.put(sender.getUUID(), sender.getGameProfile().name());
		names.put(target.getUUID(), target.getGameProfile().name());
		setBalance(sender.getUUID(), senderBal - amount);
		setBalance(target.getUUID(), getBalance(target.getUUID()) + amount);
		save();
		showMoney(sender);
		showMoney(target);
		src.sendSuccess(
			() ->
				Component.literal(
					"Inviati " +
						amount +
						" a " +
						target.getGameProfile().name() +
						". Nuovo saldo: " +
						getBalance(sender.getUUID())
				),
			false
		);
		target.sendSystemMessage(
			Component.literal(
				sender.getGameProfile().name() +
					" ti ha inviato " +
					amount +
					". Nuovo saldo: " +
					getBalance(target.getUUID())
			)
		);
		return 1;
	}

	private int mint(CommandSourceStack src, int amount) {
		MinecraftServer server = src.getServer();
		List<ServerPlayer> players = new ArrayList<>(
			server.getPlayerList().getPlayers()
		);
		if (players.isEmpty()) {
			src.sendFailure(
				Component.literal(
					"Nessun giocatore online per distribuire il denaro."
				)
			);
			return 0;
		}
		if (amount <= 0) {
			src.sendFailure(
				Component.literal("L'importo deve essere maggiore di zero.")
			);
			return 0;
		}
		long share = amount / players.size();
		long remainder = amount % players.size();
		for (int i = 0; i < players.size(); i++) {
			ServerPlayer p = players.get(i);
			long add = share + (i < remainder ? 1 : 0);
			names.put(p.getUUID(), p.getGameProfile().name());
			setBalance(p.getUUID(), getBalance(p.getUUID()) + add);
		}
		save();
		for (ServerPlayer p : players) showMoney(p);
		src.sendSuccess(
			() ->
				Component.literal(
					"Zecca: messi in circolazione " +
						amount +
						" (totale ora: " +
						totalMoney() +
						"), distribuiti a " +
						players.size() +
						" giocatori."
				),
			true
		);
		for (ServerPlayer p : players) {
			p.sendSystemMessage(
				Component.literal(
					"La zecca ha distribuito denaro. Il tuo saldo: " +
						getBalance(p.getUUID())
				)
			);
		}
		return 1;
	}

	private int economy(CommandSourceStack src) {
		MinecraftServer server = src.getServer();
		long total = totalMoney();
		List<Map.Entry<UUID, Long>> entries = new ArrayList<>(
			balances.entrySet()
		);
		entries.sort(
			Comparator.comparingLong((Map.Entry<UUID, Long> e) ->
				e.getValue()
			).reversed()
		);
		src.sendSuccess(
			() -> Component.literal("Totale in circolazione: " + total),
			false
		);
		if (total <= 0) {
			src.sendSuccess(
				() -> Component.literal("Nessun denaro in circolazione."),
				false
			);
			return 1;
		}
		for (Map.Entry<UUID, Long> e : entries) {
			if (e.getValue() <= 0) continue;
			String name = names.getOrDefault(e.getKey(), e.getKey().toString());
			double pct = (e.getValue() * 100.0) / total;
			String line =
				name +
				": " +
				e.getValue() +
				" (" +
				String.format("%.2f", pct) +
				"%)";
			src.sendSuccess(() -> Component.literal(line), false);
		}
		return 1;
	}
}
