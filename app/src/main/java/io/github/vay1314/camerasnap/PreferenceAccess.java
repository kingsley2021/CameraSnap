package io.github.vay1314.camerasnap;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PreferenceAccess {
    final Class<?> preference, group, screen;
    final List<Method> clickMethods;
    final Method find;
    private final Field context, parent;
    private Field key, title, visible;
    private Method getSummary, setSummary, isEnabled, enable, isSelectable, isShown, isVisible;
    private Method add, remove;
    private volatile boolean prepared;

    PreferenceAccess(ClassLoader loader) throws ReflectiveOperationException {
        this(Class.forName("androidx.preference.Preference", false, loader),
                Class.forName("androidx.preference.PreferenceGroup", false, loader),
                Class.forName("androidx.preference.PreferenceScreen", false, loader));
    }

    PreferenceAccess(Class<?> preference, Class<?> group, Class<?> screen) throws ReflectiveOperationException {
        this.preference = preference;
        this.group = group;
        this.screen = screen;
        context = uniqueField(preference, Context.class);
        parent = uniqueField(preference, group);
        find = unique(methods(group, preference, CharSequence.class), "preference lookup");
        clickMethods = new ArrayList<>();
        Method viewClick = named("performClick", View.class);
        if (viewClick != null && viewClick.getReturnType() == void.class) clickMethods.add(viewClick);
        else {
            List<Method> candidates = methods(preference, void.class, View.class);
            if (!candidates.isEmpty()) clickMethods.add(unique(candidates, "preference click"));
        }
        try {
            Method click = Reflection.method(preference, "performClick");
            if (click.getReturnType() == void.class) clickMethods.add(click);
        }
        catch (NoSuchMethodException ignored) { }
        if (clickMethods.isEmpty()) throw new NoSuchMethodException("No preference click path");
    }

    Context context(Object item) throws IllegalAccessException { return (Context) context.get(item); }

    Object root(Object fragment) throws ReflectiveOperationException {
        try { return Reflection.method(fragment.getClass(), "getPreferenceScreen").invoke(fragment); }
        catch (NoSuchMethodException ignored) { }
        for (Class<?> cls = fragment.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == screen) {
                    field.setAccessible(true);
                    Object root = field.get(fragment);
                    if (root != null) return root;
                }
            }
        }
        return null;
    }

    Object find(Object root, String name) throws ReflectiveOperationException { return find.invoke(root, name); }

    private Object create(Context context) throws ReflectiveOperationException {
        for (Class<?>[] parameters : new Class<?>[][]{{Context.class}, {Context.class, AttributeSet.class},
                {Context.class, AttributeSet.class, int.class}, {Context.class, AttributeSet.class, int.class, int.class}}) {
            try {
                Constructor<?> constructor = preference.getDeclaredConstructor(parameters);
                constructor.setAccessible(true);
                Object[] values = new Object[parameters.length];
                values[0] = context;
                for (int i = 2; i < values.length; i++) values[i] = 0;
                return constructor.newInstance(values);
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException("No preference constructor");
    }

    void prepare(Object keyedItem, String knownKey) throws ReflectiveOperationException {
        if (prepared) return;
        List<Field> keys = new ArrayList<>();
        for (Field field : fields(preference, String.class))
            if (knownKey.equals(field.get(keyedItem))) keys.add(field);
        key = unique(keys, "preference key field");
        Context host = context(keyedItem);
        Object probe = create(host);
        getSummary = named("getSummary");
        if (getSummary == null) getSummary = unique(methods(preference, CharSequence.class), "summary getter");
        String marker = "CameraSnap:summary-probe";
        List<Field> summaries = new ArrayList<>();
        List<Field> texts = fields(preference, CharSequence.class);
        for (Field field : texts) {
            Object original = field.get(probe);
            field.set(probe, marker);
            if (marker.equals(getSummary.invoke(probe))) summaries.add(field);
            field.set(probe, original);
        }
        Field summary = unique(summaries, "summary field");
        title = unique(texts.stream().filter(field -> !field.equals(summary)).collect(java.util.stream.Collectors.toList()), "title field");
        setSummary = named("setSummary", CharSequence.class);
        if (setSummary == null) {
            List<Method> setters = new ArrayList<>();
            for (Method method : methods(preference, void.class, CharSequence.class)) {
                Object fresh = create(host);
                method.invoke(fresh, marker);
                if (marker.equals(getSummary.invoke(fresh))) setters.add(method);
            }
            setSummary = unique(setters, "summary setter");
        }
        isEnabled = named("isEnabled");
        enable = named("setEnabled", boolean.class);
        if (isEnabled == null) {
            List<Method> getters = new ArrayList<>();
            List<Method> setters = new ArrayList<>();
            for (Method setter : methods(preference, void.class, boolean.class)) {
                for (Method getter : methods(preference, boolean.class)) {
                    Object fresh = create(host);
                    setter.invoke(fresh, false);
                    boolean disabled = Boolean.FALSE.equals(getter.invoke(fresh));
                    setter.invoke(fresh, true);
                    if (disabled && Boolean.TRUE.equals(getter.invoke(fresh))) {
                        getters.add(getter);
                        setters.add(setter);
                    }
                }
            }
            isEnabled = unique(getters, "enabled getter/setter pair");
            enable = unique(setters, "enabled setter");
        }
        isSelectable = named("isSelectable");
        isShown = named("isShown");
        isVisible = named("isVisible");
        if (isShown == null && isVisible == null) {
            List<Field> candidates = new ArrayList<>();
            for (Method method : methods(preference, void.class, boolean.class)) {
                Object fresh = create(host);
                if (enable != null) enable.invoke(fresh, true);
                method.invoke(fresh, true);
                List<Field> booleans = fields(preference, boolean.class);
                List<Object> before = new ArrayList<>();
                for (Field field : booleans) before.add(field.get(fresh));
                method.invoke(fresh, false);
                if (!Boolean.TRUE.equals(isEnabled.invoke(fresh))) continue;
                for (int i = 0; i < booleans.size(); i++)
                    if (Boolean.TRUE.equals(before.get(i)) && Boolean.FALSE.equals(booleans.get(i).get(fresh)))
                        candidates.add(booleans.get(i));
            }
            visible = unique(candidates.stream().distinct().collect(java.util.stream.Collectors.toList()), "visible field");
        }
        add = namedGroup("addPreference", preference);
        remove = namedGroup("removePreference", preference);
        if (add == null || remove == null) {
            List<Method> operations = methods(group, boolean.class, preference).stream()
                    .filter(method -> Modifier.isPublic(method.getModifiers())).collect(java.util.stream.Collectors.toList());
            add = unique(operations.stream().filter(method -> !Modifier.isFinal(method.getModifiers())).collect(java.util.stream.Collectors.toList()), "add preference");
            remove = unique(operations.stream().filter(method -> !method.equals(add)).collect(java.util.stream.Collectors.toList()), "remove preference");
        }
        prepared = true;
    }

    boolean prepared() { return prepared; }
    String key(Object item) throws IllegalAccessException { return (String) key.get(item); }
    CharSequence summary(Object item) throws ReflectiveOperationException { return (CharSequence) getSummary.invoke(item); }
    void summary(Object item, CharSequence value) throws ReflectiveOperationException { setSummary.invoke(item, value); }

    boolean visible(Object item) throws ReflectiveOperationException {
        if (isShown != null) return Boolean.TRUE.equals(isShown.invoke(item));
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object current = item; current != null; current = parent.get(current)) {
            if (!seen.add(current) || !(isVisible == null ? visible.getBoolean(current) :
                    Boolean.TRUE.equals(isVisible.invoke(current)))) return false;
        }
        return true;
    }

    boolean usable(Object item) throws ReflectiveOperationException {
        return visible(item) && Boolean.TRUE.equals(isEnabled.invoke(item)) &&
                (isSelectable == null || Boolean.TRUE.equals(isSelectable.invoke(item)));
    }

    Object entry(Context host, String label, String description) throws ReflectiveOperationException {
        Object item = create(host);
        key.set(item, CameraSettingsPolicy.MODULE_KEY);
        title.set(item, label);
        summary(item, description);
        if (enable != null) enable.invoke(item, true);
        if (visible != null) visible.setBoolean(item, true);
        else {
            Method show = named("setVisible", boolean.class);
            if (show != null) show.invoke(item, true);
        }
        Method selectable = named("setSelectable", boolean.class);
        if (selectable != null) selectable.invoke(item, true);
        Method persistent = named("setPersistent", boolean.class);
        if (persistent != null) persistent.invoke(item, false);
        return item;
    }

    boolean add(Object parent, Object item) throws ReflectiveOperationException { return Boolean.TRUE.equals(add.invoke(parent, item)); }
    void remove(Object parent, Object item) throws ReflectiveOperationException { remove.invoke(parent, item); }

    private Method named(String name, Class<?>... parameters) {
        try { return Reflection.method(preference, name, parameters); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    private Method namedGroup(String name, Class<?>... parameters) {
        try { return Reflection.method(group, name, parameters); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    static List<Method> methods(Class<?> cls, Class<?> returns, Class<?>... parameters) {
        List<Method> matches = new ArrayList<>();
        for (Method method : cls.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) && !method.isSynthetic() &&
                    method.getReturnType() == returns && Arrays.equals(method.getParameterTypes(), parameters)) {
                method.setAccessible(true);
                matches.add(method);
            }
        }
        return matches;
    }

    private static List<Field> fields(Class<?> cls, Class<?> type) {
        List<Field> matches = new ArrayList<>();
        for (Field field : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                field.setAccessible(true);
                matches.add(field);
            }
        }
        return matches;
    }

    private static Field uniqueField(Class<?> cls, Class<?> type) throws NoSuchFieldException {
        return unique(fields(cls, type), type.getName() + " field");
    }

    private static <T> T unique(List<T> values, String description) throws NoSuchFieldException {
        if (values.size() != 1) throw new NoSuchFieldException("Ambiguous " + description + ": " + values.size());
        return values.get(0);
    }
}
