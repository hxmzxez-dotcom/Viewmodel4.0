package com.example.viewmodel;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class ViewModelMod implements ClientModInitializer {

    enum State { IDLE, GLIDE_START, ROCKET, APPROACH, STRIKE, ENTER, LAND }

    private static boolean enabled = false;
    private static int bindKey = InputUtil.GLFW_KEY_G;
    private static boolean keyWasDown = false;

    private State state = State.IDLE;
    private PlayerEntity target;
    private Vec3d origin;
    private int prevSlot = -1;
    private int timer = 0;
    private int rocketCooldown = 0;
    private double lastDist = 999;
    private Vec3d pushDir;
    private BlockPos targetCell;
    private int cooldown = 0;
    private int hits = 0;

    private static final double REACH = 3.0;
    private static final int TIMEOUT = 40;
    private static final int MAX_HITS = 40;

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.ALLOW_CHAT.register(msg -> {
            if (!msg.startsWith(".viewmodel")) return true;
            String[] a = msg.trim().split("\\s+");
            if (a.length >= 3 && a[1].equalsIgnoreCase("bind")) {
                try {
                    bindKey = InputUtil.fromTranslationKey("key.keyboard." + a[2].toLowerCase()).getCode();
                    info("Tus atandi: " + a[2].toUpperCase());
                } catch (Exception e) {
                    info("Gecersiz tus: " + a[2]);
                }
            } else {
                toggle();
            }
            return false;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void toggle() {
        enabled = !enabled;
        if (!enabled) reset();
        info("§6[⚔ ViewModel] §f" + (enabled ? "ACIK" : "KAPALI"));
    }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;

        boolean down = InputUtil.isKeyPressed(mc.getWindow(), bindKey);
        if (down && !keyWasDown && mc.currentScreen == null) toggle();
        keyWasDown = down;

        if (!enabled) return;

        if (cooldown > 0) cooldown--;

        if (state == State.IDLE) {
            if (cooldown > 0) return;
            target = findTightTarget(mc, p);
            if (target == null) return;

            origin = p.getPos();
            prevSlot = p.getInventory().getSelectedSlot();
            timer = 0;
            lastDist = 999;

            if (canReach(p, target)) {
                targetCell = target.getBlockPos();
                pushDir = findPushDir(mc, targetCell, p);
                state = State.STRIKE;
            } else {
                if (headroom(mc, p.getBlockPos()) < 2) return;
                if (p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
                    info("Elytra takili degil."); prevSlot = -1; return;
                }
                if (findSlot(p, Items.FIREWORK_ROCKET) < 0) { info("Fisek yok."); prevSlot = -1; return; }
                state = State.GLIDE_START;
            }
        }

        if (target == null || !target.isAlive() || ++timer > TIMEOUT) { finish(p); return; }
        if (rocketCooldown > 0) rocketCooldown--;

        switch (state) {
            case GLIDE_START -> {
                mc.options.sneakKey.setPressed(true);
                if (p.isOnGround()) {
                    mc.options.jumpKey.setPressed(true);
                } else {
                    mc.options.jumpKey.setPressed(false);
                    mc.options.sneakKey.setPressed(false);
                    p.networkHandler.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                    fireRocket(mc, p);
                    state = State.APPROACH;
                }
            }
            case ROCKET -> {
                fireRocket(mc, p);
                state = State.APPROACH;
            }
            case APPROACH -> {
                // Hitbox'a eristik: havadayken hemen yer degistirme hareketini yap
                if (canReach(p, target)) {
                    targetCell = target.getBlockPos();
                    pushDir = findPushDir(mc, targetCell, p);
                    state = State.STRIKE;
                    return;
                }
                double dist = reachDist(p, target);
                if (p.horizontalCollision && timer > 5) { finish(p); return; }
                if (timer % 5 == 0) {
                    if (lastDist - dist < 0.8) { finish(p); return; }
                    lastDist = dist;
                }
                // Rakip bizden alcaktaysa suzulurken hafifce asagi in
                if (!p.isOnGround() && target.getY() < p.getY()) {
                    p.addVelocity(0, -0.06, 0);
                }
                if (rocketCooldown == 0 && dist > 4) fireRocket(mc, p);
            }
            case STRIKE -> {
                int best = findBestSweepingSlot(mc, p);
                if (best >= 0) p.getInventory().setSelectedSlot(best);

                Vec3d back = pushDir != null ? pushDir : origin.subtract(p.getPos());
                if (back.horizontalLength() < 0.5) back = target.getPos().subtract(p.getPos());
                float yaw = (float) (Math.toDegrees(Math.atan2(-back.x, back.z)));
                float pitch = 0f;
                sendLook(p, yaw, pitch);

                mc.interactionManager.attackEntity(p, target);
                p.swingHand(Hand.MAIN_HAND);
                hits++;

                sendLook(p, p.getYaw(), p.getPitch());
                if (pushDir != null) { state = State.ENTER; timer = TIMEOUT - 8; enterStep(p); }
                else { state = State.LAND; timer = TIMEOUT - 10; }
            }
            case ENTER -> enterStep(p);
            case LAND -> {
                if (headroom(mc, p.getBlockPos()) == 1) {
                    info("§6[⚔ ViewModel] §f1 blokluk alana gecildi, durdu.");
                    finish(p);
                } else if (p.isOnGround()) {
                    finish(p);
                }
            }
            default -> {}
        }
    }

    private void enterStep(ClientPlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        // Rakip hala hucredeyse ve erisimdeyse: gidene kadar tekrar vur
        if (target != null && target.isAlive() && hits < MAX_HITS
                && target.getBlockPos().equals(targetCell) && canReach(p, target)) {
            state = State.STRIKE;
            return;
        }
        if (p.getBlockPos().equals(targetCell) && headroom(mc, targetCell) == 1) {
            info("§6[⚔ ViewModel] §f1 blokluk alana gecildi, durdu.");
            finish(p); return;
        }
        Vec3d d = Vec3d.ofBottomCenter(targetCell).subtract(p.getPos());
        Vec3d v = new Vec3d(d.x, 0, d.z);
        if (v.lengthSquared() > 1.0E-4) v = v.normalize().multiply(0.45);
        // Havadaysak hucrenin tabanina dogru da in
        double vy = p.isOnGround() ? p.getVelocity().y : Math.min(p.getVelocity().y, 0) - 0.1;
        p.setVelocity(v.x, vy, v.z);
    }

    private void fireRocket(MinecraftClient mc, ClientPlayerEntity p) {
        int slot = findSlot(p, Items.FIREWORK_ROCKET);
        if (slot < 0) return;
        p.getInventory().setSelectedSlot(slot);

        // Rakibin ayak hizasina nisan al: yorunge asagi dogru kivrilir
        Vec3d aim = new Vec3d(target.getX(), target.getY() + 0.3, target.getZ());
        Vec3d d = aim.subtract(p.getEyePos());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));

        float oy = p.getYaw(), op = p.getPitch();
        p.setYaw(yaw); p.setPitch(pitch);
        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        p.setYaw(oy); p.setPitch(op);
        rocketCooldown = 8;
    }

    private void sendLook(ClientPlayerEntity p, float yaw, float pitch) {
        p.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                yaw, pitch, p.isOnGround(), p.horizontalCollision));
    }

    private int findBestSweepingSlot(MinecraftClient mc, ClientPlayerEntity p) {
        RegistryEntry<Enchantment> sweep = mc.world.getRegistryManager()
                .getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(Enchantments.SWEEPING_EDGE);
        int best = -1, bestLvl = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            int lvl = EnchantmentHelper.getLevel(sweep, s);
            if (lvl > bestLvl) { bestLvl = lvl; best = i; }
        }
        return bestLvl > 0 ? best : -1;
    }

    private int findSlot(ClientPlayerEntity p, net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++)
            if (p.getInventory().getStack(i).isOf(item)) return i;
        return -1;
    }

    /** Ayaktan yukari kac blok gecilebilir (hava veya orumcek agi). Kati blok = tavan. */
    private int headroom(MinecraftClient mc, BlockPos feet) {
        int n = 0;
        for (int i = 0; i < 3; i++) {
            BlockPos b = feet.up(i);
            if (!mc.world.getBlockState(b).getCollisionShape(mc.world, b).isEmpty()) break;
            n++;
        }
        return n;
    }

    private PlayerEntity findTightTarget(MinecraftClient mc, ClientPlayerEntity p) {
        PlayerEntity best = null; double bd = 900;
        for (PlayerEntity o : mc.world.getPlayers()) {
            if (o == p) continue;
            double d = p.squaredDistanceTo(o);
            if (d > bd) continue;
            if (headroom(mc, o.getBlockPos()) == 1) { best = o; bd = d; }
        }
        return best;
    }

    private Vec3d findPushDir(MinecraftClient mc, BlockPos cell, ClientPlayerEntity p) {
        Direction bestDir = null;
        boolean bestHasWeb = false;
        double bestDist = 1e9;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos n = cell.offset(d);
            if (headroom(mc, n) < 2) continue;
            boolean web = false;
            for (int i = 0; i <= 3; i++) {
                if (mc.world.getBlockState(n.up(i)).isOf(net.minecraft.block.Blocks.COBWEB)) { web = true; break; }
            }
            double dist = p.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(n));
            if ((web && !bestHasWeb) || (web == bestHasWeb && dist < bestDist)) {
                bestDir = d; bestHasWeb = web; bestDist = dist;
            }
        }
        if (bestDir == null) {
            Vec3d v = p.getPos().subtract(Vec3d.ofBottomCenter(cell));
            return new Vec3d(v.x, 0, v.z).normalize();
        }
        return new Vec3d(bestDir.getOffsetX(), 0, bestDir.getOffsetZ());
    }

    /** Gozden hedefin hitbox'ina en yakin noktaya mesafe. */
    private double reachDist(ClientPlayerEntity p, PlayerEntity t) {
        Vec3d e = p.getEyePos();
        Box b = t.getBoundingBox();
        double x = Math.max(b.minX, Math.min(e.x, b.maxX));
        double y = Math.max(b.minY, Math.min(e.y, b.maxY));
        double z = Math.max(b.minZ, Math.min(e.z, b.maxZ));
        return Math.sqrt(e.squaredDistanceTo(x, y, z));
    }

    private boolean canReach(ClientPlayerEntity p, PlayerEntity t) {
        return reachDist(p, t) <= REACH;
    }

    private void finish(ClientPlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        cooldown = (mc.player != null && headroom(mc, mc.player.getBlockPos()) == 1) ? 3 : 0;
        if (p != null && prevSlot >= 0) p.getInventory().setSelectedSlot(prevSlot);
        reset();
    }

    private void reset() {
        hits = 0; pushDir = null; targetCell = null;
        state = State.IDLE; target = null; prevSlot = -1; timer = 0;
    }

    private static void info(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal(s), false);
    }
}
