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
    private static final boolean targetLockEnabled = true;
    private static float baseSmoothFactor = 0.07f;
    private static float currentSmoothFactor = 0.01f;
    private static final float MAX_DISTANCE = 10.0f;
    private static final float MAX_FOV = 80.0f; // FOV限制
    private static final float MIN_SMOOTH_FACTOR = 0.01f;
    private static final float MAX_SMOOTH_FACTOR = 1.0f;
    private static final float ACCELERATION_RATE = 0.005f;
    private static final float DECELERATION_RATE = 0.02f;
    
    // 添加反应时间相关常量
    private static final int TARGET_SWITCH_DELAY_TICKS = 4; // 目标切换反应时间（ticks）
    private static final int TARGET_FOLLOW_DELAY_TICKS = 3;   // 目标跟随反应时间（ticks）
    private static final int TARGET_MOVEMENT_DELAY_TICKS = 2;   // 目标移动反应时间（ticks）约200ms
    
    // 纵向抖动相关常量
    private static final float VERTICAL_JITTER_AMPLITUDE = 0.1f; // 纵向抖动幅度
    private static final float VERTICAL_JITTER_FREQUENCY = 0.1f; // 纵向抖动频率
    private static final float MIN_ANGLE_FOR_JITTER = 2.0f; // 应用垂直抖动的最小角度差
    
    private static Entity lastTarget = null;
    private static Entity lockedTarget = null;
    private static int targetSwitchDelayCounter = 0; // 目标切换延迟计数器
    private static int targetFollowDelayCounter = 0; // 目标跟随延迟计数器
    private static int targetMovementDelayCounter = 0; // 目标移动延迟计数器
    private static Vec3d lastTargetPos = null; // 上次目标位置
    private static float lastTargetYaw = 0.0f; // 上次目标偏航角
    private static float lastTargetPitch = 0.0f; // 上次目标俯仰角
    private static int aimTicks = 0; // 瞄准时长计数器

    @Override
    public void onInitializeClient() {
        KeyBindings.register();

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

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
            if (!enabled) {
                currentSmoothFactor = MIN_SMOOTH_FACTOR;
                lastTarget = null;
                lockedTarget = null;
                targetSwitchDelayCounter = 0;
                targetFollowDelayCounter = 0;
                targetMovementDelayCounter = 0;
                lastTargetPos = null;
                lastTargetYaw = 0.0f;
                lastTargetPitch = 0.0f;
                aimTicks = 0;
            }
        }

        // 处理清除目标锁定（mouse5）
        if (KeyBindings.CLEAR_TARGET.wasPressed()) {
            lockedTarget = null;
            targetSwitchDelayCounter = 0;
            if (client.player != null) {
                client.player.sendMessage(net.minecraft.text.Text.literal("§6已清除目标锁定"), false);
            }
        }
        
        // 更新延迟计数器
        if (targetSwitchDelayCounter > 0) {
            targetSwitchDelayCounter--;
        }
        
        if (targetFollowDelayCounter > 0) {
            targetFollowDelayCounter--;
        }
        
        if (targetMovementDelayCounter > 0) {
            targetMovementDelayCounter--;
        }
    }

    private void onRender(net.minecraft.client.gui.DrawContext context, float tickDelta) {
        if (!enabled) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        // 获取目标实体
        Entity target = findTarget(player, client);
        if (target == null) {
            currentSmoothFactor = Math.max(MIN_SMOOTH_FACTOR, currentSmoothFactor - DECELERATION_RATE);
            aimTicks = 0; // 重置瞄准计数器
            return;
        }

        // 检查目标是否改变
        if (lastTarget != target) {
            // 重置目标切换延迟计数器
            targetSwitchDelayCounter = TARGET_SWITCH_DELAY_TICKS;
            currentSmoothFactor = MIN_SMOOTH_FACTOR;
            lastTarget = target;
            lastTargetPos = target.getPos();
            targetMovementDelayCounter = 0;
            aimTicks = 0; // 重置瞄准计数器
        } else {
            // 检查目标是否移动
            Vec3d currentTargetPos = target.getPos();
            if (lastTargetPos != null && !lastTargetPos.equals(currentTargetPos)) {
                // 目标移动了，重置移动延迟计数器
                targetMovementDelayCounter = TARGET_MOVEMENT_DELAY_TICKS;
                lastTargetPos = currentTargetPos;
                aimTicks = 0; // 重置瞄准计数器
            }
        }

        // 增加瞄准计数器
        aimTicks++;

        // 如果目标切换延迟尚未结束，则不执行瞄准
        if (targetSwitchDelayCounter > 0) {
            return;
        }
        
        // 如果目标移动延迟尚未结束，则不执行瞄准
        if (targetMovementDelayCounter > 0) {
            return;
        }

        // 计算目标角度
        float[] targetAngles = calculateAngles(player, target, tickDelta);
        float targetYaw = targetAngles[0];
        float targetPitch = targetAngles[1];

        // 计算当前与目标的角度差
        float yawDiff = Math.abs(interpolateAngle(player.getYaw(), targetYaw, 1.0f));
        float pitchDiff = Math.abs(interpolateAngle(player.getPitch(), targetPitch, 1.0f));
        float angleDiff = (float) Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);

        // 只有当角度差足够大时才添加纵向抖动效果
        if (angleDiff > MIN_ANGLE_FOR_JITTER) {
            targetPitch += calculateVerticalJitter(aimTicks);
        }
        yawDiff = Math.abs(interpolateAngle(player.getYaw(), targetYaw, 1.0f));
        pitchDiff = Math.abs(interpolateAngle(player.getPitch(), targetPitch, 1.0f));
        angleDiff = (float) Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);

        // 根据角度差调整平滑因子，添加惯性效果
        if (angleDiff > 30.0f) {
            // 大角度偏差时快速增加平滑因子
            currentSmoothFactor = Math.min(baseSmoothFactor, currentSmoothFactor + ACCELERATION_RATE * 2);
        } else if (angleDiff > 5.0f) {
            // 中等角度偏差时中等速度增加平滑因子
            currentSmoothFactor = Math.min(baseSmoothFactor, currentSmoothFactor + ACCELERATION_RATE);
        } else {
            // 小角度偏差时缓慢减少平滑因子，增加惯性效果
            currentSmoothFactor = Math.max(MIN_SMOOTH_FACTOR, currentSmoothFactor - DECELERATION_RATE * 0.5f);
        }

        // 应用惯性效果 - 基于上次移动和当前目标的差异
        float yawInertia = Math.abs(interpolateAngle(lastTargetYaw, targetYaw, 1.0f));
        float pitchInertia = Math.abs(interpolateAngle(lastTargetPitch, targetPitch, 1.0f));
        
        // 如果目标移动变化大，降低平滑因子以增加惯性
        if (yawInertia > 10.0f || pitchInertia > 10.0f) {
            currentSmoothFactor *= 0.8f; // 减少平滑因子增加惯性
        }

        // 保存当前角度用于下次惯性计算
        lastTargetYaw = targetYaw;
        lastTargetPitch = targetPitch;

        // 平滑插值
        float newYaw = interpolateAngle(player.getYaw(), targetYaw, currentSmoothFactor);
        float newPitch = interpolateAngle(player.getPitch(), targetPitch, currentSmoothFactor);

        // 限制俯仰角在合法范围内
        newPitch = Math.max(-90.0f, Math.min(90.0f, newPitch));

        // 设置玩家角度
        player.setYaw(newYaw);
        player.setPitch(newPitch);

        // 更新prev角度以避免渲染抖动
        updatePreviousAngles(player, newYaw, newPitch);
    }

    private Entity findTarget(ClientPlayerEntity player, MinecraftClient client) {
        // 如果当前有锁定目标，检查锁定目标是否仍然有效且在FOV内
        if (lockedTarget != null) {
            if (isValidTarget(lockedTarget, player) &&
                    player.squaredDistanceTo(lockedTarget) <= MAX_DISTANCE * MAX_DISTANCE &&
                    canSeeEntity(player, lockedTarget)) {
                return lockedTarget;
            } else {
                lockedTarget = null;
                // 当锁定目标丢失时，重置目标跟随延迟
                targetFollowDelayCounter = TARGET_FOLLOW_DELAY_TICKS;
            }
        }

        // 方法1: 使用十字准星目标（不受FOV限制）
        HitResult hitResult = client.crosshairTarget;
        if (hitResult != null && hitResult.getType() == HitResult.Type.ENTITY) {
            Entity target = ((EntityHitResult) hitResult).getEntity();
            if (isValidTarget(target, player)) {
                lockedTarget = target;
                // 当找到新目标时，重置目标跟随延迟
                targetFollowDelayCounter = TARGET_FOLLOW_DELAY_TICKS;
                return target;
            }
        }

        // 方法2: 查找视线方向最近的实体（在FOV内）
        Entity directionalTarget = findDirectionalEntity(player, client);
        if (directionalTarget != null) {
            lockedTarget = directionalTarget;
            // 当找到新目标时，重置目标跟随延迟
            targetFollowDelayCounter = TARGET_FOLLOW_DELAY_TICKS;
            return directionalTarget;
        }

        // 如果目标跟随延迟尚未结束，继续保持之前的瞄准方向
        if (targetFollowDelayCounter > 0 && lastTarget != null && 
            isValidTarget(lastTarget, player) && 
            player.squaredDistanceTo(lastTarget) <= MAX_DISTANCE * MAX_DISTANCE) {
            return lastTarget;
        }

        return null;
    }

    private Entity findDirectionalEntity(ClientPlayerEntity player, MinecraftClient client) {
        Entity closest = null;
        double closestAngleDiff = Double.MAX_VALUE;
        float playerYaw = player.getYaw();
        float playerPitch = player.getPitch();

        for (Entity entity : client.world.getEntities()) {
            if (!isValidTarget(entity, player)) continue;

            double distance = player.squaredDistanceTo(entity);
            if (distance > MAX_DISTANCE * MAX_DISTANCE) continue;

            if (!canSeeEntity(player, entity)) continue;

            // 检查是否在FOV内
            if (!isInFov(player, entity)) continue;

            float[] targetAngles = calculateAngles(player, entity, 1.0f);
            float targetYaw = targetAngles[0];
            float targetPitch = targetAngles[1];

            float yawDiff = Math.abs(interpolateAngle(playerYaw, targetYaw, 1.0f));
            float pitchDiff = Math.abs(interpolateAngle(playerPitch, targetPitch, 1.0f));
            double angleDiff = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);

            if (angleDiff < closestAngleDiff) {
                closest = entity;
                closestAngleDiff = angleDiff;
            }
        }

        return closest;
    }

    // 检查实体是否在FOV内
    private boolean isInFov(ClientPlayerEntity player, Entity entity) {
        float[] targetAngles = calculateAngles(player, entity, 1.0f);
        float targetYaw = targetAngles[0];
        float targetPitch = targetAngles[1];

        // 计算角度差异
        float yawDiff = Math.abs(interpolateAngle(player.getYaw(), targetYaw, 1.0f));
        float pitchDiff = Math.abs(interpolateAngle(player.getPitch(), targetPitch, 1.0f));

        // 使用欧几里得距离计算总角度差异
        float totalAngleDiff = (float) Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);

        return totalAngleDiff <= MAX_FOV;
    }

    private boolean isValidTarget(Entity entity, ClientPlayerEntity player) {
        if (entity == player || !entity.isAlive() || entity.isSpectator()) {
            return false;
        }

        return entity instanceof MobEntity ||
                (entity instanceof PlayerEntity);
    }

    private boolean canSeeEntity(ClientPlayerEntity player, Entity entity) {
        Vec3d playerEyes = player.getEyePos();
        Vec3d entityEyes = entity.getEyePos();

        return player.getWorld().raycast(new net.minecraft.world.RaycastContext(
                playerEyes, entityEyes,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE,
                player
        )).getType() == HitResult.Type.MISS;
    }

    private float[] calculateAngles(ClientPlayerEntity player, Entity target, float tickDelta) {
        Vec3d playerEyes = player.getEyePos();
        net.minecraft.util.math.Box targetBox = target.getBoundingBox();

        Vec3d targetPos = target.getLerpedPos(tickDelta);
        targetBox = targetBox.offset(targetPos.subtract(target.getPos()));

        double closestX = Math.max(targetBox.minX, Math.min(playerEyes.x, targetBox.maxX));
        double closestY = Math.max(targetBox.minY, Math.min(playerEyes.y, targetBox.maxY));
        double closestZ = Math.max(targetBox.minZ, Math.min(playerEyes.z, targetBox.maxZ));

        double dx = closestX - playerEyes.x;
        double dy = closestY - playerEyes.y;
        double dz = closestZ - playerEyes.z;

        double distanceXZ = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distanceXZ));

        return new float[]{yaw, pitch};
    }
    

    private float interpolateAngle(float current, float target, float factor) {
        float difference = target - current;
        while (difference < -180.0f) difference += 360.0f;
        while (difference > 180.0f) difference -= 360.0f;

        return current + difference * factor;
    }

    private void updatePreviousAngles(ClientPlayerEntity player, float yaw, float pitch) {
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
        return baseSmoothFactor;
    }

    public static void setSmoothFactor(float factor) {
        baseSmoothFactor = Math.max(MIN_SMOOTH_FACTOR, Math.min(MAX_SMOOTH_FACTOR, factor));
    }

    public static boolean isTargetLockEnabled() {
        return true;
    }

    public static Entity getLockedTarget() {
        return lockedTarget;
    }

    public static void setLockedTarget(Entity target) {
        lockedTarget = target;
    }

    public static void clearLockedTarget() {
        lockedTarget = null;
    }

    /**
     * 计算纵向抖动值，创建轻微的圆弧轨迹效果
     * @param ticks 瞄准持续的tick数
     * @return 纵向抖动偏移值
     */
    private float calculateVerticalJitter(int ticks) {
        // 使用正弦函数创建先向上后向下的圆弧轨迹
        // 公式: amplitude * sin(frequency * ticks - π/2)
        // 这样开始时值为负（向下），然后变为正（向上），最后回到负（向下）
        return VERTICAL_JITTER_AMPLITUDE * (float) Math.sin(VERTICAL_JITTER_FREQUENCY * ticks - Math.PI / 2);
    }

    // 获取FOV限制
    public static float getMaxFov() {
        return MAX_FOV;
    }
    
    // 获取目标切换延迟计数器
    public static int getTargetSwitchDelayCounter() {
        return targetSwitchDelayCounter;
    }
    
    // 获取目标跟随延迟计数器
    public static int getTargetFollowDelayCounter() {
        return targetFollowDelayCounter;
    }
    
    // 获取目标移动延迟计数器
    public static int getTargetMovementDelayCounter() {
        return targetMovementDelayCounter;
    }
    
}