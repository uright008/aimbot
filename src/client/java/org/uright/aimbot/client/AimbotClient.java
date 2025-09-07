package org.uright.aimbot.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

public class AimbotClient implements ClientModInitializer {

    private static boolean enabled = false;
    private static float smoothFactor = 0.15f;
    private static final float MAX_DISTANCE = 10.0f;
    private static final float MIN_SMOOTH_FACTOR = 0.01f;
    private static final float MAX_SMOOTH_FACTOR = 1.0f;

    @Override
    public void onInitializeClient() {
        // 注册按键绑定
        KeyBindings.register();

        // 注册客户端tick事件
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // 注册渲染事件 - 实现丝滑转动
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> this.onRender(drawContext, tickCounter.getTickDelta(false)));

        System.out.println("Smooth Aimbot mod initialized!");
    }

    private void onClientTick(MinecraftClient client) {
        // 处理按键切换
        if (KeyBindings.TOGGLE_AIMBOT.wasPressed()) {
            toggle();
            if (client.player != null) {
                String status = enabled ? "§a启用" : "§c禁用";
                client.player.sendMessage(net.minecraft.text.Text.literal("Aimbot: " + status), false);
            }
        }
    }

    private void onRender(net.minecraft.client.gui.DrawContext context, float tickDelta) {
        if (!enabled) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        // 获取目标实体
        Entity target = findTarget(player, client);
        if (target == null) return;

        // 计算目标角度，使用tickDelta实现更精确的预测
        float[] targetAngles = calculateAngles(player, target, tickDelta);
        float targetYaw = targetAngles[0];
        float targetPitch = targetAngles[1];

        // 平滑插值
        float newYaw = interpolateAngle(player.getYaw(), targetYaw, smoothFactor);
        float newPitch = interpolateAngle(player.getPitch(), targetPitch, smoothFactor);

        // 限制俯仰角在合法范围内
        newPitch = Math.max(-90.0f, Math.min(90.0f, newPitch));

        // 设置玩家角度
        player.setYaw(newYaw);
        player.setPitch(newPitch);

        // 更新prev角度以避免渲染抖动
        updatePreviousAngles(player, newYaw, newPitch);
    }

    private Entity findTarget(ClientPlayerEntity player, MinecraftClient client) {
        // 方法1: 使用十字准星目标
        HitResult hitResult = client.crosshairTarget;
        if (hitResult != null && hitResult.getType() == HitResult.Type.ENTITY) {
            Entity target = ((EntityHitResult) hitResult).getEntity();
            if (isValidTarget(target, player)) {
                return target;
            }
        }

        // 方法2: 查找最近的实体
        return findClosestEntity(player, client);
    }

    private Entity findClosestEntity(ClientPlayerEntity player, MinecraftClient client) {
        Entity closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (Entity entity : client.world.getEntities()) {
            if (!isValidTarget(entity, player)) continue;

            double distance = player.squaredDistanceTo(entity);
            if (distance < closestDistance && distance <= AimbotClient.MAX_DISTANCE * AimbotClient.MAX_DISTANCE) {
                // 检查视线是否被阻挡
                if (canSeeEntity(player, entity)) {
                    closest = entity;
                    closestDistance = distance;
                }
            }
        }

        return closest;
    }

    private boolean isValidTarget(Entity entity, ClientPlayerEntity player) {
        if (entity == player || !entity.isAlive() || entity.isSpectator()) {
            return false;
        }

        // 只瞄准敌对生物和玩家
        return entity instanceof MobEntity ||
                (entity instanceof PlayerEntity);
    }

    private boolean canSeeEntity(ClientPlayerEntity player, Entity entity) {
        Vec3d playerEyes = player.getEyePos();
        Vec3d entityEyes = entity.getEyePos();

        // 简单的视线检测
        return player.getWorld().raycast(new net.minecraft.world.RaycastContext(
                playerEyes, entityEyes,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE,
                player
        )).getType() == HitResult.Type.MISS;
    }

    private float[] calculateAngles(ClientPlayerEntity player, Entity target, float tickDelta) {
        Vec3d playerEyes = player.getEyePos();

        // 预测目标位置（考虑tickDelta进行插值）
        Vec3d targetPos = target.getLerpedPos(tickDelta).add(0, target.getEyeHeight(target.getPose()), 0);

        double dx = targetPos.x - playerEyes.x;
        double dy = targetPos.y - playerEyes.y;
        double dz = targetPos.z - playerEyes.z;

        double distanceXZ = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distanceXZ));

        return new float[]{yaw, pitch};
    }

    private float interpolateAngle(float current, float target, float factor) {
        // 处理角度环绕（-180到180度）
        float difference = target - current;
        while (difference < -180.0f) difference += 360.0f;
        while (difference > 180.0f) difference -= 360.0f;

        return current + difference * factor;
    }

    private void updatePreviousAngles(ClientPlayerEntity player, float yaw, float pitch) {
        // 更新prev角度字段
        player.prevYaw = yaw;
        player.prevPitch = pitch;
    }

    public static void toggle() {
        enabled = !enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean state) {
        enabled = state;
    }

    public static float getSmoothFactor() {
        return smoothFactor;
    }

    public static void setSmoothFactor(float factor) {
        smoothFactor = Math.max(MIN_SMOOTH_FACTOR, Math.min(MAX_SMOOTH_FACTOR, factor));
    }
}