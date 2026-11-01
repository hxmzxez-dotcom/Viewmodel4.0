package com.example.viewmodel;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

public class ViewModelMod implements ClientModInitializer {

    enum State { IDLE, PREP, APPROACH, STRIKE }

    private static boolean enabled = false;
    private static int bindKey = InputUtil.GLFW_KEY_G;
    private static boolean keyWasDown = false;
    private static String forcedName = null;

    private State state = State.IDLE;
    private PlayerEntity target;
    private int prevSlot = -1;
    private int timer = 0;
    private int limit = 70;
    private int rocketCooldown = 0;
    private double lastDist = 999;
    private BlockPos tightCell;
    private int cooldown = 0;
    private int hits = 0;
    private int diagCd = 0;

    private int now = 0;
    private int lastHit = -999;

    private int restoreSlot = -1;
    private int restoreAt = 0;

    private int webTicks = 0;
    private int noMove = 0;
    private Vec3d lastTargetPos = null;
    private int tries = 0;
    private int noLos = 0;

    private static final double REACH = 3.0;
    private static final double TIGHT_MAX = 1.7;
    private static final int TIMEOUT = 70;
    private static final int MAX_HITS = 30;
    private static final int HIT_INTERVAL = 6;   // 0.3 sn
    private static final int SPACE_INTERVAL = 6; // 0.3 sn
    private static final int RESTORE_DELAY = 8;
    private static final int SWEEP_SLOT = 1;     // 2. slot

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.ALLOW_CHAT.register(msg -> {
            if (!msg.startsWith(".viewmodel")) return true;
            String[] a = msg.trim().split("\\s+");
            MinecraftClient mc = MinecraftClient.getInstance();
            if (a.length >= 3 && a[1].equalsIgnoreCase("bind")) {
                try {
                    bindKey = InputUtil.fromTranslationKey("key.keyboard." + a[2].toLowerCase()).getCode();
                    info("Tus atandi: " + a[2].toUpperCase());
                } catch (Exception e) {
                    info("Gecersiz tus: " + a[2]);
                }
            } else if (a.length >= 3 && a[1].equalsIgnoreCase("target")) {
                if (a[2].equalsIgnoreCase("off")) {
                    forcedName = null;
                    info("Elle hedef kapatildi.");
                } else {
                    forcedName = a[2];
                    info("Hedef: " + forcedName);
                }
            } else if (a.length >= 2 && a[1].equalsIgnoreCase("scan")) {
                if (mc.player != null && mc.world != null) {
                    info(analyze(mc, mc.player));
                    info(hotbarReport(mc.player));
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
        if (diagCd > 0) diagCd--;

        if (state == State.IDLE) {
            if (cooldown > 0 || restoreSlot >= 0) return;
            target = findTarget(mc, p);
            if (target == null) {
                if (diagCd == 0) { info(analyze(mc, p)); diagCd = 60; }
                return;
            }

            boolean ownTight = tight(mc, p);
            boolean reach = canReach(p, target);
            double dist = reachDist(p, target);

            State next;
            if (ownTight) {
                if (reach) next = State.STRIKE;
                else {
                    if (diagCd == 0) { info("Sen sikisik alandasin, rakip erisim disinda (" + r1(dist) + " blok)."); diagCd = 60; }
                    target = null; return;
                }
            } else {
                String why = glideProblem(p);
                if (why != null) {
                    if (diagCd == 0) { info(why); diagCd = 60; }
                    target = null; return;
                }
                next = State.PREP;
            }

            prevSlot = p.getInventory().getSelectedSlot();
            timer = 0;
            lastDist = 999;
            hits = 0;
            noMove = 0;
            noLos = 0;
            lastTargetPos = null;
            webTicks = 0;
            tightCell = null;
            tries = 0;
            state = next;
            limit = (next == State.STRIKE) ? 220 : TIMEOUT;
            info("Analiz: rakip " + target.getName().getString() + ", mesafe " + r1(dist)
                    + ", rakip alan " + r1(freeH(mc, target)) + ", senin alanin " + r1(freeH(mc, p)) + " -> " + state);
        }

        if (target == null || !target.isAlive() || ++timer > limit) { finish(p); return; }

        switch (state) {
            case PREP -> {
                // Shift basili, space 0.3 sn'de bir. Havada 2. space suzulmeyi acar.
                mc.options.sneakKey.setPressed(true);
                if (p.isGliding()) {
                    mc.options.jumpKey.setPressed(false);
                    mc.options.sneakKey.setPressed(false);
                    state = State.APPROACH;
                    timer = 0;
                    lastDist = 999;
                    fireRocket(mc, p, aimPoint(mc, p));
                    return;
                }
                mc.options.jumpKey.setPressed(timer % SPACE_INTERVAL == 1);
                if (timer >= 14 && timer % SPACE_INTERVAL == 3 && !p.isOnGround()) {
                    p.networkHandler.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                }
                if (timer > 40 && !p.isGliding()) {
                    tries++;
                    info("Suzulme baslamadi (deneme " + tries + "/3). Ag/ust blok/elytra engel olabilir.");
                    cooldown = tries >= 3 ? 80 : 10;
                    if (tries >= 3) tries = 0;
                    finish(p);
                }
            }
            case APPROACH -> {
                // Bu asamada VURMA YOK, yurume yok: sadece fisek
                boolean reach = canReach(p, target);
                if (tight(mc, p) && reach) {
                    state = State.STRIKE;
                    timer = 0;
                    limit = 220;
                    return;
                }
                // Yere indiysek ve suzulmuyorsak tekrar suzul
                if (p.isOnGround() && !p.isGliding() && timer > 6) {
                    if (++tries > 3) {
                        info("Fisek rakibin hucresine tasimadi, vazgeciyorum.");
                        cooldown = 80;
                        finish(p);
                        return;
                    }
                    state = State.PREP;
                    timer = 0;
                    limit = TIMEOUT;
                    return;
                }
                double dist = reachDist(p, target);
                if (!reach && p.horizontalCollision && timer > 8) {
                    info("Suzulurken bir yere carptim, turu bitiriyorum.");
                    finish(p);
                    return;
                }
                if (!reach && timer > 10 && timer % 8 == 0) {
                    if (lastDist - dist < 0.5) { finish(p); return; }
                    lastDist = dist;
                }
                if (rocketCooldown == 0) fireRocket(mc, p, aimPoint(mc, p));
            }
            case STRIKE -> {
                mc.options.jumpKey.setPressed(false);
                mc.options.sneakKey.setPressed(false);

                if (!tight(mc, target)) {
                    info("§6[⚔ ViewModel] §fRakip 2 blokluk alana gonderildi.");
                    finish(p);
                    return;
                }
                if (!canReach(p, target)) { finish(p); return; }
                // Biz sikisik alandan ciktiysak: tekrar suzulerek gir
                if (!tight(mc, p)) {
                    String why = glideProblem(p);
                    if (why != null) { info(why); cooldown = 40; finish(p); return; }
                    state = State.PREP;
                    timer = 0;
                    limit = TIMEOUT;
                    return;
                }
                if (hits >= MAX_HITS) {
                    info("En fazla vurus sayisina ulasildi, rakip hala sikisik.");
                    cooldown = 40;
                    finish(p);
                    return;
                }
                if (inWeb(mc, p)) {
                    if (++webTicks > 15) { info("Ag icinde takildim, turu bitiriyorum."); finish(p); }
                    return;
                }
                webTicks = 0;

                // Normal oyunda olmayacak vuruslari yapma
                if (p.isTouchingWater() || p.isInLava()) return;
                if (!clearShot(mc, p, target)) {
                    if (++noLos > 20) {
                        info("Rakibe acik hat yok (blok arada), turu bitiriyorum.");
                        finish(p);
                    }
                    return;
                }
                noLos = 0;

                int best = sweepSlot(p);
                if (best < 0) {
                    info("2. slotta item yok ve Savurma buyulu item bulunamadi.");
                    cooldown = 40;
                    finish(p);
                    return;
                }
                if (p.getInventory().getSelectedSlot() != best) {
                    p.getInventory().setSelectedSlot(best);
                    return;
                }
                if (now - lastHit < HIT_INTERVAL) return;

                Vec3d tp = pos(target);
                if (lastTargetPos != null) {
                    if (tp.distanceTo(lastTargetPos) < 0.05) noMove++; else noMove = 0;
                }
                List<Vec3d> cands = pushCandidates(mc, target, p);
                int idx = (noMove / 3) % cands.size();
                if (noMove > 0 && noMove % 3 == 0 && cands.size() > 1) {
                    info("Rakip oynamiyor, yon degistiriyorum (" + (idx + 1) + "/" + cands.size() + ").");
                }
                Vec3d dir = cands.get(idx);

                float yaw = (float) (Math.toDegrees(Math.atan2(-dir.x, dir.z)));
                sendLook(p, yaw, 0f);
                mc.interactionManager.attackEntity(p, target);
                p.swingHand(Hand.MAIN_HAND);
                hits++;
                lastHit = now;
                lastTargetPos = tp;
                sendLook(p, p.getYaw(), p.getPitch());
            }
            default -> {}
        }
    }

    /** Suzulme icin eksik sart varsa nedenini dondurur. */
    private String glideProblem(ClientPlayerEntity p) {
        if (p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem() != Items.ELYTRA) return "Elytra takili degil.";
        if (findSlot(p, Items.FIREWORK_ROCKET) < 0) return "Hotbarda fisek yok.";
        return null;
    }

    /** Fisegin hedefi: rakibin yanindaki 1 blokluk hucrenin ortasi, yoksa rakibin hitbox merkezi. */
    private Vec3d aimPoint(MinecraftClient mc, ClientPlayerEntity p) {
        if (tightCell == null) tightCell = findTightCell(mc, target, p);
        if (tightCell != null) return Vec3d.ofBottomCenter(tightCell).add(0, 0.5, 0);
        return target.getBoundingBox().getCenter();
    }

    private void fireRocket(MinecraftClient mc, ClientPlayerEntity p, Vec3d aim) {
        int slot = findSlot(p, Items.FIREWORK_ROCKET);
        if (slot < 0) return;
        if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);

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

    /** Goz ile rakibin hitbox'u arasinda kati blok var mi? (ag gecirgen sayilir) */
    private boolean clearShot(MinecraftClient mc, ClientPlayerEntity p, PlayerEntity t) {
        Vec3d e = p.getEyePos();
        Box b = t.getBoundingBox();
        Vec3d to = new Vec3d(
                Math.max(b.minX, Math.min(e.x, b.maxX)),
                Math.max(b.minY, Math.min(e.y, b.maxY)),
                Math.max(b.minZ, Math.min(e.z, b.maxZ)));
        var hit = mc.world.raycast(new RaycastContext(e, to,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        return hit.getType() == HitResult.Type.MISS;
    }

    // ---- Savurma slotu: buyuyu algila, yoksa 2. slot ----
    private int sweepLevel(ItemStack s) {
        if (s.isEmpty()) return 0;
        int lvl = 0;
        for (var e : s.getEnchantments().getEnchantmentEntries()) {
            String id = e.getKey().getIdAsString();
            if (id.contains("sweeping")) lvl = Math.max(lvl, e.getIntValue());
        }
        return lvl;
    }

    private int findBestSweepingSlot(ClientPlayerEntity p) {
        int best = -1, bestLvl = 0;
        for (int i = 0; i < 9; i++) {
            int lvl = sweepLevel(p.getInventory().getStack(i));
            if (lvl > bestLvl) { bestLvl = lvl; best = i; }
        }
        return best;
    }

    private int sweepSlot(ClientPlayerEntity p) {
        int best = findBestSweepingSlot(p);
        if (best >= 0) return best;
        ItemStack s = p.getInventory().getStack(SWEEP_SLOT);
        if (!s.isEmpty() && !s.isOf(Items.FIREWORK_ROCKET)) return SWEEP_SLOT;
        return -1;
    }

    private String hotbarReport(ClientPlayerEntity p) {
        StringBuilder sb = new StringBuilder("Hotbar: ");
        boolean any = false;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            StringBuilder en = new StringBuilder();
            for (var e : s.getEnchantments().getEnchantmentEntries()) {
                en.append(e.getKey().getIdAsString().replace("minecraft:", "")).append(' ').append(e.getIntValue()).append(' ');
            }
            if (en.length() > 0) {
                sb.append("[").append(i + 1).append(": ").append(en.toString().trim()).append("] ");
                any = true;
            }
        }
        if (!any) sb.append("hicbir itemde buyu gorunmuyor (2. slot yine de kullanilir).");
        return sb.toString();
    }

    private int findSlot(ClientPlayerEntity p, net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++)
            if (p.getInventory().getStack(i).isOf(item)) return i;
        return -1;
    }

    // ---- Alan olcumu (ag gecilebilir sayilir, kati blok tavan) ----
    private double freeHeightAt(MinecraftClient mc, double x, double y, double z) {
        BlockPos base = BlockPos.ofFloored(x, y + 0.01, z);
        for (int i = 0; i < 4; i++) {
            BlockPos b = base.up(i);
            VoxelShape sh = mc.world.getBlockState(b).getCollisionShape(mc.world, b);
            if (sh.isEmpty()) continue;
            double top = b.getY() + sh.getMax(Direction.Axis.Y);
            double bottom = b.getY() + sh.getMin(Direction.Axis.Y);
            if (top <= y + 0.05) continue;
            return Math.max(0, bottom - y);
        }
        return 3.0;
    }

    private double freeH(MinecraftClient mc, Entity e) {
        return freeHeightAt(mc, e.getX(), e.getY(), e.getZ());
    }

    private boolean tight(MinecraftClient mc, Entity e) {
        return e.getBoundingBox().getLengthY() < 1.0 || freeH(mc, e) < TIGHT_MAX;
    }

    private PlayerEntity findTarget(MinecraftClient mc, ClientPlayerEntity p) {
        PlayerEntity best = null; double bd = 3600;
        for (PlayerEntity o : mc.world.getPlayers()) {
            if (o == p) continue;
            if (forcedName != null) {
                if (o.getName().getString().equalsIgnoreCase(forcedName)) return o;
                continue;
            }
            double d = p.squaredDistanceTo(o);
            if (d > 900 || d > bd) continue;
            if (tight(mc, o)) { best = o; bd = d; }
        }
        return best;
    }

    private BlockPos findTightCell(MinecraftClient mc, PlayerEntity t, ClientPlayerEntity p) {
        BlockPos best = null;
        double bd = 1e9;
        BlockPos tp = t.getBlockPos();
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos n = tp.offset(d);
            double f = freeHeightAt(mc, n.getX() + 0.5, t.getY(), n.getZ() + 0.5);
            if (f < 0.5 || f >= TIGHT_MAX) continue;
            double dist = pos(p).squaredDistanceTo(Vec3d.ofBottomCenter(n));
            if (dist < bd) { bd = dist; best = n; }
        }
        return best;
    }

    private List<Vec3d> pushCandidates(MinecraftClient mc, PlayerEntity t, ClientPlayerEntity p) {
        List<double[]> tmp = new ArrayList<>();
        BlockPos tp = t.getBlockPos();
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos n = tp.offset(d);
            double f = freeHeightAt(mc, n.getX() + 0.5, t.getY(), n.getZ() + 0.5);
            if (f < TIGHT_MAX) continue;
            boolean web = false;
            for (int i = 0; i <= 3; i++) {
                if (mc.world.getBlockState(n.up(i)).isOf(Blocks.COBWEB)) { web = true; break; }
            }
            double dist = Math.sqrt(pos(p).squaredDistanceTo(Vec3d.ofBottomCenter(n)));
            tmp.add(new double[]{(web ? 100 : 0) + dist, d.getOffsetX(), d.getOffsetZ()});
        }
        tmp.sort((a, b) -> Double.compare(b[0], a[0]));
        List<Vec3d> out = new ArrayList<>();
        for (double[] a : tmp) out.add(new Vec3d(a[1], 0, a[2]));
        if (out.isEmpty()) {
            Vec3d v = pos(t).subtract(pos(p));
            Vec3d h = new Vec3d(v.x, 0, v.z);
            out.add(h.lengthSquared() < 1.0E-4 ? new Vec3d(0, 0, 1) : h.normalize());
        }
        return out;
    }

    private String analyze(MinecraftClient mc, ClientPlayerEntity p) {
        PlayerEntity near = null; double bd = 1e9;
        for (PlayerEntity o : mc.world.getPlayers()) {
            if (o == p) continue;
            double d = p.squaredDistanceTo(o);
            if (d < bd) { bd = d; near = o; }
        }
        if (near == null) return "Analiz: yakinda baska oyuncu gorunmuyor.";
        String s = "Analiz: " + near.getName().getString() + " mesafe " + r1(reachDist(p, near))
                + " blok, rakip alan " + r1(freeH(mc, near)) + " (hitbox " + r1(near.getBoundingBox().getLengthY())
                + "), senin alanin " + r1(freeH(mc, p)) + " -> ";
        if (bd > 900) return s + "30 blokten uzak.";
        if (!tight(mc, near)) return s + "rakip sikisik degil (2 blokluk alan).";
        if (tight(mc, p)) return s + (canReach(p, near) ? "vurus modu." : "sen de sikisiksin, rakip erisim disinda.");
        String why = glideProblem(p);
        return s + (why != null ? why : "suzulme + fisek modu.");
    }

    private static double r1(double v) { return Math.round(v * 10.0) / 10.0; }

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
        hits = 0; tightCell = null; noMove = 0; webTicks = 0; noLos = 0;
        lastTargetPos = null;
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
