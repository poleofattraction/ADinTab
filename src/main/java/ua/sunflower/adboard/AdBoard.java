package ua.sunflower.adboard;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class AdBoard extends JavaPlugin implements CommandExecutor {

    record Ad(String id, String authorName, String text, long expires) {}

    private final List<Ad> ads = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> lastPurchase = new ConcurrentHashMap<>();

    private Economy economy;
    private File dataFile;

    private double price;
    private String currency, emptyText;
    private long cooldownMs, durationMs, rotateMs;
    private int maxLen, maxAds, marqueeWidth;
    private long stepMs;
    private static final String SEP = "  \u2022  ";
    private List<String> blocked;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("Vault-економіку (Banco) не знайдено! Вимикаю плагін.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = rsp.getProvider();
        getLogger().info("Економіка: " + economy.getName());

        dataFile = new File(getDataFolder(), "data.yml");
        loadSettings();
        loadData();

        getCommand("ad").setExecutor(this);
        new Expansion().register();
    }

    private void loadSettings() {
        reloadConfig();
        price = getConfig().getDouble("price", 30);
        currency = getConfig().getString("currency-name", "діамантів");
        cooldownMs = getConfig().getLong("cooldown-minutes", 60) * 60_000L;
        durationMs = getConfig().getLong("duration-minutes", 10) * 60_000L;
        rotateMs = Math.max(1, getConfig().getLong("rotate-seconds", 8)) * 1000L;
        maxLen = getConfig().getInt("max-length", 40);
        marqueeWidth = Math.max(5, getConfig().getInt("marquee-width", 22));
        stepMs = Math.max(100, getConfig().getLong("marquee-step-ms", 300));
        maxAds = getConfig().getInt("max-active-ads", 10);
        emptyText = getConfig().getString("empty-text", "Ваше оголошення: /ad");
        blocked = getConfig().getStringList("blocked-words").stream()
                .map(String::toLowerCase).toList();
    }

    // ---------- збереження у файл ----------

    private void loadData() {
        ads.clear();
        lastPurchase.clear();
        if (!dataFile.exists()) return;

        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        long now = System.currentTimeMillis();

        ConfigurationSection adSec = y.getConfigurationSection("ads");
        if (adSec != null) {
            for (String id : adSec.getKeys(false)) {
                long exp = adSec.getLong(id + ".expires");
                if (exp <= now) continue;
                ads.add(new Ad(id, adSec.getString(id + ".author", "?"),
                        adSec.getString(id + ".text", ""), exp));
            }
        }
        ConfigurationSection cdSec = y.getConfigurationSection("cooldowns");
        if (cdSec != null) {
            for (String uuid : cdSec.getKeys(false)) {
                long t = cdSec.getLong(uuid);
                if (now - t < cooldownMs) {
                    try { lastPurchase.put(UUID.fromString(uuid), t); } catch (IllegalArgumentException ignored) {}
                }
            }
        }
    }

    private synchronized void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Ad ad : ads) {
            y.set("ads." + ad.id() + ".author", ad.authorName());
            y.set("ads." + ad.id() + ".text", ad.text());
            y.set("ads." + ad.id() + ".expires", ad.expires());
        }
        long now = System.currentTimeMillis();
        lastPurchase.forEach((u, t) -> {
            if (now - t < cooldownMs) y.set("cooldowns." + u, t);
        });
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Не вдалося зберегти data.yml: " + e.getMessage());
        }
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        if (ads.removeIf(a -> a.expires() <= now)) saveData();
    }

    // ---------- команда ----------

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("admin")) {
            return admin(s, a);
        }
        if (!(s instanceof Player p)) {
            s.sendMessage("Тільки для гравців.");
            return true;
        }
        if (!p.hasPermission("adboard.use")) {
            p.sendMessage("§cНемає прав.");
            return true;
        }
        if (a.length == 0) {
            p.sendMessage("§eВикористання: §f/ad <текст>");
            p.sendMessage("§7Ціна: §f" + fmt(price) + " " + currency + "§7, висить " + (durationMs / 60_000) + " хв, кулдаун " + (cooldownMs / 60_000) + " хв.");
            return true;
        }

        // кулдаун
        if (!p.hasPermission("adboard.bypass")) {
            Long last = lastPurchase.get(p.getUniqueId());
            if (last != null) {
                long left = cooldownMs - (System.currentTimeMillis() - last);
                if (left > 0) {
                    p.sendMessage("§cНаступне оголошення можна купити через " + formatTime(left) + ".");
                    return true;
                }
            }
        }

        // текст: прибираємо кольори, MiniMessage-теги та %, щоб не ламали TAB/PAPI
        String text = String.join(" ", a).replaceAll("[<>&§%]", "").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            p.sendMessage("§cПорожній текст.");
            return true;
        }
        if (text.length() > maxLen) {
            p.sendMessage("§cМаксимум " + maxLen + " символів (у вас " + text.length() + ").");
            return true;
        }
        String lower = text.toLowerCase();
        for (String w : blocked) {
            if (!w.isBlank() && lower.contains(w)) {
                p.sendMessage("§cТекст містить заборонені слова.");
                return true;
            }
        }

        cleanExpired();
        if (ads.size() >= maxAds) {
            p.sendMessage("§cДошка заповнена, спробуйте пізніше.");
            return true;
        }

        // оплата через Vault (Banco)
        if (!economy.has(p, price)) {
            p.sendMessage("§cПотрібно " + fmt(price) + " " + currency + ".");
            return true;
        }
        EconomyResponse r = economy.withdrawPlayer(p, price);
        if (!r.transactionSuccess()) {
            p.sendMessage("§cНе вдалося списати кошти: " + r.errorMessage);
            return true;
        }

        long now = System.currentTimeMillis();
        ads.add(new Ad(UUID.randomUUID().toString().substring(0, 8), p.getName(), text, now + durationMs));
        lastPurchase.put(p.getUniqueId(), now);
        saveData();

        p.sendMessage("§aОголошення розміщено на " + (durationMs / 60_000) + " хв! Списано " + fmt(price) + " " + currency + ".");
        return true;
    }

    private boolean admin(CommandSender s, String[] a) {
        if (!s.hasPermission("adboard.admin")) {
            s.sendMessage("§cНемає прав.");
            return true;
        }
        if (a.length < 2) {
            s.sendMessage("§e/ad admin list | remove <нік> | reset <нік> | reload");
            return true;
        }
        switch (a[1].toLowerCase()) {
            case "list" -> {
                cleanExpired();
                if (ads.isEmpty()) s.sendMessage("§7Активних оголошень немає.");
                for (Ad ad : ads) {
                    s.sendMessage("§e" + ad.authorName() + "§7: §f" + ad.text()
                            + " §8(ще " + formatTime(ad.expires() - System.currentTimeMillis()) + ")");
                }
            }
            case "remove" -> {
                if (a.length < 3) { s.sendMessage("§c/ad admin remove <нік>"); return true; }
                boolean removed = ads.removeIf(ad -> ad.authorName().equalsIgnoreCase(a[2]));
                saveData();
                s.sendMessage(removed ? "§aВидалено." : "§cОголошень цього гравця немає.");
            }
            case "reset" -> {
                if (a.length < 3) { s.sendMessage("§c/ad admin reset <нік>"); return true; }
                OfflinePlayer op = getServer().getOfflinePlayer(a[2]);
                lastPurchase.remove(op.getUniqueId());
                saveData();
                s.sendMessage("§aКулдаун скинуто.");
            }
            case "reload" -> {
                loadSettings();
                loadData();
                s.sendMessage("§aПерезавантажено.");
            }
            default -> s.sendMessage("§e/ad admin list | remove <нік> | reset <нік> | reload");
        }
        return true;
    }

    private String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private String formatTime(long ms) {
        long sec = Math.max(0, ms / 1000);
        long m = sec / 60, h = m / 60;
        if (h > 0) return h + " год " + (m % 60) + " хв";
        if (m > 0) return m + " хв " + (sec % 60) + " с";
        return sec + " с";
    }

    // ---------- PlaceholderAPI ----------

    class Expansion extends PlaceholderExpansion {
        public String getIdentifier() { return "adboard"; }
        public String getAuthor() { return "sunflower"; }
        public String getVersion() { return "1.1"; }
        public boolean persist() { return true; }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            long now = System.currentTimeMillis();
            List<Ad> active = ads.stream().filter(ad -> ad.expires() > now).toList();

            if (params.equals("count")) return String.valueOf(active.size());

            if (active.isEmpty()) {
                return params.equals("author") ? "" : emptyText;
            }

            // кожне оголошення показується rotateMs, а довге - достатньо, щоб проїхати повне коло
            long[] dur = new long[active.size()];
            long total = 0;
            for (int i = 0; i < dur.length; i++) {
                dur[i] = displayMs(active.get(i));
                total += dur[i];
            }
            long t = now % total;
            int idx = 0;
            while (t >= dur[idx]) { t -= dur[idx]; idx++; }
            Ad ad = active.get(idx);

            return switch (params) {
                case "text" -> ad.text();
                case "author" -> ad.authorName();
                case "current" -> ad.text() + " \u00a77(" + ad.authorName() + ")";
                case "marquee" -> marquee(ad.text(), t);
                default -> null;
            };
        }

        private long displayMs(Ad ad) {
            int len = ad.text().codePointCount(0, ad.text().length());
            if (len <= marqueeWidth) return rotateMs;
            return Math.max(rotateMs, (len + SEP.length()) * stepMs);
        }

        private String marquee(String text, long elapsedMs) {
            int[] cps = text.codePoints().toArray();
            if (cps.length <= marqueeWidth) return text;
            int[] loop = (text + SEP).codePoints().toArray();
            int pos = (int) ((elapsedMs / stepMs) % loop.length);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < marqueeWidth; i++) {
                sb.appendCodePoint(loop[(pos + i) % loop.length]);
            }
            return sb.toString();
        }
    }
}
