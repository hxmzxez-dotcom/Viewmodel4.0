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

    private int now = 0;
    private int swapTicks = 38;    // yer degistirme suresi (38 tick = 1.9 sn)
    private int swapStart = -1;
    private int lastHit = -999;
    private int limit = 40;

    private static final double REACH = 3.0;
    private static final int TIMEOUT = 40;
    private static final int MAX_HITS = 40;
    private static final int HIT_INTERVAL = 5; // vuruslar arasi tick (3 vurus / 0.5 sn)

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
                    info("Yer degistirme suresi: " + sec + " sn (" + swapTicks + " tick)");
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

        boolean down = InputUtil.isKeyPressed(mc.getWindow(), bindKey);
        if (down && !keyWasDown && mc.currentScreen == null) toggle();
        keyWasDown = down;

        if (!enabled) return;

        now++;
        if (cooldown > 0) cooldown--;

        if (state == State.IDLE) {
            if (cooldown > 0) return;
            target = findTightTarget(mc, p);
            if (target == null) return;

            origin = pos(p);
            prevSlot = p.getInventory().getSelectedSlot();
            timer = 0;
            limit = TIMEOUT;
            swapStart = -1;
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

        if (target == null || !target.isAlive() || ++timer > limit) { finish(p); return; }
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
                if (!p.isOnGround() && target.getY() < p.getY()) {
                    p.addVelocity(0, -0.06, 0);
                }
                if (rocketCooldown == 0 && dist > 4) fireRocket(mc, p);
            }
            case STRIKE -> {
                if (pushDir != null && swapStart >= 0 && !target.getBlockPos().equals(targetCell)) {
                    state = State.ENTER;
                    enterStep(p);
                    return;
                }
                if (now - lastHit < HIT_INTERVAL) return;

                int best = findBestSweepingSlot(mc, p);
                if (best >= 0 && p.getInventory().getSelectedSlot() != best) {
                    p.getInventory().setSelectedSlot(best);
                }

                Vec3d back = pushDir != null ? pushDir : origin.subtract(pos(p));
                if (back.horizontalLength() < 0.5) back = pos(target).subtract(pos(p));
                float yaw = (float) (Math.toDegrees(Math.atan2(-back.x, back.z)));
                float pitch = 0f;
                sendLook(p, yaw, pitch);

                mc.interactionManager.attackEntity(p, target);
                p.swingHand(Hand.MAIN_HAND);
                hits++;
                lastHit = now;

                sendLook(p, p.getYaw(), p.getPitch());

                if (pushDir != null) {
                    if (swapStart < 0) {
                        swapStart = now;
                        timer = 0;
                        limit = swapTicks + 20;
                    }
                    state = State.ENTER;
                    enterStep(p);
                } else {
                    state = State.LAND;
                    timer = limit - 10;
                }
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
        if (target != null && target.isAlive() && hits
