package io.github.vay1314.camerasnap;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreferenceAccessTest {
    public static class RenamedPreference {
        static boolean defaultEnabled = true;
        final Context a;
        RenamedGroup parent;
        String m, dependency;
        CharSequence i, j;
        boolean enabled = true, shown = true;
        public RenamedPreference(Context context, AttributeSet attrs) { a = context; enabled = defaultEnabled; }
        public void V(View view) { }
        public CharSequence v() { return j; }
        public void c0(CharSequence value) { j = value; }
        public void e0(String value) { i = value; }
        public boolean w() { return enabled; }
        public boolean g0() { return !enabled; }
        public boolean h0() { return false; }
        public void Y(boolean value) { enabled = value; }
        public void f0(boolean value) { shown = value; }
        public void A(boolean value) { }
    }

    public static class RenamedGroup extends RenamedPreference {
        final List<RenamedPreference> children = new ArrayList<>();
        public RenamedGroup() { super(null, null); }
        public RenamedPreference k0(CharSequence key) {
            if (key.equals(m)) return this;
            for (RenamedPreference child : children) if (key.equals(child.m)) return child;
            return null;
        }
        public boolean j0(RenamedPreference child) { children.add(child); child.parent = this; return true; }
        public final boolean n0(RenamedPreference child) { child.parent = null; return children.remove(child); }
    }

    public static class RenamedScreen extends RenamedGroup { }

    public static class LegacyPreference {
        final Context context;
        LegacyGroup parent;
        String key, dependency;
        CharSequence title, summary;
        boolean enabled = true, shown = true, persistent = true;
        public LegacyPreference(Context context) { this.context = context; }
        public void performClick(View view) { }
        public void performClick() { }
        public CharSequence getTitle() { return title; }
        public CharSequence getSummary() { return summary; }
        public void setSummary(CharSequence value) { summary = value; }
        public void setTitle(CharSequence value) { title = value; }
        public boolean isEnabled() { return enabled; }
        public boolean isSelectable() { return true; }
        public boolean isShown() { return shown && (parent == null || parent.isShown()); }
        public void setPersistent(boolean value) { persistent = value; }
    }

    public static class LegacyGroup extends LegacyPreference {
        final List<LegacyPreference> children = new ArrayList<>();
        public LegacyGroup() { super(null); }
        public LegacyPreference findPreference(CharSequence key) {
            if (key.equals(this.key)) return this;
            for (LegacyPreference child : children) if (key.equals(child.key)) return child;
            return null;
        }
        public boolean addPreference(LegacyPreference child) { children.add(child); child.parent = this; return true; }
        public boolean removePreference(LegacyPreference child) { child.parent = null; return children.remove(child); }
    }

    public static class LegacyScreen extends LegacyGroup { }
    public static class AmbiguousPreference {
        Context context;
        AmbiguousGroup parent;
        public void a(View view) { }
        public void b(View view) { }
    }
    public static class AmbiguousGroup extends AmbiguousPreference {
        public AmbiguousPreference find(CharSequence key) { return null; }
    }
    public static class AmbiguousScreen extends AmbiguousGroup { }
    public static class RenamedFragment { private final RenamedScreen p = new RenamedScreen(); }
    public static class LegacyFragment {
        private final LegacyScreen root = new LegacyScreen();
        public LegacyScreen getPreferenceScreen() { return root; }
    }

    private PreferenceAccess renamed() throws Exception {
        PreferenceAccess access = new PreferenceAccess(RenamedPreference.class, RenamedGroup.class, RenamedScreen.class);
        RenamedPreference street = new RenamedPreference(null, null);
        street.m = CameraSettingsPolicy.STREET_KEY;
        access.prepare(street, CameraSettingsPolicy.STREET_KEY);
        return access;
    }

    @Test public void renamedAndInlinedMembersAreResolvedWithoutVersionTable() throws Exception {
        PreferenceAccess access = renamed();
        assertTrue(access.prepared());
        assertEquals(1, access.clickMethods.size());
        assertEquals("V", access.clickMethods.get(0).getName());
        RenamedPreference entry = (RenamedPreference) access.entry(null, "CameraSnap", "Module settings");
        assertEquals(CameraSettingsPolicy.MODULE_KEY, entry.m);
        assertEquals("CameraSnap", entry.i);
        assertEquals("Module settings", entry.j);
        RenamedScreen root = new RenamedScreen();
        assertTrue(access.add(root, entry));
        assertSame(entry, access.find(root, CameraSettingsPolicy.MODULE_KEY));
        access.remove(root, entry);
        assertNull(access.find(root, CameraSettingsPolicy.MODULE_KEY));
    }

    @Test public void dependencyKeysDoNotBecomeClickableModuleEntries() throws Exception {
        PreferenceAccess access = renamed();
        RenamedPreference dependent = new RenamedPreference(null, null);
        dependent.m = "other_setting";
        dependent.dependency = CameraSettingsPolicy.STREET_KEY;
        assertFalse(CameraSettingsPolicy.isEntryKey(access.key(dependent)));
    }

    @Test public void hiddenParentsAndDisabledEntriesAreRejected() throws Exception {
        PreferenceAccess access = renamed();
        RenamedPreference entry = (RenamedPreference) access.entry(null, "CameraSnap", "Settings");
        RenamedScreen root = new RenamedScreen();
        access.add(root, entry);
        assertTrue(access.usable(entry));
        root.f0(false);
        assertFalse(access.visible(entry));
        root.f0(true);
        entry.Y(false);
        assertFalse(access.usable(entry));
    }

    @Test public void disabledThemeDefaultsDoNotInvertEnabledDetection() throws Exception {
        RenamedPreference.defaultEnabled = false;
        try {
            PreferenceAccess access = renamed();
            RenamedPreference entry = (RenamedPreference) access.entry(null, "CameraSnap", "Settings");
            assertTrue(access.usable(entry));
            entry.Y(false);
            assertFalse(access.usable(entry));
        } finally { RenamedPreference.defaultEnabled = true; }
    }

    @Test public void screenDiscoverySupportsRenamedFragmentBaseClasses() throws Exception {
        PreferenceAccess access = renamed();
        RenamedFragment fragment = new RenamedFragment();
        assertSame(fragment.p, access.root(fragment));
        assertNull(access.root(new Object()));
    }

    @Test public void ambiguousClickShapesAreRejectedInsteadOfHookingViewSetters() {
        assertThrows(ReflectiveOperationException.class, () -> new PreferenceAccess(
                AmbiguousPreference.class, AmbiguousGroup.class, AmbiguousScreen.class));
    }

    @Test public void originalApiStillSupportsBothClicksAndRestorableSummaries() throws Exception {
        PreferenceAccess access = new PreferenceAccess(LegacyPreference.class, LegacyGroup.class, LegacyScreen.class);
        LegacyPreference street = new LegacyPreference(null);
        street.key = CameraSettingsPolicy.STREET_KEY;
        street.summary = "Original summary";
        access.prepare(street, CameraSettingsPolicy.STREET_KEY);
        assertEquals(2, access.clickMethods.size());
        CharSequence original = access.summary(street);
        access.summary(street, "Module summary");
        assertEquals("Module summary", street.summary);
        access.summary(street, original);
        assertEquals("Original summary", street.summary);
        LegacyPreference entry = (LegacyPreference) access.entry(null, "CameraSnap", "Settings");
        assertFalse(entry.persistent);
        LegacyFragment fragment = new LegacyFragment();
        assertSame(fragment.root, access.root(fragment));
        access.add(fragment.root, entry);
        assertSame(entry, access.find(fragment.root, CameraSettingsPolicy.MODULE_KEY));
        access.remove(fragment.root, entry);
        assertNull(access.find(fragment.root, CameraSettingsPolicy.MODULE_KEY));
    }
}
