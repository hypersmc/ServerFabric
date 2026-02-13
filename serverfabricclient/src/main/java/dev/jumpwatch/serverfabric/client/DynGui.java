package dev.jumpwatch.serverfabric.client;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public final class DynGui implements Listener {

    private final DynClientPlugin plugin;
    private CommandInputManager commandInput;

    // per-player data
    private final Map<UUID, DynStatus> status = new HashMap<>();
    private final Map<UUID, Integer> page = new HashMap<>();

    private final Map<UUID, DynTemplates> templates = new HashMap<>();
    private final Map<UUID, Mode> mode = new HashMap<>();

    private enum Mode { INSTANCES, TEMPLATES }

    private static final String TITLE = "ServerFabric";

    public DynGui(DynClientPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        page.putIfAbsent(p.getUniqueId(), 0);
        mode.put(p.getUniqueId(), Mode.INSTANCES);
        Inventory inv = Bukkit.createInventory(null, 54, TITLE);
        p.openInventory(inv);
        render(p);
    }

    public void setStatus(Player p, DynStatus newStatus) {
        status.put(p.getUniqueId(), newStatus);
        render(p);
    }

    public void setTemplates(Player p, DynTemplates t) {
        templates.put(p.getUniqueId(), t);
        render(p);
    }

    private void render(Player p) {
        Inventory inv = p.getOpenInventory().getTopInventory();
        if (inv == null) return;
        if (!TITLE.equals(p.getOpenInventory().getTitle())) return;

        inv.clear();

        int pg = page.getOrDefault(p.getUniqueId(), 0);
        DynStatus st = status.getOrDefault(p.getUniqueId(), new DynStatus(List.of()));

        // header buttons
        inv.setItem(45, button(Material.ARROW, "§ePrev"));
        inv.setItem(49, button(Material.NETHER_STAR, "§bRefresh"));
        inv.setItem(47, button(Material.BOOK, "§bTemplates"));
        inv.setItem(53, button(Material.ARROW, "§eNext"));
        inv.setItem(51, button(Material.COMPASS, "§aInstances"));
        // instances grid slots 0..44 (45 slots)
        Mode m = mode.getOrDefault(p.getUniqueId(), Mode.INSTANCES);
        if (m == Mode.TEMPLATES) {
            renderTemplates(p, inv);
            return;
        }
        renderInstances(p, inv, pg, st);

    }

    private void renderInstances(Player p, Inventory inv, int pg, DynStatus st) {
        int start = pg * 45;
        List<DynStatus.Instance> list = st.instances();
        for (int i = 0; i < 45; i++) {
            int idx = start + i;
            if (idx >= list.size()) break;
            DynStatus.Instance inst = list.get(idx);

            Material mat = switch (inst.state().toUpperCase()) {
                case "RUNNING" -> Material.LIME_WOOL;
                case "STARTING" -> Material.YELLOW_WOOL;
                case "CRASHED" -> Material.RED_WOOL;
                default -> Material.GRAY_WOOL;
            };

            ItemStack it = new ItemStack(mat);
            ItemMeta meta = it.getItemMeta();
            meta.setDisplayName("§f" + inst.name());
            meta.setLore(List.of(
                    "§7Host: §f" + inst.hostId(),
                    "§7Port: §f" + inst.port(),
                    "§7State: §f" + inst.state(),
                    "",
                    "§aLeft-click: Join",
                    "§eRight-click: Start/Stop",
                    "§bShift-left: Send command"
            ));
            it.setItemMeta(meta);
            inv.setItem(i, it);

        }
    }

    private ItemStack button(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!TITLE.equals(e.getView().getTitle())) return;

        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        // nav
        if (slot == 49) {
            plugin.messenger().requestStatus(p);
            p.sendMessage("§7Refreshing...");
            return;
        }
        if (slot == 45) {
            page.put(p.getUniqueId(), Math.max(0, page.getOrDefault(p.getUniqueId(), 0) - 1));
            render(p);
            return;
        }
        if (slot == 53) {
            page.put(p.getUniqueId(), page.getOrDefault(p.getUniqueId(), 0) + 1);
            render(p);
            return;
        }

        if (slot == 47) {
            mode.put(p.getUniqueId(), Mode.TEMPLATES);
            plugin.messenger().requestTemplates(p);
            p.sendMessage("§7Loading templates...");
            render(p);
            return;
        }
        if (slot == 51) {
            mode.put(p.getUniqueId(), Mode.INSTANCES);
            plugin.messenger().requestStatus(p);
            p.sendMessage("§7Loading instances...");
            render(p);
            return;
        }

        // instance click
        DynStatus st = status.getOrDefault(p.getUniqueId(), new DynStatus(List.of()));
        int pg = page.getOrDefault(p.getUniqueId(), 0);
        int idx = pg * 45 + slot;
        if (idx < 0 || idx >= st.instances().size()) return;

        DynStatus.Instance inst = st.instances().get(idx);
        Mode m = mode.getOrDefault(p.getUniqueId(), Mode.INSTANCES);
        if (m == Mode.TEMPLATES) {
            handleTemplateClick(p, slot, e);
            return;
        }
        boolean right = e.isRightClick();
        boolean shiftLeft = e.isLeftClick() && e.isShiftClick();
        if (shiftLeft) {
            if (commandInput == null) {
                p.sendMessage("§cCommand input not configured.");
                return;
            }
            p.closeInventory();
            commandInput.begin(p.getUniqueId(), inst.name());
            p.sendMessage("§bType a command in chat for §f" + inst.name() + "§b (without /).");
            p.sendMessage("§7Type §fcancel§7 to abort.");
            return;
        }
        if (!right) {
            plugin.messenger().connect(p, inst.name());
            return;
        }

        // Start/Stop toggle
        String s = inst.state().toUpperCase();
        if ("RUNNING".equals(s) || "STARTING".equals(s)) {
            plugin.messenger().sendAction(p, "STOP", inst.name(), "");
            p.sendMessage("§7Stopping " + inst.name() + "...");
        } else {
            plugin.messenger().sendAction(p, "START", inst.name(), "");
            p.sendMessage("§7Starting " + inst.name() + "...");
            new TimedTask(plugin, 10, () -> {
                plugin.messenger().requestStatus(p);
            });
        }
    }

    public void setCommandInput(CommandInputManager mgr) {
        this.commandInput = mgr;
    }

    private void renderTemplates(Player p, Inventory inv) {
        DynTemplates tp = templates.getOrDefault(p.getUniqueId(), new DynTemplates(List.of()));
        int pg = page.getOrDefault(p.getUniqueId(), 0);

        int start = pg * 45;
        List<DynTemplates.Item> list = tp.items();

        for (int i = 0; i < 45; i++) {
            int idx = start + i;
            if (idx >= list.size()) break;

            DynTemplates.Item item = list.get(idx);

            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta meta = it.getItemMeta();
            meta.setDisplayName("§f" + item.template());
            meta.setLore(List.of(
                    "§7Host: §f" + item.hostId(),
                    "",
                    "§aLeft-click: Play (create+start)"
            ));
            it.setItemMeta(meta);

            inv.setItem(i, it);
        }
    }
    private void handleTemplateClick(Player p, int slot, InventoryClickEvent e) {
        DynTemplates tp = templates.getOrDefault(p.getUniqueId(), new DynTemplates(List.of()));
        int pg = page.getOrDefault(p.getUniqueId(), 0);
        int idx = pg * 45 + slot;
        if (idx < 0 || idx >= tp.items().size()) return;

        DynTemplates.Item item = tp.items().get(idx);

        if (e.isLeftClick()) {
            // ACTION: PLAY_ON (instance=hostId, template=templateName)
            plugin.messenger().sendAction(p, "PLAY_ON", item.hostId(), item.template());
            p.sendMessage("§7Starting " + item.template() + " on " + item.hostId() + "...");
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!TITLE.equals(e.getView().getTitle())) return;
        // optional: cleanup per-player state
    }
}
