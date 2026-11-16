package dev.moneta;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;

public final class MonetaConfig {

	public String currencySymbol = "$";
	public long startingBalance = 0L;
	public String adminPermission = "gamemaster";
	public int economyTopLimit = 10;
	public int economyMaxLimit = 100;
	public int transactionLogLimit = 1000;
	public int autosaveSeconds = 30;

	/* Politica monetaria */
	public boolean regulationEnabled = true;
	public long targetBalancePerPlayer = 1000L;
	public int regulationIntervalSeconds = 300;
	public int regulationTolerancePercent = 10;
	public int regulationRatePercent = 2;
	public int transactionTaxPercent = 5;
	public long maxBalance = 100000L;
	public int mintCooldownSeconds = 60;
	public long mintDailyLimit = 100000L;

	/* Richieste di denaro */
	public boolean requestsEnabled = true;
	public int requestReminderIntervalSeconds = 300;
	public boolean requestReminderOnJoin = true;
	public int requestMaxPendingPerPayer = 10;

	private transient Permission adminPermissionValue =
		Permissions.COMMANDS_GAMEMASTER;

	public static MonetaConfig load(Path path, Logger log) {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		MonetaConfig config = new MonetaConfig();

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				MonetaConfig loaded = gson.fromJson(reader, MonetaConfig.class);
				if (loaded != null) config = loaded;
			} catch (Exception e) {
				log.error("Moneta: impossibile leggere la config: {}", e.toString());
			}
		}

		config.validate();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				gson.toJson(config, writer);
			}
		} catch (Exception e) {
			log.error("Moneta: impossibile scrivere la config: {}", e.toString());
		}
		return config;
	}

	public static void save(MonetaConfig config, Path path, Logger log) {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		config.validate();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				gson.toJson(config, writer);
			}
		} catch (Exception e) {
			log.error("Moneta: impossibile salvare la config: {}", e.toString());
		}
	}

	private void validate() {
		if (currencySymbol == null || currencySymbol.isBlank()) currencySymbol = "$";
		if (startingBalance < 0L) startingBalance = 0L;
		if (economyTopLimit < 1) economyTopLimit = 1;
		if (economyMaxLimit < economyTopLimit) economyMaxLimit = economyTopLimit;
		if (economyMaxLimit > 10_000) economyMaxLimit = 10_000;
		if (transactionLogLimit < 1) transactionLogLimit = 1;
		if (transactionLogLimit > 100_000) transactionLogLimit = 100_000;
		if (autosaveSeconds < 0) autosaveSeconds = 0;

		if (targetBalancePerPlayer < 0L) targetBalancePerPlayer = 0L;
		if (regulationIntervalSeconds < 30) regulationIntervalSeconds = 30;
		if (regulationTolerancePercent < 0) regulationTolerancePercent = 0;
		if (regulationTolerancePercent > 100) regulationTolerancePercent = 100;
		if (regulationRatePercent < 0) regulationRatePercent = 0;
		if (regulationRatePercent > 100) regulationRatePercent = 100;
		if (transactionTaxPercent < 0) transactionTaxPercent = 0;
		if (transactionTaxPercent > 100) transactionTaxPercent = 100;
		if (maxBalance < 0L) maxBalance = 0L;
		if (mintCooldownSeconds < 0) mintCooldownSeconds = 0;
		if (mintDailyLimit < 0L) mintDailyLimit = 0L;
		if (requestReminderIntervalSeconds < 0) {
			requestReminderIntervalSeconds = 0;
		}
		if (requestMaxPendingPerPayer < 0) requestMaxPendingPerPayer = 0;

		String permission = adminPermission == null
			? ""
			: adminPermission.toLowerCase(Locale.ROOT);
		adminPermission = switch (permission) {
			case "moderator" -> {
				adminPermissionValue = Permissions.COMMANDS_MODERATOR;
				yield "moderator";
			}
			case "admin" -> {
				adminPermissionValue = Permissions.COMMANDS_ADMIN;
				yield "admin";
			}
			case "owner" -> {
				adminPermissionValue = Permissions.COMMANDS_OWNER;
				yield "owner";
			}
			default -> {
				adminPermissionValue = Permissions.COMMANDS_GAMEMASTER;
				yield "gamemaster";
			}
		};
	}

	public Permission adminPermission() {
		return adminPermissionValue;
	}

	public boolean isAdmin(CommandSourceStack source) {
		return source.permissions().hasPermission(adminPermissionValue);
	}

	public String money(long amount) {
		return currencySymbol + amount;
	}
}
