package com.example.viewmodel;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
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

    enum State { IDLE, PREP, APPROACH, ENTER_TIGHT, STRIKE }

    private static boolean enabled = false;
    private static int bindKey = InputUtil.GLFW_KEY_G;
    private static boolean keyWasDown = false;

    private State state = State.IDLE;
    private PlayerEntity target;
    private int prevSlot = -1;
    private int timer = 0;
    private int limit = 60;
    private int rocketCooldown = 0;
    private double lastDist = 999;
    private BlockPos tightCell;
    private int cooldown = 0;
    private int hits = 0;
    private int jumpPhase = 0;

    private int now = 0;
    private int swapTicks = 38;
    private int enterStart = 0;
    private int lastHit = -999;

    private int restoreSlot = -1;
    private int restoreAt = 0;

    private static final double REACH = 3.0;
    private static final int TIMEOUT = 60;
    private static final int MAX_HITS = 4;
    private static final int HIT_INTERVAL = 5;
    private static final int RESTORE_DELAY = 8;

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
            } else if (a.length >= 3 && a[1].equalsIgnoreCase("time")) {
                try {
                    double sec = Double.parseDouble(a[2].replace(',', '.'));
                    sec = Math.max(0.5, Math.min(5.0, sec));
                    swapTicks = (int) Math.round(sec * 20);
                    info("1 bloga girme suresi: " + sec + " sn (" + swapTicks + " tick)");
                } catch (Exception e) {
                    info("Gecersiz sure. Ornek: .viewmodel time 1.5");
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

        now++;

        if (restoreSlot >= 0 && now >= restoreAt) {
            if (p.getInventory().getSelectedSlot() != restoreSlot) {
                p.getInventory().setSelectedSlot(restoreSlot);
            }
            restoreSlot = -1;
        }

        boolean down = InputUtil.isKeyPressed(mc.getWindow(), bindKey);
        if (down && !keyWasDown && mc.currentScreen == null) toggle();
        keyWasDown = down;

        if (!enabled) return;

        if (cooldown > 0) cooldown--;
        if (rocketCooldown > 0) rocketCooldown--;

        if (state == State.IDLE) {
            if (cooldown > 0 || restoreSlot >= 0) return;
            target = findTightTarget(mc, p);
            if (target == null) return;

            prevSlot = p.getInventory().getSelectedSlot();
            timer = 0;
            limit = TIMEOUT;
            lastDist = 999;
            hits = 0;
            jumpPhase = 0;
            tightCell = null;

            int ownHead = headroom(mc, p.getBlockPos());
            if (canReach(p, target)) {
                if (ownHead == 1) {
                    state = State.STRIKE;
                    limit = 40;
                } else {
                    state = State.ENTER_TIGHT;
                    enterStart = now;
                    limit = swapTicks + 20;
                }
            } else {
                if (ownHead < 2) { prevSlot = -1; return; }
                if (p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
                    info("Elytra takili degil."); prevSlot = -1; cooldown = 40; return;
                }
                if (findSlot(p, Items.FIREWORK_ROCKET) < 0) {
                    info("Fisek yok."); prevSlot = -1; cooldown = 40; return;
                }
                state = State.PREP;
            }
        }

        if (target == null || !target.isAlive() || ++timer > limit) { finish(p); return; }

        switch (state) {
            case PREP -> {
                // Space, havadayken 2. space (suzulme), sonra fisek
                if (jumpPhase == 0) {
                    mc.options.sneakKey.setPressed(true);
                    if (p.isOnGround()) {
                        mc.options.jumpKey.setPressed(true);
                        jumpPhase = 1;
                    }
                } else if (jumpPhase == 1) {
                    if (!p.isOnGround()) {
                        mc.options.jumpKey.setPressed(false);
                        jumpPhase = 2;
                    }
                } else if (jumpPhase == 2) {
                    mc.options.sneakKey.setPressed(false);
                    mc.options.jumpKey.setPressed(true);
                    p.networkHandler.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                    jumpPhase = 3;
                } else {
                    mc.options.jumpKey.setPressed(false);
                    fireRocket(mc, p);
                    state = State.APPROACH;
                }
            }
            case APPROACH -> {
                // Bu asamada vurma yok
                if (canReach(p, target)) {
                    state = State.ENTER_TIGHT;
                    enterStart = now;
                    timer = 0;
                    limit = swapTicks + 20;
                    tightCell = null;
                    return;
                }
                double dist = reachDist(p, target);
                if (p.horizontalCollision && timer > 5) { finish(p); return; }
                if (timer % 5 == 0) {
                    if (lastDist - dist < 0.8) { finish(p); return; }
                    lastDist = dist;
                }
                if (!p.isOnGround() && target.getY() < p.getY()) {
                    p.addVelocity(0, -0.06, 0);
                }
                if (rocketCooldown == 0 && dist > 4) fireRocket(mc, p);
            }
            case ENTER_TIGHT -> {
                // Henuz vurma yok: once 1 blokluk alana gec
                if (headroom(mc, p.getBlockPos()) == 1 && canReach(p, target)) {
                    state = State.STRIKE;
                    timer = 0;
                    limit = 40;
                    return;
                }
                if (tightCell == null) tightCell = findTightCell(mc, target.getBlockPos(), p);
                if (tightCell == null) {
                    info("Rakibin yaninda 1 blokluk bos hucre yok.");
                    cooldown = 40;
                    finish(p);
                    return;
                }
                moveToCell(p, tightCell);
            }
            case STRIKE -> {
                // Rakip 2 blokluk alana gittiyse bitti
                if (headroom(mc, target.getBlockPos()) >= 2) {
                    info("§6[⚔ ViewModel] §fRakip 2 blokluk alana gonderildi.");
                    finish(p);
                    return;
                }
                if (!canReach(p, target)) { finish(p); return; }
                // Sadece 1 blokluk alandayken vur
                if (headroom(mc, p.getBlockPos()) != 1) {
                    state = State.ENTER_TIGHT;
                    enterStart = now;
                    timer = 0;
                    limit = swapTicks + 20;
                    tightCell = null;
                    return;
                }
                if (hits >= MAX_HITS) { finish(p); return; }
                if (inWeb(mc, p)) return;

                int best = findBestSweepingSlot(mc, p);
                if (best < 0) {
                    info("Hotbarda Savurma buyulu item yok, vurmuyorum.");
                    cooldown = 40;
                    finish(p);
                    return;
                }
                if (p.getInventory().getSelectedSlot() != best) {
                    p.getInventory().setSelectedSlot(best);
                    return;
                }
                if (now - lastHit < HIT_INTERVAL) return;

                Vec3d dir = findPushOut(mc, target.getBlockPos(), p);
                float yaw = (float) (Math.toDegrees(Math.atan2(-dir.x, dir.z)));
                sendLook(p, yaw, 0f);

                mc.interactionManager.attackEntity(p, target);
                p.swingHand(Hand.MAIN_HAND);
                hits++;
                lastHit = now;

                sendLook(p, p.getYaw(), p.getPitch());
            }
            default -> {}
        }
    }

    private void moveToCell(ClientPlayerEntity p, BlockPos cell) {
        Vec3d d = Vec3d.ofBottomCenter(cell).subtract(pos(p));
        double dist = Math.hypot(d.x, d.z);
        int remaining = Math.max(3, enterStart + swapTicks - now);
        double speed = Math.max(0.05, Math.min(0.2, dist / remaining));
        Vec3d v = new Vec3d(d.x, 0, d.z);
        if (v.lengthSquared() > 1.0E-4) v = v.normalize().multiply(speed);
        double vy = p.isOnGround() ? p.getVelocity().y : Math.min(p.getVelocity().y, 0) - 0.1;
        p.setVelocity(v.x, vy, v.z);
    }

    private void fireRocket(MinecraftClient mc, ClientPlayerEntity p) {
        int slot = findSlot(p, Items.FIREWORK_ROCKET);
        if (slot < 0) return;
        if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);

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

    private boolean inWeb(MinecraftClient mc, ClientPlayerEntity p) {
        BlockPos feet = p.getBlockPos();
        return mc.world.getBlockState(feet).isOf(Blocks.COBWEB)
                || mc.world.getBlockState(feet.up()).isOf(Blocks.COBWEB);
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

    /** Ayaktan yukari kac blok gecilebilir (hava, ag, dugme...). Kati blok = tavan. */
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

    /** Rakibin hucresinin yanindaki, bize en yakin 1 blokluk bos hucre. */
    private BlockPos findTightCell(MinecraftClient mc, BlockPos targetPos, ClientPlayerEntity p) {
        BlockPos best = null;
        double bd = 1e9;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos n = targetPos.offset(d);
            if (headroom(mc, n) != 1) continue;
            double dist = pos(p).squaredDistanceTo(Vec3d.ofBottomCenter(n));
            if (dist < bd) { bd = dist; best = n; }
        }
        return best;
    }

    /** Rakibi 2 blokluk (ag olan) tarafa atacak yon. */
    private Vec3d findPushOut(MinecraftClient mc, BlockPos cell, ClientPlayerEntity p) {
        Direction bestDir = null;
        boolean bestHasWeb = false;
        double bestDist = -1;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos n = cell.offset(d);
            if (headroom(mc, n) < 2) continue;
            boolean web = false;
            for (int i = 0; i <= 3; i++) {
                if (mc.world.getBlockState(n.up(i)).isOf(Blocks.COBWEB)) { web = true; break; }
            }
            double dist = pos(p).squaredDistanceTo(Vec3d.ofBottomCenter(n));
            if ((web && !bestHasWeb) || (web == bestHasWeb && dist > bestDist)) {
                bestDir = d; bestHasWeb = web; bestDist = dist;
            }
        }
        if (bestDir == null) {
            Vec3d v = pos(target).subtract(pos(p));
            Vec3d h = new Vec3d(v.x, 0, v.z);
            return h.lengthSquared() < 1.0E-4 ? new Vec3d(0, 0, 1) : h.normalize();
        }
        return new Vec3d(bestDir.getOffsetX(), 0, bestDir.getOffsetZ());
    }

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
        if (cooldown < 3) cooldown = 3;
        if (p != null && prevSlot >= 0 && p.getInventory().getSelectedSlot() != prevSlot) {
            restoreSlot = prevSlot;
            restoreAt = now + RESTORE_DELAY;
        }
        reset();
    }

    private void reset() {
        hits = 0; tightCell = null; jumpPhase = 0;
        lastHit = -999; limit = TIMEOUT;
        state = State.IDLE; target = null; prevSlot = -1; timer = 0;
    }

    private static Vec3d pos(Entity e) {
        return new Vec3d(e.getX(), e.getY(), e.getZ());
    }

    private static void info(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal(s), false);
    }
}
