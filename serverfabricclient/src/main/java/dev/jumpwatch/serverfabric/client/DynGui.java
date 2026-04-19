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

    private static final String TITLE = "ServerFabric";
    private static final int PAGE_SIZE = 45;

    private enum Screen {
        INSTANCES,
        INSTANCE_DETAILS,
        TEMPLATES,
        CONFIRM_ACTION
    }

    private record PendingAction(String action, String instanceName) {}

    // per-player cached data
    private final Map<UUID, DynStatus> status = new HashMap<>();
    private final Map<UUID, DynTemplates> templates = new HashMap<>();

    // per-player UI state
    private final Map<UUID, Integer> page = new HashMap<>();
    private final Map<UUID, Screen> screen = new HashMap<>();
    private final Map<UUID, String> selectedInstance = new HashMap<>();
    private final Map<UUID, PendingAction> pendingAction = new HashMap<>();

    public DynGui(DynClientPlugin plugin) {
        this.plugin = plugin;
    }

    public void setCommandInput(CommandInputManager mgr) {
        this.commandInput = mgr;
    }

    public void open(Player p) {
        page.putIfAbsent(p.getUniqueId(), 0);
        screen.put(p.getUniqueId(), Screen.INSTANCES);

        Inventory inv = Bukkit.createInventory(null, 54, TITLE);
        p.openInventory(inv);
        render(p);
    }

    public void setStatus(Player p, DynStatus newStatus) {
        status.put(p.getUniqueId(), newStatus);
        render(p);
    }

    public void setTemplates(Player p, DynTemplates newTemplates) {
        templates.put(p.getUniqueId(), newTemplates);
        render(p);
    }

    private void render(Player p) {
        Inventory inv = p.getOpenInventory().getTopInventory();
        if (inv == null) return;
        if (!TITLE.equals(p.getOpenInventory().getTitle())) return;

        inv.clear();

        Screen current = screen.getOrDefault(p.getUniqueId(), Screen.INSTANCES);
        switch (current) {
            case INSTANCES -> renderInstancesScreen(p, inv);
            case INSTANCE_DETAILS -> renderInstanceDetailsScreen(p, inv);
            case TEMPLATES -> renderTemplatesScreen(p, inv);
            case CONFIRM_ACTION -> renderConfirmActionScreen(p, inv);
        }
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private void renderInstancesScreen(Player p, Inventory inv) {
        int pg = page.getOrDefault(p.getUniqueId(), 0);
        DynStatus st = status.getOrDefault(p.getUniqueId(), new DynStatus(List.of()));

        // top / bottom navigation
        inv.setItem(45, button(Material.ARROW, "§ePrev"));
        inv.setItem(47, button(Material.BOOK, "§bTemplates"));
        inv.setItem(49, button(Material.NETHER_STAR, "§bRefresh"));
        inv.setItem(51, button(Material.COMPASS, "§aInstances"));
        inv.setItem(53, button(Material.ARROW, "§eNext"));

        inv.setItem(46, button(Material.PAPER, "§7Page §f" + (pg + 1)));
        inv.setItem(48, button(Material.LIME_DYE, "§aRunning: §f" + countState(st, "RUNNING")));
        inv.setItem(50, button(Material.RED_DYE, "§cProblems: §f" + (countState(st, "CRASHED") + countState(st, "START_TIMEOUT") + countState(st, "BROKEN"))));

        int start = pg * PAGE_SIZE;
        List<DynStatus.Instance> list = st.instances();

        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= list.size()) break;

            DynStatus.Instance inst = list.get(idx);

            Material mat = switch (inst.state().toUpperCase()) {
                case "RUNNING" -> Material.LIME_WOOL;
                case "STARTING" -> Material.YELLOW_WOOL;
                case "STOPPING" -> Material.ORANGE_WOOL;
                case "CRASHED", "START_TIMEOUT" -> Material.RED_WOOL;
                case "BROKEN" -> Material.BLACK_WOOL;
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
                    "§aLeft-click: Open details",
                    "§7Shift-left: Join",
                    "§eRight-click: Start/Stop",
                    "§cShift-right: Kill"
            ));
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }
    }

    private void renderInstanceDetailsScreen(Player p, Inventory inv) {
        String instanceName = selectedInstance.get(p.getUniqueId());
        if (instanceName == null) {
            screen.put(p.getUniqueId(), Screen.INSTANCES);
            render(p);
            return;
        }

        DynStatus.Instance inst = findInstance(p, instanceName);
        if (inst == null) {
            inv.setItem(22, button(Material.BARRIER, "§cInstance not found"));
            inv.setItem(45, button(Material.ARROW, "§eBack"));
            inv.setItem(49, button(Material.NETHER_STAR, "§bRefresh"));
            return;
        }

        inv.setItem(45, button(Material.ARROW, "§eBack"));
        inv.setItem(49, button(Material.NETHER_STAR, "§bRefresh"));

        Material statusMat = switch (inst.state().toUpperCase()) {
            case "RUNNING" -> Material.LIME_WOOL;
            case "STARTING" -> Material.YELLOW_WOOL;
            case "STOPPING" -> Material.ORANGE_WOOL;
            case "CRASHED", "START_TIMEOUT" -> Material.RED_WOOL;
            case "BROKEN" -> Material.BLACK_WOOL;
            default -> Material.GRAY_WOOL;
        };

        inv.setItem(4, button(statusMat, "§f" + inst.name(), List.of(
                "§7State: §f" + inst.state(),
                "§7Host: §f" + inst.hostId(),
                "§7Port: §f" + inst.port()
        )));

        // info row
        inv.setItem(10, button(Material.OAK_SIGN, "§7Host: §f" + inst.hostId()));
        inv.setItem(11, button(Material.REPEATER, "§7Port: §f" + inst.port()));
        inv.setItem(12, button(Material.CLOCK, "§7State: §f" + inst.state()));

        // placeholders for future runtime/stats integration
        inv.setItem(13, button(Material.NAME_TAG, "§7Uptime: §fSoon"));
        inv.setItem(14, button(Material.REDSTONE, "§7PID: §fSoon"));
        inv.setItem(15, button(Material.CHEST, "§7RAM: §fSoon"));
        inv.setItem(16, button(Material.BARREL, "§7Disk: §fSoon"));

        // actions
        inv.setItem(28, button(Material.ENDER_PEARL, "§aJoin"));
        inv.setItem(29, button(Material.LIME_DYE, "§aStart"));
        inv.setItem(30, button(Material.RED_DYE, "§cStop"));
        inv.setItem(31, button(Material.CLOCK, "§eRestart"));
        inv.setItem(32, button(Material.TNT, "§cKill"));
        inv.setItem(33, button(Material.WRITABLE_BOOK, "§bSend Command"));
    }

    private void renderTemplatesScreen(Player p, Inventory inv) {
        int pg = page.getOrDefault(p.getUniqueId(), 0);
        DynTemplates tp = templates.getOrDefault(p.getUniqueId(), new DynTemplates(List.of()));

        inv.setItem(45, button(Material.ARROW, "§ePrev"));
        inv.setItem(47, button(Material.BOOK, "§bTemplates"));
        inv.setItem(49, button(Material.NETHER_STAR, "§bRefresh"));
        inv.setItem(51, button(Material.COMPASS, "§aInstances"));
        inv.setItem(53, button(Material.ARROW, "§eNext"));

        inv.setItem(46, button(Material.PAPER, "§7Page §f" + (pg + 1)));

        int start = pg * PAGE_SIZE;
        List<DynTemplates.Item> list = tp.items();

        for (int i = 0; i < PAGE_SIZE; i++) {
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

    private void renderConfirmActionScreen(Player p, Inventory inv) {
        PendingAction pa = pendingAction.get(p.getUniqueId());
        if (pa == null) {
            screen.put(p.getUniqueId(), Screen.INSTANCES);
            render(p);
            return;
        }

        inv.setItem(11, button(Material.TNT, "§cConfirm " + pa.action(), List.of(
                "§7Target: §f" + pa.instanceName(),
                "",
                "§cThis action is destructive."
        )));
        inv.setItem(13, button(Material.LIME_WOOL, "§aConfirm"));
        inv.setItem(15, button(Material.RED_WOOL, "§cCancel"));
    }

    // -------------------------------------------------------------------------
    // Click handling
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!TITLE.equals(e.getView().getTitle())) return;

        e.setCancelled(true);

        int rawSlot = e.getRawSlot();
        if (rawSlot < 0 || rawSlot >= 54) return;

        Screen current = screen.getOrDefault(p.getUniqueId(), Screen.INSTANCES);
        switch (current) {
            case INSTANCES -> handleInstancesClick(p, rawSlot, e);
            case INSTANCE_DETAILS -> handleInstanceDetailsClick(p, rawSlot, e);
            case TEMPLATES -> handleTemplatesClick(p, rawSlot, e);
            case CONFIRM_ACTION -> handleConfirmClick(p, rawSlot);
        }
    }

    private void handleInstancesClick(Player p, int rawSlot, InventoryClickEvent e) {
        if (rawSlot == 49) {
            plugin.messenger().requestStatus(p);
            p.sendMessage("§7Refreshing instances...");
            return;
        }
        if (rawSlot == 45) {
            page.put(p.getUniqueId(), Math.max(0, page.getOrDefault(p.getUniqueId(), 0) - 1));
            render(p);
            return;
        }
        if (rawSlot == 53) {
            page.put(p.getUniqueId(), page.getOrDefault(p.getUniqueId(), 0) + 1);
            render(p);
            return;
        }
        if (rawSlot == 47) {
            page.put(p.getUniqueId(), 0);
            screen.put(p.getUniqueId(), Screen.TEMPLATES);
            plugin.messenger().requestTemplates(p);
            render(p);
            return;
        }
        if (rawSlot < 0 || rawSlot >= PAGE_SIZE) return;

        DynStatus st = status.getOrDefault(p.getUniqueId(), new DynStatus(List.of()));
        int pg = page.getOrDefault(p.getUniqueId(), 0);
        int idx = pg * PAGE_SIZE + rawSlot;
        if (idx < 0 || idx >= st.instances().size()) return;

        DynStatus.Instance inst = st.instances().get(idx);

        boolean shiftLeft = e.isLeftClick() && e.isShiftClick();
        boolean shiftRight = e.isRightClick() && e.isShiftClick();
        boolean right = e.isRightClick();

        if (shiftRight) {
            pendingAction.put(p.getUniqueId(), new PendingAction("KILL", inst.name()));
            selectedInstance.put(p.getUniqueId(), inst.name());
            screen.put(p.getUniqueId(), Screen.CONFIRM_ACTION);
            render(p);
            return;
        }

        if (shiftLeft) {
            plugin.messenger().connect(p, inst.name());
            return;
        }

        if (right) {
            String s = inst.state().toUpperCase();
            if ("RUNNING".equals(s) || "STARTING".equals(s) || "STOPPING".equals(s)) {
                plugin.messenger().sendAction(p, "STOP", inst.name(), "");
                p.sendMessage("§7Stopping " + inst.name() + "...");
            } else {
                plugin.messenger().sendAction(p, "START", inst.name(), "");
                p.sendMessage("§7Starting " + inst.name() + "...");
                new StartWatchTask(plugin, p, inst.name()).runTaskLater(plugin, 10L);
            }
            return;
        }

        selectedInstance.put(p.getUniqueId(), inst.name());
        screen.put(p.getUniqueId(), Screen.INSTANCE_DETAILS);
        render(p);
    }

    private void handleInstanceDetailsClick(Player p, int rawSlot, InventoryClickEvent e) {
        String instanceName = selectedInstance.get(p.getUniqueId());
        if (instanceName == null) {
            screen.put(p.getUniqueId(), Screen.INSTANCES);
            render(p);
            return;
        }

        if (rawSlot == 45) {
            screen.put(p.getUniqueId(), Screen.INSTANCES);
            render(p);
            return;
        }

        if (rawSlot == 49) {
            plugin.messenger().requestStatus(p);
            p.sendMessage("§7Refreshing instance...");
            return;
        }

        switch (rawSlot) {
            case 28 -> plugin.messenger().connect(p, instanceName);
            case 29 -> {
                plugin.messenger().sendAction(p, "START", instanceName, "");
                p.sendMessage("§7Starting " + instanceName + "...");
                new StartWatchTask(plugin, p, instanceName).runTaskLater(plugin, 10L);
            }
            case 30 -> {
                plugin.messenger().sendAction(p, "STOP", instanceName, "");
                p.sendMessage("§7Stopping " + instanceName + "...");
            }
            case 31 -> {
                plugin.messenger().sendAction(p, "STOP", instanceName, "");
                p.sendMessage("§7Restarting " + instanceName + "...");
                new StartWatchTask(plugin, p, instanceName).runTaskLater(plugin, 40L);
            }
            case 32 -> {
                pendingAction.put(p.getUniqueId(), new PendingAction("KILL", instanceName));
                screen.put(p.getUniqueId(), Screen.CONFIRM_ACTION);
                render(p);
            }
            case 33 -> {
                if (commandInput == null) {
                    p.sendMessage("§cCommand input not configured.");
                    return;
                }
                p.closeInventory();
                commandInput.begin(p.getUniqueId(), instanceName);
                p.sendMessage("§bType a command in chat for §f" + instanceName + "§b (without /).");
                p.sendMessage("§7Type §fcancel§7 to abort.");
            }
        }
    }

    private void handleTemplatesClick(Player p, int rawSlot, InventoryClickEvent e) {
        if (rawSlot == 49) {
            plugin.messenger().requestTemplates(p);
            p.sendMessage("§7Refreshing templates...");
            return;
        }
        if (rawSlot == 45) {
            page.put(p.getUniqueId(), Math.max(0, page.getOrDefault(p.getUniqueId(), 0) - 1));
            render(p);
            return;
        }
        if (rawSlot == 53) {
            page.put(p.getUniqueId(), page.getOrDefault(p.getUniqueId(), 0) + 1);
            render(p);
            return;
        }
        if (rawSlot == 51) {
            page.put(p.getUniqueId(), 0);
            screen.put(p.getUniqueId(), Screen.INSTANCES);
            plugin.messenger().requestStatus(p);
            render(p);
            return;
        }
        if (rawSlot < 0 || rawSlot >= PAGE_SIZE) return;

        DynTemplates tp = templates.getOrDefault(p.getUniqueId(), new DynTemplates(List.of()));
        int pg = page.getOrDefault(p.getUniqueId(), 0);
        int idx = pg * PAGE_SIZE + rawSlot;
        if (idx < 0 || idx >= tp.items().size()) return;

        DynTemplates.Item item = tp.items().get(idx);

        if (e.isLeftClick()) {
            plugin.messenger().sendAction(p, "PLAY_ON", item.hostId(), item.template());
            p.sendMessage("§7Starting §f" + item.template() + "§7 on host §f" + item.hostId() + "§7...");
        }
    }

    private void handleConfirmClick(Player p, int rawSlot) {
        PendingAction pa = pendingAction.get(p.getUniqueId());
        if (pa == null) {
            screen.put(p.getUniqueId(), Screen.INSTANCES);
            render(p);
            return;
        }

        if (rawSlot == 13) {
            if ("KILL".equalsIgnoreCase(pa.action())) {
                plugin.messenger().sendAction(p, "KILL", pa.instanceName(), "");
                p.sendMessage("§cForce killing " + pa.instanceName() + "...");
            }

            pendingAction.remove(p.getUniqueId());
            screen.put(p.getUniqueId(), Screen.INSTANCES);
            plugin.messenger().requestStatus(p);
            render(p);
            return;
        }

        if (rawSlot == 15) {
            pendingAction.remove(p.getUniqueId());
            if (selectedInstance.containsKey(p.getUniqueId())) {
                screen.put(p.getUniqueId(), Screen.INSTANCE_DETAILS);
            } else {
                screen.put(p.getUniqueId(), Screen.INSTANCES);
            }
            render(p);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ItemStack button(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack button(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private DynStatus.Instance findInstance(Player p, String instanceName) {
        DynStatus st = status.getOrDefault(p.getUniqueId(), new DynStatus(List.of()));
        for (DynStatus.Instance inst : st.instances()) {
            if (inst.name().equalsIgnoreCase(instanceName)) {
                return inst;
            }
        }
        return null;
    }

    private int countState(DynStatus st, String state) {
        int count = 0;
        for (DynStatus.Instance inst : st.instances()) {
            if (inst.state().equalsIgnoreCase(state)) {
                count++;
            }
        }
        return count;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!TITLE.equals(e.getView().getTitle())) return;
        // keep state for now; clear later if you want stricter cleanup
    }
}