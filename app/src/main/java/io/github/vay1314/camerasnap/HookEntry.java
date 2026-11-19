package io.github.vay1314.camerasnap;

import android.content.Context;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.Set;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.Hooker;

public final class HookEntry extends XposedModule {
    static final String TAG = "CameraSnap";
    private volatile SystemKeyDispatcher dispatcher;
    private volatile boolean retiring;
    private ClassLoader systemLoader;
    private Context systemContext;
    private boolean systemHooksInstalled;
    private String processName;
    private final Set<ClassLoader> cameraLoaders = new HashSet<>();
    private final Set<Object> cameraFragments = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Object, PreferenceLink> preferenceLinks = new WeakHashMap<>();
    private final Map<ClassLoader, PreferenceAccess> cameraPreferences = new java.util.HashMap<>();
    private Handler cameraMain;
    private boolean cameraRefreshPending;
    private final Runnable cameraRefresh = () -> {
        cameraRefreshPending = false;
        if (retiring) return;
        for (Object fragment : cameraFragments.toArray()) {
            try { addSettingsLink(fragment, fragment.getClass().getClassLoader()); }
            catch (ReflectiveOperationException | RuntimeException e) {
                log(Log.ERROR, TAG, "Cannot refresh camera settings entry", e);
            }
        }
    };
    private final ReloadHooks reloadHooks = new ReloadHooks();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "API " + getApiVersion() + ", process=" + param.getProcessName());
    }

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        installSystemHooks(param.getClassLoader());
    }

    private void installHook(Method method, Hooker hooker) {
        String id = "CameraSnap:" + method.toGenericString();
        reloadHooks.install(id, hooker, () -> hook(method).setId(id).intercept(hooker));
    }

    private void installSystemHooks(ClassLoader loader) {
        systemLoader = loader;
        if (systemHooksInstalled) return;
        String[] names = {"com.android.server.policy.MiuiPhoneWindowManager",
                "com.android.server.policy.BaseMiuiPhoneWindowManager",
                "com.android.server.policy.PhoneWindowManager"};
        Method keyMethod = null;
        Class<?> policy = null;
        for (String name : names) {
            try {
                Class<?> cls = Class.forName(name, false, loader);

                for (Method candidate : cls.getDeclaredMethods()) {
                    if (candidate.getName().equals("interceptKeyBeforeQueueing") &&
                            candidate.getReturnType() == int.class &&
                            java.util.Arrays.equals(candidate.getParameterTypes(),
                                    new Class<?>[]{KeyEvent.class, int.class})) {
                        keyMethod = candidate;
                        policy = cls;
                        break;
                    }
                }
                if (keyMethod != null) break;
            } catch (ClassNotFoundException ignored) { }
        }
        if (keyMethod == null) {
            log(Log.ERROR, TAG, "No compatible policy key method; system key hook was not installed");
            return;
        }
        try {
            final String description = keyMethod.toString();
            installHook(keyMethod, chain -> {
                if (retiring) return chain.proceed();
                SystemKeyDispatcher target = dispatcher;
                if (target == null) {
                    try { ensureDispatcher((Context) Reflection.field(chain.getThisObject(), "mContext"), description); }
                    catch (ReflectiveOperationException e) { log(Log.ERROR, TAG, "Cannot obtain policy context", e); }
                    target = dispatcher;
                }
                if (target != null) {
                    int userId = 0;
                    try { userId = (Integer) Reflection.field(chain.getThisObject(), "mCurrentUserId"); }
                    catch (ReflectiveOperationException ignored) { }
                    if (target.intercept((KeyEvent) chain.getArgs().get(0), userId)) return 0;
                }
                return chain.proceed();
            });

            for (Class<?> cls = policy; cls != null; cls = cls.getSuperclass()) {
                Method init = null;
                for (Method candidate : cls.getDeclaredMethods()) {
                    if (candidate.getName().equals("init") && candidate.getParameterCount() > 0 &&
                            candidate.getParameterTypes()[0] == Context.class) { init = candidate; break; }
                }
                if (init != null) {
                    installHook(init, chain -> {
                        Object result = chain.proceed();
                        ensureDispatcher((Context) chain.getArgs().get(0), description);
                        return result;
                    });
                    break;
                }
            }
            systemHooksInstalled = true;
            if (systemContext != null) ensureDispatcher(systemContext, description);
            log(Log.INFO, TAG, "Installed system key hook: " + description);
        } catch (RuntimeException e) { log(Log.ERROR, TAG, "System hook setup failed", e); }
    }

    private synchronized void ensureDispatcher(Context context, String description) {
        if (retiring || context == null) return;
        systemContext = context;
        if (dispatcher == null) dispatcher = new SystemKeyDispatcher(context, this, description);
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (retiring || !"com.android.camera".equals(param.getPackageName()) || !param.isFirstPackage() ||
                !"com.android.camera".equals(processName)) return;
        installCameraHooks(param.getClassLoader());
    }

    @SuppressLint("PrivateApi")
    private void installCameraHooks(ClassLoader loader) {
        if (!cameraLoaders.add(loader)) return;
        if (cameraMain == null) cameraMain = new Handler(Looper.getMainLooper());
        try {
            PreferenceAccess access = new PreferenceAccess(loader);
            cameraPreferences.put(loader, access);
            Hooker redirect = chain -> {
                if (!retiring && access.prepared()) {
                    try {
                        Object item = chain.getThisObject();
                        if (CameraSettingsPolicy.isEntryKey(access.key(item)) && access.usable(item)) {
                            Context context = access.context(item);
                            if (context != null && "com.android.camera".equals(context.getPackageName()) && openSettings(context))
                                return null;
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        log(Log.ERROR, TAG, "Cannot redirect camera preference; retaining original action", e);
                    }
                }
                return chain.proceed();
            };
            for (Method click : access.clickMethods) installHook(click, redirect);
            log(Log.INFO, TAG, "Preference click redirect installed by stable entry key");
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "Camera preference click path unsupported; use the module launcher", e);
            return;
        }
        try {
            Class<?> fragment = Class.forName("androidx.fragment.app.Fragment", false, loader);
            installHook(Reflection.method(fragment, "performResume"), chain -> {
                Object result = chain.proceed();
                Object target = chain.getThisObject();
                if (!retiring) {
                    try { addSettingsLink(target, loader); }
                    catch (ReflectiveOperationException | RuntimeException e) {
                        log(Log.ERROR, TAG, "Cannot add camera settings entry; launcher remains available", e);
                    }
                }
                return result;
            });
            log(Log.INFO, TAG, "Camera settings discovery installed on AndroidX fragment resume");
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "Camera settings lifecycle unsupported; click redirect remains available", e);
        }
        try {
            PreferenceAccess access = cameraPreferences.get(loader);
            if (access != null) for (Method operation : PreferenceAccess.methods(access.group, void.class)) {
                installHook(operation, chain -> {
                    Object result = chain.proceed();
                    scheduleCameraSettingsRefresh();
                    return result;
                });
            }
        } catch (RuntimeException e) {
            log(Log.WARN, TAG, "Camera settings rebuild observer unavailable", e);
        }
    }

    private void scheduleCameraSettingsRefresh() {
        Handler main = cameraMain;
        if (retiring || main == null) return;
        if (Looper.myLooper() != main.getLooper()) {
            main.post(this::scheduleCameraSettingsRefresh);
            return;
        }
        if (cameraRefreshPending || cameraFragments.isEmpty()) return;
        cameraRefreshPending = true;
        if (!main.post(cameraRefresh)) cameraRefreshPending = false;
    }

    private void addSettingsLink(Object fragment, ClassLoader loader) throws ReflectiveOperationException {
        if (retiring) return;
        PreferenceAccess access = cameraPreferences.get(loader);
        if (access == null) return;
        Class<?> cls = fragment.getClass();
        if (!(Boolean) Reflection.method(cls, "isResumed").invoke(fragment)) return;
        Object root = access.root(fragment);
        if (root == null) return;
        cameraFragments.add(fragment);
        Context context = access.context(root);
        if (context == null || !"com.android.camera".equals(context.getPackageName())) return;
        Object street = access.find(root, CameraSettingsPolicy.STREET_KEY);
        Object existing = access.find(root, CameraSettingsPolicy.MODULE_KEY);
        Map<String, Object> categories = new java.util.LinkedHashMap<>();
        for (String key : CameraSettingsPolicy.CATEGORY_KEYS) {
            Object category = access.find(root, key);
            if (category != null && access.group.isInstance(category)) categories.put(key, category);
        }
        if (!access.prepared()) {
            if (street != null) access.prepare(street, CameraSettingsPolicy.STREET_KEY);
            else if (existing != null) access.prepare(existing, CameraSettingsPolicy.MODULE_KEY);
            else if (!categories.isEmpty()) {
                Map.Entry<String, Object> category = categories.entrySet().iterator().next();
                access.prepare(category.getValue(), category.getKey());
            } else return;
        }
        if (!access.visible(root)) return;
        java.util.Iterator<Map.Entry<String, Object>> entries = categories.entrySet().iterator();
        while (entries.hasNext()) if (!access.visible(entries.next().getValue())) entries.remove();
        CameraSettingsPolicy.Target target = CameraSettingsPolicy.target(street != null && access.usable(street),
                existing != null, street != null, categories.keySet());
        if (target == CameraSettingsPolicy.Target.NONE || target == CameraSettingsPolicy.Target.EXISTING) return;
        Context moduleContext;
        try { moduleContext = context.createPackageContext(SnapConfig.PACKAGE, 0); }
        catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new ReflectiveOperationException("Module resources unavailable", e);
        }
        if (target == CameraSettingsPolicy.Target.STREET) {
            if (existing != null) {
                PreferenceLink previous = preferenceLinks.remove(existing);
                if (previous != null) previous.restore();
            }
            String summary = moduleContext.getString(R.string.module_settings_summary);
            CharSequence current = access.summary(street);
            PreferenceLink link = preferenceLinks.get(street);
            if (link == null || !java.util.Objects.equals(current == null ? null : current.toString(), link.installedSummary))
                link = new PreferenceLink(access, street, current);
            link.installedSummary = summary;
            access.summary(street, summary);
            preferenceLinks.put(street, link);
            return;
        }
        Object item = access.entry(context, moduleContext.getString(R.string.app_name),
                moduleContext.getString(R.string.module_settings_summary));
        String category = CameraSettingsPolicy.insertionCategory(categories.keySet());
        Object parent = category == null ? root : categories.get(category);
        boolean added = access.add(parent, item);
        if (added) {
            PreferenceLink link = new PreferenceLink(access, item, null);
            link.parent = new WeakReference<>(parent);
            preferenceLinks.put(item, link);
            log(Log.DEBUG, TAG, "Added module entry to camera settings category " + category);
        }
    }

    @Override public boolean onHotReloading(HotReloadingParam param) {
        SystemKeyDispatcher previous;
        synchronized (this) {
            retiring = true;
            previous = dispatcher;
        }
        try {
            if (previous != null && !previous.closeAndAwait(3000))
                throw new IllegalStateException("Policy worker did not finish retiring");
            Object[][] fragments = {new Object[0]};
            if ("com.android.camera".equals(processName) && !onMainAndAwait(() -> {
                fragments[0] = cameraFragments.toArray();
                if (cameraMain != null) cameraMain.removeCallbacks(cameraRefresh);
                cameraRefreshPending = false;
                for (PreferenceLink link : new ArrayList<>(preferenceLinks.values())) link.restore();
                preferenceLinks.clear();
                cameraFragments.clear();
            })) throw new IllegalStateException("Camera preference cleanup did not finish");
            param.setSavedInstanceState(new Object[]{systemLoader, systemContext,
                    cameraLoaders.toArray(new ClassLoader[0]), fragments[0]});
            dispatcher = null;
            cameraLoaders.clear();
            cameraPreferences.clear();
            log(Log.INFO, TAG, "Old module generation retired for hot reload");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log(Log.ERROR, TAG, "Hot reload interrupted; restart the device", e);
        } catch (RuntimeException e) {
            log(Log.ERROR, TAG, "Hot reload rejected after cleanup failure; restart the device", e);
        }
        return false;
    }

    @Override public void onHotReloaded(HotReloadedParam param) {
        processName = param.getProcessName();
        reloadHooks.restore(param.getOldHookHandles());
        try {
            if (!(param.getSavedInstanceState() instanceof Object[] state) || state.length != 4)
                throw new IllegalStateException("Missing hot reload state");
            if (param.isSystemServer()) {
                if (!(state[0] instanceof ClassLoader loader))
                    throw new IllegalStateException("Missing system classloader");
                systemContext = state[1] instanceof Context context ? context : null;
                installSystemHooks(loader);
                if (!systemHooksInstalled) throw new IllegalStateException("System key hook was not restored");
            } else if ("com.android.camera".equals(processName)) {
                if (state[2] instanceof ClassLoader[] loaders)
                    for (ClassLoader loader : loaders) installCameraHooks(loader);
                if (state[3] instanceof Object[] fragments && !onMainAndAwait(() -> {
                    for (Object fragment : fragments) {
                        try { addSettingsLink(fragment, fragment.getClass().getClassLoader()); }
                        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    }
                })) throw new IllegalStateException("Camera preference reinstall did not finish");
            }
            log(Log.INFO, TAG, "Hot reload installed, version=" + BuildConfig.VERSION_NAME + ", process=" + processName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log(Log.ERROR, TAG, "Hot reload initialization interrupted; restart the target process or device", e);
        } catch (RuntimeException e) {
            log(Log.ERROR, TAG, "Hot reload initialization failed; restart the target process or device", e);
        } finally {
            reloadHooks.removeRemaining();
        }
    }

    private boolean onMainAndAwait(Runnable action) throws InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) { action.run(); return true; }
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch complete = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<RuntimeException> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable task = () -> {
            try { action.run(); }
            catch (RuntimeException e) { failure.set(e); }
            finally { complete.countDown(); }
        };
        if (!main.post(task)) return false;
        if (!complete.await(3000, TimeUnit.MILLISECONDS)) {
            main.removeCallbacks(task);
            return false;
        }
        if (failure.get() != null) throw failure.get();
        return true;
    }

    private static final class PreferenceLink {
        final PreferenceAccess access;
        final WeakReference<Object> item;
        final String originalSummary;
        String installedSummary;
        WeakReference<Object> parent = new WeakReference<>(null);

        PreferenceLink(PreferenceAccess access, Object item, CharSequence summary) {
            this.access = access;
            this.item = new WeakReference<>(item);
            originalSummary = summary == null ? null : summary.toString();
        }

        void restore() {
            Object preference = item.get();
            if (preference == null) return;
            try {
                Object group = parent.get();
                if (group != null) {
                    access.remove(group, preference);
                } else {
                    CharSequence current = access.summary(preference);
                    if (java.util.Objects.equals(current == null ? null : current.toString(), installedSummary))
                        access.summary(preference, originalSummary);
                }
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    }

    private boolean openSettings(Context context) {
        try {
            context.startActivity(new Intent().setClassName(SnapConfig.PACKAGE,
                    SnapConfig.PACKAGE + ".SettingsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            log(Log.ERROR, TAG, "Cannot open module settings", e);
            return false;
        }
    }
}
