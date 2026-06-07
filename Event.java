package com.tobi.stairdoors;

import net.minecraft.world.InteractionResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;

public final class StairDoorPlacement {
    private static final boolean DEBUG = Boolean.getBoolean("stairdoors.debug");

    private StairDoorPlacement() {}

    public static InteractionResult tryPlace(Object player, Object level, Object hand, Object hitResult) {
        try {
            Object stack = call(player, "getItemInHand", hand);
            if (isTrue(stack, "isEmpty")) return result("PASS");

            Object item = call(stack, "getItem");
            Object doorBlock = getDoorBlock(item);
            if (doorBlock == null) return result("PASS");

            Object clickedPos = call(hitResult, "getBlockPos");
            Object stairState = call(level, "getBlockState", clickedPos);
            Object stairBlock = call(stairState, "getBlock");
            if (!looksLikeStairBlock(stairBlock)) return result("PASS");

            Object shape = getPropertyValue(stairState, stairBlock.getClass(), "SHAPE", "shape");
            if (!"STRAIGHT".equals(nameOf(shape))) return result("PASS");

            Object stairFacing = getPropertyValue(stairState, stairBlock.getClass(), "FACING", "facing");
            if (stairFacing == null) return result("PASS");
            Object doorFacing = call(stairFacing, "getOpposite");

            Object doorPos = call(clickedPos, "above");
            Object upperPos = call(doorPos, "above");
            if (!canReplace(level, doorPos) || !canReplace(level, upperPos)) return result("PASS");

            // On the client, claim the interaction and play the local placement sound.
            // The server callback performs the actual block placement and broadcasts to other players.
            if (isClientSide(level)) {
                playPlaceSound(level, null, doorBlock, null, doorPos, true);
                return success(level);
            }

            Object lowerState = call(doorBlock, "defaultBlockState");
            Class<?> doorBlockClass = doorBlock.getClass();
            lowerState = setPropertyValue(lowerState, doorBlockClass, "FACING", "facing", doorFacing);
            lowerState = setPropertyValue(lowerState, doorBlockClass, "HALF", "half", enumConstant("net.minecraft.world.level.block.state.properties.DoubleBlockHalf", "LOWER"));
            Object hinge = chooseHingeAndSyncNeighbor(level, doorPos, doorBlock, lowerState, doorBlockClass, doorFacing);
            lowerState = setPropertyValueIfPresent(lowerState, doorBlockClass, "HINGE", "hinge", hinge);
            lowerState = setPropertyValueIfPresent(lowerState, doorBlockClass, "OPEN", "open", Boolean.FALSE);
            boolean powered = hasNeighborSignal(level, doorPos) || hasNeighborSignal(level, upperPos);
            lowerState = setPropertyValueIfPresent(lowerState, doorBlockClass, "POWERED", "powered", powered);

            Object upperState = setPropertyValue(lowerState, doorBlockClass, "HALF", "half", enumConstant("net.minecraft.world.level.block.state.properties.DoubleBlockHalf", "UPPER"));

            boolean placedLower = setBlock(level, doorPos, lowerState, 3);
            boolean placedUpper = setBlock(level, upperPos, upperState, 3);
            if (!placedLower || !placedUpper) {
                // Do not leave half a door behind if one half failed.
                trySetAir(level, doorPos);
                trySetAir(level, upperPos);
                return result("PASS");
            }

            shrinkIfNotCreative(player, stack);
            fireGameEvent(level, player, doorPos);
            playPlaceSound(level, null, doorBlock, lowerState, doorPos, false);
            return success(level);
        } catch (Throwable t) {
            debug("placement failed", t);
            return result("PASS");
        }
    }

    private static Object chooseHingeAndSyncNeighbor(Object level, Object doorPos, Object doorBlock, Object newLowerState, Class<?> doorBlockClass, Object doorFacing) {
        Object leftHinge;
        Object rightHinge;
        try {
            leftHinge = enumConstant("net.minecraft.world.level.block.state.properties.DoorHingeSide", "LEFT");
            rightHinge = enumConstant("net.minecraft.world.level.block.state.properties.DoorHingeSide", "RIGHT");
        } catch (Throwable ignored) {
            return null;
        }

        try {
            Object leftDir = horizontalLeft(doorFacing);
            Object rightDir = horizontalRight(doorFacing);
            Object leftPos = call(doorPos, "relative", leftDir);
            Object rightPos = call(doorPos, "relative", rightDir);

            Object leftDoor = matchingNeighborDoor(level, leftPos, doorBlock, doorBlockClass, doorFacing);
            if (leftDoor != null) {
                // Existing door is on the left side of the new door: keep its outer hinge on LEFT, put the new hinge on RIGHT.
                forceDoorHinge(level, leftPos, leftDoor, leftHinge);
                return rightHinge;
            }

            Object rightDoor = matchingNeighborDoor(level, rightPos, doorBlock, doorBlockClass, doorFacing);
            if (rightDoor != null) {
                // Existing door is on the right side of the new door: put the new hinge on LEFT and force the existing door to RIGHT.
                forceDoorHinge(level, rightPos, rightDoor, rightHinge);
                return leftHinge;
            }
        } catch (Throwable t) {
            debug("hinge selection failed", t);
        }
        return leftHinge;
    }

    private static Object matchingNeighborDoor(Object level, Object pos, Object newDoorBlock, Class<?> newDoorBlockClass, Object newFacing) {
        try {
            Object state = call(level, "getBlockState", pos);
            Object block = call(state, "getBlock");
            if (!looksLikeDoorBlock(block)) return null;

            Object half = getPropertyValue(state, block.getClass(), "HALF", "half");
            String halfName = nameOf(half);
            if (!halfName.isEmpty() && !"LOWER".equals(halfName)) return null;

            Object facing = getPropertyValue(state, block.getClass(), "FACING", "facing");
            if (!sameDirection(facing, newFacing)) return null;
            return state;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean looksLikeDoorBlock(Object block) {
        if (block == null) return false;
        String blockClassName = block.getClass().getName().toLowerCase(Locale.ROOT);
        if (blockClassName.contains("door")) return true;
        try {
            Class<?> doorBlockClass = Class.forName("net.minecraft.world.level.block.DoorBlock");
            return doorBlockClass.isInstance(block);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void forceDoorHinge(Object level, Object lowerPos, Object lowerState, Object hinge) {
        try {
            Object block = call(lowerState, "getBlock");
            Class<?> blockClass = block.getClass();
            Object newLower = setPropertyValueIfPresent(lowerState, blockClass, "HINGE", "hinge", hinge);
            setBlock(level, lowerPos, newLower, 3);

            Object upperPos = call(lowerPos, "above");
            Object upperState = call(level, "getBlockState", upperPos);
            Object upperBlock = call(upperState, "getBlock");
            if (looksLikeDoorBlock(upperBlock)) {
                Object newUpper = setPropertyValueIfPresent(upperState, upperBlock.getClass(), "HINGE", "hinge", hinge);
                setBlock(level, upperPos, newUpper, 3);
            }
        } catch (Throwable t) {
            debug("neighbor hinge update failed", t);
        }
    }

    private static Object horizontalLeft(Object facing) throws Exception {
        for (String name : new String[] { "getCounterClockWise", "getCounterClockwise", "counterClockWise", "counterClockwise" }) {
            try { return call(facing, name); } catch (Throwable ignored) {}
        }
        throw new NoSuchMethodException(facing.getClass().getName() + ".counterClockwise");
    }

    private static Object horizontalRight(Object facing) throws Exception {
        for (String name : new String[] { "getClockWise", "getClockwise", "clockWise", "clockwise" }) {
            try { return call(facing, name); } catch (Throwable ignored) {}
        }
        throw new NoSuchMethodException(facing.getClass().getName() + ".clockwise");
    }

    private static boolean sameDirection(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        return nameOf(a).equals(nameOf(b));
    }

    private static Object getDoorBlock(Object item) {
        try {
            Object block = call(item, "getBlock");
            if (block == null) return null;
            String blockClassName = block.getClass().getName().toLowerCase(Locale.ROOT);
            if (blockClassName.contains("door")) return block;
            try {
                Class<?> doorBlockClass = Class.forName("net.minecraft.world.level.block.DoorBlock");
                if (doorBlockClass.isInstance(block)) return block;
            } catch (Throwable ignored) {}
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean looksLikeStairBlock(Object block) {
        if (block == null) return false;
        String name = block.getClass().getName().toLowerCase(Locale.ROOT);
        if (name.contains("stair")) return true;
        try {
            Class<?> stairBlockClass = Class.forName("net.minecraft.world.level.block.StairBlock");
            return stairBlockClass.isInstance(block);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object getPropertyValue(Object state, Class<?> owner, String fieldName, String propertyName) throws Exception {
        Object property = findProperty(state, owner, fieldName, propertyName);
        return property == null ? null : call(state, "getValue", property);
    }

    private static Object setPropertyValue(Object state, Class<?> owner, String fieldName, String propertyName, Object value) throws Exception {
        Object property = findProperty(state, owner, fieldName, propertyName);
        if (property == null) throw new NoSuchFieldException(fieldName + "/" + propertyName);
        return call(state, "setValue", property, value);
    }

    private static Object setPropertyValueIfPresent(Object state, Class<?> owner, String fieldName, String propertyName, Object value) {
        try {
            return setPropertyValue(state, owner, fieldName, propertyName, value);
        } catch (Throwable ignored) {
            return state;
        }
    }

    private static Object findProperty(Object state, Class<?> owner, String fieldName, String propertyName) {
        try {
            Field f = findPublicField(owner, fieldName);
            if (f != null) return f.get(null);
        } catch (Throwable ignored) {}

        try {
            Object props = call(state, "getProperties");
            if (props instanceof Collection<?> collection) {
                for (Object prop : collection) {
                    try {
                        Object n = call(prop, "getName");
                        if (propertyName.equals(String.valueOf(n))) return prop;
                    } catch (Throwable ignored) {}
                }
            } else if (props instanceof Iterable<?> iterable) {
                for (Object prop : iterable) {
                    try {
                        Object n = call(prop, "getName");
                        if (propertyName.equals(String.valueOf(n))) return prop;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Field findPublicField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {}
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean canReplace(Object level, Object pos) throws Exception {
        Object state = call(level, "getBlockState", pos);
        try { if (isTrue(state, "isAir")) return true; } catch (Throwable ignored) {}
        try { return isTrue(state, "canBeReplaced"); } catch (Throwable ignored) {}
        try {
            Object fluid = call(state, "getFluidState");
            return !isTrue(fluid, "isEmpty");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setBlock(Object level, Object pos, Object state, int flags) throws Exception {
        Object r = call(level, "setBlock", pos, state, flags);
        return !(r instanceof Boolean) || (Boolean) r;
    }

    private static void trySetAir(Object level, Object pos) {
        try {
            Object blocks = Class.forName("net.minecraft.world.level.block.Blocks");
            Object air = staticField((Class<?>) blocks, "AIR");
            Object airState = call(air, "defaultBlockState");
            setBlock(level, pos, airState, 3);
        } catch (Throwable ignored) {}
    }

    private static boolean hasNeighborSignal(Object level, Object pos) {
        try { return isTrue(level, "hasNeighborSignal", pos); } catch (Throwable ignored) { return false; }
    }

    private static boolean isClientSide(Object level) throws Exception {
        try { return isTrue(level, "isClientSide"); } catch (Throwable ignored) {}
        try {
            Field f = level.getClass().getField("isClientSide");
            return Boolean.TRUE.equals(f.get(level));
        } catch (Throwable ignored) {}
        return false;
    }

    private static void shrinkIfNotCreative(Object player, Object stack) {
        try {
            Object abilities = call(player, "getAbilities");
            boolean instabuild = false;
            try {
                Field f = abilities.getClass().getField("instabuild");
                instabuild = Boolean.TRUE.equals(f.get(abilities));
            } catch (Throwable ignored) {
                try { instabuild = isTrue(abilities, "instabuild"); } catch (Throwable ignored2) {}
            }
            if (!instabuild) call(stack, "shrink", 1);
        } catch (Throwable ignored) {
            try { call(stack, "shrink", 1); } catch (Throwable ignored2) {}
        }
    }

    private static void fireGameEvent(Object level, Object player, Object pos) {
        try {
            Object blockPlace = staticField(Class.forName("net.minecraft.world.level.gameevent.GameEvent"), "BLOCK_PLACE");
            call(level, "gameEvent", player, blockPlace, pos);
        } catch (Throwable ignored) {}
    }

    private static void playPlaceSound(Object level, Object player, Object doorBlock, Object placedState, Object pos, boolean localOnly) {
        try {
            Object state = placedState != null ? placedState : call(doorBlock, "defaultBlockState");
            Object soundType = findSoundType(doorBlock, state, level, pos);
            Object sound = call(soundType, "getPlaceSound");
            Object source = enumConstant("net.minecraft.sounds.SoundSource", "BLOCKS");
            float volume = (((Number) call(soundType, "getVolume")).floatValue() + 1.0F) / 2.0F;
            float pitch = ((Number) call(soundType, "getPitch")).floatValue() * 0.8F;

            if (localOnly && tryPlayLocalSound(level, pos, sound, source, volume, pitch)) return;

            // Passing null here intentionally includes the placing player in the sound broadcast.
            try {
                call(level, "playSound", player, pos, sound, source, volume, pitch);
                return;
            } catch (Throwable ignored) {}

            double x = ((Number) call(pos, "getX")).doubleValue() + 0.5D;
            double y = ((Number) call(pos, "getY")).doubleValue() + 0.5D;
            double z = ((Number) call(pos, "getZ")).doubleValue() + 0.5D;
            call(level, "playSound", player, x, y, z, sound, source, volume, pitch);
        } catch (Throwable t) {
            debug("sound failed", t);
        }
    }

    private static Object findSoundType(Object doorBlock, Object state, Object level, Object pos) throws Exception {
        try { return call(state, "getSoundType"); } catch (Throwable ignored) {}
        try { return call(state, "getSoundType", level, pos, null); } catch (Throwable ignored) {}
        try { return call(doorBlock, "getSoundType", state); } catch (Throwable ignored) {}
        try { return call(doorBlock, "getSoundType", state, level, pos, null); } catch (Throwable ignored) {}
        return call(doorBlock, "getSoundType");
    }

    private static boolean tryPlayLocalSound(Object level, Object pos, Object sound, Object source, float volume, float pitch) {
        try {
            double x = ((Number) call(pos, "getX")).doubleValue() + 0.5D;
            double y = ((Number) call(pos, "getY")).doubleValue() + 0.5D;
            double z = ((Number) call(pos, "getZ")).doubleValue() + 0.5D;
            try {
                call(level, "playLocalSound", x, y, z, sound, source, volume, pitch, false);
                return true;
            } catch (Throwable ignored) {}
            try {
                call(level, "playLocalSound", pos, sound, source, volume, pitch, false);
                return true;
            } catch (Throwable ignored) {}
            call(level, "playSound", null, pos, sound, source, volume, pitch);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object staticField(Class<?> owner, String name) throws Exception {
        Field f = findPublicField(owner, name);
        if (f == null) throw new NoSuchFieldException(owner.getName() + "." + name);
        return f.get(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(String className, String constant) throws Exception {
        Class<?> raw = Class.forName(className);
        if (raw.isEnum()) return Enum.valueOf((Class<? extends Enum>) raw.asSubclass(Enum.class), constant);
        Field f = findPublicField(raw, constant);
        if (f != null) return f.get(null);
        throw new NoSuchFieldException(className + "." + constant);
    }

    private static String nameOf(Object value) {
        if (value == null) return "";
        if (value instanceof Enum<?> e) return e.name().toUpperCase(Locale.ROOT);
        return String.valueOf(value).toUpperCase(Locale.ROOT);
    }

    private static boolean isTrue(Object target, String method, Object... args) throws Exception {
        Object r = call(target, method, args);
        return Boolean.TRUE.equals(r);
    }

    private static Object call(Object target, String method, Object... args) throws Exception {
        Method m = findMethod(target.getClass(), method, args);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static Method findMethod(Class<?> type, String name, Object[] args) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (isCandidate(m, name, args)) return m;
            }
        }
        for (Method m : type.getMethods()) {
            if (isCandidate(m, name, args)) return m;
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + args.length);
    }

    private static boolean isCandidate(Method m, String name, Object[] args) {
        if (!m.getName().equals(name) || m.getParameterCount() != args.length) return false;
        Class<?>[] parameterTypes = m.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (args[i] == null) continue;
            Class<?> p = wrap(parameterTypes[i]);
            if (!p.isAssignableFrom(args[i].getClass())) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private static InteractionResult success(Object level) {
        try {
            Class<?> resultClass = InteractionResult.class;
            for (String methodName : new String[] { "sidedSuccess", "success" }) {
                try {
                    Method m = resultClass.getMethod(methodName, boolean.class);
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    Object value = m.invoke(null, isClientSide(level));
                    if (resultClass.isInstance(value)) return (InteractionResult) value;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return result("SUCCESS");
    }

    private static InteractionResult result(String name) {
        try {
            Class<?> resultClass = InteractionResult.class;
            if (resultClass.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object enumValue = Enum.valueOf((Class<? extends Enum>) resultClass.asSubclass(Enum.class), name);
                return (InteractionResult) enumValue;
            }

            Field f = findPublicField(resultClass, name);
            if (f != null) {
                Object value = f.get(null);
                if (resultClass.isInstance(value)) return (InteractionResult) value;
            }

            String lower = name.toLowerCase(Locale.ROOT);
            try {
                Method m = resultClass.getMethod(lower);
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    Object value = m.invoke(null);
                    if (resultClass.isInstance(value)) return (InteractionResult) value;
                }
            } catch (Throwable ignored) {}

            if ("SUCCESS".equals(name)) {
                for (String methodName : new String[] { "sidedSuccess", "success" }) {
                    try {
                        Method m = resultClass.getMethod(methodName, boolean.class);
                        if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                        Object value = m.invoke(null, Boolean.TRUE);
                        if (resultClass.isInstance(value)) return (InteractionResult) value;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        throw new IllegalStateException("Could not resolve InteractionResult." + name);
    }

    private static void debug(String message, Throwable t) {
        if (!DEBUG) return;
        System.out.println("[Stair Doors] " + message + ": " + t);
        t.printStackTrace(System.out);
    }
}
